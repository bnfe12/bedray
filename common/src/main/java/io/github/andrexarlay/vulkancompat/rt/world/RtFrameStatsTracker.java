package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingReadbackSlots;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Locale;

/**
 * The frame's statistics, carved out of {@link RtFrameRenderer}: the readback slots the trace writes its counters into,
 * the decode of a completed slot into the operator-facing shares (hit / reject / history / leaf / transparency) and
 * auto-exposure, and the per-frame dispatch rotation. The renderer still drives the frame - it leases a slot, zeroes it,
 * writes the header and hands the offset to the pass - but the measurement state and its lifecycle live here. Static,
 * like the renderer it was split from: one RT frame is in flight at a time.
 */
final class RtFrameStatsTracker {
    static final int SLOTS = 3;
    static final int STRIDE = RtStatsLayout.SLOT_STRIDE;

    record StatsMetadata(boolean accumulated, boolean transparency, float day, long sequence,
                         long recordedNanos, int width, int height, int frameIndex) {}

    private static final RtExposure exposure = new RtExposure();
    private static long lastExposureNanos;
    /** Last per-frame statistics of {@link RtWorld#update} (F3 / PerfMonitor), null before the first RT frame. */
    private static volatile RtWorld.FrameStats lastStats;
    private static long blasBuiltTotal = 0;
    private static RtMemory.Buf statsBuf;
    private static RecordingReadbackSlots<StatsMetadata> statsReadbacks = new RecordingReadbackSlots<>(SLOTS);
    private static long lastStatsSequence = -1;
    private static long dispatchCounter = 0;
    private static StatsMetadata lastStatsMetadata;
    private static long lastStatsTrustedPixels;
    private static double lastHitPct = -1.0;
    /** Share of the metered pixels the tracer refused; -1 until a counter slot carries a measurement. */
    private static double lastRejectedPct = -1.0;
    /** The trace's split of the frame's bad endings, or null while no slot carried a measurement this host agrees with. */
    private static RtStatsMetering.Breakdown lastRejectSplit;
    /** Share of the hit pixels whose key light reached them through a leaf card (1-in-16 mask, like hist_pct). */
    private static double lastLeafPct = -1.0;
    private static volatile String lastTransparency = "[RT-TRANSPARENCY] counters=WAITING";
    private static volatile float lastExposureValue = 1.1f;
    private static volatile double lastAvgLum;
    private static volatile double lastHistPct = -1.0;

    private RtFrameStatsTracker() {
    }

    static RtExposure exposure() { return exposure; }
    static RecordingReadbackSlots<StatsMetadata> readbacks() { return statsReadbacks; }
    static boolean hasFreeSlot() { return statsReadbacks.hasFreeSlot(); }
    static long dispatchCounter() { return dispatchCounter; }
    static void advanceDispatch() { dispatchCounter++; }
    static RtWorld.FrameStats lastStats() { return lastStats; }
    static long blasBuiltTotal() { return blasBuiltTotal; }
    static StatsMetadata lastStatsMetadata() { return lastStatsMetadata; }
    static long lastStatsTrustedPixels() { return lastStatsTrustedPixels; }
    static double lastHitPct() { return lastHitPct; }
    static double lastRejectedPct() { return lastRejectedPct; }
    static RtStatsMetering.Breakdown lastRejectSplit() { return lastRejectSplit; }
    static double lastHistPct() { return lastHistPct; }
    static double lastLeafPct() { return lastLeafPct; }
    static String lastTransparency() { return lastTransparency; }
    static float lastExposureValue() { return lastExposureValue; }
    static double lastAvgLum() { return lastAvgLum; }

    /** The slot buffer, created on the first traced frame and kept for the session. */
    static RtMemory.Buf ensureStatsBuffer(RtWorld world) {
        if (statsBuf == null) {
            statsBuf = world.memory().createBuffer("rt_stats", (long) SLOTS * STRIDE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        }
        return statsBuf;
    }

    static void recordWorldStats(RtWorld.FrameStats stats) {
        lastStats = stats;
        blasBuiltTotal += stats.builtThisFrame();
    }

    /** Completed readbacks describe a retired scene; keep them out of the new exposure history. */
    static void drainReady() {
        for (var lease : statsReadbacks.ready()) statsReadbacks.consume(lease);
    }

    static void clearLastStats() { lastStats = null; }

    static void resetHistoryShares() {
        exposure.reset();
        lastHistPct = -1.0;
        lastLeafPct = -1.0;
    }

    static void destroyStatsBuffer(RtMemory memory) {
        if (statsBuf != null) {
            memory.destroy(statsBuf);
            statsBuf = null;
        }
    }

    static void resetAfterShutdown() {
        statsReadbacks = new RecordingReadbackSlots<>(SLOTS);
        lastStatsSequence = -1;
        lastStatsMetadata = null;
        lastStatsTrustedPixels = 0L;
        lastTransparency = "[RT-TRANSPARENCY] counters=WAITING";
    }

    /** Only completed, still-leased slots may be read; sequence orders results, never proves completion. */
    static void consumeCompletedStats() {
        var ready = new java.util.ArrayList<>(statsReadbacks.ready());
        ready.sort(java.util.Comparator.comparingLong(lease -> lease.metadata().sequence()));
        for (var lease : ready) {
            var metadata = lease.metadata();
            if (metadata.sequence() > lastStatsSequence) {
                long base = statsBuf.mapped + (long) lease.slot() * STRIDE;
                int hits = MemoryUtil.memGetInt(base + RtStatsLayout.HITS);
                int samples = MemoryUtil.memGetInt(base + RtStatsLayout.SAMPLES);
                lastStatsMetadata = metadata;
                lastStatsTrustedPixels = Integer.toUnsignedLong(samples);
                lastHitPct = RtStatsMetering.hitPct(metadata.width(), metadata.height(), metadata.frameIndex(), hits, samples);
                // The counter pass only adds the samples it trusts, so this slot's own extent and rotation say how
                // many pixels it weighed: the difference is the share the tracer refused, which no other word carries.
                lastRejectedPct = RtStatsMetering.rejectedPct(metadata.width(), metadata.height(),
                    metadata.frameIndex(), samples);
                // The trace pass's own words: why pixels were refused, how many paths ran short, how many shadow walks
                // gave up. The metered word must equal this mask's count, or the native stand-in left it zero: n/a.
                lastRejectSplit = RtStatsMetering.breakdown(metadata.width(), metadata.height(),
                    metadata.frameIndex(), MemoryUtil.memGetInt(base + RtStatsLayout.PATH_REJECTS),
                    MemoryUtil.memGetInt(base + RtStatsLayout.PATH_REJECTS + 4),
                    MemoryUtil.memGetInt(base + RtStatsLayout.PATH_REJECTS + 8),
                    MemoryUtil.memGetInt(base + RtStatsLayout.PATH_REJECTS + 12));
                lastTransparency = metadata.transparency() ? String.format(Locale.ROOT,
                    "[RT-TRANSPARENCY] counters=READBACK queries=%d candidates=%d layers=%d overflows=%d direct_shadow_queries=%d indirect_shadow_queries=%d",
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_QUERIES)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_CANDIDATES)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_LAYERS)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_OVERFLOWS)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_DIRECT_SHADOWS)),
                    Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.TRANS_INDIRECT_SHADOWS)))
                    : "[RT-TRANSPARENCY] counters=WAITING";
                lastHistPct = -1.0;
                lastLeafPct = -1.0;
                if (lastHitPct >= 0.0 && hits > 0) {
                    // Without the accumulating variant histAccepted is never incremented, and the share used to print as a
                    // plausible 0% ("history rejected every pixel") in exactly the sessions that have no history at all.
                    lastHistPct = RtAccumulation.acceptedPct(MemoryUtil.memGetInt(base + RtStatsLayout.HIST_ACCEPTED), hits,
                        metadata.accumulated());
                    // leafBacklit is the one head counter with no writer. The legacy transparency pass counted the
                    // sun-through-a-leaf hits behind its own 1-in-16 mask and the statistics pass keeps no count of it,
                    // so a zero here is the absence of a measurement, not a share of 0%: -1 is what the F3 line prints
                    // as n/a.
                    int leafBacklit = MemoryUtil.memGetInt(base + RtStatsLayout.LEAF_BACKLIT);
                    lastLeafPct = leafBacklit > 0 && leafBacklit <= hits ? 100.0 * leafBacklit / hits : -1.0;
                }
                if (samples > 0) {
                    exposure.setMax(RtSkyState.maxExposure(metadata.day()));
                    long expNow = metadata.recordedNanos();
                    exposure.observe(Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.LUM_LOG_SUM)), samples,
                        lastExposureNanos == 0L ? 1.0f / 60.0f : (float) ((expNow - lastExposureNanos) / 1.0e9));
                    lastExposureNanos = expNow;
                    lastExposureValue = exposure.value();
                    long sumLog = Integer.toUnsignedLong(MemoryUtil.memGetInt(base + RtStatsLayout.LUM_LOG_SUM));
                    lastAvgLum = sumLog == 0L ? 0.0 : Math.pow(2.0, sumLog / 64.0 / samples - 16.0);
                }
                lastStatsSequence = metadata.sequence();
            }
            statsReadbacks.consume(lease);
        }
    }

    /** GPU writes become visible to coherent mapped host memory after this recording completes. */
    static void recordStatsHostVisibility(VkCommandBuffer cmd, int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(statsBuf.handle).offset((long) slot * STRIDE).size(STRIDE);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0, null, barrier, null);
        }
    }
}