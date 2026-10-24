package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 tests for the vanilla water sheet (rt_common_water.glsl, vanillaWaterSurface) and the two shaders that light it.
 * <p>
 * Two owner-visible defects:
 * <ul>
 *   <li>A torch or lantern on the bank barely showed on the water: the sheet was lit by the baked vanilla light level
 *       ({@code blockLightColor() * s.light.x}) while every other surface already sampled the real emitter list. Baked light
 *       is a 15-block voxel field with no direction and no shadows, so a flame two blocks from the water looked like the
 *       ambient of a whole region.</li>
 *   <li>{@code normalize(L + V)} at the glint is a zero vector when the camera looks exactly along the key light (the sun
 *       over water): 0/0 is NaN, and a NaN in the radiance spreads over the pixel and its temporal history.</li>
 * </ul>
 * The checks are source checks of the expanded common file plus a numeric port of the guarded glint, as the neighbouring
 * shader tests in this package do.
 */
public class RtVanillaWaterLightTest {

    @Test
    public void testVanillaSheetSamplesTheEmitterList() {
        String sheet = sheetSource();
        assertTrue(sheet.contains("vec3 vanillaWaterSurface(Surface s, vec3 dir, float reach, inout uint seed)"),
            "the vanilla sheet must receive the ray reach and the sampling seed to ask the emitter list");
        assertTrue(sheet.contains("blockLight = sampleEmitters(s, vec3(1.0), reach, seed);"),
            "the sheet's block light must be the traced emitter estimate; albedo is applied by s.albedoLit * light");
        assertTrue(sheet.contains("if (hasFlag(FLAG_EMISSIVE) && emitterCount() > 0u && s.ng.y > 0.5) {"),
            "the emitter path must be gated like the surface shading and must not touch the underside, whose flipped normal\n"
                + "puts every emitter above the water behind the receiver hemisphere (the NEE would return exactly 0)");
    }

    @Test
    public void testBakedBlockLightSurvivesOnlyAsTheFallback() {
        String sheet = sheetSource();
        int baked = sheet.indexOf("vec3 blockLight = blockLightColor() * s.light.x;");
        int traced = sheet.indexOf("blockLight = sampleEmitters(s, vec3(1.0), reach, seed);");
        assertTrue(baked >= 0, "the baked light level must stay as the fallback for a frame without emitters");
        assertTrue(traced > baked, "the traced estimate must replace the baked value, not be added on top of it");
        assertEquals(2, count(sheet, "blockLight ="),
            "the declaration and the one traced assignment are the only writers of the block light (no addition on top)");
    }

    @Test
    public void testGlintNormalizationIsGuarded() {
        String sheet = sheetSource();
        assertFalse(sheet.contains("normalize(L + V)"),
            "normalize(L + V) is 0/0 when the camera looks along the key light and poisons the glint with NaN");
        assertTrue(sheet.contains("vec3 H = hl2 > 1.0e-6 ? Hv * inversesqrt(hl2) : s.ns;"),
            "the half vector must fall back to the shading normal at exact opposition (that view is the specular peak)");
    }

    @Test
    public void testCallSitesPassReachAndSeed() {
        String rays = RtShaderCompiler.resolveIncludes(
            RtShaderCompiler.loadSource("/shaders/rt_rays.comp"), "/shaders/rt_rays.comp");
        assertTrue(rays.contains("vanillaWaterSurface(s, rayDir, emissiveDistance(), seed)"),
            "ray mode must light the sheet from the emissive reach configured for the frame");

        String path = RtShaderCompiler.resolveIncludes(
            RtShaderCompiler.loadSource("/shaders/rt_path.comp"), "/shaders/rt_path.comp");
        assertTrue(path.contains("vanillaWaterSurface(s, d, reach, seed)"),
            "path mode must light the sheet from the path reach with the path sampler seed");

        String src = RtWorldChecks.rtCommon();
        assertNotNull(src);
        assertFalse(src.contains("vec3 vanillaWaterSurface(Surface s) {"),
            "the one-argument overload had no caller and would hide a forgotten seed");
        assertEquals(1, count(src, "vanillaWaterSurface("), "the sheet must have exactly one definition");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** Body of vanillaWaterSurface only: the gate it uses also exists in rt_common_shade.glsl, so the whole file is not enough. */
    private static String sheetSource() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src, "rt_common.glsl must be on the classpath");
        int from = src.indexOf("vec3 vanillaWaterSurface(");
        int to = src.indexOf("bool isWaterBoundary(", from);
        assertTrue(from >= 0 && to > from, "vanillaWaterSurface must be followed by isWaterBoundary in rt_common_water.glsl");
        return src.substring(from, to);
    }

    @Test
    public void testGuardedGlintIsFiniteAtExactOpposition() {
        // L = up, V = down: L + V = 0, the case the old normalize() turned into NaN.
        double[] n = {0.0, 1.0, 0.0};
        double[] L = {0.0, 1.0, 0.0};
        double[] V = {0.0, -1.0, 0.0};
        double sumX = L[0] + V[0];
        double sumY = L[1] + V[1];
        double sumZ = L[2] + V[2];
        double hl2 = sumX * sumX + sumY * sumY + sumZ * sumZ;

        double oldNdotH = Math.max(normalizeOld(sumX, sumY, sumZ)[1] * n[1], 0.0);
        double oldSpec = Math.pow(oldNdotH, 128.0) * 2.4 + Math.pow(oldNdotH, 24.0) * 0.5;
        assertTrue(Double.isNaN(oldSpec),
            "the removed normalize(L + V) must be 0/0 here, otherwise the guard would be pointless (got " + oldSpec + ")");

        double[] h = hl2 > 1.0e-6 ? new double[] {sumX, sumY, sumZ} : n;
        if (hl2 > 1.0e-6) {
            double inv = 1.0 / Math.sqrt(hl2);
            h[0] *= inv;
            h[1] *= inv;
            h[2] *= inv;
        }
        double ndotH = Math.max(h[0] * n[0] + h[1] * n[1] + h[2] * n[2], 0.0);
        double spec = Math.pow(ndotH, 128.0) * 2.4 + Math.pow(ndotH, 24.0) * 0.5;
        assertTrue(Double.isFinite(spec), "the glint at exact opposition must be finite, got " + spec);
        assertEquals(2.9, spec, 1e-9, "exact opposition is the specular peak: both lobes must be at full strength");

        // And a grazing view keeps the physical half vector (no regression in the normal case).
        double[] grazing = {0.0, 0.0, -1.0};
        double gx = L[0] + grazing[0];
        double gy = L[1] + grazing[1];
        double gz = L[2] + grazing[2];
        double gl = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double gNdotH = Math.max((gx / gl) * n[0] + (gy / gl) * n[1] + (gz / gl) * n[2], 0.0);
        assertEquals(Math.sqrt(0.5), gNdotH, 1e-9, "a 90 degree view must keep the geometric half vector");
    }

    /** The removed `normalize(L + V)`: dividing by a zero length is exactly the NaN the shader used to write out. */
    private static double[] normalizeOld(double x, double y, double z) {
        double inv = 1.0 / Math.sqrt(x * x + y * y + z * z);
        return new double[] {x * inv, y * inv, z * inv};
    }

    public static void runAll() {
        RtVanillaWaterLightTest t = new RtVanillaWaterLightTest();
        t.testVanillaSheetSamplesTheEmitterList();
        t.testBakedBlockLightSurvivesOnlyAsTheFallback();
        t.testGlintNormalizationIsGuarded();
        t.testCallSitesPassReachAndSeed();
        t.testGuardedGlintIsFiniteAtExactOpposition();
    }
}
