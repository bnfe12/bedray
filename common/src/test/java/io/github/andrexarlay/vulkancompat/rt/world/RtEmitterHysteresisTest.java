package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lights in the published list stay unless a newcomer is clearly more important (2026-11-07: the hard top-K swapped
 * 97 of 512 lights per publish on the testbed, and every swap dropped that light's NEE and DI history at once).
 */
class RtEmitterHysteresisTest {

    @Test
    void scoreIsMonotonicInImportance() {
        float previous = -1f;
        for (float importance = 1e-6f; importance < 1e6f; importance *= 1.05f) {
            float score = RtEmitters.selectionScore(importance, 0L, false);
            assertTrue(score >= previous, "score must not decrease with importance at " + importance);
            previous = score;
        }
    }

    @Test
    void scoreIsBoundedAndExact() {
        float max = RtEmitters.selectionScore(Float.MAX_VALUE, -1L, true);
        assertTrue(max < 2048f, "the score must stay where a float is exact to 1/4096: " + max);
        assertEquals(RtEmitters.selectionScore(3.7f, 12345L, false), RtEmitters.selectionScore(3.7f, 12345L, false), 0f);
    }

    @Test
    void aPublishedLightBeatsASlightlyBrighterNewcomer() {
        float published = RtEmitters.selectionScore(1.0f, 1L << 60, true);
        float newcomer = RtEmitters.selectionScore(1.3f, 7L << 60, false);
        assertTrue(published > newcomer, "a newcomer 30% more important must not evict a published light");
        float clearlyBetter = RtEmitters.selectionScore(2.0f, 7L << 60, false);
        assertTrue(clearlyBetter > published, "a newcomer twice as important still wins");
    }

    @Test
    void stickKeyIgnoresTheOrigin() {
        float[] r = new float[RtEmitters.FLOATS];
        for (int i = 3; i < RtEmitters.FLOATS; i++) r[i] = 0.1f * i;
        // the same world corner (100.25, 64.5, -30.125) seen from two origins
        long a = RtEmitters.stickKey(100.25f - 96, 64.5f - 60, -30.125f + 40, r, 0, 96, 60, -40);
        long b = RtEmitters.stickKey(100.25f - 0, 64.5f - 0, -30.125f - 0, r, 0, 0, 0, 0);
        assertEquals(a, b, "re-anchoring the origin must not make a published light lose its membership bonus");
        r[14] = 99f; // emission (word 14) is not part of the identity: a light whose brightness changes stays sticky
        assertEquals(a, RtEmitters.stickKey(100.25f - 96, 64.5f - 60, -30.125f + 40, r, 0, 96, 60, -40));
    }

    @Test
    void hysteresisIsOnByDefault() throws java.io.IOException {
        assertEquals(4, RtEmitters.STICKY_BUCKETS);
        // The declared default, not the runtime field: tests share a config/bedray.toml that may hold an older saved value.
        String settings = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/rtx/config/RtSettings.java"));
        assertTrue(settings.contains("public static int lightBudget = 2048;"),
            "a 512 budget cut a 531-light room and swapped 365 lights per publish");
        String page = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/sodium/VulkanSodiumConfig.java"));
        assertTrue(page.contains("intOpt(builder, \"rt_light_budget\", 2048,"), "the Sodium page resets to the same default");
    }

    @Test
    void bothSelectionPathsUseTheStickyScore() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtEmitters.java"));
        String sticky = "float score = selectionScore(importance, key, wasPublished(key));";
        int first = source.indexOf(sticky);
        assertTrue(first >= 0 && source.indexOf(sticky, first + 1) > first,
            "block lights and entity lights both get the membership bonus");
        assertTrue(source.contains("addCandidate(r, o, dx, dy, dz, score);"), "the heap orders block lights by the score");
        assertTrue(source.contains("publishedKeys = keys;"), "the bonus follows the last published list");
    }
}
