package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingReadbackSlots;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Locale;

/**
 * Owns the GPU statistics slot ring and everything read back from it: the mapped buffer, the per-recording leases, the
 * sequence that orders results, and the host-side counters (hit / reject / leaf / history shares, transparency telemetry
 * and the auto-exposure meter). The renderer borrows the buffer handle to zero and fill a slot, then hands the lease back
 * here once the pass has written it; only this owner reads completed slots.
 */
final class RtFrameStatsTracker {
    static final int STATS_SLOTS = 3;
    private static final int STATS_STRIDE = RtStatsLayout.SLOT_STRIDE;

    record StatsMetadata(boolean accumulated, boolean transparency, float day, long sequence,
                         long recordedNanos, int width, int height, int frameIndex) {}

    private RtMemory.Buf statsBuf;
    private RecordingReadbackSlots<StatsMetadata> statsReadbacks = new RecordingReadbackSlots<>(STATS_SLOTS);
    private long lastStatsSequence = -1;
    private long dispatchCounter = 0;
    private double lastHitPct = -1.0;
    /** Share of the metered pixels the tracer refused; -1 until a counter slot carries a measurement. */
    private double lastRejectedPct = -1.0;
    /** The trace's split of the frame's bad endings, or null while no slot carried a measurement this host agrees with. */
    private RtStatsMetering.Breakdown lastRejectSplit;
    /** Share of the hit pixels whose key light reached them through a leaf card (1-in-16 mask, like hist_pct). */
    private double lastLeafPct = -1.0;
    private volatile String lastTransparency = "[RT-TRANSPARENCY] counters=WAITING";
    private volatile double lastHistPct = -1.0;
    private final RtExposure exposure = new RtExposure();
    private long lastExposureNanos;
    private volatile float lastExposureValue = 1.1f;
    private volatile double lastAvgLum;
    /** Last per-frame statistics of {@link RtWorld#update} (F3 / PerfMonitor), null before the first RT frame. */
    private volatile RtWorld.FrameStats lastStats;
    private long blasBuiltTotal = 0;

    RtMemory.Buf statsBuf() { return statsBuf; }
    RecordingReadbackSlots<StatsMetadata> slots() { return statsReadbacks; }
    RtExposure exposure() { return exposure; }
    long dispatchCounter() { return dispatchCounter; }
    void incrementDispatchCounter() { dispatchCounter++; }
    double lastHitPct() { return lastHitPct; }
    double lastRejectedPct() { return lastRejectedPct; }
    RtStatsMetering.Breakdown lastRejectSplit() { return lastRejectSplit; }
    double lastLeafPct() { return lastLeafPct; }
    double lastHistPct() { return lastHistPct; }
    String lastTransparency() { return lastTransparency; }
    float lastExposureValue() { return lastExposureValue; }
    double lastAvgLum() { return lastAvgLum; }
    RtWorld.FrameStats lastStats() { return lastStats; }
    long blasBuiltTotal() { return blasBuiltTotal; }

    void recordWorldStats(RtWorld.FrameStats stats) {
        lastStats = stats;
        blasBuiltTotal += stats.builtThisFrame();
    }

    void ensureStatsBuffer(RtMemory memory) {
        if (statsBuf == null) {
            statsBuf = memory.createBuffer("rt_stats", (long) STATS_SLOTS * STATS_STRIDE,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                    | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        }
    }

    void clearStatsBuf() { statsBuf = null; }

    /** Only completed, still-leased slots may be read; sequence orders results, never proves completion. */
    void consumeCompletedStats() {
        var ready = statsReadbacks.ready().stream()
            .sorted(java.util.Comparator.comparingLong(lease -> lease.metadata().sequence())).toList();
        for (var lease : ready) {
            var metadata = lease.metadata();
            if (metadata.sequence() > lastStatsSequence) {
                long base = statsBuf.mapped + (long) lease.slot() * STATS_STRIDE;
                int hits = MemoryUtil.memGetInt(base + RtStatsLayout.HITS);
                int samples = MemoryUtil.memGetInt(base + RtStatsLayout.SAMPLES);
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
                if (hits > 0) {
                    // Without the accumulating variant histAccepted is never incremented, and the share used to print as a
                    // plausible 0% ("history rejected every pixel") in exactly the sessions that have no history at all.
                    lastHistPct = RtAccumulation.acceptedPct(MemoryUtil.memGetInt(base + RtStatsLayout.HIST_ACCEPTED), hits,
                        metadata.accumulated());
                    // leafBacklit is the one head counter with no writer. The legacy transparency pass counted the
                    // sun-through-a-leaf hits behind its own 1-in-16 mask and the statistics pass keeps no count of it,
                    // so a zero here is the absence of a measurement, not a share of 0%: -1 is what the F3 line prints
                    // as n/a.
                    int leafBacklit = MemoryUtil.memGetInt(base + RtStatsLayout.LEAF_BACKLIT);
                    lastLeafPct = leafBacklit > 0 ? 100.0 * leafBacklit / hits : -1.0;
                }
                if (samples > 0) {
                    lastHitPct = 100.0 * hits / samples;
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
    void recordStatsHostVisibility(VkCommandBuffer cmd, int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(statsBuf.handle).offset((long) slot * STATS_STRIDE).size(STATS_STRIDE);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0, null, barrier, null);
        }
    }

    void resetHistoryShares() {
        lastHistPct = -1.0;
        lastLeafPct = -1.0;
    }

    void resetAfterShutdown() {
        statsReadbacks = new RecordingReadbackSlots<>(STATS_SLOTS);
        lastStatsSequence = -1;
        lastTransparency = "[RT-TRANSPARENCY] counters=WAITING";
        lastStats = null;
    }
}