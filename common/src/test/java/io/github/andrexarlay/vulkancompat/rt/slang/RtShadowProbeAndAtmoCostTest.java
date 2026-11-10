package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FPS items 2-4 of the 2026-11-08 frame-cost review (read-only workflow + skeptic). */
class RtShadowProbeAndAtmoCostTest {
    private static final String SHADERS = "src/main/resources/shaders/slang/";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(SHADERS + path)).replace("\r\n", "\n");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    void theProbeIsAFirstHitQueryThatOnlyDecidesClearOrOpaque() throws IOException {
        assertTrue(read("contracts/scene_tracer.slang").contains(
                "ShadowProbe traceShadowProbe(Ray ray) {\n        return undecidedShadowProbe(ray.tMax);"),
            "backends without hardware queries keep the full walk");
        String scene = read("traversal/scene_traversal.slang");
        assertEquals(1, count(scene, "override ShadowProbe traceShadowProbe(Ray ray) {"));
        for (String source : new String[] {scene}) {
            assertTrue(source.contains("query.TraceRayInline(tlas, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, instanceMask, desc);"));
            assertTrue(source.contains("if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) sawNonOpaque = true;"),
                "non-opaque candidates are never committed by the probe, only noted");
            assertTrue(source.contains("} else if (!sawNonOpaque) {\n            probe.state = SHADOW_PROBE_CLEAR;"),
                "clear only when the segment met no candidate the material walk would have to judge");
        }
        assertTrue(read("traversal/reordering_traversal.slang").contains(
            "override ShadowProbe traceShadowProbe(Ray ray) { return base.traceShadowProbe(ray); }"), "SER forwards it");
    }

    @Test
    void theWalkTrustsAnOpaqueHitOnlyWhenTheSurfaceLayerWouldBlockToo() throws IOException {
        String visibility = read("surface/visibility.slang");
        assertTrue(visibility.contains("if (probe.state == SHADOW_PROBE_OPAQUE_HIT && visibilityOpaqueSectionBlocker(probe.blocker)) {"),
            "an opaque OMM leaf texel or a foliage triangle still takes the walk (it may transmit)");
        assertEquals(1, count(visibility, "ShadowProbe probe = traversal.traceShadowProbe(ray);"),
            "plain walk only; the water walk keeps its per-segment absorption");
    }

    @Test
    void bounceVerticesIndoorsShiftTheirKeyLightShareToEmitters() throws IOException {
        String emitters = read("light/block_emitters.slang");
        assertTrue(emitters.contains("float keyShare = (1.0 - emitterProbability) * indoorKeyLightProbability(receiver);"));
        assertTrue(emitters.contains("emitterSample.sourcePdf *= emitterShare;") && emitters.contains("sunSample.sourcePdf *= keyShare;"),
            "each branch divides by its own probability");
    }

    @Test
    void godRayStepsFollowTheRayLength() throws IOException {
        String volumetric = read("light/volumetric.slang");
        assertTrue(volumetric.contains("uint n = volumetricStepCount(tEnd, steps);"));
        assertTrue(volumetric.contains("return clamp(uint(ceil(max(tEnd, 0.0) / VOLUMETRIC_STEP_BLOCKS)), 2u, maxSteps);"));
        // mirror of the shader formula: a 3-block shaft takes 2 steps, a 100-block one the quality's 12
        assertEquals(2, steps(3f, 12));
        assertEquals(12, steps(100f, 12));
        assertEquals(4, steps(10f, 12));
    }

    private static int steps(float tEnd, int quality) {
        int max = Math.max(2, Math.min(48, quality));
        return Math.max(2, Math.min(max, (int) Math.ceil(Math.max(tEnd, 0f) / 3.0f)));
    }
}
