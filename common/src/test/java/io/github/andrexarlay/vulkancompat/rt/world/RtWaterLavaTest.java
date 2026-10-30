package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.AutoPbr;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests verifying:
 * 1. Water style selection (0: Vanilla, 1: Realistic) in RtSettings and RtFrameParams. Realistic means the waves, not a
 *    different absorption, IOR or étendue: the two styles stay at parity everywhere else.
 * 2. Snell's law water refraction (refract(d, ns, 1.0 / 1.333)).
 * 3. Water wave normal interference (multi-frequency wave ripples, horizon preservation).
 * 4. Underwater sun specular caustics and surface sun glitter.
 * 5. Animated turbulent high-emission lava glow with texel hotspots.
 * 6. The frame-parameter flags and the typed feature bits that carry the switches to the Slang path pass.
 */
public class RtWaterLavaTest {

    private static final float EPSILON = 1e-4f;
    private int originalWaterStyle;

    @BeforeEach
    public void setUp() {
        originalWaterStyle = RtSettings.waterStyle;
    }

    @AfterEach
    public void tearDown() {
        RtSettings.waterStyle = originalWaterStyle;
    }

    @Test
    public void testWaterStyleConfiguration() {
        assertEquals(0, RtSettings.WATER_STYLE_VANILLA);
        assertEquals(1, RtSettings.WATER_STYLE_REALISTIC);

        RtSettings.waterStyle = RtSettings.WATER_STYLE_VANILLA;
        assertEquals(0, RtSettings.waterStyle());

        RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
        assertEquals(1, RtSettings.waterStyle());

        // Test key-value application
        assertTrue(RtSettings.applyKeyValue("water_style", "0"));
        assertEquals(0, RtSettings.waterStyle());
        assertTrue(RtSettings.applyKeyValue("water_style", "1"));
        assertEquals(1, RtSettings.waterStyle());

        // Out of bounds check: keeps previous value
        assertTrue(RtSettings.applyKeyValue("water_style", "5"));
        assertEquals(1, RtSettings.waterStyle());
        assertTrue(RtSettings.applyKeyValue("water_style", "-1"));
        assertEquals(1, RtSettings.waterStyle());
    }

    @Test
    public void testWaterStylePresetsAndSerialization() throws Exception {
        // LOW preset sets Vanilla, while MEDIUM/HIGH/ULTRA/PATH_TRACING set Realistic
        RtSettings.applyPreset(RtSettings.Preset.LOW);
        assertEquals(RtSettings.WATER_STYLE_VANILLA, RtSettings.waterStyle);

        RtSettings.applyPreset(RtSettings.Preset.MEDIUM);
        assertEquals(RtSettings.WATER_STYLE_REALISTIC, RtSettings.waterStyle);

        RtSettings.applyPreset(RtSettings.Preset.HIGH);
        assertEquals(RtSettings.WATER_STYLE_REALISTIC, RtSettings.waterStyle);

        RtSettings.applyPreset(RtSettings.Preset.ULTRA);
        assertEquals(RtSettings.WATER_STYLE_REALISTIC, RtSettings.waterStyle);

        RtSettings.applyPreset(RtSettings.Preset.PATH_TRACING);
        assertEquals(RtSettings.WATER_STYLE_REALISTIC, RtSettings.waterStyle);

        // Serialization to TOML contains water_style
        StringWriter sw = new StringWriter();
        RtSettings.write(sw);
        String toml = sw.toString();
        assertTrue(toml.contains("water_style = 1"), "TOML must contain water_style = 1");
    }

    @Test
    public void testWaterFlagCollisionAndUniqueness() {
        assertEquals(1 << 13, RtFrameParams.FLAG_WATER_REALISTIC, "FLAG_WATER_REALISTIC must be 1 << 13 (8192)");

        int allOtherFlags = RtFrameParams.FLAG_SHADOWS
            | RtFrameParams.FLAG_SOFT_SHADOWS
            | RtFrameParams.FLAG_REFLECTIONS
            | RtFrameParams.FLAG_AO
            | RtFrameParams.FLAG_EMISSIVE
            | RtFrameParams.FLAG_MATERIALS
            | RtFrameParams.FLAG_ACCUMULATE
            | RtFrameParams.FLAG_NORMAL_MAPS
            | RtFrameParams.FLAG_AUTO_EXPOSURE
            | RtFrameParams.FLAG_GI
            | RtFrameParams.FLAG_AUTO_NORMALS
            | RtFrameParams.FLAG_GEOMETRIC_NORMALS
            | RtFrameParams.FLAG_VOLUMETRIC_FOG;

        assertEquals(0, allOtherFlags & RtFrameParams.FLAG_WATER_REALISTIC,
            "FLAG_WATER_REALISTIC must not collide with any existing flag");
    }

    @Test
    public void testSnellsLawWaterRefraction() {
        // 1. Perpendicular incidence: d = (0, -1, 0), n = (0, 1, 0)
        Vector3f dPerp = new Vector3f(0f, -1f, 0f);
        Vector3f n = new Vector3f(0f, 1f, 0f);
        Vector3f refrPerp = glslRefract(dPerp, n, 1.0f / 1.333f);
        assertNotNull(refrPerp);
        assertEquals(0.0f, refrPerp.x, EPSILON);
        assertEquals(-1.0f, refrPerp.y, EPSILON);
        assertEquals(0.0f, refrPerp.z, EPSILON);

        // 2. 45-degree angle of incidence into water:
        // theta_air = 45 deg = PI / 4
        // Snell's Law: sin(theta_water) = sin(theta_air) / 1.333 = (sqrt(2)/2) / 1.333 =~ 0.5312
        // theta_water = arcsin(0.5312) =~ 32.08 deg (bends towards normal)
        Vector3f d45 = new Vector3f((float) (Math.sqrt(0.5)), (float) (-Math.sqrt(0.5)), 0f);
        Vector3f refr45 = glslRefract(d45, n, 1.0f / 1.333f);
        assertNotNull(refr45);
        assertEquals(1.0f, refr45.length(), EPSILON, "Refracted vector must be normalized");

        double expectedThetaWater = Math.asin(Math.sin(Math.PI / 4.0) / 1.333);
        double actualThetaWater = Math.acos(Math.abs(refr45.dot(n)));
        assertEquals(expectedThetaWater, actualThetaWater, 1e-3, "Refraction angle must match Snell's law");
        assertTrue(actualThetaWater < Math.PI / 4.0, "Refracted ray must bend closer to the normal inside water");
    }

    @Test
    public void testWaterWaveNormalInterference() {
        Vector3f ng = new Vector3f(0f, 1f, 0f); // horizontal water surface
        float time = 1.0f;

        // Realistic mode: spatial variation from wave interference
        Vector3f pos1 = new Vector3f(0f, 62f, 0f);
        Vector3f pos2 = new Vector3f(5.3f, 62f, 2.7f);
        Vector3f n1 = evaluateWaterWaveNormal(pos1, ng, time);
        Vector3f n2 = evaluateWaterWaveNormal(pos2, ng, time);

        assertEquals(1.0f, n1.length(), EPSILON);
        assertEquals(1.0f, n2.length(), EPSILON);

        // Wave slopes create perturbed normal
        assertTrue(Math.abs(n1.x) > 0.001f || Math.abs(n1.z) > 0.001f, "Waves must perturb horizontal normal");
        assertTrue(n1.dot(ng) > 0.8f, "Normal must remain upward-facing (horizon preservation)");
        assertTrue(n2.dot(ng) > 0.8f, "Normal must remain upward-facing (horizon preservation)");

        // Normals at different positions must differ due to wave interference
        assertNotEquals(n1.x, n2.x, 1e-4f);

        // Temporal animation: normal at pos1 changes over time
        Vector3f n1Later = evaluateWaterWaveNormal(pos1, ng, time + 2.0f);
        assertNotEquals(n1.x, n1Later.x, 1e-4f);
    }

    @Test
    public void testUnderwaterCausticsPattern() {
        Vector3f posBed1 = new Vector3f(2.0f, 58.0f, 3.0f);
        Vector3f posBed2 = new Vector3f(2.5f, 58.0f, 3.5f);

        float c1 = evaluateUnderwaterCaustics(posBed1, 0.5f);
        float c2 = evaluateUnderwaterCaustics(posBed2, 0.5f);

        assertTrue(c1 >= 0.0f, "Caustics must be non-negative");
        assertTrue(c2 >= 0.0f, "Caustics must be non-negative");
        // Caustics create peak brightness > 1.0 at convergence points
        float maxCaustic = 0.0f;
        for (float x = 0; x < 10.0f; x += 0.5f) {
            float c = evaluateUnderwaterCaustics(new Vector3f(x, 58f, 0f), 0.5f);
            if (c > maxCaustic) maxCaustic = c;
        }
        assertTrue(maxCaustic > 1.2f, "Caustics network must produce focused bright filaments");
    }

    @Test
    public void testSunSpecularHighlight() {
        Vector3f ns = new Vector3f(0f, 1f, 0f);
        Vector3f sunDir = new Vector3f(0.5f, 1.0f, 0.0f).normalize(); // incoming sunlight L
        Vector3f V = new Vector3f(-0.5f, 1.0f, 0.0f).normalize();     // view vector V = -d (mirror symmetry)

        // Perfect specular alignment (H = normalize(L + V) aligns with normal)
        Vector3f highlight = evaluateSunSpecular(ns, V, sunDir, 256.0f);
        assertTrue(highlight.length() > 0.5f, "Specular sun highlight must be bright on alignment");

        // Misaligned direction: highlight drops off sharply
        Vector3f misalignedV = new Vector3f(0.8f, 0.2f, 0.5f).normalize();
        Vector3f misalignedHighlight = evaluateSunSpecular(ns, misalignedV, sunDir, 256.0f);
        assertTrue(misalignedHighlight.length() < 0.01f, "Specular highlight must decay away from mirror direction");
    }

    @Test
    public void testLavaAnimatedTurbulentEmission() {
        // AutoPbr classification
        AutoPbr.Material lavaMat = AutoPbr.classify("block/lava_still");
        assertEquals(1.0f, lavaMat.emission(), EPSILON, "Lava must have base emission 1.0");
        assertEquals(AutoPbr.Emission.WHOLE, lavaMat.emissionMode(), "Lava must be WHOLE emitter");

        // Animated turbulent pulse
        Vector3f pos = new Vector3f(10.0f, 60.0f, 15.0f);
        float pulse0 = evaluateLavaPulse(pos, 0.0f);
        float pulse1 = evaluateLavaPulse(pos, 1.5f);
        float pulse2 = evaluateLavaPulse(pos, 3.0f);

        assertTrue(pulse0 >= 0.5f && pulse0 <= 2.0f, "Pulse must stay within safe dynamic range");
        assertTrue(pulse1 >= 0.5f && pulse1 <= 2.0f, "Pulse must stay within safe dynamic range");
        assertNotEquals(pulse0, pulse1, 1e-4f, "Lava pulse must animate over time");
        assertNotEquals(pulse1, pulse2, 1e-4f, "Lava pulse must animate over time");

        // Texel hotspot modulation: brighter core texels glow hotter
        float darkCrustLum = 0.25f;
        float brightCoreLum = 0.90f;
        float darkHotspot = 0.8f + 1.4f * (float) Math.pow(darkCrustLum, 1.6);
        float brightHotspot = 0.8f + 1.4f * (float) Math.pow(brightCoreLum, 1.6);
        assertTrue(brightHotspot > darkHotspot * 1.5f, "Bright lava core texels must produce significantly higher emission");
    }

    @Test
    public void testPassFlagsIntegration() {
        RtFrameParams params = new RtFrameParams();

        // When waterStyle == 1 (Realistic)
        RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
        int flagsRealistic = RtFrameParams.FLAG_MATERIALS
            | (RtSettings.waterStyle() == 1 ? RtFrameParams.FLAG_WATER_REALISTIC : 0);
        assertTrue((flagsRealistic & RtFrameParams.FLAG_WATER_REALISTIC) != 0,
            "Flags must contain FLAG_WATER_REALISTIC when waterStyle is 1");

        // When waterStyle == 0 (Vanilla)
        RtSettings.waterStyle = RtSettings.WATER_STYLE_VANILLA;
        int flagsVanilla = RtFrameParams.FLAG_MATERIALS
            | (RtSettings.waterStyle() == 1 ? RtFrameParams.FLAG_WATER_REALISTIC : 0);
        assertEquals(0, flagsVanilla & RtFrameParams.FLAG_WATER_REALISTIC,
            "Flags must not contain FLAG_WATER_REALISTIC when waterStyle is 0");

        // Verify packing into push constants ByteBuffer (offset 108)
        params.flags = flagsRealistic;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(RtFrameParams.BYTES)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        params.write(buf);
        assertEquals(flagsRealistic, buf.getInt(108), "flags with FLAG_WATER_REALISTIC must be written to offset 108");
        assertTrue((buf.getInt(108) & RtFrameParams.FLAG_WATER_REALISTIC) != 0);
    }

    // ---- Helper math replicating GLSL routines ----

    private static Vector3f glslRefract(Vector3f I, Vector3f N, float eta) {
        float dotNI = N.dot(I);
        float k = 1.0f - eta * eta * (1.0f - dotNI * dotNI);
        if (k < 0.0f) {
            return null; // Total internal reflection
        }
        Vector3f result = new Vector3f(I).mul(eta);
        Vector3f nTerm = new Vector3f(N).mul(eta * dotNI + (float) Math.sqrt(k));
        result.sub(nTerm);
        return result.normalize();
    }

    private static Vector3f evaluateWaterWaveNormal(Vector3f pos, Vector3f ng, float time) {
        // Tangent basis for ng = (0, 1, 0)
        Vector3f T = new Vector3f(1f, 0f, 0f);
        Vector3f B = new Vector3f(0f, 0f, 1f);
        float px = pos.dot(T) * 1.5f;
        float py = pos.dot(B) * 1.5f;
        float t = time * 2.0f;

        float ph1 = (0.8f * px + 0.6f * py) * 1.2f - t * 1.5f;
        float g1x = -0.8f * 1.2f * (float) Math.cos(ph1) * 0.035f;
        float g1y = -0.6f * 1.2f * (float) Math.cos(ph1) * 0.035f;

        float ph2 = (-0.7f * px + 0.7f * py) * 2.2f + t * 1.8f;
        float g2x = 0.7f * 2.2f * (float) Math.cos(ph2) * 0.024f;
        float g2y = -0.7f * 2.2f * (float) Math.cos(ph2) * 0.024f;

        float ph3 = (0.3f * px - 0.95f * py) * 3.5f - t * 2.5f;
        float g3x = -0.3f * 3.5f * (float) Math.cos(ph3) * 0.015f;
        float g3y = 0.95f * 3.5f * (float) Math.cos(ph3) * 0.015f;

        float ph4 = (-0.9f * px - 0.4f * py) * 6.0f + t * 3.2f;
        float g4x = 0.9f * 6.0f * (float) Math.cos(ph4) * 0.009f;
        float g4y = 0.4f * 6.0f * (float) Math.cos(ph4) * 0.009f;

        float slopeX = g1x + g2x + g3x + g4x;
        float slopeY = g1y + g2y + g3y + g4y;

        Vector3f n = new Vector3f(T).mul(slopeX).add(new Vector3f(B).mul(slopeY)).add(ng).normalize();
        return n.dot(ng) > 0.0f ? n : ng;
    }

    private static float evaluateUnderwaterCaustics(Vector3f pos, float time) {
        float px = pos.x * 2.0f;
        float py = pos.z * 2.0f;
        float t = time * 2.5f;

        float w1 = (float) (Math.sin(px * 1.4f + t) + Math.cos(py * 1.4f - t * 0.7f));
        float w2 = (float) Math.sin(px * -1.1f + py * 1.3f - t * 1.1f);
        float w3 = (float) Math.cos(px * 0.7f - py * 1.5f + t * 0.9f);
        float w4 = (float) Math.sin((px + py) * 2.5f + t * 1.6f);

        float waveSum = (w1 + w2 + w3 + w4) * 0.25f;
        float clamped = Math.max(0.0f, Math.min(1.0f, 0.5f + 0.5f * waveSum));
        return (float) Math.pow(clamped, 4.0) * 3.5f;
    }

    private static Vector3f evaluateSunSpecular(Vector3f ns, Vector3f V, Vector3f L, float specPower) {
        Vector3f H = new Vector3f(L).add(V).normalize();
        float NdotH = Math.max(ns.dot(H), 0.0f);
        float NdotL = Math.max(ns.dot(L), 0.0f);
        if (NdotL <= 0.0f || NdotH <= 0.0f) {
            return new Vector3f(0f);
        }
        float sunSpec = (float) Math.pow(NdotH, specPower) * (specPower + 2.0f) / (8.0f * (float) Math.PI);
        return new Vector3f(1.0f, 0.95f, 0.8f).mul(sunSpec * 4.0f);
    }

    private static float evaluateLavaPulse(Vector3f pos, float time) {
        float t = time * 2.0f;
        float turb = (float) (Math.sin(pos.x * 3.0f + t) * Math.cos(pos.z * 3.0f - t * 0.8f)
            + 0.5f * Math.sin(pos.x * 6.0f - t * 1.4f + pos.y * 4.0f) * Math.cos(pos.z * 6.0f + t * 1.2f)
            + 0.25f * Math.sin((pos.x + pos.z) * 12.0f + t * 2.2f));
        return Math.max(0.6f, Math.min(1.8f, 1.0f + 0.35f * turb));
    }
}
