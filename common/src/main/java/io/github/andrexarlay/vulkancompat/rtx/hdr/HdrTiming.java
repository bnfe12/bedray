package io.github.andrexarlay.vulkancompat.rtx.hdr;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Opt-in GPU cost of the two HDR passes ({@code -Dbedray.hdr.gpuTiming=true}): a timestamp pair around the scene pass (world
 * command buffer) and around the whole output sequence (present command buffer: copy, PQ pass, final blit). Each kind has a ring
 * of {@link #RING} slots; every {@link #REPORT_EVERY} output frames the finished slots are read without waiting and one
 * {@code [VKB-HDR] gpu_ms} line reports the mean and the worst case. Off by default: no query pool exists then.
 */
final class HdrTiming {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/HDR");
    private static final boolean ON = Boolean.getBoolean("bedray.hdr.gpuTiming");
    private static final int RING = 64;
    private static final int REPORT_EVERY = 240;
    static final int SCENE = 0;
    static final int OUTPUT = 1;

    private static long pool;
    private static float periodNs;
    private static final int[] frames = new int[2];
    private static boolean failed;

    private HdrTiming() {}

    static boolean enabled() {
        return ON && !failed;
    }

    private static void ensure(VkDevice device) {
        if (pool != 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), props);
            periodNs = props.limits().timestampPeriod();
            LongBuffer out = stack.mallocLong(1);
            int r = VK10.vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(2 * RING * 2), null, out);
            if (r != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateQueryPool returned " + r);
            }
            pool = out.get(0);
        }
    }

    private static int query(int kind, int frame) {
        return (kind * RING + (frame % RING)) * 2;
    }

    static void begin(VkDevice device, VkCommandBuffer cmd, int kind) {
        if (!enabled()) {
            return;
        }
        try {
            ensure(device);
            int q = query(kind, frames[kind]);
            VK10.vkCmdResetQueryPool(cmd, pool, q, 2);
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, q);
        } catch (Throwable t) {
            fail(t);
        }
    }

    static void end(VkDevice device, VkCommandBuffer cmd, int kind) {
        if (!enabled() || pool == 0L) {
            return;
        }
        try {
            int q = query(kind, frames[kind]);
            VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, q + 1);
            frames[kind]++;
            if (kind == OUTPUT && frames[OUTPUT] % REPORT_EVERY == 0) {
                report(device);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void report(VkDevice device) {
        StringBuilder line = new StringBuilder("[VKB-HDR] gpu_ms");
        for (int kind = 0; kind < 2; kind++) {
            // The newest slot may still be in flight: skip it, read the others without waiting.
            int newest = (frames[kind] - 1) % RING;
            double sum = 0;
            double max = 0;
            int n = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer data = stack.mallocLong(RING * 2 * 2);
                VK10.vkGetQueryPoolResults(device, pool, kind * RING * 2, RING * 2, data, 16L,
                    VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                for (int slot = 0; slot < RING; slot++) {
                    if (slot == newest) {
                        continue;
                    }
                    long begin = data.get(slot * 4);
                    long beginOk = data.get(slot * 4 + 1);
                    long end = data.get(slot * 4 + 2);
                    long endOk = data.get(slot * 4 + 3);
                    if (beginOk == 0L || endOk == 0L || end < begin) {
                        continue;
                    }
                    double ms = (end - begin) * (double) periodNs / 1.0e6;
                    sum += ms;
                    max = Math.max(max, ms);
                    n++;
                }
            }
            line.append(kind == SCENE ? " scene=" : " output=").append(n == 0 ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.3f(mean) %.3f(max) n=%d", sum / n, max, n));
        }
        LOGGER.info(line.toString());
    }

    private static void fail(Throwable t) {
        if (!failed) {
            LOGGER.error("[VKB-HDR] gpu timing failed, switched off", t);
        }
        failed = true;
    }

    static void release(VkDevice device) {
        if (device != null && pool != 0L) {
            VK10.vkDestroyQueryPool(device, pool, null);
        }
        pool = 0L;
        frames[0] = 0;
        frames[1] = 0;
    }
}
