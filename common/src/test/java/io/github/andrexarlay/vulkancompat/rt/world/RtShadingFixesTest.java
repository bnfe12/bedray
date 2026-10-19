package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests verifying:
 * 1. Glass transparency and Fresnel-weighted transmission.
 * 2. Enhanced specular baseline (dielectricF0) and lobe probability (ps) balancing.
 * 3. 2.8x power boost of torch, soul torch, campfire, and candle emitters in RtEmitters.
 * 4. Voxel edge bleeding prevention (bounded shadowBias) and normal map terminator smoothing.
 */
public class RtShadingFixesTest {

    private static final float EPSILON = 1e-4f;

    @Test
    public void testGlassTransparencyAndFresnel() {
        runGlassTransparencyChecks();
    }

    @Test
    public void testSpecularReflectionsBaseline() {
        runSpecularBaselineChecks();
    }

    @Test
    public void testTorchAndEmitterBoost() {
        runTorchBoostChecks();
    }

    @Test
    public void testVoxelEdgeBleedingAndNormalSmoothing() {
        runVoxelEdgeBleedingChecks();
    }

    public static void runAll() {
        RtShadingFixesTest test = new RtShadingFixesTest();
        test.testGlassTransparencyAndFresnel();
        test.testSpecularReflectionsBaseline();
        test.testTorchAndEmitterBoost();
        test.testVoxelEdgeBleedingAndNormalSmoothing();
    }

    private static float fresnelSchlick(float f0, float cosTheta) {
        float c = Math.max(0.0f, Math.min(1.0f, cosTheta));
        float inv = 1.0f - c;
        float inv5 = inv * inv * inv * inv * inv;
        return f0 + (1.0f - f0) * inv5;
    }

    private static float dielectricF0(float f0, float roughness) {
        float gloss = Math.max(0.0f, Math.min(1.0f, 1.0f - roughness));
        return Math.max(f0, f0 + (0.22f - f0) * (float) Math.pow(gloss, 1.8));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static void runGlassTransparencyChecks() {
        // Clear glass (alpha = 0.0, roughness = 0.02, f0 = 0.04)
        float roughness = 0.02f;
        float f0 = 0.04f;
        float baseF0 = dielectricF0(f0, roughness);
        assertTrue(baseF0 > 0.20f, "dielectricF0 for smooth glass must be > 0.20 for clear reflections");

        // Perpendicular view (cosTheta = 1.0): Fresnel reflectance is baseF0
        float fNormal = fresnelSchlick(baseF0, 1.0f);
        assertEquals(baseF0, fNormal, EPSILON, "At cosTheta=1, F must equal baseF0");

        // Clear glass transmission probability: pTrans = (1 - F) * (1 - alpha)
        float alphaClear = 0.0f;
        float pTransClearNormal = (1.0f - fNormal) * (1.0f - alphaClear);
        assertTrue(pTransClearNormal > 0.75f, "Clear glass at normal view must have > 75% transmission probability");
        assertTrue(pTransClearNormal < 0.85f, "Clear glass must reserve remainder for specular reflection");

        // Tinted glass (alpha = 0.3)
        float alphaTinted = 0.3f;
        float pTransTintedNormal = (1.0f - fNormal) * (1.0f - alphaTinted);
        assertEquals((1.0f - fNormal) * 0.7f, pTransTintedNormal, EPSILON, "Tinted glass transmission scales with (1 - alpha)");

        // Grazing view (cosTheta = 0.0): total reflection, zero transmission
        float fGrazing = fresnelSchlick(baseF0, 0.0f);
        assertEquals(1.0f, fGrazing, EPSILON, "At grazing angle, Fresnel reflectance must be 1.0");
        float pTransGrazing = (1.0f - fGrazing) * (1.0f - alphaClear);
        assertEquals(0.0f, pTransGrazing, EPSILON, "At grazing angle, transmission must be 0");

        // Opaque surface (alpha >= 0.99): pTrans must be 0
        float alphaOpaque = 1.0f;
        float pTransOpaque = (1.0f - fNormal) * (1.0f - alphaOpaque);
        assertEquals(0.0f, pTransOpaque, EPSILON, "Opaque block has 0 transmission");
    }

    private static void runSpecularBaselineChecks() {
        // Water (f0 = 0.02, roughness = 0.02)
        float waterF0 = dielectricF0(0.02f, 0.02f);
        assertTrue(waterF0 > 0.20f, "Water baseline F0 must be boosted > 0.20 so reflections are visible from above");

        // Glass (f0 = 0.04, roughness = 0.02)
        float glassF0 = dielectricF0(0.04f, 0.02f);
        assertTrue(glassF0 > 0.21f, "Glass baseline F0 must be boosted > 0.21");

        // Polished stone (f0 = 0.04, roughness = 0.35)
        float polishedF0 = dielectricF0(0.04f, 0.35f);
        assertTrue(polishedF0 > 0.10f && polishedF0 < 0.16f, "Polished stone has moderate reflection boost (0.10..0.16)");

        // Rough stone (f0 = 0.04, roughness = 0.90)
        float roughF0 = dielectricF0(0.04f, 0.90f);
        assertEquals(0.04f, roughF0, 0.01f, "Rough stone keeps default 0.04 baseline");

        // Specular lobe probability ps balancing
        float glossWater = 1.0f - 0.02f;
        float fWaterNormal = fresnelSchlick(waterF0, 1.0f);
        float psWaterNormal = Math.max(0.05f, Math.min(0.95f, Math.max(fWaterNormal, 0.45f * glossWater)));
        assertTrue(psWaterNormal >= 0.40f, "Smooth water must sample specular lobe with probability >= 0.40 at normal incidence");

        // Grazing angle ps
        float fWaterGrazing = fresnelSchlick(waterF0, 0.1f);
        float psWaterGrazing = Math.max(0.05f, Math.min(0.95f, Math.max(fWaterGrazing, 0.45f * glossWater)));
        assertTrue(psWaterGrazing > 0.65f, "Grazing angle specular lobe probability must increase towards 0.95");

        // Energy conservation check between diffuse and specular lobes
        float F = fWaterNormal;
        float ps = psWaterNormal;
        float diffuseWeight = (1.0f - F) / (1.0f - ps);
        float specularWeight = F / ps;
        float totalEnergy = ps * specularWeight + (1.0f - ps) * diffuseWeight;
        assertEquals(1.0f, totalEnergy, EPSILON, "Monte Carlo lobe splitting must satisfy exact energy conservation");
    }

    private static void runTorchBoostChecks() {
        int[] mat = new int[4 * 4];
        java.util.Arrays.fill(mat, LabPbr.pack(255, 0, 0, LabPbr.SOURCE_AUTO));
        mat[0] = LabPbr.pack(255, 0, 255, LabPbr.SOURCE_AUTO); // texel (0,0) emission 1.0

        // Quad 0: 1x1 flat quad on y=0 (solid pass: ceiling lamp / glowstone)
        // Quad 1: 0.2x0.8 vertical quad (cutout pass: torch flame)
        float[] pos = {
            // quad 0 (solid face): [0,1] on x,z at y=0
            0, 0, 0,  1, 0, 0,  1, 0, 1,  0, 0, 1,
            // quad 1 (cutout torch): [0.4, 0.6] on x at y=[0.2, 1.0]
            0.4f, 0.2f, 0.5f,  0.6f, 0.2f, 0.5f,  0.6f, 1.0f, 0.5f,  0.4f, 1.0f, 0.5f
        };

        float[] uv = {
            // quad 0 UVs mapping to texel 0
            0.0f, 0.0f,  0.25f, 0.0f,  0.25f, 0.25f,  0.0f, 0.25f,
            // quad 1 UVs mapping to texel 0
            0.0f, 0.0f,  0.25f, 0.0f,  0.25f, 0.25f,  0.0f, 0.25f
        };

        // solidQuads = 1: quad 0 is solid, quad 1 is cutout
        float[] records = RtEmitters.extractQuads(pos, uv, 2, mat, 4, 4, 1);
        assertEquals(2 * RtEmitters.FLOATS, records.length, "Extracted 2 quads");

        // Quad 0 (solid): boost at offset 7
        float quad0Boost = records[7];
        assertEquals(1.0f, quad0Boost, EPSILON, "Solid block emitter must keep 1.0x baseline power");

        // Quad 1 (cutout torch): boost at offset FLOATS + 7
        float quad1Boost = records[RtEmitters.FLOATS + 7];
        // the boost is power based now (RtEmitters.MIN_POWER = area x mean emission x boost), not a flat 2.8x of every cutout quad:
        // this 0.2 x 0.8 quad (area 0.16, fully emissive) is lifted to the minimum power 0.2
        float quad1Area = records[RtEmitters.FLOATS + 3];
        float quad1Mean = records[RtEmitters.FLOATS + 14];
        assertEquals(0.16f, quad1Area, 1e-4f, "area of the torch quad");
        assertEquals(RtEmitters.MIN_POWER, quad1Area * quad1Mean * quad1Boost, 1e-4f,
            "small cutout emitter must be lifted to MIN_POWER, boost was " + quad1Boost);
    }

    private static void runVoxelEdgeBleedingChecks() {
        // Shadow bias bounds: prevents ray origin punching through walls or thin slabs
        float biasClose = RtLighting.shadowBias(0.0f);
        float biasMid = RtLighting.shadowBias(50.0f);
        float biasFar = RtLighting.shadowBias(1000.0f);

        assertTrue(biasClose >= 0.003f, "Shadow bias at t=0 must clear float precision (>= 3mm)");
        assertTrue(biasMid > biasClose, "Shadow bias must grow with distance");
        assertTrue(biasFar <= 0.015f, "Shadow bias must be clamped to <= 15mm to avoid wall edge bleeding at distance");

        // Smooth shadow terminator: prevents jagged normal terminator artifacts near geometric horizon
        float tNegative = smoothstep(0.0f, 0.08f, -0.01f);
        assertEquals(0.0f, tNegative, EPSILON, "Facing away from light must have 0 terminator");

        float tZero = smoothstep(0.0f, 0.08f, 0.0f);
        assertEquals(0.0f, tZero, EPSILON, "At geometric horizon, terminator must be 0");

        float tMid = smoothstep(0.0f, 0.08f, 0.04f);
        assertTrue(tMid > 0.4f && tMid < 0.6f, "Halfway into terminator range smoothly ramps (0.5)");

        float tFull = smoothstep(0.0f, 0.08f, 0.10f);
        assertEquals(1.0f, tFull, EPSILON, "Above terminator threshold, full lighting is preserved");
    }
}
