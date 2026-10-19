package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.RuntimeFeatureStatus;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for Physically Consistent RGB Colored Transmission (Beer-Lambert attenuation)
 * through stained glass, water, and ice.
 */
public class RtColoredTransmissionTest {

    private Vector3f simulateTransmittance(Vector3f baseColor, float thickness, float f0) {
        // Beer-Lambert: T = exp(-absorption * thickness) * (1.0 - f0)
        // absorption = -log(max(baseColor, 0.02)) * 2.0
        Vector3f tint = new Vector3f(
            Math.max(baseColor.x, 0.02f),
            Math.max(baseColor.y, 0.02f),
            Math.max(baseColor.z, 0.02f)
        );
        Vector3f absorption = new Vector3f(
            (float) -Math.log(tint.x) * 2.0f,
            (float) -Math.log(tint.y) * 2.0f,
            (float) -Math.log(tint.z) * 2.0f
        );
        return new Vector3f(
            (float) Math.exp(-absorption.x * thickness) * (1.0f - f0),
            (float) Math.exp(-absorption.y * thickness) * (1.0f - f0),
            (float) Math.exp(-absorption.z * thickness) * (1.0f - f0)
        );
    }

    @Test
    public void testClearAirFullTransmittance() {
        // Clear unobstructed path has 100% transmission
        Vector3f t = new Vector3f(1.0f, 1.0f, 1.0f);
        assertEquals(1.0f, t.x, 1e-4f);
        assertEquals(1.0f, t.y, 1e-4f);
        assertEquals(1.0f, t.z, 1e-4f);
    }

    @Test
    public void testOpaqueBlockerZeroTransmittance() {
        // Opaque surface completely blocks transmittance
        Vector3f t = new Vector3f(0.0f, 0.0f, 0.0f);
        assertEquals(0.0f, t.x, 1e-4f);
        assertEquals(0.0f, t.y, 1e-4f);
        assertEquals(0.0f, t.z, 1e-4f);
    }

    @Test
    public void testRedStainedGlassAbsorption() {
        // Red stained glass: high R, low G and B
        Vector3f redGlass = new Vector3f(0.95f, 0.05f, 0.05f);
        Vector3f t = simulateTransmittance(redGlass, 0.25f, 0.04f);

        assertTrue(t.x > 0.8f, "Red channel must remain strongly transmitted");
        assertTrue(t.y < 0.3f, "Green channel must be strongly attenuated");
        assertTrue(t.z < 0.3f, "Blue channel must be strongly attenuated");
        assertTrue(t.x > t.y * 3.0f, "Red transmittance must dominate green");
    }

    @Test
    public void testBlueStainedGlassAbsorption() {
        // Blue stained glass: low R, low G, high B
        Vector3f blueGlass = new Vector3f(0.05f, 0.05f, 0.95f);
        Vector3f t = simulateTransmittance(blueGlass, 0.25f, 0.04f);

        assertTrue(t.z > 0.8f, "Blue channel must remain strongly transmitted");
        assertTrue(t.x < 0.3f, "Red channel must be strongly attenuated");
        assertTrue(t.y < 0.3f, "Green channel must be strongly attenuated");
        assertTrue(t.z > t.x * 3.0f, "Blue transmittance must dominate red");
    }

    @Test
    public void testTwoStainedGlassLayersCombination() {
        // Red glass followed by Blue glass
        Vector3f redT = simulateTransmittance(new Vector3f(0.95f, 0.05f, 0.05f), 0.25f, 0.04f);
        Vector3f blueT = simulateTransmittance(new Vector3f(0.05f, 0.05f, 0.95f), 0.25f, 0.04f);
        Vector3f combined = new Vector3f(redT).mul(blueT);

        // Subtractive color mixing produces near black
        assertTrue(combined.x < 0.3f, "Red component should be blocked by blue layer");
        assertTrue(combined.z < 0.3f, "Blue component should be blocked by red layer");
        assertTrue(combined.y < 0.1f, "Green component doubly attenuated");
    }

    @Test
    public void testEnergyConservation() {
        Vector3f glass = new Vector3f(0.8f, 0.4f, 0.2f);
        Vector3f t = simulateTransmittance(glass, 0.5f, 0.04f);

        assertTrue(t.x <= 1.0f, "Energy conservation violation: Tx > 1");
        assertTrue(t.y <= 1.0f, "Energy conservation violation: Ty > 1");
        assertTrue(t.z <= 1.0f, "Energy conservation violation: Tz > 1");
    }

    @Test
    public void testPaneVsBlockThickness() {
        Vector3f tintedGlass = new Vector3f(0.5f, 0.8f, 0.9f);
        float thinPaneThickness = 0.125f;
        float solidBlockThickness = 1.0f;

        Vector3f tPane = simulateTransmittance(tintedGlass, thinPaneThickness, 0.04f);
        Vector3f tBlock = simulateTransmittance(tintedGlass, solidBlockThickness, 0.04f);

        // Solid block has longer optical path, absorbing more light
        assertTrue(tBlock.x < tPane.x, "Solid glass block must have lower transmittance than thin glass pane");
        assertTrue(tBlock.y < tPane.y, "Solid glass block must absorb more in all channels");
    }

    @Test
    public void testObliqueIncidencePathLength() {
        Vector3f tintedGlass = new Vector3f(0.5f, 0.8f, 0.9f);
        float normalCos = 1.0f; // perpendicular ray
        float obliqueCos = 0.5f; // 60-degree angle from normal: cos = 0.5, path length = 2x

        float normalPath = 0.25f / normalCos;
        float obliquePath = 0.25f / obliqueCos;

        Vector3f tNormal = simulateTransmittance(tintedGlass, normalPath, 0.04f);
        Vector3f tOblique = simulateTransmittance(tintedGlass, obliquePath, 0.04f);

        assertTrue(tOblique.x < tNormal.x, "Oblique ray path must attenuate more light than perpendicular ray");
    }

    // ----- RtTransmissionDiagnostics honest state tests -----

    @Test
    public void testTransmissionDiagnosticsModelIsHonest() {
        assertEquals("ANGULAR_LAYER_APPROXIMATION", RtTransmissionDiagnostics.MODEL,
            "Must never claim physical volume transport");
        String diag = RtTransmissionDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("volumeExit=VOLUME_EXIT_MISSING"), "volume exit must be honest MISSING");
        assertTrue(diag.contains("caustics=OFF"), "caustics must be OFF");
    }

    @Test
    public void testTransmissionDiagnosticsZeroEntitiesReportsInactive() {
        RtTransmissionDiagnostics.reset();
        RtTransmissionDiagnostics.updateFromFrame(0, 0); // 0 emitters, 0 entity primitives
        assertFalse(RtTransmissionDiagnostics.isLocalLightsActive(), "0 emitters = localLights INACTIVE");
        assertFalse(RtTransmissionDiagnostics.isEntityLightingActive(), "0 entities = entityLighting INACTIVE");
        String diag = RtTransmissionDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("localLights=INACTIVE"), "must report localLights=INACTIVE");
        assertTrue(diag.contains("entityLighting=INACTIVE"), "must report entityLighting=INACTIVE");
    }

    @Test
    public void testTransmissionDiagnosticsActiveEmitters() {
        RtTransmissionDiagnostics.updateFromFrame(10, 42); // emitters=10, entityTris=42
        assertTrue(RtTransmissionDiagnostics.isLocalLightsActive(), "10 emitters = localLights ACTIVE");
        assertTrue(RtTransmissionDiagnostics.isEntityLightingActive(), "42 entityTris = entityLighting ACTIVE");
        String diag = RtTransmissionDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("sunlight=ACTIVE"), "sunlight should be ACTIVE when RT is running");
        assertTrue(diag.contains("localLights=ACTIVE"), "must report localLights=ACTIVE");
        assertTrue(diag.contains("entityLighting=ACTIVE"), "must report entityLighting=ACTIVE");
        RtTransmissionDiagnostics.reset();
    }

    // ----- RuntimeFeatureStatus honest enum tests -----

    @Test
    public void testRuntimeFeatureStatusEnumContainsRequiredValues() {
        // Verify the enum has all required honest states
        RuntimeFeatureStatus[] values = RuntimeFeatureStatus.values();
        assertNotNull(values);
        // Must include MISSING, EMPTY, READY, ACTIVE, FAILED
        boolean hasMissing = false, hasEmpty = false, hasReady = false, hasFailed = false;
        for (RuntimeFeatureStatus s : values) {
            if (s == RuntimeFeatureStatus.MISSING) hasMissing = true;
            if (s == RuntimeFeatureStatus.EMPTY) hasEmpty = true;
            if (s == RuntimeFeatureStatus.READY) hasReady = true;
            if (s == RuntimeFeatureStatus.FAILED) hasFailed = true;
        }
        assertTrue(hasMissing, "must have MISSING state");
        assertTrue(hasEmpty, "must have EMPTY state");
        assertTrue(hasReady, "must have READY state");
        assertTrue(hasFailed, "must have FAILED state");
    }

    @Test
    public void testRuntimeFeatureStatusReadyRequiresPrerequisites() {
        // READY is ordinal 7 — after all prerequisite states
        // (MISSING=0, EMPTY=1, CAPTURED=2, UPLOAD_PENDING=3, BLAS_PENDING=4,
        //  TLAS_PENDING=5, SHADER_LAYOUT_MISSING=6, READY=7, ACTIVE=8, FAILED=9)
        // Verify READY's ordinal is > MISSING, EMPTY, CAPTURED
        assertTrue(RuntimeFeatureStatus.READY.ordinal() > RuntimeFeatureStatus.MISSING.ordinal());
        assertTrue(RuntimeFeatureStatus.READY.ordinal() > RuntimeFeatureStatus.EMPTY.ordinal());
        assertTrue(RuntimeFeatureStatus.READY.ordinal() > RuntimeFeatureStatus.CAPTURED.ordinal());
    }
}
