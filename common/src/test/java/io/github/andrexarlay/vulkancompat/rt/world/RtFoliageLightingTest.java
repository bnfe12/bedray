package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source check for the thin-card key light of cutout foliage (grass, flowers, leaves).
 *
 * <p>A plant seen against the sun has {@code dot(ng, L) < 0}: the flipped geometric normal faces the camera, the key light
 * is behind the card. The front-face gate ({@code ndotl > 0 && ngdotl > 0}) rejects exactly that case, so a transmission
 * term placed inside it can never contribute — {@code ndotl > 0} forces {@code max(dot(-ng, L), 0)} to zero. That is how
 * every back-lit plant ended up with no direct sun at all and a sunlit slope read as dark grass dots.
 *
 * <p>A JUnit test cannot shade a leaf, so it guards the structural invariants that make the term reachable and correct:
 * the foliage transmission lives outside the front-face gate, its shadow ray starts on the sun side of the leaf (outside
 * the caster cell for a leaf block), it keeps the refracted origin under water, and it uses the same ray cone as the front
 * face so a back-lit leaf is not the one hard-edged shadow in a soft-shadow frame.
 */
public class RtFoliageLightingTest {

    private static final String PATH_SHADER = "/shaders/rt_path.comp";
    private static final String RAYS_SHADER = "/shaders/rt_rays.comp";
    private static final String SHADE_MODULE = "/shaders/rt_common_shade.glsl";
    private static final String SURFACE_MODULE = "/shaders/rt_common_surface.glsl";
    private static final String ORIGIN_HELPER = "vec3 leafShadowOrigin(Surface s, vec3 L) {";

    @Test
    public void shadowOriginClearsTheCaster() {
        String src = RtShaderCompiler.loadSource(SURFACE_MODULE);
        String helper = requireBlock(src, ORIGIN_HELPER);
        assertTrue(helper.contains("s.leafCube && !s.backFace"),
            "only a full leaf cube needs the one-block step; a card, a vine or a pane is cleared by 1 cm along the sun");
        assertTrue(helper.contains("s.pos - s.ng * 1.01"),
            "a leaf block presents one face of a full cube whose sun-side face is exactly 1 block along -ng: that step always "
                + "leaves the caster cell");
        assertTrue(helper.contains(": s.pos + L * 0.01"),
            "a back-face hit already sits on the sun-side face (the ray got there through a cutout hole), and a card is zero "
                + "thickness: both need only the 1 cm step along the sun");
        assertFalse(helper.contains("axis < 0.98"),
            "a normal is not a thickness: a wall vine is axis-aligned too, and the one-block step started the ray inside the "
                + "wall behind it");
        assertFalse(helper.contains("1.0 / leafBack"),
            "a step measured along the sun direction depends on the sun angle: capped at 1.75 blocks it kept the origin inside "
                + "the block whenever leafBack < 0.57, and the block's own far face blocked the ray again");
        assertEquals(occurrences(helper, "s.pos + L * 0.01"), occurrences(src, "s.pos + L * 0.01"),
            "the sun-side offset must exist only inside leafShadowOrigin, so every caller gets the cube-aware start");
    }

    @Test
    public void pathTracerLightsBackLitFoliage() {
        String src = RtShaderCompiler.loadSource(PATH_SHADER);
        assertTrue(src.contains("float leafBack = s.isFoliage ? max(dot(-s.ng, Ls), 0.0) : 0.0;"),
            "rt_path.comp must evaluate the leaf transmission from the normal that faces the key light");
        assertFalse(requireBlock(src, "if (lit && ndotl > 0.0 && ngdotl > 0.0) {").contains("leafBack"),
            "the leaf transmission must not sit inside the front-face gate: that gate is false for every back-lit plant");
        assertTrue(src.contains(
                "traceTransmittance(med.inside ? shOrigin : leafShadowOrigin(s, Ls), shDir, 2048.0) * gain;"),
            "under water the back-lit ray must continue the refracted beam (shOrigin / shDir / gain) and must not re-cross "
                + "the pool from the leaf, which would double the absorption and ignore the pool walls");
        assertTrue(requireBlock(src, "if (leafBack > 0.0) {").contains("0.42"),
            "the transmission term must still be applied once the back-lit shadow ray has run");
    }

    @Test
    public void raysShaderLightsBackLitFoliage() {
        String src = RtShaderCompiler.loadSource(RAYS_SHADER);
        assertTrue(src.contains("float leafBack = s.isFoliage ? max(dot(-s.ng, L), 0.0) : 0.0;"),
            "rt_rays.comp must evaluate the leaf transmission from the normal that faces the key light");
        assertFalse(requireBlock(src, "} else if (keyLightOn() && ndotl > 0.0 && ngdotl > 0.0) {").contains("float leafBack"),
            "the leaf transmission must not be evaluated inside the front-face branch");
        String underwater = requireBlock(src, "} else if (uwLit && keyLightOn() && leafBack > 0.0) {");
        assertTrue(underwater.contains("traceTransmittance(uwOrigin, uwDir, 2048.0) * uwGain"),
            "the submerged back-lit ray must use the refracted beam origin and the water gain of waterSunBeam");
        String backLit = requireBlock(src, "} else if (keyLightOn() && leafBack > 0.0) {");
        assertTrue(backLit.contains("leafShadowOrigin"),
            "the back-lit ray must start on the sun side of the leaf, outside the caster cell for a leaf block");
        assertTrue(backLit.contains("sunShadowDirection") && backLit.contains("shadowRays()"),
            "soft shadows must apply to the back-lit leaf too: one hard ray next to soft ground leaves a crisp leaf shadow");
        assertTrue(backLit.contains("hasFlag(FLAG_SHADOWS)"),
            "the leaf ray must still honour the shadows switch");
    }

    @Test
    public void secondaryShadingLightsBackLitFoliage() {
        String src = RtShaderCompiler.loadSource(SHADE_MODULE);
        assertEquals(2, occurrences(src, "float leafBack = s.isFoliage ? max(dot(-s.ng, L), 0.0) : 0.0;"),
            "reflection and GI shading must both evaluate the leaf transmission from the light-facing normal");
        assertEquals(2, occurrences(src, "traceTransmittance(leafShadowOrigin(s, L), L, 2048.0)"),
            "both secondary-shading shadow rays must use the sun-side leaf origin");
        assertEquals(0, occurrences(src, "s.pos + L * 0.01"),
            "the raw 1 cm offset starts inside a leaf block: it must go through leafShadowOrigin");
        assertTrue(src.contains("vec3 directSun = pc.keyColor.rgb * ndotl * sunT * terminator;"),
            "the direct sun term of shadeSimple must keep its shape");
        assertFalse(src.contains("float backScatter = max(dot(-s.ns, L), 0.0);"),
            "the old backScatter term (always zero behind the front-face gate) must be gone");
    }

    @Test
    public void leafCounterProvesTheTermFires() {
        String path = RtShaderCompiler.loadSource(PATH_SHADER);
        String term = requireBlock(path, "if (leafBack > 0.0) {");
        assertTrue(term.contains("gLeafBacklit = true;"),
            "the leaf transmission must flag the pixel it reaches: leaf_pct is the only runtime proof that sun reaches a "
                + "back-lit slope, since a testbed screenshot cannot tell the fixed term from the old missing-light bug");
        assertFalse(term.contains("atomicAdd"),
            "tracePath runs once per spp sample while the stats denominator counts pixels: the atomic add belongs after the "
                + "loop, or leaf_pct exceeds 100%");
        assertTrue(term.contains("any(greaterThan(tr, vec3(0.0)))"),
            "only delivered light may be counted: leaf blocks that swallow the ray return tr = 0");
        assertTrue(term.contains("b == 0u"),
            "only the primary hit may be counted, otherwise every leaf bounce inflates the share");
        assertEquals(1, occurrences(path, "atomicAdd(StatsBuf(pc.statsAddr).leafBacklit, 1u);"),
            "exactly one atomic add per pixel");
        assertTrue(path.indexOf("if (gLeafBacklit) {") > path.indexOf("sum += v;"),
            "the add must sit after the spp loop: inside it the counter would count samples, not pixels");
        assertTrue(RtStatsLayout.LEAF_BACKLIT + 4 <= RtStatsLayout.SIZE,
            "the counter must fit the shared StatsBuf slot that RtStatsLayoutTest verifies against the GLSL struct");
    }

    @Test
    public void emissiveCutoutIsNotFoliage() {
        String src = RtShaderCompiler.loadSource(SURFACE_MODULE);
        int at = src.indexOf("s.isFoliage = (rawLight0 & VERT_FLAG_VEGETATION) != 0u");
        assertTrue(at >= 0, "the foliage classification must stay in the shared surface decode");
        String statement = src.substring(at, src.indexOf(';', at));
        assertTrue(statement.contains("geomPass == 1u") && statement.contains("s.emission < 0.5"),
            "torches, lanterns and campfires are drawn in the cutout pass, but a flame does not transmit key light: they must "
                + "not take the leaf term");
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

    private static int occurrences(String source, String needle) {
        int n = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
