package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statistics cluster carved out of {@link RtFrameRenderer}: the readback slots, the decode of a completed slot into
 * the operator-facing shares and auto-exposure, and the dispatch rotation now live in {@link RtFrameStatsTracker}. The
 * renderer keeps driving the frame (it leases a slot, zeroes it, writes the header and hands the offset to the pass),
 * but it no longer owns the measurement state. This pins that boundary so a later edit cannot quietly pull the decode
 * back into the monolith, nor move the GPU plumbing the frame recording depends on out of it.
 */
class RtFrameStatsTrackerTest {

    private static final Path JAVA = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world");
    private static final Path OWNER = JAVA.resolve("RtFrameStatsTracker.java");
    private static final Path RENDERER = JAVA.resolve("RtFrameRenderer.java");
    private static final Path RENDER_LOG = JAVA.resolve("RtRenderLog.java");

    @Test
    void theOwnerHoldsTheReadbackDecodeAndTheShares() throws IOException {
        String owner = read(OWNER);
        assertTrue(owner.contains("static void consumeCompletedStats()"), "the decode moved into the owner");
        assertTrue(owner.contains("lastRejectedPct = RtStatsMetering.rejectedPct(metadata.width(), metadata.height(),"),
            "the rejected share is recomputed from the slot's own extent and rotation");
        assertTrue(owner.contains("lastRejectSplit = RtStatsMetering.breakdown(metadata.width(), metadata.height(),"),
            "the trace's split of the bad endings is read back here");
        assertTrue(owner.contains("lastLeafPct = leafBacklit > 0 && leafBacklit <= hits ? 100.0 * leafBacklit / hits : -1.0;"),
            "an unwritten leaf counter stays n/a, never a measured zero");
        assertTrue(owner.contains("sumLog / 64.0 / samples - 16.0"), "the luminance fixed point is decoded here");
        assertTrue(owner.contains("static void recordStatsHostVisibility(VkCommandBuffer cmd, int slot)"),
            "the host-visibility barrier for the slot is the owner's");
    }

    @Test
    void theOwnerKeepsTheMeasurementStateAndItsLifecycle() throws IOException {
        String owner = read(OWNER);
        assertTrue(owner.contains("record StatsMetadata(boolean accumulated, boolean transparency, float day, long sequence,"),
            "the slot metadata type moved with the slots");
        assertTrue(owner.contains("private static RecordingReadbackSlots<StatsMetadata> statsReadbacks"),
            "the readback slots are the owner's");
        assertTrue(owner.contains("private static final RtExposure exposure = new RtExposure();"), "auto-exposure is the owner's");
        assertTrue(owner.contains("private static long dispatchCounter = 0;"), "the dispatch rotation is the owner's");
        assertTrue(owner.contains("static void resetHistoryShares()") && owner.contains("exposure.reset();"),
            "re-arming exposure and clearing the history/leaf shares is one owner call");
        assertTrue(owner.contains("static void resetAfterShutdown()"), "the post-shutdown reset is the owner's");
        assertTrue(owner.contains("static void destroyStatsBuffer(RtMemory memory)"), "the slot buffer is released by the owner");
    }

    @Test
    void theFacadeNoLongerOwnsTheStatsState() throws IOException {
        String renderer = read(RENDERER);
        assertFalse(renderer.contains("private static final RtExposure exposure = new RtExposure();"),
            "exposure moved to the owner");
        assertFalse(renderer.contains("private static RtMemory.Buf statsBuf;"), "the slot buffer moved to the owner");
        assertFalse(renderer.contains("private static long dispatchCounter = 0;"), "the dispatch rotation moved to the owner");
        assertFalse(renderer.contains("record StatsMetadata("), "the metadata type moved to the owner");
        assertFalse(renderer.contains("private static void consumeCompletedStats()"), "the decode moved to the owner");
        assertTrue(renderer.contains("RtFrameStatsTracker.consumeCompletedStats()"), "the frame delegates the decode");
        assertTrue(renderer.contains("RtFrameStatsTracker.readbacks()"), "the frame leases through the owner's slots");
    }

    @Test
    void theFrameGpuPlumbingStaysInTheFacade() throws IOException {
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset, 16, 0);"),
            "the head is still zeroed where the frame records it");
        assertTrue(renderer.contains("VK10.vkCmdFillBuffer(cmd, statsBuf.handle, writeOffset + RtStatsLayout.PATH_REJECTS, 16, 0);"),
            "and so is the trace's split block");
        assertTrue(renderer.contains("params.statsBuffer = statsBuf.handle;"), "the pass still gets the slot handle here");
        assertTrue(renderer.contains("System.nanoTime(), w, h, params.frame));"),
            "the readback is still stamped with the extent and rotation the pass metered with");
        assertTrue(renderer.contains("if (traced) recordStatsHostVisibility(cmd, statsLease.slot());"),
            "the frame still rides the visibility barrier on the traced path");
    }

    @Test
    void theRenderLogReadsTheOwnerMetadataType() throws IOException {
        String renderLog = read(RENDER_LOG);
        assertTrue(renderLog.contains("RtFrameStatsTracker.StatsMetadata lastStatsMetadata();"),
            "the Context borrows the owner's metadata type");
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("RtFrameStatsTracker.StatsMetadata lastStatsMetadata()"),
            "and the facade's Context impl returns the owner's");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}