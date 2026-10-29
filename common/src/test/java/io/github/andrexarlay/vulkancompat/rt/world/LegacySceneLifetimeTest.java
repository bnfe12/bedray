package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacySceneLifetimeTest {
    @Test void completionPermitsOneUpdateAndFrameAgeNeverPermitsAnother() {
        var lifetime = new LegacySceneLifetime();
        int[] collections = {0};
        assertThrows(IllegalStateException.class, () -> lifetime.begin(17));
        lifetime.prepareAfterHostDrain(() -> collections[0]++);
        lifetime.begin(17);
        for (int frame = 0; frame < 100; frame++) {
            assertThrows(IllegalStateException.class, () -> lifetime.begin(18));
            assertThrows(IllegalStateException.class, () -> lifetime.prepareAfterHostDrain(() -> collections[0]++));
        }
        lifetime.release(18);
        assertThrows(IllegalStateException.class, lifetime::requireReleased);
        assertEquals(1, collections[0]);
        lifetime.release(17);
        assertDoesNotThrow(lifetime::requireReleased);
        assertThrows(IllegalStateException.class, () -> lifetime.begin(18), "completion alone does not replace the next host drain");
        lifetime.prepareAfterHostDrain(() -> collections[0]++);
        lifetime.begin(18);
        assertEquals(2, collections[0]);
    }

    @Test void failedCollectionAndInvalidHandlesNeverPermitMappedWrites() {
        var lifetime = new LegacySceneLifetime();
        lifetime.prepareAfterHostDrain(() -> {});
        assertThrows(IllegalStateException.class, () -> lifetime.prepareAfterHostDrain(() -> {
            throw new IllegalStateException("retirement failed");
        }));
        assertThrows(IllegalStateException.class, () -> lifetime.begin(17));
        lifetime.prepareAfterHostDrain(() -> {});
        assertThrows(IllegalStateException.class, () -> lifetime.begin(0));
        lifetime.begin(17);
        assertThrows(IllegalStateException.class, lifetime::requireReleased);
    }

    @Test void exactHostTicketsProtectOpenUnknownAndReusedCommandBuffers() {
        var lifetime = new LegacySceneLifetime();
        var completion = new RecordingCompletion(failure -> fail(failure));
        List<Runnable> callbacks = new ArrayList<>();
        lifetime.prepareAfterHostDrain(() -> {});
        var first = completion.begin(17, () -> lifetime.release(17));
        lifetime.begin(17);
        completion.afterHostDrain(); // An open CB was never executed or deliberately abandoned.
        assertThrows(IllegalStateException.class, () -> lifetime.prepareAfterHostDrain(() -> {}));
        completion.submissionAttempted(first);
        completion.submitted(first, callbacks::add);
        completion.afterHostDrain();
        lifetime.prepareAfterHostDrain(() -> {});
        var second = completion.begin(17, () -> lifetime.release(17));
        lifetime.begin(17);
        callbacks.getFirst().run(); // A late old callback cannot release the new handle generation.
        assertThrows(IllegalStateException.class, () -> lifetime.prepareAfterHostDrain(() -> {}));
        completion.submissionAttempted(second); // Unknown execute outcome stays pinned until host proof.
        assertThrows(IllegalStateException.class, () -> lifetime.prepareAfterHostDrain(() -> {}));
        completion.afterHostDrain();
        lifetime.prepareAfterHostDrain(() -> {});
        var abandoned = completion.begin(18, () -> lifetime.release(18));
        lifetime.begin(18);
        completion.discard(abandoned);
        assertDoesNotThrow(lifetime::requireReleased);
        assertThrows(IllegalStateException.class, () -> lifetime.begin(19));
    }

    @Test void rendererDrainsActualLegacyOwnerBeforeOpeningAnyLocalRecording() throws Exception {
        String renderer = source("RtFrameRenderer.java");
        int gate = renderer.indexOf("if (world.requiresLegacyHostDrain())");
        int drain = renderer.indexOf("drainHostSubmissions(device, \"legacy_world_inputs_drain\")", gate);
        int prepare = renderer.indexOf("world.prepareLegacyFrameAfterHostDrain()", drain);
        int allocate = renderer.indexOf("encoder.allocateAndBeginTransientCommandBuffer()", prepare);
        int ticket = renderer.indexOf("recordingCompletion.beginWithDiscard", allocate);
        int update = renderer.indexOf("recordingWorld.update(cmd", ticket);
        assertTrue(gate >= 0 && drain > gate && prepare > drain && allocate > prepare && ticket > allocate && update > ticket);
        String world = source("RtWorld.java");
        assertTrue(world.contains("public boolean requiresLegacyHostDrain() {\n        return nativeScene == null;"));
        int guard = world.indexOf("if (nativeScene == null) legacyLifetime.begin(cmd.address());");
        assertTrue(guard >= 0 && guard < world.indexOf("frameIndex++;"));
        assertTrue(world.contains("legacyLifetime.prepareAfterHostDrain(() -> {\n            releaseDeferredAfterHostDrain();\n            entityBlas.releaseRetiredAfterHostDrain();\n            if (omm != null) omm.releaseRetiredAfterHostDrain();"));
        assertEquals(2, world.split("legacyLifetime.release\\(commandBuffer\\);", -1).length - 1,
            "both completed and deliberately discarded recordings release their exact input owner");
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
        assertTrue(world.contains("frameIndex % STAGING_SEGMENTS"), "ring layout remains a capacity choice");
    }

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world", file)).replace("\r\n", "\n");
    }
}
