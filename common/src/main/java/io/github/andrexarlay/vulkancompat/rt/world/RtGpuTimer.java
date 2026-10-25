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
 * {@code [<label>] gpu_ms name=0.123(n=..) ...}. A ring of {@link #RING} frames: the stamps of a frame are read {@code RING} frames
 * later without waiting. On by default (the only frame-level split of the RT cost; {@code -Dvkrtx.rt.gpuTimers=false} disables
 * the query pool and the stamp writes).
 */
final class RtGpuTimer {
    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("vkrtx.rt.gpuTimers", "true"));
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int RING = 4;
    private static final int WINDOW_FRAMES = 900;

    private final VkDevice device;
    private final String label;
    private final String[] names;
    private final int marks;       // 2 stamps per span
    private final long pool;
    private final float periodNs;
    private final boolean[] written;
    private final double[] sumMs;
    private final int[] count;
    /** Exponentially smoothed milliseconds per span: the live value {@link RtAdaptiveQuality} decides on. */
    private final double[] ewmaMs;
    private final boolean[] sampled;
    private LongBuffer results;
    private long frame;
    private int slot;
    private int framesInWindow;

    RtGpuTimer(VkDevice device, String label, String... spanNames) {
        this.device = device;
        this.label = label;
        this.names = spanNames;
        this.marks = spanNames.length * 2;
        this.written = new boolean[RING * marks];
        this.sumMs = new double[spanNames.length];
        this.count = new int[spanNames.length];
        this.ewmaMs = new double[spanNames.length];
        this.sampled = new boolean[spanNames.length];
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
        LOGGER.info("[{}] gpu_timers=on spans={} timestamp_period_ns={}", label, String.join(",", spanNames), periodNs);
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
                    double spanMs = ticks * periodNs * 1.0e-6;
                    sumMs[span] += spanMs;
                    count[span]++;
                    ewmaMs[span] = sampled[span] ? ewmaMs[span] * 0.9 + spanMs * 0.1 : spanMs;
                    sampled[span] = true;
                }
            }
        }
        for (int i = 0; i < marks; i++) {
            written[base + i] = false;
        }
        if (++framesInWindow >= WINDOW_FRAMES) {
            StringBuilder sb = new StringBuilder("[").append(label).append("] gpu_ms");
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

    /**
     * Smoothed GPU time of the given spans (inclusive), milliseconds; negative until every one of them has produced a
     * readable sample. The stamps of a frame are read {@link #RING} frames late, so the first frames of a session have none.
     */
    double recentMs(int from, int to) {
        if (from < 0 || to >= ewmaMs.length || from > to) {
            return -1.0;
        }
        double total = 0.0;
        for (int span = from; span <= to; span++) {
            if (!sampled[span]) {
                return -1.0;
            }
            total += ewmaMs[span];
        }
        return total;
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
