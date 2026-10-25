package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 guards for distant block light. The emitter list is sorted by importance from the camera and a candidate outside
 * {@code EMITTER_RADIUS} of the shaded point is rejected, so a receiver far from the player has no usable candidate at all and
 * its lamps are not lit (torches in a distant village, a lit cave across the valley). Past the list's reach the vanilla
 * block-light field has to take the term over, and only there: baked light next to the player is the leak the traced emitters
 * replaced.
 */
class RtDistantBlockLightTest {

    private static final String SHADE = "/shaders/rt_common_shade.glsl";
    private static final String RAYS = "/shaders/rt_rays.comp";
    private static final String PATH = "/shaders/rt_path.comp";
    private static final String BAKED = "blockLightColor() * clamp(s.light.x, 0.0, 1.0)";

    private static String source(String resource) {
        return RtShaderCompiler.loadSource(resource).replace("\r\n", "\n");
    }

    /** Plain-text occurrence count (String.split would read the shader text as a regex). */
    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void theFallbackStartsWhereTheEmitterListStopsBeingComplete() {
        String shade = source(SHADE);
        assertTrue(shade.contains("float blockLightFarMix(float cameraDist, float listRange)"),
            "the distance rule has to be shared by the two modes, not copied into each pass");
        assertTrue(shade.contains("smoothstep(max(listRange - EMITTER_RADIUS, 8.0), listRange + EMITTER_RADIUS, cameraDist);"),
            "the list stops being complete at listRange - EMITTER_RADIUS, but a negative inner edge (rays_emissive_distance 8)"
                + " would leak baked light onto the player");
        assertTrue(shade.contains("return mix(traced, vanillaBlockIrradiance(s), far);"),
            "mix, not max: max() on a 1..4 spp estimator that reads 0 on most pixels biases the band upwards");
        assertEquals(1, count(shade, BAKED), "one vanilla field term per frame, in vanillaBlockIrradiance");
    }

    @Test
    void theFieldIsScaledToTheTracedEmitters() {
        String shade = source(SHADE);
        assertTrue(shade.contains("const float BLOCK_LIGHT_FIELD_GAIN = 0.05;"),
            "the lightmap texel is ~0.87 at level 13 while the traced torch reads ~0.05: the field has to be divided by ~20 or"
                + " the far field is 5..15x brighter than the same torch next to the player");
        assertTrue(shade.contains("* BLOCK_LIGHT_FIELD_GAIN;"), "the gain is applied inside vanillaBlockIrradiance");
    }

    @Test
    void theRaysPassBlendsInsideTheEmissiveSwitch() {
        String rays = source(RAYS);
        int flag = rays.indexOf("if (hasFlag(FLAG_EMISSIVE) && hasFlag(FLAG_MATERIALS)) {");
        assertTrue(flag > 0, "the block light switch of the primary pass moved");
        int blend = rays.indexOf("distantBlockLight(emissiveLight, s, cameraDist, listRange);");
        int indirect = rays.indexOf("// Indirect light:", flag);
        assertTrue(blend > flag && blend < indirect,
            "the baked field may only stand in for the emitter list, never for an emissive lighting the user turned off");
        assertTrue(rays.contains("if ((farMix < 0.999 || s.light.x <= 0.0) && emitterCount() > 0u)"),
            "a receiver the list cannot serve must not pay an NEE walk whose every candidate is rejected, but a surface"
                + " with no vanilla block light has nothing to fall back to and must keep tracing");
        assertTrue(rays.contains("float reach = emissiveDistance();"), "the near field keeps the configured NEE reach");
        assertTrue(rays.contains("float listRange = emissiveDistance() + 8.0;"),
            "the list radius of the rays pass is emissiveDistance() + 8 blocks (RtFrameRenderer), not the NEE reach");
        assertEquals(0, count(rays, BAKED), "the baked term stays behind distantBlockLight, not inline");
    }

    @Test
    void theDistanceIsMeasuredFromTheCameraNotFromTheLastInterface() {
        String rays = source(RAYS);
        assertTrue(rays.contains("float cameraDist = length(s.pos);"),
            "s.t counts from the last glass or water face and s.pos is camera-relative: a surface behind a pane must not"
                + " read as next to the player");
        String path = source(PATH);
        assertTrue(path.contains("float cameraDist = length(s.pos);"), "the path tracer measures from the same origin");
        assertEquals(0, count(rays, "blockLightFarMix(s.t"), "the hit distance is not the camera distance");
        assertEquals(0, count(path, "blockLightFarMix(s.t"), "the hit distance is not the camera distance");
    }

    @Test
    void theBakedFillFadesWithTheSameBandInsteadOfDoubleCounting() {
        String rays = source(RAYS);
        assertEquals(2, count(rays, "bakedBlockFill(s) * blockFillScale"),
            "both the direct channel and the denoised diffuse channel fade the baked fill out past the list's reach");
        assertEquals(0, count(rays, "bakedBlockFill(s) +"), "no unscaled baked fill next to the vanilla fallback term");
    }

    @Test
    void thePathTracerFallsBackOnlyOnThePrimaryHit() {
        String path = source(PATH);
        assertTrue(path.contains("float listRange = min(reach, 128.0);"),
            "the path tracer's list radius is min(path distance, 128) blocks (RtFrameRenderer)");
        assertTrue(path.contains("if (b > 0u || blockLightFarMix(cameraDist, listRange) < 0.999 || s.light.x <= 0.0)"),
            "only a primary hit whose list is exhausted skips the NEE walk, and a surface with no vanilla block light never does");
        assertTrue(path.contains("nee = distantBlockLight(nee, s, cameraDist, listRange);"),
            "a bounce is shaded where the previous vertex sent it, so its camera distance is unknown and it keeps tracing");
        assertTrue(path.contains("s.albedoLit * nee"),
            "the albedo is applied once: the estimator is called with vec3(1.0) and multiplied here");
        assertEquals(1, count(path, "sampleEmitters(s, vec3(1.0), reach, seed)"), "one NEE call site in the path tracer");
        assertEquals(0, count(path, BAKED), "the baked term must stay behind distantBlockLight, not appear inline");
    }
}
