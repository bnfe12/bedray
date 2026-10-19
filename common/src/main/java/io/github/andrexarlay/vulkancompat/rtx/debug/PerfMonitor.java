package io.github.andrexarlay.vulkancompat.rtx.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.GpuDevice;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.mixin.debug.VulkanQueryPoolAccessor;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Per-frame GPU and CPU timing of the stages of a frame (F3 block and {@code [VKB-PERF]} log lines). Render thread only.
 *
 * <p>GPU: engine timestamp queries. {@code GpuDevice.createTimestampQueryPool(int)} and
 * {@code CommandEncoder.writeTimestamp(GpuQueryPool, int)} (javap -p libs/minecraft-26.3-client.jar; the Vulkan backend
 * records {@code vkCmdWriteTimestamp2KHR} into the engine's current frame command buffer after a host
 * {@code vkResetQueryPool} of that query, decompiled {@code VulkanCommandEncoder.writeTimestamp}). Because the stamps go into
 * the engine command stream, they are ordered with the engine's own work and with the transient command buffers we splice in
 * with {@code VulkanCommandEncoder.execute} (RT, upscaler): {@code execute} ends the current engine buffer and appends ours to
 * the same {@code vkQueueSubmit2} (decompiled {@code VulkanCommandEncoder.execute}). One submit per frame
 * ({@code Minecraft.renderFrame}: gameRenderer.render -> blitFromTexture -> submit -> present).
 *
 * <p>Results are read without waiting and without Java allocations: a ring of {@link #RING} frame slots; the slot about to
 * be reused belongs to the frame {@link #RING} frames back, which is complete (the engine waits for submit n-2 in
 * {@code VulkanCommandEncoder.submit}). The raw {@code VkQueryPool} is read with {@code vkGetQueryPoolResults} into a
 * preallocated buffer (handle through {@link VulkanQueryPoolAccessor}, {@code protected long vkQueryPool()} of
 * {@code VulkanQueryPool}, javap). Averages are published every {@link #WINDOW_NS} ns.
 */
public final class PerfMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-PERF");

    /** Stamp points of a frame, in submission order. */
    public enum Mark {
        /** Head of GameRenderer.render(). */
        FRAME_BEGIN,
        /** Right before GameRenderer.renderLevel() (after the main target clear and the lightmap). */
        WORLD_BEGIN,
        /** Head of render3dHud: world raster (sky, terrain, entities, particles, weather, clouds) is recorded. */
        RASTER_END,
        /** After our RT pass (BLAS/TLAS update + ray dispatch), still at the head of render3dHud. */
        RT_END,
        /** After applyPostEffects: hand, entity outline, post chain. */
        POST_END,
        /** After our upscale (bilinear blit, or velocity + XeSS/DLSS + copy). */
        UPSCALE_END,
        /** Return of GameRenderer.render(): GUI recorded (our hook runs before other mods' render-end hooks, priority 500). */
        GUI_END,
        /** After GameRenderer.render() in Minecraft.renderFrame: render-end hooks of other mods (Bedray Core GL layer). */
        RENDER_END,
        /** Before the frame submit: swapchain blit recorded. */
        FRAME_END
    }

    /** Measured intervals. GPU and CPU use the same marks. */
    public enum Segment {
        PRE(Mark.FRAME_BEGIN, Mark.WORLD_BEGIN),
        WORLD(Mark.WORLD_BEGIN, Mark.RASTER_END),
        RT(Mark.RASTER_END, Mark.RT_END),
        POST(Mark.RT_END, Mark.POST_END),
        UPSCALE(Mark.POST_END, Mark.UPSCALE_END),
        /** From UPSCALE_END, or from FRAME_BEGIN when no world was rendered. */
        GUI(Mark.UPSCALE_END, Mark.GUI_END),
        HOOKS(Mark.GUI_END, Mark.RENDER_END),
        BLIT(Mark.RENDER_END, Mark.FRAME_END),
        TOTAL(Mark.FRAME_BEGIN, Mark.FRAME_END);

        final Mark from;
        final Mark to;

        Segment(Mark from, Mark to) {
            this.from = from;
            this.to = to;
        }
    }

    public static final int RING = 4;
    public static final long WINDOW_NS = 500_000_000L;
    private static final int MARKS = Mark.values().length;
    private static final int SEGS = Segment.values().length;
    private static final Mark[] MARK_VALUES = Mark.values();
    private static final Segment[] SEG_VALUES = Segment.values();

    // ---- GPU state ----
    private static GpuQueryPool pool;
    private static long vkPool;
    private static VkDevice vkDevice;
    private static float timestampPeriodNs;
    private static LongBuffer results;
    private static final boolean[] written = new boolean[RING * MARKS];
    private static long frameIndex;
    private static int slot;
    private static boolean gpuUnavailableLogged;
    private static String gpuUnavailableReason;

    // ---- CPU state of the current frame ----
    private static final long[] cpuNs = new long[MARKS];
    private static final boolean[] cpuWritten = new boolean[MARKS];
    private static long lastFrameBeginNs;
    private static boolean frameOpen;

    // ---- window accumulators ----
    private static final double[] gpuSum = new double[SEGS];
    private static final int[] gpuCount = new int[SEGS];
    private static final double[] cpuSum = new double[SEGS];
    private static final int[] cpuCount = new int[SEGS];
    private static double intervalSumMs;
    private static int intervalCount;
    private static long windowStartNs;

    // ---- published averages (read by DebugInfo, render thread) ----
    private static final double[] gpuAvg = new double[SEGS];
    private static final double[] cpuAvg = new double[SEGS];
    private static final boolean[] gpuValid = new boolean[SEGS];
    private static final boolean[] cpuValid = new boolean[SEGS];
    private static double frameIntervalAvgMs;
    private static double fpsAvg;
    private static long publishedVersion;

    private PerfMonitor() {}

    /** Head of GameRenderer.render(): harvests the slot about to be reused, then stamps FRAME_BEGIN. */
    public static void frameBegin() {
        long now = System.nanoTime();
        if (lastFrameBeginNs != 0L) {
            intervalSumMs += (now - lastFrameBeginNs) / 1.0e6;
            intervalCount++;
        }
        lastFrameBeginNs = now;
        if (windowStartNs == 0L) {
            windowStartNs = now;
        }
        java.util.Arrays.fill(cpuWritten, false);
        frameOpen = true;
        slot = (int) (frameIndex % RING);
        if (ensurePool()) {
            harvest(slot);
        }
        mark(Mark.FRAME_BEGIN);
    }

    /** Records the CPU time of {@code m} and, on Vulkan, a GPU timestamp in the engine command stream. */
    public static void mark(Mark m) {
        if (!frameOpen) {
            return;
        }
        int i = m.ordinal();
        cpuNs[i] = System.nanoTime();
        cpuWritten[i] = true;
        if (pool != null) {
            int q = slot * MARKS + i;
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            enc.writeTimestamp(pool, q);
            written[q] = true;
        }
    }

    /** Before the frame submit: stamps FRAME_END, accumulates CPU segments, publishes the window when it elapsed. */
    public static void frameEnd() {
        if (!frameOpen) {
            return;
        }
        mark(Mark.FRAME_END);
        frameOpen = false;
        accumulate(cpuNs, cpuWritten, 1.0e-6, cpuSum, cpuCount);
        frameIndex++;
        long now = System.nanoTime();
        if (now - windowStartNs >= WINDOW_NS) {
            publish(now);
        }
    }

    private static boolean ensurePool() {
        if (pool != null) {
            return true;
        }
        if (gpuUnavailableReason != null) {
            return false;
        }
        if (!VulkanBridge.isVulkan()) {
            return failGpu("backend is not Vulkan");
        }
        GpuDevice device = RenderSystem.getDevice();
        com.mojang.renderpearl.backend.vulkan.VulkanDevice vk = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null || vk == null) {
            return false; // device not created yet: try again next frame
        }
        GpuQueryPool created = device.createTimestampQueryPool(RING * MARKS);
        if (!(created instanceof VulkanQueryPoolAccessor acc)) {
            created.close();
            return failGpu("timestamp pool is " + created.getClass().getName() + ", not VulkanQueryPool (accessor mixin missing)");
        }
        timestampPeriodNs = device.getDeviceInfo().timestampPeriod();
        if (!(timestampPeriodNs > 0.0f)) {
            created.close();
            return failGpu("device reports timestampPeriod=" + timestampPeriodNs);
        }
        pool = created;
        vkPool = acc.vkb$vkQueryPool();
        vkDevice = vk.vkDevice();
        results = MemoryUtil.memAllocLong(MARKS * 2);
        LOGGER.info("[VKB-PERF] gpu_timestamps=on queries={} ring={} timestamp_period_ns={}", RING * MARKS, RING, timestampPeriodNs);
        return true;
    }

    private static boolean failGpu(String reason) {
        gpuUnavailableReason = reason;
        if (!gpuUnavailableLogged) {
            gpuUnavailableLogged = true;
            LOGGER.warn("[VKB-PERF] gpu_timestamps=off reason={}", reason);
        }
        return false;
    }

    /** Reads the stamps of {@code s} (written {@link #RING} frames ago) without waiting and adds its GPU segments. */
    private static void harvest(int s) {
        int base = s * MARKS;
        boolean any = false;
        for (int i = 0; i < MARKS; i++) {
            any |= written[base + i];
        }
        if (!any) {
            return;
        }
        results.clear();
        // VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT, no WAIT: VK_NOT_READY just means "not all available".
        int r = VK10.vkGetQueryPoolResults(vkDevice, vkPool, base, MARKS, results, 16L,
            VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if (r != VK10.VK_SUCCESS && r != VK10.VK_NOT_READY) {
            throw new IllegalStateException("vkGetQueryPoolResults failed: " + r);
        }
        for (int i = 0; i < MARKS; i++) {
            int q = base + i;
            boolean available = written[q] && results.get(i * 2 + 1) != 0L;
            gpuTicks[i] = available ? results.get(i * 2) : 0L;
            gpuHave[i] = available;
            written[q] = false;
        }
        accumulate(gpuTicks, gpuHave, timestampPeriodNs * 1.0e-6, gpuSum, gpuCount);
    }

    private static final long[] gpuTicks = new long[MARKS];
    private static final boolean[] gpuHave = new boolean[MARKS];

    /** Adds every segment whose two marks are present; GUI starts at FRAME_BEGIN when no world was rendered. */
    private static void accumulate(long[] t, boolean[] have, double toMs, double[] sum, int[] count) {
        for (Segment seg : SEG_VALUES) {
            Mark from = seg.from;
            if (seg == Segment.GUI && !have[Mark.UPSCALE_END.ordinal()]) {
                from = Mark.FRAME_BEGIN;
                if (have[Mark.WORLD_BEGIN.ordinal()]) {
                    continue; // a world frame without UPSCALE_END: incomplete, skip
                }
            }
            int a = from.ordinal();
            int b = seg.to.ordinal();
            if (!have[a] || !have[b]) {
                continue;
            }
            long d = t[b] - t[a];
            if (d < 0) {
                continue;
            }
            sum[seg.ordinal()] += d * toMs;
            count[seg.ordinal()]++;
        }
    }

    private static void publish(long now) {
        for (int i = 0; i < SEGS; i++) {
            gpuValid[i] = gpuCount[i] > 0;
            gpuAvg[i] = gpuValid[i] ? gpuSum[i] / gpuCount[i] : 0.0;
            cpuValid[i] = cpuCount[i] > 0;
            cpuAvg[i] = cpuValid[i] ? cpuSum[i] / cpuCount[i] : 0.0;
            gpuSum[i] = 0.0;
            gpuCount[i] = 0;
            cpuSum[i] = 0.0;
            cpuCount[i] = 0;
        }
        frameIntervalAvgMs = intervalCount > 0 ? intervalSumMs / intervalCount : 0.0;
        fpsAvg = frameIntervalAvgMs > 0.0 ? 1000.0 / frameIntervalAvgMs : 0.0;
        intervalSumMs = 0.0;
        intervalCount = 0;
        windowStartNs = now;
        publishedVersion++;
        DebugInfo.onPerfPublished();
    }

    // ---- read side (render thread) ----

    public static long publishedVersion() {
        return publishedVersion;
    }

    public static boolean gpuValid(Segment s) {
        return gpuValid[s.ordinal()];
    }

    public static double gpuMs(Segment s) {
        return gpuAvg[s.ordinal()];
    }

    public static boolean cpuValid(Segment s) {
        return cpuValid[s.ordinal()];
    }

    public static double cpuMs(Segment s) {
        return cpuAvg[s.ordinal()];
    }

    /** Wall time between two frame heads (the real frame time). */
    public static double frameIntervalMs() {
        return frameIntervalAvgMs;
    }

    public static double fps() {
        return fpsAvg;
    }

    /** Null while GPU timestamps work (or are not set up yet); otherwise why they are off. */
    public static String gpuUnavailableReason() {
        return gpuUnavailableReason;
    }

    /**
     * GPU-bound when the GPU is busy for most of the frame interval (the CPU then waits in submit/present), CPU-bound when
     * the GPU idles a large part of the interval. {@code null} when there is not enough data.
     */
    public static String boundVerdict() {
        if (!gpuValid[Segment.TOTAL.ordinal()] || frameIntervalAvgMs <= 0.0) {
            return null;
        }
        double busy = gpuAvg[Segment.TOTAL.ordinal()] / frameIntervalAvgMs;
        if (busy >= 0.90) {
            return "GPU";
        }
        if (busy <= 0.75) {
            return "CPU";
        }
        return "mixed";
    }

    public static double gpuBusyPercent() {
        if (!gpuValid[Segment.TOTAL.ordinal()] || frameIntervalAvgMs <= 0.0) {
            return -1.0;
        }
        return 100.0 * gpuAvg[Segment.TOTAL.ordinal()] / frameIntervalAvgMs;
    }
}
