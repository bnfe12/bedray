package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A canopy is a light filter, not a wall. The deleted legacy path knew this in three places: the shadow walk multiplied
 * its transmittance by {@code 0.60 * mix(1, hue, 0.45)} per leaf face, the direct term added a velvet-style back-lit
 * lobe outside the front-face gate, and the key-light sample was taken on the sun side of the card. Without them every
 * tree shades like stone and every back-lit plant is a black dot on a sunlit slope.
 *
 * <p>The port keeps the exact rules, including the ones that are easy to lose in a rewrite: only cutout plants filter
 * (bars, panes, ladders and doors share the pass), a glowing cutout model is an opaque emitter, shadow rays start on the
 * sun side of a full leaf cube, and a camera, AO or sky query still sees the canopy as an occluder. The constants are
 * pinned here with a Java mirror of the shader arithmetic, so a silent nudge either way fails the suite.
 */
class RtSlangFoliageTransmissionTest {

    private static final String SURFACE = "src/main/resources/shaders/slang/surface/surface.slang";
    private static final String VISIBILITY = "src/main/resources/shaders/slang/surface/visibility.slang";
    private static final String SUN = "src/main/resources/shaders/slang/light/sun.slang";
    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";
    private static final String LIGHT_CONTRACT = "src/main/resources/shaders/slang/contracts/light.slang";

    // Mirrors of the shader constants in surface.slang.  Kept as duplicate literals on purpose: if the shader drifts,
    // the mirror no longer describes what ships and the numeric tests below stop meaning anything.
    private static final float LAYER_TRANSMITTANCE = 0.60f;
    private static final float HUE_MIX = 0.45f;
    private static final float BACKLIT_GAIN = 0.42f;
    private static final float[] BACKLIT_TINT = {1.05f, 1.25f, 0.75f};
    private static final float LEAF_F0 = 0.04f;

    // ---- the filter's arithmetic, mirrored from surface.slang ----

    /** mirror of {@code foliageLayerTransmittance}: 0.60 * mix(white, albedo / max(albedo), 0.45). */
    private static float[] layerTransmittance(float r, float g, float b) {
        float maximum = Math.max(r, Math.max(g, b));
        float denominator = Math.max(maximum, 1.0e-3f);
        float[] albedo = {r, g, b};
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            float hue = albedo[i] / denominator;
            result[i] = LAYER_TRANSMITTANCE * (1.0f + HUE_MIX * (hue - 1.0f));
        }
        return result;
    }

    private static float[] twoFaces(float[] oneFace) {
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            result[i] = oneFace[i] * oneFace[i];
        }
        return result;
    }

    /** mirror of the back-lit gain: leafBack * 0.42 * smoothstep(0, 0.08, leafBack) * (1 - luminance(f0)). */
    private static float backLitGain(float leafBack, float f0Luminance) {
        float t = Math.min(Math.max(leafBack / 0.08f, 0.0f), 1.0f);
        float ramp = t * t * (3.0f - 2.0f * t);
        return leafBack * BACKLIT_GAIN * ramp * (1.0f - Math.min(Math.max(f0Luminance, 0.0f), 1.0f));
    }

    private static float[] backLitColor(float r, float g, float b, float leafBack, float f0Luminance) {
        float gain = backLitGain(leafBack, f0Luminance);
        float[] albedo = {r, g, b};
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            result[i] = albedo[i] * (gain * BACKLIT_TINT[i]);
        }
        return result;
    }

    private static float[] scale(float[] v, float s) {
        return new float[] {v[0] * s, v[1] * s, v[2] * s};
    }

    @Test
    void oneLeafFaceKeepsSixtyPercentAndOneCubeKeepsItsHue() {
        float[] white = twoFaces(layerTransmittance(1.0f, 1.0f, 1.0f));
        assertEquals(0.36f, white[0], 1e-5f, "the documented ~36 % through the entry and exit face of one leaf");
        assertEquals(0.36f, white[1], 1e-5f);
        assertEquals(0.36f, white[2], 1e-5f);

        // vanilla oak leaves: a green texel must shade the light green, not grey and not black
        float[] green = twoFaces(layerTransmittance(0.30f, 0.45f, 0.15f));
        assertEquals(0.36f, green[1], 1e-4f, "the strongest channel keeps the full 60 % per face");
        assertTrue(green[1] > green[0] * 1.3f, "green must dominate red under a leaf: " + green[0] + " vs " + green[1]);
        assertTrue(green[0] > green[2] * 1.4f, "blue is the weakest channel of a green leaf: " + green[2]);
        assertTrue(green[2] > 0.1f, "two faces must not extinguish the weakest channel: " + green[2]);
    }

    @Test
    void aBlackTexelFiltersGreyInsteadOfProducingNan() {
        // the hue divisor floors at 1e-3, so an untextured or near-black plant texel is a neutral 33 % filter, never NaN
        float[] black = layerTransmittance(0.0f, 0.0f, 0.0f);
        for (float channel : black) {
            assertFalse(Float.isNaN(channel), "a black albedo must not divide by zero");
            assertEquals(0.33f, channel, 1e-5f, "neutral 0.60 * 0.55 filter");
        }
    }

    @Test
    void everyFilteredLayerObeystheAbsorberBudget() {
        for (float[] albedo : new float[][] {
            {1.0f, 1.0f, 1.0f}, {0.30f, 0.45f, 0.15f}, {0.9f, 0.9f, 0.2f}, {0.0f, 0.0f, 0.0f}}) {
            float[] t = layerTransmittance(albedo[0], albedo[1], albedo[2]);
            for (float channel : t) {
                assertTrue(channel > 0.0f && channel <= LAYER_TRANSMITTANCE + 1e-6f,
                    "one leaf face may never gain energy: " + channel);
            }
        }
    }

    @Test
    void theBacklitRampDiesAtTheSilhouetteAndSaturatesIntoTheLeaf() {
        assertEquals(0.0f, backLitGain(0.0f, LEAF_F0), 1e-7f, "a grazing leaf has no transmission lobe");
        assertTrue(backLitGain(0.002f, LEAF_F0) < 1.0e-4f, "the term fades rather than pops at the silhouette");
        assertTrue(backLitGain(0.02f, LEAF_F0) < backLitGain(0.04f, LEAF_F0), "the ramp is monotonic");
        assertTrue(backLitGain(0.04f, LEAF_F0) < backLitGain(0.08f, LEAF_F0), "the ramp is monotonic");
        assertEquals(BACKLIT_GAIN * (1.0f - LEAF_F0), backLitGain(1.0f, LEAF_F0), 1e-5f,
            "a sun behind the leaf gets the full 0.42, minus the specular share the BSDF already takes");
    }

    @Test
    void aBackLitGreenLeafIsWarmAndBrightButNotACandle() {
        float[] lit = backLitColor(0.30f, 0.45f, 0.15f, 1.0f, LEAF_F0);
        assertTrue(lit[1] > 0.2f, "a back-lit leaf must actually light up: " + lit[1]);
        assertTrue(lit[1] > lit[0] && lit[0] > lit[2], "the warm tint keeps green ahead of red and blue: "
            + lit[0] + ", " + lit[1] + ", " + lit[2]);
        // the tint only bends the spectrum; the texel's own albedo still decides the balance, exactly as the legacy
        // term multiplied the lit albedo: red/blue = (0.30 / 0.15) * (1.05 / 0.75)
        assertEquals((0.30f / 0.15f) * (BACKLIT_TINT[0] / BACKLIT_TINT[2]), lit[0] / lit[2], 1e-3f);

        // a metal leaf (f0 ~ 1) reflects instead of transmitting: the term must vanish rather than double-count
        assertEquals(0.0f, backLitGain(1.0f, 1.0f), 1e-6f, "no transmission through a metal F0");
    }

    @Test
    void theFilteredWalkCannotSeeThroughItsOwnTextureHoles() {
        String visibility = read(VISIBILITY);
        int hole = -1;
        int filter = -1;
        for (int walk = 0; walk < 2; walk++) {
            hole = visibility.indexOf("if (surfaceIsHole(surface, options.alphaCutoff)) {", hole + 1);
            filter = visibility.indexOf("materialFiltersAsFoliage(surface.material)", filter + 1);
            assertTrue(hole >= 0 && filter >= 0, "both walks must test the hole and the leaf");
            assertTrue(hole < filter,
                "a cutout hole is skipped before it can be counted as a leaf layer, or a sparse canopy would "
                    + "multiply its own alpha-zero texels into the transmittance");
        }
        assertTrue(read(VISIBILITY).contains(
            "if ((options.flags & MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE) != 0u\n"
                + "                && materialFiltersAsFoliage(surface.material)) {"),
            "the leaf branch is gated on the shadow-only flag, not on the tag alone");
    }

    @Test
    void onlyShadowRaysTreatALeafAsAFilter() {
        String visibility = read(VISIBILITY);
        assertEquals(3, count(visibility, "options.flags |= MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE;"),
            "exactly the three shadow entry points (plain, medium, beam) may turn the filter on");

        // the camera, AO and sky walks must stay as they were: a canopy still occludes them.  They read the flag (the
        // branch is inside the shared walk) but may never turn it on.
        String plain = between(visibility, "SurfaceVisibility traceFilteredVisibility<", "SurfaceVisibility traceFilteredVisibilityMedium<");
        assertTrue(plain.length() > 0, "the plain walk still exists");
        assertFalse(plain.contains("options.flags |= MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE"),
            "a camera or AO query through a leaf is still blocked; only shadow rays filter");
        String occluded = between(visibility, "bool traceFilteredOccluded<", "SurfaceVisibility traceShadowVisibility<");
        assertFalse(occluded.contains("options.flags |= MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE"),
            "the boolean visibility form must keep the binary cutout");
    }

    @Test
    void onlyCutoutPlantsThatDoNotGlowFilter() {
        String surface = read(SURFACE);
        assertTrue(surface.contains("if ((hit.flags & HIT_FLAG_FOLIAGE) != 0u) {\n"
                + "        material.sourceFlags |= MATERIAL_SOURCE_FOLIAGE;\n"
                + "    }"),
            "the tag arrives with the geometry; the atlas cannot name a plant");
        String rules = block(surface, surface.indexOf("bool materialFiltersAsFoliage(MaterialSample material) {"));
        assertTrue(rules.contains("(material.flags & MATERIAL_FLAG_CUTOUT) == 0u"),
            "bars, panes, ladders, rails and doors share the cutout pass and must not filter");
        assertTrue(rules.contains("(material.flags & MATERIAL_FLAG_EMISSIVE) == 0u"),
            "a torch flower or a campfire model drawn as a cutout is an opaque emitter: a flame transmits no sun");
        assertTrue(surface.contains("static const float FOLIAGE_LAYER_TRANSMITTANCE = 0.60;"));
        assertTrue(surface.contains("static const float FOLIAGE_HUE_MIX = 0.45;"));
        assertTrue(surface.contains("static const float FOLIAGE_BACKLIT_GAIN = 0.42;"));
        assertTrue(surface.contains("static const float3 FOLIAGE_BACKLIT_TINT = float3(1.05, 1.25, 0.75);"));
        assertTrue(surface.contains("static const uint MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE = 1u << 4u;"),
            "the filter flag must own a bit of its own");
    }

    @Test
    void theKeyLightSamplerAcceptsALeafFromEitherSide() {
        String sun = read(SUN);
        assertEquals(2, count(sun, "abs(dot(receiver.normal, direction))"),
            "sample() and evaluate() must both accept the sun from the sun side of the card");
        assertFalse(sun.contains("-receiver.normal"),
            "flipping the normal answers only the back-lit half and starves every front-lit plant of its sample");
        assertTrue(read(LIGHT_CONTRACT).contains("bool   foliage;"),
            "the receiver carries the tag to the sampler");
        assertTrue(read(LIGHT_CONTRACT).contains("receiver.foliage = false;"),
            "a receiver from the factory is one-sided unless the caller says otherwise");
    }

    @Test
    void theBacklitTermReadsTheSunSideNormal() {
        String tracer = read(TRACER);
        int flip = tracer.indexOf("if (dot(shadowNormal, view) < 0.0) {");
        int leafBack = tracer.indexOf("float leafBack = foliage && keyLight ? max(dot(-shadowNormal, arrival), 0.0) : 0.0;");
        assertTrue(flip >= 0 && leafBack > flip,
            "the sun-side normal exists before the transmission cosine reads it");
        assertTrue(tracer.contains("float leafBack = foliage && keyLight ? max(dot(-shadowNormal, arrival), 0.0) : 0.0;"),
            "the term must use the sun-facing normal; the shaded one makes every back-lit leaf black");
        assertTrue(tracer.contains("if (cosine <= 0.0 && leafBack <= 0.0) {"),
            "the transmission term lives outside the front-face gate, which is the whole point");
        assertTrue(tracer.contains("float3 estimator = leafBack > 0.0 ? leafTerm : bsdf;"),
            "a back-lit leaf is shaded by its transmission term instead of the zero BSDF");
        assertTrue(tracer.contains("float3 unshadowed = leafBack > 0.0 ? leafTerm : bsdf;"),
            "the RT Shadows switch must still light a back-lit leaf");
        String term = block(tracer, tracer.indexOf("if (leafBack > 0.0) {"));
        assertTrue(term.contains("smoothstep(0.0, 0.08, leafBack)"),
            "the silhouette ramp is what keeps the term from flickering");
        assertTrue(term.contains("(1.0 - clamp(luminance(surface.material.f0), 0.0, 1.0))"),
            "the specular share stays with the BSDF; a metal leaf transmits nothing");
        assertTrue(tracer.contains("bool foliage = (surface.material.sourceFlags & MATERIAL_SOURCE_FOLIAGE) != 0u\n"
                + "        && (surface.material.flags & MATERIAL_FLAG_EMISSIVE) == 0u;"),
            "a glowing cutout plant is not a transmitting leaf");
    }

    @Test
    void theShadowRayStartsOnTheSunSideOfTheCaster() {
        String tracer = read(TRACER);
        String origin = block(tracer, tracer.indexOf("if (leafBack > 0.0 && !medium.inside) {"));
        assertTrue(origin.contains("bool leafCube = (surface.hit.flags & HIT_FLAG_LEAF_CUBE) != 0u;"),
            "a full leaf cube is one block thick; a card is not");
        assertTrue(origin.contains("bool frontFace = (surface.hit.flags & HIT_FLAG_FRONT_FACE) != 0u;"),
            "a back-face hit is already on the sun-side face and needs no one-block step");
        assertTrue(origin.contains("? position - shadowNormal * 1.01"),
            "the step crosses the leaf's own cell, not the cell behind it");
        assertTrue(origin.contains(": position + lightDirection * 0.01"),
            "a zero-thickness card is cleared by one centimetre toward the sun");
        assertTrue(tracer.contains("if (leafBack > 0.0 && !medium.inside) {"),
            "under water the bent beam origin carries the light, so the straight-line leaf step must not replace it");
    }

    @Test
    void theSamplerAndTheEstimatorAgreeOnWhatALeafIs() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains(
                "receiver.foliage = (surface.material.sourceFlags & MATERIAL_SOURCE_FOLIAGE) != 0u\n"
                    + "            && (surface.material.flags & MATERIAL_FLAG_EMISSIVE) == 0u;"),
            "the receiver flag must exclude the same emissive plants the estimator does, or a glow is handed a "
                + "key-light sample that gets discarded");
    }

    // ---- source helpers ----

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        int end = source.indexOf(to, start + 1);
        assertTrue(start >= 0 && end > start, "cannot find " + from + " .. " + to);
        return source.substring(start, end);
    }

    private static String block(String source, int at) {
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block at offset " + at);
    }

    private static int count(String source, String needle) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + needle.length();
        }
    }
}
