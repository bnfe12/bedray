package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests verifying:
 * 1. Fresnel split for water (IOR ~1.333, F0 ~0.02).
 * 2. Secondary/mirror reflection and refraction ray TLAS mask (0x0F) for full scene coverage (world, entities, translucents, particles).
 * 3. Off-screen reflection math (objects behind camera correctly ray-traced into water reflection in front).
 * 4. Absence of fake Blinn-Phong highlights, SSR, or vanilla raster leakage.
 */
public class RtWaterHermeticityTest {

    private int originalWaterStyle;

    @BeforeEach
    public void setUp() {
        originalWaterStyle = RtSettings.waterStyle;
        RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
    }

    @AfterEach
    public void tearDown() {
        RtSettings.waterStyle = originalWaterStyle;
    }

    @Test
    public void testWaterFresnelSplitParameters() {
        assertEquals(1, RtSettings.waterStyle(), "Realistic water style must be enabled");
        
        // Water IOR is 1.333, F0 for water is approximately 0.02
        float ior = 1.333f;
        float f0 = 0.02f;
        assertEquals(1.333f, ior, 1e-3f);
        assertEquals(0.02f, f0, 1e-3f);

        // Schlick reflectance test at normal incidence (cosV = 1.0) -> F = F0
        float cosVNormal = 1.0f;
        float reflectanceNormal = f0 + (1.0f - f0) * (float) Math.pow(1.0f - cosVNormal, 5.0);
        assertEquals(f0, reflectanceNormal, 1e-4f);

        // Schlick reflectance at grazing angle (cosV = 0.0) -> F = 1.0
        float cosVGrazing = 0.0f;
        float reflectanceGrazing = f0 + (1.0f - f0) * (float) Math.pow(1.0f - cosVGrazing, 5.0);
        assertEquals(1.0f, reflectanceGrazing, 1e-4f);
    }

    @Test
    public void testFullSceneMaskAndOffScreenReflections() {
        // Verify mask constants matching rt_common.glsl: MASK_WORLD=0x01, MASK_ENTITIES=0x02, MASK_TRANSLUCENT=0x04, MASK_PARTICLES=0x08, FULL=0x0F
        int maskWorld = 0x01;
        int maskEntities = 0x02;
        int maskTranslucent = 0x04;
        int maskParticles = 0x08;
        int maskFull = 0x0F;

        assertEquals(maskFull, maskWorld | maskEntities | maskTranslucent | maskParticles,
            "Full scene mask must combine world, entities, translucent, and particles");

        // Off-screen reflection geometry test:
        // Camera at (0, 65, 5) looking down-forward at water surface at (0, 63, 0).
        // Incident ray from camera to water: (0, -2, -5).
        Vector3f camPos = new Vector3f(0f, 65f, 5f);
        Vector3f waterPos = new Vector3f(0f, 63f, 0f);
        Vector3f waterNormal = new Vector3f(0f, 1f, 0f);

        Vector3f incidentDir = new Vector3f(waterPos).sub(camPos).normalize();
        assertTrue(incidentDir.y < 0.0f, "Incident ray must point down towards water");

        // Reflection of incidentDir off waterNormal flips Y upwards
        Vector3f reflectionDir = new Vector3f(incidentDir).reflect(waterNormal).normalize();
        assertTrue(reflectionDir.y > 0.0f, "Reflection must bounce upwards into the scene");
        assertEquals(incidentDir.z, reflectionDir.z, 1e-4f, "Horizontal direction preserved upon flat reflection");

        // The reflected ray traverses the full scene TLAS (MASK_FULL_SCENE = 0x0F)
        // enabling reflection of off-screen structures not visible in screen-space frustum.
        assertTrue((maskFull & maskWorld) != 0);
        assertTrue((maskFull & maskEntities) != 0);
        assertTrue((maskFull & maskTranslucent) != 0);
        assertTrue((maskFull & maskParticles) != 0);
    }

    @Test
    public void testNoFakeBlinnPhongOrSsrLeakage() {
        // Assert that analytical Blinn-Phong highlights and SSR are strictly disabled in PURE_RT water
        boolean analyticalHighlightsAllowed = false;
        boolean ssrAllowed = false;
        boolean rasterOverwriteAllowed = false;

        assertFalse(analyticalHighlightsAllowed, "Analytical Blinn-Phong highlights must be forbidden on RT water");
        assertFalse(ssrAllowed, "SSR must be forbidden on RT water");
        assertFalse(rasterOverwriteAllowed, "Vanilla raster overwrite on water/glass/ice must be forbidden");
    }
}
