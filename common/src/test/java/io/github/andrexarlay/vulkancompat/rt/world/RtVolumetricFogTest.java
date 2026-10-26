package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for volumetric fog and atmospheric haze:
 * 1. Shaderc runtime SPIR-V compilation of rt_rays.comp and rt_path.comp (plain and gbuffer).
 * 2. Beer-Lambert exponential transmittance (transmittance -> 0 at infinity, 1.0 at distance 0).
 * 3. Anisotropic Mie solar in-scattering (Henyey-Greenstein g ~ 0.65 produces bright halo towards sun).
 * 4. Night attenuation (deep dark blue / black fog at night, bright sky-tinted fog by day).
 * 5. Configuration parameters and bounds in RtSettings and RtFrameParams.
 */
public class RtVolumetricFogTest {

    private static final float EPSILON = 1e-4f;

    private boolean savedVolumetricFogEnabled;
    private float savedFogDensity;
    private float savedFogInScattering;
    private float savedFogDistanceScale;

    @BeforeEach
    void setUp() {
        savedVolumetricFogEnabled = RtSettings.volumetricFogEnabled();
        savedFogDensity = RtSettings.fogDensity();
        savedFogInScattering = RtSettings.fogInScattering();
        savedFogDistanceScale = RtSettings.fogDistanceScale();
    }

    @AfterEach
    void tearDown() {
        RtSettings.volumetricFogEnabled = savedVolumetricFogEnabled;
        RtSettings.rtFog = savedVolumetricFogEnabled;
        RtSettings.fogDensity = savedFogDensity;
        RtSettings.rtFogDensity = Math.round(savedFogDensity * 100f);
        RtSettings.fogInScattering = savedFogInScattering;
        RtSettings.rtFogInScattering = Math.round(savedFogInScattering * 100f);
        RtSettings.fogDistanceScale = savedFogDistanceScale;
    }

    public static void runAll() {
        RtVolumetricFogTest test = new RtVolumetricFogTest();
        test.setUp();
        try {
            test.testShaderCompilation();
            test.testTransmittanceMath();
            test.testSunInScattering();
            test.testNightAttenuation();
            test.testSettingsAndConfigIntegration();
        } finally {
            test.tearDown();
        }
    }

    @Test
    public void testShaderCompilation() {
        // Verify shader source presence of volumetric fog functions and flags
        String commonSrc = RtWorldChecks.rtCommon();
        assertTrue(commonSrc.contains("applyVolumetricAtmosphere"), "rt_common.glsl must declare applyVolumetricAtmosphere");
        assertTrue(commonSrc.contains("FLAG_VOLUMETRIC_FOG"), "rt_common.glsl must define FLAG_VOLUMETRIC_FOG");
        assertTrue(commonSrc.contains("phaseHenyeyGreenstein"), "rt_common.glsl must declare phaseHenyeyGreenstein");

        String raysSrc = RtShaderCompiler.loadSource(RtPipeline.SHADER_PATH);
        assertTrue(raysSrc.contains("applyVolumetricAtmosphere"), "rt_rays.comp must invoke applyVolumetricAtmosphere");

        String pathSrc = RtShaderCompiler.loadSource(RtPipeline.PATH_SHADER_PATH);
        assertTrue(pathSrc.contains("applyVolumetricAtmosphere"), "rt_path.comp must invoke applyVolumetricAtmosphere");

        // Compile compute pipelines with Shaderc
        ByteBuffer raysSpv = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp");
        assertNotNull(raysSpv, "rt_rays.comp failed to compile");
        assertTrue(raysSpv.remaining() > 1000 && raysSpv.getInt(0) == 0x07230203, "rt_rays.comp valid SPIR-V");

        ByteBuffer raysGbSpv = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp+gbuffer", RtPipeline.GBUFFER_DEFINE);
        assertNotNull(raysGbSpv, "rt_rays.comp+gbuffer failed to compile");
        assertTrue(raysGbSpv.remaining() > 1000 && raysGbSpv.getInt(0) == 0x07230203, "rt_rays.comp+gbuffer valid SPIR-V");

        ByteBuffer pathSpv = RtShaderCompiler.compileResource(RtPipeline.PATH_SHADER_PATH, "rt_path.comp", RtPathTracerPass.ACCUM_DEFINE);
        assertNotNull(pathSpv, "rt_path.comp failed to compile");
        assertTrue(pathSpv.remaining() > 1000 && pathSpv.getInt(0) == 0x07230203, "rt_path.comp valid SPIR-V");

        ByteBuffer pathGbSpv = RtShaderCompiler.compileResource(RtPipeline.PATH_SHADER_PATH, "rt_path.comp+gbuffer",
            RtPipeline.GBUFFER_DEFINE + RtPathTracerPass.ACCUM_DEFINE);
        assertNotNull(pathGbSpv, "rt_path.comp+gbuffer failed to compile");
        assertTrue(pathGbSpv.remaining() > 1000 && pathGbSpv.getInt(0) == 0x07230203, "rt_path.comp+gbuffer valid SPIR-V");
    }

    @Test
    public void testTransmittanceMath() {
        float sigmaT = 2.5f / 128.0f; // typical sigma_t for 128 block fog distance

        // 1. Zero distance -> full transmittance (no extinction)
        float tZero = RtSky.beerLambertTransmittance(1.0f, 0.0f, sigmaT);
        assertEquals(1.0f, tZero, EPSILON, "Transmittance at distance 0 must be exactly 1.0");

        // 2. Infinite / very large distance -> zero transmittance (full extinction)
        float tInf = RtSky.beerLambertTransmittance(1.0f, 10000.0f, sigmaT);
        assertTrue(tInf < 1e-4f, "Transmittance at distance 10000 must approach 0.0 (got " + tInf + ")");

        // 3. Monotonic decrease over distance
        float prevT = 1.0f;
        for (float d = 10.0f; d <= 500.0f; d += 25.0f) {
            float t = RtSky.beerLambertTransmittance(1.0f, d, sigmaT);
            assertTrue(t < prevT, "Transmittance must strictly decrease with distance (at d=" + d + ")");
            assertTrue(t >= 0.0f && t <= 1.0f, "Transmittance must remain in [0, 1]");
            prevT = t;
        }

        // 4. Density scaling
        float tDensityZero = RtSky.beerLambertTransmittance(0.0f, 100.0f, sigmaT);
        assertEquals(1.0f, tDensityZero, EPSILON, "Transmittance with density 0 must be 1.0");

        float tLow = RtSky.beerLambertTransmittance(0.5f, 100.0f, sigmaT);
        float tNorm = RtSky.beerLambertTransmittance(1.0f, 100.0f, sigmaT);
        float tHigh = RtSky.beerLambertTransmittance(2.0f, 100.0f, sigmaT);
        assertTrue(tLow > tNorm && tNorm > tHigh, "Higher fog density must produce lower transmittance");
    }

    @Test
    public void testSunInScattering() {
        // 1. Henyey-Greenstein anisotropic phase function with g = 0.65
        float pForward = RtSky.henyeyGreensteinPhase(1.0f, 0.65f);   // cosTheta = 1 (towards sun)
        float pBack = RtSky.henyeyGreensteinPhase(-1.0f, 0.65f);     // cosTheta = -1 (away from sun)
        float pSide = RtSky.henyeyGreensteinPhase(0.0f, 0.65f);      // cosTheta = 0 (perpendicular)

        assertTrue(pForward > 1.0f, "Forward phase must exceed 1.0 sr^-1 (got " + pForward + ")");
        assertTrue(pBack < 0.02f, "Backward phase must be small (got " + pBack + ")");
        assertTrue(pForward > pSide && pSide > pBack, "Phase must strictly decrease from forward to backward");
        assertTrue(pForward > 50.0f * pBack, "Forward scattering must be >50x stronger than backscattering for g=0.65");

        // 2. Integrated atmosphere inscattering towards vs away from sun
        float[] surfaceColor = {0.2f, 0.2f, 0.2f};
        float[] origin = {0f, 64f, 0f};
        float[] sunDir = {0f, 1f, 0f}; // Sun directly overhead
        float[] sunColor = {1.0f, 0.95f, 0.85f};
        float[] skyColor = {0.4f, 0.6f, 1.0f};

        float[] rayTowardsSun = {0f, 1f, 0f};
        float[] rayAwayFromSun = {0f, -1f, 0f};

        float[] outTowards = new float[3];
        float[] outAway = new float[3];

        RtSky.volumetricAtmosphere(surfaceColor, origin, rayTowardsSun, 200.0f, true,
            sunDir, sunColor, skyColor, 1.0f, 1.0f, 1.0f, 128.0f, true, outTowards);

        RtSky.volumetricAtmosphere(surfaceColor, origin, rayAwayFromSun, 200.0f, true,
            sunDir, sunColor, skyColor, 1.0f, 1.0f, 1.0f, 128.0f, true, outAway);

        float lumTowards = 0.2126f * outTowards[0] + 0.7152f * outTowards[1] + 0.0722f * outTowards[2];
        float lumAway = 0.2126f * outAway[0] + 0.7152f * outAway[1] + 0.0722f * outAway[2];

        assertTrue(lumTowards > lumAway * 2.0f,
            "Ray facing sun must be significantly brighter due to Mie in-scattering (towards=" + lumTowards + ", away=" + lumAway + ")");
    }

    @Test
    public void testNightAttenuation() {
        float[] surfaceColor = {0.1f, 0.1f, 0.1f};
        float[] origin = {0f, 64f, 0f};
        float[] sunDir = {0.707f, 0.707f, 0f};
        float[] sunColor = {1.0f, 0.9f, 0.8f};
        float[] skyColor = {0.3f, 0.5f, 0.9f};
        float[] dir = {0f, 0f, 1f};

        float[] outDay = new float[3];
        float[] outNight = new float[3];
        float[] outTwilight = new float[3];

        // Far distance (1000 blocks hit) so fog dominates
        RtSky.volumetricAtmosphere(surfaceColor, origin, dir, 1000.0f, true,
            sunDir, sunColor, skyColor, 1.0f, 1.0f, 1.0f, 128.0f, true, outDay);

        RtSky.volumetricAtmosphere(surfaceColor, origin, dir, 1000.0f, true,
            sunDir, sunColor, skyColor, 0.0f, 1.0f, 1.0f, 128.0f, true, outNight);

        RtSky.volumetricAtmosphere(surfaceColor, origin, dir, 1000.0f, true,
            sunDir, sunColor, skyColor, 0.2f, 1.0f, 1.0f, 128.0f, true, outTwilight);

        float lumDay = 0.2126f * outDay[0] + 0.7152f * outDay[1] + 0.0722f * outDay[2];
        float lumNight = 0.2126f * outNight[0] + 0.7152f * outNight[1] + 0.0722f * outNight[2];
        float lumTwilight = 0.2126f * outTwilight[0] + 0.7152f * outTwilight[1] + 0.0722f * outTwilight[2];

        // Day must be bright daylight
        assertTrue(lumDay > 0.40f, "Daytime fog must be bright (got " + lumDay + ")");

        // Night must be dark midnight atmosphere
        assertTrue(lumNight < 0.03f, "Night fog must be deeply attenuated (< 0.03, got " + lumNight + ")");
        assertTrue(outNight[0] < 0.02f && outNight[1] < 0.02f && outNight[2] < 0.035f,
            "Night fog RGB channels must be suppressed into midnight blue / black");

        // Twilight must lie strictly between night and day
        assertTrue(lumTwilight > lumNight && lumTwilight < lumDay,
            "Twilight luminance (" + lumTwilight + ") must lie between night (" + lumNight + ") and day (" + lumDay + ")");
    }

    @Test
    public void testSettingsAndConfigIntegration() {
        // 1. Default settings
        RtSettings.applyPreset(RtSettings.Preset.MEDIUM);
        assertTrue(RtSettings.volumetricFogEnabled(), "volumetricFogEnabled default must be true");
        assertEquals(1.0f, RtSettings.fogDensity(), EPSILON, "fogDensity default must be 1.0");
        assertEquals(1.0f, RtSettings.fogInScattering(), EPSILON, "fogInScattering default must be 1.0");
        assertEquals(1.0f, RtSettings.fogDistanceScale(), EPSILON, "fogDistanceScale default must be 1.0");

        // 2. FLAG_VOLUMETRIC_FOG
        assertEquals(1 << 12, RtFrameParams.FLAG_VOLUMETRIC_FOG, "FLAG_VOLUMETRIC_FOG must equal 1 << 12 (4096)");

        // 3. TOML / Key-Value configuration
        assertTrue(RtSettings.applyKeyValue("volumetric_fog_enabled", "false"), "volumetric_fog_enabled applied");
        assertFalse(RtSettings.volumetricFogEnabled(), "volumetricFogEnabled updated to false");

        assertTrue(RtSettings.applyKeyValue("fog_density", "1.75"), "fog_density applied");
        assertEquals(1.75f, RtSettings.fogDensity(), EPSILON, "fogDensity updated");

        assertTrue(RtSettings.applyKeyValue("fog_in_scattering", "0.45"), "fog_in_scattering applied");
        assertEquals(0.45f, RtSettings.fogInScattering(), EPSILON, "fogInScattering updated");

        assertTrue(RtSettings.applyKeyValue("fog_distance_scale", "1.60"), "fog_distance_scale applied");
        assertEquals(1.60f, RtSettings.fogDistanceScale(), EPSILON, "fogDistanceScale updated");

        // Clamping of out-of-range values
        RtSettings.applyKeyValue("fog_density", "5.0");
        assertEquals(2.0f, RtSettings.fogDensity(), EPSILON, "fogDensity clamped to max 2.0");

        RtSettings.applyKeyValue("fog_density", "-1.0");
        assertEquals(0.0f, RtSettings.fogDensity(), EPSILON, "fogDensity clamped to min 0.0");

        RtSettings.applyKeyValue("fog_distance_scale", "0.05");
        assertEquals(0.2f, RtSettings.fogDistanceScale(), EPSILON, "fogDistanceScale clamped to min 0.2");

        RtSettings.applyKeyValue("fog_distance_scale", "10.0");
        assertEquals(2.0f, RtSettings.fogDistanceScale(), EPSILON, "fogDistanceScale clamped to max 2.0");

        // 4. Reset Expert level restores defaults
        RtSettings.resetLevel(RtSettings.Level.EXPERT);
        assertEquals(1.0f, RtSettings.fogDensity(), EPSILON, "resetLevel EXPERT resets fogDensity");
        assertEquals(1.0f, RtSettings.fogInScattering(), EPSILON, "resetLevel EXPERT resets fogInScattering");
        assertEquals(1.0f, RtSettings.fogDistanceScale(), EPSILON, "resetLevel EXPERT resets fogDistanceScale");
    }
}
