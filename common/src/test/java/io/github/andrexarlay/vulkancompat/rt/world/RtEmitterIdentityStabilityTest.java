package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Light identity must not depend on where the emitter origin happens to be, and entity lights must be placed with the
 * camera of the frame that publishes them (2026-11-07 instability audit, steps 1-2 of the emitter plan).
 */
class RtEmitterIdentityStabilityTest {

    @Test
    void snappedCornersSurviveEveryOriginExactly() {
        // EmitterTable.Key rebuilds the world position as (double) origin + float(local + dx); on the grid that float
        // addition is exact, so the key is the same for every origin.
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 20_000; i++) {
            float local = RtEmitters.snapCorner(random.nextFloat() * 16f);
            int sectionDx = random.nextInt(1201) - 600;   // section*16 - origin, within a 600-block window
            float shifted = local + sectionDx;
            assertEquals((double) local + sectionDx, (double) shifted, 0.0,
                "corner " + local + " + " + sectionDx + " rounded: the identity key would change with the origin");
        }
    }

    @Test
    void unsnappedCornersDidChangeWithTheOrigin() {
        // The failure the snap removes: an arbitrary spot-shrunk corner is not exact once a large offset is added.
        float local = 3.1234567f;
        float shifted = local + 513;
        assertTrue((double) shifted != (double) local + 513, "control: without the grid the sum rounds");
    }

    @Test
    void snapMovesACornerByAtMostHalfAGridStep() {
        for (float v = -2048f; v <= 2048f; v += 0.37f) {
            assertTrue(Math.abs(RtEmitters.snapCorner(v) - v) <= 0.5f / RtEmitters.CORNER_GRID + Math.ulp(v),
                "snap error at " + v);
        }
    }

    @Test
    void entityOffsetUsesThePublishingCamera() {
        assertEquals(7.25f, RtEmitters.entityOffset(7.25, 0L), 0.0f);
        assertEquals(-2.5f, RtEmitters.entityOffset(100.5, 103L), 0.0f);
    }

    @Test
    void publishPlacesEntityLightsWithThisFramesCamera() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtEmitters.java"))
            .replace("\r\n", "\n");
        assertTrue(source.contains("processEntities(entityQuads, entityCount,\n"
                + "            entityOffset(camX, originX), entityOffset(camY, originY), entityOffset(camZ, originZ));"),
            "a scan that finishes frames later publishes entity lights with the current camera, not the scan's first one");
        assertTrue(source.contains("addCandidate(entityQuads, o, ex, ey, ez, importance);"),
            "processEntities uses the offset it is given");
        assertTrue(source.contains("entityInRange = 0; // restoreStatic restores only the static counters"),
            "the entity-only path resets its counter (the log read entity_quads=47/3948)");
        assertTrue(source.contains("dst[o] = snapCorner(px + e1x * aMin + e2x * bMin);"), "corners are snapped at build time");
    }
}
