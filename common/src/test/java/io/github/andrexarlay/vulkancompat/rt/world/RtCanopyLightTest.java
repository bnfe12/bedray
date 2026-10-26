package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source checks for the two changes that made dappled light readable: traced sky occlusion on the primary hit of the path
 * tracer, and foliage as a coloured shadow filter instead of an opaque cutout.
 *
 * <p>Owner report 2026-10-23: hard blue-grey texel squares of a grass tuft on snow ("looks like reflections"), a canopy
 * whose trunk and leaves crush to black, and ice / water that stay nearly black in a hollow. A JUnit test cannot shade a
 * pixel, so it guards the structural invariants: the fill keeps its calibrated 0.28 share but traces its occlusion, only the
 * shadow query treats foliage as a filter, and the filter is gated on the per-quad vegetation tag.
 */
public class RtCanopyLightTest {

    private static final String PATH_SHADER = "/shaders/rt_path.comp";
    private static final String CORE_MODULE = "/shaders/rt_common_core.glsl";
    private static final String SURFACE_MODULE = "/shaders/rt_common_surface.glsl";

    @Test
    public void primarySkyFillIsTracedNotVertexSquared() {
        String src = RtShaderCompiler.loadSource(PATH_SHADER);
        assertTrue(src.contains("vec3 primarySkyFill(Surface s, inout uint seed) {"), "the fill helper must exist in rt_path.comp");
        assertTrue(requireBlock(src, "vec3 primarySkyFill(Surface s, inout uint seed) {").contains("tracedSkyLight(s, 1u, seed)"),
            "the primary fill must trace its sky occlusion like rays mode and every secondary hit");
        assertFalse(src.contains("0.28 * skyVis * skyVis"),
            "the baked vertex sky light squared crushed every shadow under a tree and under ice to near black");
        assertTrue(src.contains("primarySkyFill(s, seed) * 0.28 * s.mapAo"),
            "the additive artistic fill keeps the open-sky budget of the old 0.28 term");
        assertFalse(src.contains("gFillSkyReady"),
            "each jittered spp hit needs its own sky visibility; a cached result can leak across an occlusion silhouette");
    }

    @Test
    public void primarySkyFillIsEvaluatedPerSppHit() {
        String src = RtShaderCompiler.loadSource(PATH_SHADER);
        String helper = requireBlock(src, "vec3 primarySkyFill(Surface s, inout uint seed) {");
        assertTrue(helper.contains("return tracedSkyLight(s, 1u, seed);"),
            "the helper must trace the current hit rather than returning cached radiance");
        assertFalse(src.contains("gFillPos"), "no position-tolerant cross-sample cache");
        assertFalse(src.contains("gFillNormal"), "no normal-tolerant cross-sample cache");
        assertFalse(src.contains("gFillLight"), "no light-tolerant cross-sample cache");
    }

    @Test
    public void primarySkyFillSeedIsNotQuantisedToAWorldCell() {
        // Owner report 2026-10-24: the underside of a beam under a canopy showed a regular grid of lit and dark squares. The
        // seed was floor(worldPos * 8), so every pixel inside one 1/8-block cell traced the same single visibility ray and
        // shared its binary result: eight cells per block face, each one entirely lit or entirely dark. The path seed is per
        // pixel and per frame (tracePath), which the denoiser can average and a quantised grid cannot.
        String src = RtShaderCompiler.loadSource(PATH_SHADER);
        String helper = requireBlock(src, "vec3 primarySkyFill(Surface s, inout uint seed) {");
        assertFalse(helper.contains("floor(s.worldPos"),
            "a world-space cell seed is what draws the 1/8-block checkerboard under a canopy");
        assertFalse(helper.contains("pcgHash"), "the fill samples from the path seed, not from a position hash");
        assertTrue(helper.contains("tracedSkyLight(s, 1u, seed)"), "the per-pixel per-frame path seed drives the sample");
        assertTrue(src.contains("primarySkyFill(s, seed) * 0.28 * s.mapAo"), "the call site hands the path seed over");
        assertTrue(src.contains("uint seed = pcgHash(uint(p.x) * 1973u + uint(p.y) * 9277u + pc.frame * 26699u + 7u);"),
            "the seed the pass hands down is per pixel and per frame, not quantised to a world cell");
    }

    @Test
    public void onlyTheShadowQueryFiltersFoliage() {
        String core = RtShaderCompiler.loadSource(CORE_MODULE);
        assertTrue(core.contains("return candidateAccepted(slot, geom, prim, bary, skipTranslucent, false);"),
            "visibility, AO, sky and camera rays keep the binary cutout: a canopy must still occlude them");
        String surface = RtShaderCompiler.loadSource(SURFACE_MODULE);
        assertTrue(surface.contains("candidateAccepted(customIndex, geom, prim, bary, true, true)"),
            "traceTransmittance is the one query that lets a vegetation texel filter the key light");
    }

    @Test
    public void foliageFilterIsVegetationTagged() {
        String core = RtShaderCompiler.loadSource(CORE_MODULE);
        String guard = requireBlock(core, "if (foliageFilter && skipTranslucent && a >= 0.5) {");
        assertTrue(guard.contains("VERT_FLAG_VEGETATION"),
            "bars, chains, panes, ladders and doors share the cutout pass: only the per-quad vegetation tag may filter");
        assertTrue(guard.contains("gSawTranslucent = true;"),
            "a filtered foliage texel must be reported as a skipped translucent layer, otherwise the transmittance walk "
                + "treats it as a hole and the leaf still casts a full shadow");
    }

    @Test
    public void emissiveVegetationStaysOpaque() {
        String core = RtShaderCompiler.loadSource(CORE_MODULE);
        String marker = "if (foliageFilter && skipTranslucent && a >= 0.5) {";
        int foliageAt = core.indexOf(marker);
        int alphaFilterAt = core.indexOf("if (skipTranslucent && a < 0.99) {");
        assertTrue(foliageAt >= 0, "the foliage candidate guard must exist");
        assertTrue(alphaFilterAt > foliageAt,
            "vegetation must be classified before the generic alpha filter, including texels with alpha 0.5..0.99");
        String guard = requireBlock(core, marker);
        assertTrue(guard.contains("if (emission >= 0.5)"),
            "the candidate gate must match Surface.isFoliage by detecting emissive plants");
        assertTrue(guard.contains("return true;"),
            "an emissive plant is an opaque blocker, not a generic coloured shadow filter");
    }

    @Test
    public void foliageLayerTransmitsInsteadOfBlocking() {
        String src = RtShaderCompiler.loadSource(SURFACE_MODULE);
        String walk = requireBlock(src, "vec3 traceTransmittance(vec3 origin, vec3 d, float tmax) {");
        assertTrue(walk.contains("if (s.isFoliage) {"), "the foliage layer needs its own transmittance in the shadow walk");
        String layer = requireBlock(walk, "if (s.isFoliage) {");
        assertTrue(layer.contains("0.60 * mix(vec3(1.0), hue, 0.45)"),
            "~60 % per texel keeps ~36 % through the entry + exit face of one leaf block, the share the sky term assumes");
        assertFalse(layer.contains("shadowLayerTransmittance"),
            "the generic Beer-Lambert layer would absorb the whole texel colour and crush a canopy back to black");
    }

    /** Text between the marker's opening brace and its matching closing brace, both excluded. */
    private static String requireBlock(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "marker not found, the guard would pass vacuously: " + marker);
        int start = at + marker.length();
        int depth = 1;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i);
            }
        }
        throw new AssertionError("unbalanced braces after marker: " + marker);
    }
}
