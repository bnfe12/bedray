package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Locale;

/**
 * GPU time of named spans inside the RT command buffer (Vulkan timestamp queries), averaged over a window and logged as
 * {@code [VKB-ATMO] gpu_ms name=0.123(n=..) ...}. A ring of {@link #RING} frames: the stamps of a frame are read {@code RING} frames
 * later without waiting. Only created with {@code -Dvkrtx.rt.gpuTimers=true} (otherwise no pool and no writes).
 */
final class RtGpuTimer {
    static final boolean ENABLED = Boolean.getBoolean("vkrtx.rt.gpuTimers");
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int RING = 4;
    private static final int WINDOW_FRAMES = 300;

    private final VkDevice device;
    private final String[] names;
    private final int marks;       // 2 stamps per span
    private final long pool;
    private final float periodNs;
    private final boolean[] written;
    private final double[] sumMs;
    private final int[] count;
    private LongBuffer results;
    private long frame;
    private int slot;
    private int framesInWindow;

    RtGpuTimer(VkDevice device, String... spanNames) {
        this.device = device;
        this.names = spanNames;
        this.marks = spanNames.length * 2;
        this.written = new boolean[RING * marks];
        this.sumMs = new double[spanNames.length];
        this.count = new int[spanNames.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), props);
            this.periodNs = props.limits().timestampPeriod();
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(RING * marks);
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateQueryPool(device, info, null, p), "create_gpu_timer_pool");
            this.pool = p.get(0);
        }
        this.results = MemoryUtil.memAllocLong(marks * 2);
        LOGGER.info("[VKB-ATMO] gpu_timers=on spans={} timestamp_period_ns={}", String.join(",", spanNames), periodNs);
    }

    /** Start of a frame (before the first stamp): harvests the slot about to be reused and resets its queries in {@code cmd}. */
    void beginFrame(VkCommandBuffer cmd) {
        slot = (int) (frame % RING);
        harvest(slot);
        VK10.vkCmdResetQueryPool(cmd, pool, slot * marks, marks);
        frame++;
    }

    /** Stamp of span {@code span}: {@code end} false = begin, true = end. */
    void stamp(VkCommandBuffer cmd, int span, boolean end) {
        int q = slot * marks + span * 2 + (end ? 1 : 0);
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, q);
        written[q] = true;
    }

    private void harvest(int s) {
        int base = s * marks;
        boolean any = false;
        for (int i = 0; i < marks; i++) {
            any |= written[base + i];
        }
        if (!any) {
            return;
        }
        results.clear();
        int r = VK10.vkGetQueryPoolResults(device, pool, base, marks, results, 16L,
            VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (r != VK10.VK_SUCCESS && r != VK10.VK_NOT_READY) {
            throw new RtFailure("gpu_timer", "vkGetQueryPoolResults " + r);
        }
        for (int span = 0; span < names.length; span++) {
            int a = span * 2;
            int b = a + 1;
            boolean ok = written[base + a] && written[base + b] && results.get(a * 2 + 1) != 0L && results.get(b * 2 + 1) != 0L;
            if (ok) {
                long ticks = results.get(b * 2) - results.get(a * 2);
                if (ticks >= 0) {
                    sumMs[span] += ticks * periodNs * 1.0e-6;
                    count[span]++;
                }
            }
        }
        for (int i = 0; i < marks; i++) {
            written[base + i] = false;
        }
        if (++framesInWindow >= WINDOW_FRAMES) {
            StringBuilder sb = new StringBuilder("[VKB-ATMO] gpu_ms");
            for (int span = 0; span < names.length; span++) {
                sb.append(String.format(Locale.ROOT, " %s=%.3f(n=%d)", names[span], count[span] > 0 ? sumMs[span] / count[span] : 0.0,
                    count[span]));
                sumMs[span] = 0.0;
                count[span] = 0;
            }
            LOGGER.info(sb.toString());
            framesInWindow = 0;
        }
    }

    void destroy() {
        VK10.vkDeviceWaitIdle(device);
        VK10.vkDestroyQueryPool(device, pool, null);
        if (results != null) {
            MemoryUtil.memFree(results);
            results = null;
        }
    }
}
