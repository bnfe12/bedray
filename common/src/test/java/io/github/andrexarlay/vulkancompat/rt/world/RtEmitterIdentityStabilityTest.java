package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static light identity must not depend on where the emitter origin happens to be.
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

}
