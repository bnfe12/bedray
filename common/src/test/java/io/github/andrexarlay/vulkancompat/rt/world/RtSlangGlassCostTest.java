package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cost of glass, carried over from the deleted {@code RtGlassCostTest}: a stained-glass window is two faces per
 * block and several panes deep, and every shadow ray of the frame walks it. The typed walk in
 * {@code surface/visibility.slang} pays one closest-hit query per layer, so two rules keep that bounded - the
 * transmittance floor ends a ray that is already dark, and every filtered layer advances past its own candidate so a
 * pane is never tested twice. The medium-aware walk adds the rule that keeps water from being tinted twice: the fluid
 * interface contributes its Fresnel share only, because the colour is the distance absorption along the segment.
 *
 * <p>The single-traversal property of the legacy camera walk has no typed counterpart: the typed path traces a
 * closest hit per vertex instead of folding panes from candidates. What survives - and is pinned here - is the
 * layer and energy cost, not the old query count.
 */
class RtSlangGlassCostTest {

    private static final String VISIBILITY = "src/main/resources/shaders/slang/surface/visibility.slang";
    private static final String SURFACE = "src/main/resources/shaders/slang/surface/surface.slang";

    private static final int MAX_LAYERS = 16;
    private static final float MIN_TRANSMITTANCE = 1.0e-3f;

    @Test
    void anAbsorbedRayStopsInsteadOfWalkingTheRestOfTheWindow() {
        String visibility = read(VISIBILITY);
        assertEquals(4, count(visibility, "maximum <= SURFACE_VISIBILITY_MIN_TRANSMITTANCE"),
            "both filtered branches of both walks end the ray at the floor");
        assertEquals(4, count(visibility,
                "            }\n\n            current = skipSurfaceCandidate(current, hit.t, SURFACE_VISIBILITY_BIAS);"),
            "each floor closes into the advance: paying for the next pane once the energy is gone is the cost this "
                + "rule removes, so the skip must not run before the floor decides the ray is dead");
    }

    @Test
    void fiveDarkPanesEndTheRayLongBeforeTheLayerBudget() {
        // The model below is only the shader's cost if it uses the shader's own two constants.
        String visibility = read(VISIBILITY);
        assertTrue(visibility.contains("static const uint SURFACE_VISIBILITY_MAX_LAYERS = 16u;"),
            "MAX_LAYERS is the budget the walk actually spends");
        assertTrue(visibility.contains("static const float SURFACE_VISIBILITY_MIN_TRANSMITTANCE = 1.0e-3;"),
            "MIN_TRANSMITTANCE is the floor the model below applies");
        // one pane passes a quarter of the light: the fifth takes the maximum below 1e-3, so the ray ends at five
        // layers instead of spending the whole 16-layer budget to discover that darkness is dark
        float t = 1.0f;
        int panes = 0;
        while (t > MIN_TRANSMITTANCE && panes < MAX_LAYERS) {
            t *= 0.25f;
            panes++;
        }
        assertEquals(5, panes, "0.25^5 = 0.00098 is under the floor");
        assertTrue(panes < MAX_LAYERS, "the energy floor must bind before the budget, or the budget is the cost");
        float clear = 1.0f;
        for (int i = 0; i < MAX_LAYERS; i++) {
            clear *= 0.9f;
        }
        assertTrue(clear > MIN_TRANSMITTANCE,
            "a clean window never trips the floor: the budget, not the floor, ends it");
    }

    @Test
    void everyFilteredLayerAdvancesPastItsOwnCandidate() {
        String visibility = read(VISIBILITY);
        assertEquals(6,
            count(visibility, "current = skipSurfaceCandidate(current, hit.t, SURFACE_VISIBILITY_BIAS);"),
            "the hole skip and both filter branches of both walks: without the advance the next query finds the same "
                + "pane, and the walk pays for it again");
        String skip = block(visibility, visibility.indexOf("Ray skipSurfaceCandidate("));
        assertTrue(skip.contains("ray.tMin = hitDistance + bias;"),
            "the advance moves the interval start, not the origin: hit.t stays measured from the original ray");
    }

    @Test
    void theMediumWalkTintsWaterOnce() {
        String visibility = read(VISIBILITY);
        String plain = block(visibility,
            visibility.indexOf("SurfaceVisibility traceFilteredVisibility<T : ISceneTracer>"));
        String medium = block(visibility,
            visibility.indexOf("SurfaceVisibility traceFilteredVisibilityMedium<T : ISceneTracer>"));
        assertTrue(plain.contains("transmittance *= surfaceTransmittance(surface, ray.direction);"),
            "a caller without medium state still gets the whole filter");
        assertTrue(medium.contains("transmittance *= surfaceInterfaceTransmittance(surface, ray.direction);"),
            "the medium walk asks for the interface share only");
        int segment = medium.indexOf("transmittance *= waterSegmentTransmittance(medium, max(hit.t - lastT, 0.0));");
        int share = medium.indexOf("surfaceInterfaceTransmittance");
        assertTrue(segment > 0 && share > segment,
            "the column up to the face is absorbed first, then the interface adds its Fresnel: tinting both darkens "
                + "water twice");
        String surface = read(SURFACE);
        String interfaceShare = block(surface, surface.indexOf("float3 surfaceInterfaceTransmittance("));
        assertTrue(interfaceShare.contains("return float3(1.0 - fresnel);"),
            "a fluid interface keeps only the Fresnel share");
        assertTrue(interfaceShare.contains("return surfaceTransmittance(surface, direction);"),
            "every non-fluid translucent material keeps the full filter");
    }

    @Test
    void aHoleCostsOneAdvanceWithoutFiltering() {
        String visibility = read(VISIBILITY);
        assertEquals(2, count(visibility, "if (surfaceIsHole(surface, options.alphaCutoff)) {"));
        int hole = visibility.indexOf("if (surfaceIsHole(surface, options.alphaCutoff)) {");
        String branch = block(visibility, hole);
        assertTrue(branch.contains("current = skipSurfaceCandidate(current, hit.t, SURFACE_VISIBILITY_BIAS);"),
            "a hole advances past its own candidate");
        assertTrue(branch.contains("continue;"));
        assertTrue(!branch.contains("transmittance *="),
            "a hole is skipped before any filter applies: it neither tints nor pays a filtering step");
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
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
