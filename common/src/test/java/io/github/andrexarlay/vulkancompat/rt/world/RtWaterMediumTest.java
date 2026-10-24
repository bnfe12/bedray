package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for the realistic water medium of rt_common.glsl (WaterMedium, waterSegment).
 * <p>
 * The owner-visible regression (2026-10-20): deep ocean water rendered black and opaque. The old in-scatter multiplied the
 * sky radiance by exp(-sigma * 6) * 0.28 (a second absorption of the path above the sample) and by exp(-sigma * depth * 0.8)
 * (a third one), so a 10-block water column returned about 0.02 luminance and the sea settled at ~0.003: a black hole.
 * A medium needs an explicit scattering coefficient: sigmaT = sigmaA + sigmaS, the in-scattered radiance of a segment is
 * sigmaS | sigmaT * L * (1 - exp(-sigmaT * len)), and multiple scattering keeps a floor of the downwelling light at depth.
 * <p>
 * The numeric part is a double-precision port of waterSegment with the GLSL constants copied here, as the other shader
 * tests in this package do. The source checks keep the shader on the same model.
 */
public class RtWaterMediumTest {

    // ---- rt_common.glsl constants (kept in sync with the shader) ----
    private static final double CLEAR_R = 0.17;
    private static final double CLEAR_G = 0.036;
    private static final double CLEAR_B = 0.013;
    private static final double SCATTERING = 0.055;
    private static final double FLOOR = 0.65;
    private static final double DEPTH_FALLOFF = 0.65;
    /** Ocean biome tint (0x3F76E4) as the shader sees it after undoing the baked face shade. */
    private static final double[] OCEAN_TINT = {0.25, 0.46, 0.89};
    /** Daytime hemisphereAmbient(1, day) of rt_common.glsl skyLight. */
    private static final double AMBIENT = 0.45;

    @Test
    public void testWaterBrightnessTuning() {
        // Owner request 2026-10-21: "water must be brighter". The deep column keeps most of the downwelling light now
        // (floor 0.65 instead of 0.30) and clear water absorbs ~25% less, so both the in-scatter and the visible bottom rise.
        double[] now = segment(OCEAN_TINT, 20.0, 48.0, AMBIENT);
        double[] before = segmentWith(OCEAN_TINT, 20.0, 48.0, AMBIENT, 0.22, 0.045, 0.018, 0.30);
        double ratio = luminance(now) / Math.max(luminance(before), 1e-9);
        assertTrue(ratio > 1.35, "deep water must be >=35% brighter than the 0.30 floor model, got " + ratio);

        double[] tintNow = transmittance(OCEAN_TINT, 10.0);
        double[] tintBefore = transmittanceWith(OCEAN_TINT, 10.0, 0.22, 0.045, 0.018);
        for (int i = 0; i < 3; i++) {
            assertTrue(tintNow[i] > tintBefore[i], "10 blocks of water must pass more light in channel " + i);
        }
    }

    @Test
    public void testShaderConstantsMatchTheModel() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src, "rt_common.glsl must be on the classpath");
        assertTrue(src.contains("const vec3 WATER_CLEAR_ABSORPTION = vec3(0.17, 0.036, 0.013);"),
            "WATER_CLEAR_ABSORPTION drifted from the numeric model in this test");
        assertTrue(src.contains("const float WATER_SCATTERING = 0.055;"),
            "WATER_SCATTERING drifted from the numeric model in this test");
        assertTrue(src.contains("const float WATER_MULTIPLE_SCATTER_FLOOR = 0.65;"),
            "WATER_MULTIPLE_SCATTER_FLOOR drifted from the numeric model in this test");
        assertTrue(src.contains("pc.keyColor.rgb * (0.35 * max(pc.keyDir.y, 0.0))"),
            "water downlight must include the 0.35 direct-sun share");
    }

    @Test
    public void testDeepWaterIsVisibleAndBlue() {
        double[] deep = segment(OCEAN_TINT, 20.0, 48.0, AMBIENT);
        double lum = luminance(deep);
        assertTrue(lum > 0.02, "20-block water column must not be black, got luminance " + lum + " " + vec3(deep));
        assertTrue(deep[2] > 2.0 * deep[0], "deep water must stay blue, red ignored: " + vec3(deep));
    }

    @Test
    public void testDeepWaterBeatsLegacyBlackModel() {
        double[] now = segment(OCEAN_TINT, 20.0, 48.0, AMBIENT);
        double[] legacy = legacySegment(OCEAN_TINT, 20.0, 48.0, AMBIENT);
        double ratio = luminance(now) / Math.max(luminance(legacy), 1e-6);
        assertTrue(ratio > 3.0, "new medium must be >3x brighter than the black-water model, got " + ratio);
    }

    @Test
    public void testMultipleScatteringFloorAtDepth() {
        double[] shallow = segment(OCEAN_TINT, 10.0, 48.0, AMBIENT);
        double[] deep = segment(OCEAN_TINT, 40.0, 48.0, AMBIENT);
        assertTrue(deep[2] >= 0.5 * shallow[2],
            "multiple scattering must keep deep blue at >=50% of the 10-block value, got " + deep[2] + " vs " + shallow[2]);
        assertTrue(deep[2] > deep[1] && deep[1] > deep[0], "deep water hue must stay blue > green > red: " + vec3(deep));
    }

    @Test
    public void testShallowWaterKeepsBottomVisible() {
        double[] tint = transmittance(OCEAN_TINT, 1.0);
        assertTrue(tint[1] > 0.75, "1 block of water must pass most green (bottom visible), got " + tint[1]);
        assertTrue(tint[0] < tint[2], "red must be absorbed before blue, got " + vec3(tint));
        double[] ins = segment(OCEAN_TINT, 0.5, 1.0, AMBIENT);
        assertTrue(luminance(ins) < 0.1, "1-block in-scatter must not turn shallow water milky, got " + luminance(ins));
    }

    @Test
    public void testShaderUsesAbsorptionPlusScatteringMedium() {
        String src = RtWorldChecks.rtCommon();
        assertNotNull(src, "rt_common.glsl must be on the classpath");
        assertTrue(src.contains("vec3 scatterAlbedo"), "WaterMedium must carry the single-scattering albedo");
        assertTrue(src.contains("WATER_SCATTERING"), "the medium must declare a scattering coefficient");
        assertTrue(src.contains("WATER_MULTIPLE_SCATTER_FLOOR"), "the medium must declare the multiple-scattering floor");
        assertTrue(src.contains("m.scatterAlbedo *"), "waterSegment must scale the in-scatter by sigmaS/sigmaT");
        assertFalse(src.contains("exp(-m.sigma * 6.0)"), "the legacy second absorption of the sky light must be gone");
        assertFalse(src.contains("m.sigma * depth * 0.8"), "the legacy third absorption must be gone");
        assertTrue(src.contains("exp(-m.sigmaT * travel)"), "waterSunBeam must attenuate by the total extinction");
    }

    // ---- double-precision port of waterSegment / waterSigmaT ----

    private static double[] sigmaT(double[] tint) {
        return sigmaTWith(tint, CLEAR_R, CLEAR_G, CLEAR_B);
    }

    private static double[] sigmaTWith(double[] tint, double cr, double cg, double cb) {
        double[] s = new double[3];
        double[] clear = {cr, cg, cb};
        for (int i = 0; i < 3; i++) {
            double t = Math.max(0.02, Math.min(1.0, tint[i]));
            double dye = Math.max(0.0, Math.min(0.20, 0.05 * -Math.log(t)));
            s[i] = clear[i] + dye + SCATTERING;
        }
        return s;
    }

    private static double[] scatterAlbedo(double[] tint) {
        return scatterAlbedoWith(tint, CLEAR_R, CLEAR_G, CLEAR_B);
    }

    private static double[] scatterAlbedoWith(double[] tint, double cr, double cg, double cb) {
        double[] st = sigmaTWith(tint, cr, cg, cb);
        return new double[] {SCATTERING / st[0], SCATTERING / st[1], SCATTERING / st[2]};
    }

    private static double[] transmittance(double[] tint, double len) {
        return transmittanceWith(tint, len, CLEAR_R, CLEAR_G, CLEAR_B);
    }

    private static double[] transmittanceWith(double[] tint, double len, double cr, double cg, double cb) {
        double[] st = sigmaTWith(tint, cr, cg, cb);
        return new double[] {Math.exp(-st[0] * len), Math.exp(-st[1] * len), Math.exp(-st[2] * len)};
    }

    /** In-scattered radiance of a segment at the given depth below the surface. */
    private static double[] segment(double[] tint, double depth, double len, double ambient) {
        return segmentWith(tint, depth, len, ambient, CLEAR_R, CLEAR_G, CLEAR_B, FLOOR);
    }

    private static double[] segmentWith(double[] tint, double depth, double len, double ambient,
                                        double cr, double cg, double cb, double floor) {
        double[] st = sigmaTWith(tint, cr, cg, cb);
        double[] albedo = scatterAlbedoWith(tint, cr, cg, cb);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double t = Math.exp(-st[i] * len);
            double column = floor + (1.0 - floor) * Math.exp(-st[i] * depth * DEPTH_FALLOFF);
            out[i] = ambient * albedo[i] * column * (1.0 - t);
        }
        return out;
    }

    /** The pre-fix model, kept only as the regression reference. */
    private static double[] legacySegment(double[] tint, double depth, double len, double ambient) {
        double[] st = sigmaT(tint);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double t = Math.exp(-st[i] * len);
            double scatterC = Math.exp(-st[i] * 6.0) * 0.28;
            out[i] = ambient * scatterC * Math.exp(-st[i] * depth * 0.8) * (1.0 - t);
        }
        return out;
    }

    private static double luminance(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static String vec3(double[] c) {
        return String.format("(%.4f, %.4f, %.4f)", c[0], c[1], c[2]);
    }

    public static void runAll() {
        RtWaterMediumTest t = new RtWaterMediumTest();
        t.testDeepWaterIsVisibleAndBlue();
        t.testDeepWaterBeatsLegacyBlackModel();
        t.testMultipleScatteringFloorAtDepth();
        t.testShallowWaterKeepsBottomVisible();
        t.testShaderUsesAbsorptionPlusScatteringMedium();
        t.testWaterBrightnessTuning();
        t.testShaderConstantsMatchTheModel();
    }
}
