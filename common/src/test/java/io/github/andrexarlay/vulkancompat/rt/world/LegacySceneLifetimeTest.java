package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact ownership of the legacy scene's shared inputs. Since 2026-11-08 a legacy update no longer drains the host
 * queue every frame (the GPU idled ~3 ms per frame waiting for the CPU to record the next one): a recording owns its
 * staging/instance segment and everything retired while it was the newest recording, and only that recording's exact
 * completion, deliberate discard or a host drain releases them. Frame age never releases anything.
 */
class LegacySceneLifetimeTest {
    private static final int SEGMENTS = 3;

    @Test void consecutiveUpdatesNeedNoDrainUntilTheirSegmentIsStillOwned() {
        var lifetime = new LegacySceneLifetime(SEGMENTS);
        assertFalse(lifetime.segmentBusy(1));
        lifetime.begin(101, 1);
        assertFalse(lifetime.segmentBusy(2));
        lifetime.begin(102, 2);
        assertFalse(lifetime.segmentBusy(3));
        lifetime.begin(103, 3);
        assertTrue(lifetime.segmentBusy(4), "frame 4 writes segment 1, still owned by the frame-1 recording");
        assertThrows(IllegalStateException.class, () -> lifetime.begin(104, 4), "an owned segment is never rewritten");
        for (int age = 0; age < 100; age++) assertTrue(lifetime.segmentBusy(4), "frame age never frees a segment");
        lifetime.release(101);
        assertFalse(lifetime.segmentBusy(4));
        lifetime.begin(104, 4);
        assertEquals(3, lifetime.owned());
    }

    @Test void retiredObjectsWaitForTheNewestRecordingThatCouldReferenceThem() {
        var lifetime = new LegacySceneLifetime(SEGMENTS);
        List<String> freed = new ArrayList<>();
        lifetime.retire(() -> freed.add("idle"));
        assertEquals(List.of("idle"), freed, "with no recording in flight nothing can reference it");
        lifetime.begin(101, 1);
        lifetime.retire(() -> freed.add("a"));
        lifetime.begin(102, 2);
        lifetime.retire(() -> freed.add("b"));
        lifetime.release(102);
        assertEquals(List.of("idle", "b"), freed);
        lifetime.release(101);
        assertEquals(List.of("idle", "b", "a"), freed);
        lifetime.release(101);
        assertEquals(3, freed.size(), "a release is exact and happens once");
    }

    @Test void aDiscardedRecordingHandsItsRetiredObjectsToTheRecordingsBeforeIt() {
        var lifetime = new LegacySceneLifetime(SEGMENTS);
        List<String> freed = new ArrayList<>();
        lifetime.begin(101, 1);
        lifetime.begin(102, 2);
        lifetime.retire(() -> freed.add("removed-in-2"));
        lifetime.discard(102);
        assertTrue(freed.isEmpty(), "the frame-1 TLAS may still reference what frame 2 removed");
        assertFalse(lifetime.segmentBusy(2), "the discarded recording never executed, its segment is free");
        lifetime.release(101);
        assertEquals(List.of("removed-in-2"), freed);
        lifetime.begin(103, 3);
        lifetime.retire(() -> freed.add("last"));
        lifetime.discard(103);
        assertEquals(List.of("removed-in-2", "last"), freed, "nothing older is in flight");
    }

    @Test void invalidHandlesAndDoubleOwnershipAreRejected() {
        var lifetime = new LegacySceneLifetime(SEGMENTS);
        assertThrows(IllegalArgumentException.class, () -> new LegacySceneLifetime(1));
        assertThrows(IllegalStateException.class, () -> lifetime.begin(0, 1));
        lifetime.begin(101, 1);
        assertThrows(IllegalStateException.class, () -> lifetime.begin(101, 2), "a handle owns one recording at a time");
        assertThrows(IllegalStateException.class, lifetime::requireReleased);
        lifetime.release(101);
        assertDoesNotThrow(lifetime::requireReleased);
    }

    @Test void exactHostTicketsDecideReleaseIncludingUnknownExecuteOutcomes() {
        var lifetime = new LegacySceneLifetime(SEGMENTS);
        var completion = new RecordingCompletion(failure -> fail(failure));
        List<Runnable> callbacks = new ArrayList<>();
        List<String> freed = new ArrayList<>();
        var first = completion.begin(17, () -> lifetime.release(17));
        lifetime.begin(17, 1);
        lifetime.retire(() -> freed.add("first"));
        completion.submissionAttempted(first);
        completion.submitted(first, callbacks::add);
        var second = completion.begin(18, () -> lifetime.release(18));
        lifetime.begin(18, 2);
        completion.submissionAttempted(second); // Unknown execute outcome stays pinned until host proof.
        callbacks.getFirst().run();
        assertEquals(List.of("first"), freed);
        assertTrue(lifetime.segmentBusy(5), "frame 5 reuses the unknown recording's segment");
        completion.afterHostDrain();
        assertDoesNotThrow(lifetime::requireReleased);
    }

    @Test void rendererDrainsOnlyForAnOwnedSegmentOrMicromaps() throws Exception {
        String renderer = source("RtFrameRenderer.java");
        int gate = renderer.indexOf("if (world.requiresLegacyHostDrain())");
        int drain = renderer.indexOf("drainHostSubmissions(device, \"legacy_world_inputs_drain\")", gate);
        int prepare = renderer.indexOf("world.prepareLegacyFrameAfterHostDrain()", drain);
        int allocate = renderer.indexOf("encoder.allocateAndBeginTransientCommandBuffer()", prepare);
        int ticket = renderer.indexOf("recordingCompletion.beginWithDiscard", allocate);
        int barrier = renderer.indexOf("VkBarriers.fullMemoryBarrier(cmd);", ticket);
        int update = renderer.indexOf("recordingWorld.update(cmd", ticket);
        assertTrue(gate >= 0 && drain > gate && prepare > drain && allocate > prepare && ticket > allocate
            && barrier > ticket && update > barrier,
            "the full barrier orders this frame's AS builds and scratch after earlier frames on the queue");
        String world = source("RtWorld.java");
        assertTrue(world.contains("return nativeScene == null && (omm != null || legacyLifetime.segmentBusy(frameIndex + 1));"),
            "micromap payloads are rewritten in place, so they keep the per-frame drain");
        int guard = world.indexOf("if (nativeScene == null) legacyLifetime.begin(cmd.address(), frameIndex + 1);");
        assertTrue(guard >= 0 && guard < world.indexOf("frameIndex++;"));
        assertTrue(world.contains("legacyLifetime.release(commandBuffer);"));
        assertTrue(world.contains("legacyLifetime.discard(commandBuffer);"));
        assertFalse(world.contains("deferred.add("), "removed sections are retired to their exact owner");
        assertTrue(source("RtEntityBlas.java").contains("retire.accept("), "a grown entity BLAS is retired to its exact owner");
    }

    @Test void retirementHasNoFrameWindowAndScratchGrowthPinsThePreviousBuffer() throws Exception {
        String world = source("RtWorld.java");
        String entities = source("RtEntityBlas.java");
        String omm = source("OpacityMicromapCache.java");
        assertFalse(world.contains("DEFER_FRAMES"));
        assertFalse(entities.contains("RETIRE_BUILDS"));
        assertFalse(omm.contains("RETIRED_FRAMES"));
        assertFalse(omm.contains("vkDeviceWaitIdle"));
        assertTrue(omm.contains("if (!refs.containsKey(r.handle))"), "live BLAS references still keep retired micromaps");
        assertTrue(omm.contains("if (mmScratch != null) retiredBufs.add(mmScratch);"));
        assertTrue(world.contains("frameIndex % STAGING_SEGMENTS"), "ring layout");
        assertTrue(world.contains("new LegacySceneLifetime(STAGING_SEGMENTS)"), "ownership is per staging segment");
    }

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world", file)).replace("\r\n", "\n");
    }
}
