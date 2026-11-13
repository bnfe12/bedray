package io.github.andrexarlay.bedray.compute;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

/**
 * GPU time of the upload, dispatch and blit regions inside one compute command buffer. Four timestamps
 * per frame (frame start, post-upload, post-dispatch, post-blit) in a ring of {@link #RING} frames; a
 * frame's stamps are harvested RING frames later through availability results, never waiting on the GPU.
 * If the device cannot create a timestamp pool, reports a zero period, or no sample becomes available
 * within the first frames, the timer disables itself and the caller keeps CPU timings only.
 */
final class ComputeGpuTimer implements AutoCloseable {
    static final int RING = 4;
    static final int MARKS = 4;
    static final int MARK_FRAME_START = 0, MARK_UPLOAD_END = 1, MARK_DISPATCH_END = 2, MARK_BLIT_END = 3;
    private static final int FALLBACK_AFTER_FRAMES = 2 * RING;

    private final VkDevice device;
    private final float periodNs;
    private final boolean[] written = new boolean[RING * MARKS];
    private final LongBuffer results;
    private long pool;
    private boolean available;
    private long frame;
    private int slot;
    private int framesWithoutSample;
    private boolean sampleReady;
    private long uploadNs, dispatchNs, blitNs;

    ComputeGpuTimer(VkDevice device) {
        this.device = device;
        float period = 0;
        long created = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), properties);
            period = properties.limits().timestampPeriod();
            if (period > 0) {
                VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(RING * MARKS);
                LongBuffer handle = stack.mallocLong(1);
                if (VK10.vkCreateQueryPool(device, info, null, handle) == VK10.VK_SUCCESS) created = handle.get(0);
            }
        }
        this.periodNs = period;
        this.pool = created;
        this.available = created != 0;
        this.results = MemoryUtil.memAllocLong(MARKS * 2);
    }

    boolean available() { return available; }

    float timestampPeriodNs() { return periodNs; }

    /** Harvests the slot about to be reused, then resets its queries. Call before the frame's first stamp. */
    void beginFrame(VkCommandBuffer command) {
        if (!available) return;
        slot = (int) (frame % RING);
        harvest(slot);
        VK10.vkCmdResetQueryPool(command, pool, slot * MARKS, MARKS);
        frame++;
        if (framesWithoutSample >= FALLBACK_AFTER_FRAMES) available = false;
    }

    /** Mark 0 uses TOP_OF_PIPE so the upload region includes transfer waits; the rest use BOTTOM_OF_PIPE. */
    void stamp(VkCommandBuffer command, int mark) {
        if (!available) return;
        int query = slot * MARKS + mark;
        VK10.vkCmdWriteTimestamp(command, mark == MARK_FRAME_START
            ? VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, query);
        written[query] = true;
    }

    /** True when beginFrame harvested a complete sample; out receives upload, dispatch and blit nanoseconds. */
    boolean takeSample(long[] out) {
        if (!sampleReady) return false;
        sampleReady = false;
        out[0] = uploadNs; out[1] = dispatchNs; out[2] = blitNs;
        return true;
    }

    private void harvest(int reuseSlot) {
        sampleReady = false;
        int base = reuseSlot * MARKS;
        boolean any = false;
        for (int mark = 0; mark < MARKS; mark++) any |= written[base + mark];
        if (!any) return;
        results.clear();
        int result = VK10.vkGetQueryPoolResults(device, pool, base, MARKS, results, 16L,
            VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_NOT_READY) {
            available = false;
            return;
        }
        boolean complete = result == VK10.VK_SUCCESS;
        for (int mark = 0; mark < MARKS && complete; mark++)
            complete = written[base + mark] && results.get(mark * 2 + 1) != 0L;
        for (int mark = 0; mark < MARKS; mark++) written[base + mark] = false;
        if (!complete) {
            framesWithoutSample++;
            return;
        }
        long start = results.get(0), uploadEnd = results.get(2), dispatchEnd = results.get(4), blitEnd = results.get(6);
        if (uploadEnd < start || dispatchEnd < uploadEnd || blitEnd < dispatchEnd) {
            framesWithoutSample++;
            return;
        }
        uploadNs = (long) ((uploadEnd - start) * periodNs);
        dispatchNs = (long) ((dispatchEnd - uploadEnd) * periodNs);
        blitNs = (long) ((blitEnd - dispatchEnd) * periodNs);
        sampleReady = true;
        framesWithoutSample = 0;
    }

    /** The caller waits for device idle before retiring resources; this never waits. */
    @Override public void close() {
        if (pool != 0) VK10.vkDestroyQueryPool(device, pool, null);
        pool = 0;
        available = false;
        MemoryUtil.memFree(results);
    }
}