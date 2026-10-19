package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests verifying ice and glass optics, transmission, Fresnel reflectance,
 * ray propagation through ice and transparent blocks, and screen-space depth compositing.
 */
public class RtIceGlassOpticsTest {

    private static final float EPSILON = 1e-4f;

    public static void runAll() {
        RtIceGlassOpticsTest t = new RtIceGlassOpticsTest();
        t.testIceOpticsAndTransmission();
        t.testIceSurfaceIdentification();
        t.testGlassAndIceInterfaceClassification();
        t.testDepthCompositingBehindTransparentBlocks();
    }

    @Test
    public void testIceOpticsAndTransmission() {
        // Ice IOR ~ 1.31 -> F0 approx 0.018 (clamped to 0.02 min in shader)
        float iceF0 = 0.02f;
        float roughness = 0.05f; // From AutoPbr for ice

        // Normal incidence (cosTheta = 1.0)
        float cosV = 1.0f;
        float inv = Math.max(0.0f, Math.min(1.0f, 1.0f - cosV));
        float inv5 = inv * inv * inv * inv * inv;
        float F = iceF0 + (1.0f - iceF0) * inv5;
        assertEquals(iceF0, F, EPSILON, "Ice reflectance at normal incidence must equal F0");

        // Transmission probability (1 - F) * transmittance
        float alpha = 0.5f; // translucent ice / glass
        float pTrans = (1.0f - F) * (1.0f - alpha);
        assertTrue(pTrans > 0.45f, "Ice must allow significant ray transmission (over 45%) to see the scene behind it");

        // Grazing angle (cosTheta = 0.0) -> F reaches 1.0
        float cosGrazing = 0.0f;
        float invGrazing = 1.0f - cosGrazing;
        float inv5Grazing = invGrazing * invGrazing * invGrazing * invGrazing * invGrazing;
        float FGrazing = iceF0 + (1.0f - iceF0) * inv5Grazing;
        assertEquals(1.0f, FGrazing, EPSILON, "Ice reflectance at grazing angle must be 1.0");

        // Transmittance formula: (1 - F) * pow(c, 2.0 * effAlpha)
        Vector3f c = new Vector3f(0.85f, 0.90f, 0.98f);
        float effAlpha = 0.65f;
        float transB = (1.0f - F) * (float) Math.pow(c.z, 2.0f * effAlpha);
        assertTrue(transB > 0.90f, "Blue ice component must transmit strongly through ice");
    }

    @Test
    public void testIceSurfaceIdentification() {
        // 1. Regular translucent ice (minecraft:ice)
        boolean translucent = true;
        Vector3f albedoIce = new Vector3f(0.70f, 0.85f, 0.98f);
        float roughnessIce = 0.05f;
        float f0Ice = 0.02f;
        float metalIce = 0.0f;

        boolean isIceRegular = isIceSurface(translucent, albedoIce, roughnessIce, f0Ice, metalIce);
        assertTrue(isIceRegular, "minecraft:ice (translucent, blueish, smooth) must be identified as ice");

        // 2. Solid blue ice (minecraft:blue_ice, pass 0 solid, texture alpha = 1.0)
        boolean solidPass = false; // not translucent pass
        Vector3f albedoBlueIce = new Vector3f(0.40f, 0.65f, 0.96f);
        float roughnessBlueIce = 0.05f; // from AutoPbr
        float f0BlueIce = 0.02f;        // from AutoPbr
        float metalBlueIce = 0.0f;

        boolean isIceBlue = isIceSurface(solidPass, albedoBlueIce, roughnessBlueIce, f0BlueIce, metalBlueIce);
        assertTrue(isIceBlue, "minecraft:blue_ice (solid pass, blueish, f0=0.02, roughness=0.05) must be identified as ice");

        // 3. Regular stone block (must not be ice)
        Vector3f albedoStone = new Vector3f(0.5f, 0.5f, 0.5f);
        boolean isIceStone = isIceSurface(false, albedoStone, 0.9f, 0.04f, 0.0f);
        assertFalse(isIceStone, "Stone block must not be identified as ice");

        // 4. Packed ice (roughness 0.15, not smooth ice)
        boolean isIcePacked = isIceSurface(false, new Vector3f(0.7f, 0.8f, 0.95f), 0.15f, 0.02f, 0.0f);
        assertFalse(isIcePacked, "Packed ice (roughness 0.15) must not be treated as thin clear ice interface");
    }

    @Test
    public void testGlassAndIceInterfaceClassification() {
        // Clear glass: alpha < 0.99
        assertTrue(isInterface(0.0f, false), "Clear glass (alpha 0.0) must be an interface");
        // Stained glass: alpha ~ 0.5
        assertTrue(isInterface(0.5f, false), "Stained glass (alpha 0.5) must be an interface");
        // Ice with translucent pass: alpha ~ 0.5
        assertTrue(isInterface(0.5f, true), "Translucent ice must be an interface");
        // Blue ice: alpha 1.0, isIce = true
        assertTrue(isInterface(1.0f, true), "Blue ice (alpha 1.0, isIce true) must be an interface");
        // Solid rock: alpha 1.0, isIce = false
        assertFalse(isInterface(1.0f, false), "Solid rock (alpha 1.0, isIce false) must NOT be an interface");
    }

    @Test
    public void testDepthCompositingBehindTransparentBlocks() {
        // Scenario: Player looks through an ice or glass block at 3.0 meters.
        // Behind the transparent block is a room wall at 10.0 meters.
        // In rasterization, Minecraft draws the glass / ice block at 3.0 meters.
        float distRaster = 3.0f;

        // With firstDist tracking in rt_rays.comp and rt_path.comp:
        // dRay records the first surface hit along the primary ray (the glass/ice at 3.0m).
        float dRay = 3.0f;
        float thresh = Math.max(0.20f, dRay * 0.05f);

        // rt_composite.comp logic:
        boolean isEntityCorrect = evaluateIsEntity(distRaster, dRay, thresh);
        assertFalse(isEntityCorrect, "Glass/ice surface matching raster depth must NOT be classified as an entity");

        // If dRay were erroneously recorded as the wall behind (10.0m):
        float dRayBroken = 10.0f;
        float threshBroken = Math.max(0.20f, dRayBroken * 0.05f);
        boolean isEntityBroken = evaluateIsEntity(distRaster, dRayBroken, threshBroken);
        assertTrue(isEntityBroken, "Without firstDist tracking, transparent block would falsely trigger entity mode and replace RT with raster");

        // Foreground entity (e.g. mob at 1.5m) in front of the glass:
        float distRasterMob = 1.5f;
        boolean isEntityMob = evaluateIsEntity(distRasterMob, dRay, thresh);
        assertTrue(isEntityMob, "Foreground mob in front of transparent block must be correctly preserved as an entity");
    }

    private static boolean isIceSurface(boolean translucent, Vector3f albedo, float roughness, float f0, float metal) {
        return (translucent && (albedo.z > albedo.x && albedo.z > 0.4f && roughness < 0.2f))
            || (f0 <= 0.035f && roughness <= 0.08f && metal < 0.5f && albedo.z > albedo.x * 1.1f && albedo.z > 0.35f);
    }

    private static boolean isInterface(float alpha, boolean isIce) {
        return alpha < 0.99f || isIce;
    }

    private static boolean evaluateIsEntity(float distRaster, float dRay, float thresh) {
        if (dRay >= 1.0e5f) {
            return distRaster <= 256.0f;
        } else {
            if (distRaster < dRay - thresh) {
                return true;
            } else if (distRaster < 1.2f && distRaster < dRay - 0.05f) {
                return true;
            }
        }
        return false;
    }
}
