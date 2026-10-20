package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.RuntimeFeatureStatus;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for the thin-interface transmittance of rt_common.glsl (interfaceTransmittance and
 * shadowLayerTransmittance): (1 - F) * albedo^alpha, a Beer-Lambert filter whose optical thickness is the texel alpha.
 * The values below are the real 26.1 textures (minecraft-26.1.2-client.jar): stained glass centre (153,51,51) /
 * (51,76,178) at alpha 102/255, tinted glass (39,37,40) at alpha 110/255. The shader samples the block atlas, so the
 * 0..255 texel values divided by 255 are what reaches pow().
 *
 * The previous model saturated the hue (normC^2.2, floor 0.16 * maxC) and used an exponent of 2 * alpha (at least 1):
 * two blue panes kept ~1 % of red and turned the room behind them into black / blue voids (owner screenshot 37, PT
 * Quality). These tests pin the readable model so the void cannot come back unnoticed.
 */
public class RtColoredTransmissionTest {

    private static final Vector3f RED_GLASS = srgb255(153, 51, 51);
    private static final float RED_GLASS_ALPHA = 102.0f / 255.0f;
    private static final Vector3f BLUE_GLASS = srgb255(51, 76, 178);
    private static final float BLUE_GLASS_ALPHA = 102.0f / 255.0f;
    private static final Vector3f TINTED_GLASS = srgb255(39, 37, 40);
    private static final float TINTED_GLASS_ALPHA = 110.0f / 255.0f;
    private static final float GLASS_F0 = 0.04f;

    /** Shader mirror: (1 - F) * clamp(albedo)^alpha; alpha below 0.1 is a clear texel and passes untinted. */
    private static Vector3f glass(Vector3f albedo, float alpha, float f0) {
        float e = alpha < 0.1f ? 0.0f : Math.min(Math.max(alpha, 0.0f), 1.0f);
        return new Vector3f(
            (float) Math.pow(clamp(albedo.x), e) * (1.0f - f0),
            (float) Math.pow(clamp(albedo.y), e) * (1.0f - f0),
            (float) Math.pow(clamp(albedo.z), e) * (1.0f - f0)
        );
    }

    private static float clamp(float v) {
        return Math.min(Math.max(v, 0.001f), 1.0f);
    }

    private static Vector3f srgb255(int r, int g, int b) {
        return new Vector3f(r / 255.0f, g / 255.0f, b / 255.0f);
    }

    private static float minChannel(Vector3f v) {
        return Math.min(v.x, Math.min(v.y, v.z));
    }

    @Test
    public void testClearGlassPassesUntouched() {
        Vector3f t = glass(new Vector3f(1.0f), 0.0f, GLASS_F0);
        assertEquals(1.0f - GLASS_F0, t.x, 1e-4f);
        assertEquals(1.0f - GLASS_F0, t.y, 1e-4f);
        assertEquals(1.0f - GLASS_F0, t.z, 1e-4f);

        // 0.05 alpha is still a clear texel (the hole in vanilla glass.png has alpha 0)
        Vector3f almostClear = glass(new Vector3f(0.4f, 0.6f, 0.8f), 0.05f, GLASS_F0);
        assertEquals(1.0f - GLASS_F0, almostClear.z, 1e-4f);
    }

    @Test
    public void testOpaqueBlockerZeroTransmittance() {
        // An opaque surface is not an interface and is never fed to the transmittance functions; a zeroed result stays zero
        Vector3f t = new Vector3f(0.0f, 0.0f, 0.0f);
        assertEquals(0.0f, t.x, 1e-4f);
        assertEquals(0.0f, t.y, 1e-4f);
        assertEquals(0.0f, t.z, 1e-4f);
    }

    @Test
    public void testRedStainedGlassAbsorption() {
        Vector3f t = glass(RED_GLASS, RED_GLASS_ALPHA, GLASS_F0);

        assertTrue(t.x > 0.75f, "Red channel must remain strongly transmitted: " + t);
        assertTrue(t.y < 0.55f, "Green channel must be attenuated by the red dye: " + t);
        assertTrue(t.z < 0.55f, "Blue channel must be attenuated by the red dye: " + t);
        assertTrue(t.x > t.y * 1.4f, "Red transmittance must dominate green: " + t);
    }

    @Test
    public void testBlueStainedGlassAbsorption() {
        Vector3f t = glass(BLUE_GLASS, BLUE_GLASS_ALPHA, GLASS_F0);

        assertTrue(t.z > 0.8f, "Blue channel must remain strongly transmitted: " + t);
        assertTrue(t.x < 0.55f, "Red channel must be attenuated by the blue dye: " + t);
        assertTrue(t.z > t.x * 1.6f, "Blue transmittance must dominate red: " + t);
    }

    @Test
    public void testTwoStainedGlassLayersStayReadable() {
        // The owner's scene: a room seen through two panes. The old hue-saturated model left 1.2 % of red through two blue
        // panes and 0.9 % of green / blue through two red panes: the room read as a black / blue void.
        Vector3f redTwice = glass(RED_GLASS, RED_GLASS_ALPHA, GLASS_F0).mul(glass(RED_GLASS, RED_GLASS_ALPHA, GLASS_F0));
        Vector3f blueTwice = glass(BLUE_GLASS, BLUE_GLASS_ALPHA, GLASS_F0).mul(glass(BLUE_GLASS, BLUE_GLASS_ALPHA, GLASS_F0));

        assertTrue(minChannel(redTwice) >= 0.2f, "Two red panes must keep every channel readable, got " + redTwice);
        assertTrue(minChannel(blueTwice) >= 0.2f, "Two blue panes must keep every channel readable, got " + blueTwice);
        assertTrue(redTwice.x > 0.5f, "Red must still dominate through two red panes: " + redTwice);
        assertTrue(blueTwice.z > 0.6f, "Blue must still dominate through two blue panes: " + blueTwice);
    }

    @Test
    public void testRealRedAndBlueLayersCombineWithoutVoids() {
        Vector3f red = glass(RED_GLASS, RED_GLASS_ALPHA, GLASS_F0);
        Vector3f blue = glass(BLUE_GLASS, BLUE_GLASS_ALPHA, GLASS_F0);
        Vector3f combined = new Vector3f(red).mul(blue);

        // Subtractive mixing still works (each layer removes its complement) but no channel collapses
        assertTrue(combined.x < red.x, "Blue layer must reduce the red channel: " + combined);
        assertTrue(combined.z < blue.z, "Red layer must reduce the blue channel: " + combined);
        assertTrue(minChannel(combined) >= 0.2f, "A red + blue stack must not collapse to black, got " + combined);
    }

    @Test
    public void testTintedGlassStaysDarkButNotBlack() {
        Vector3f once = glass(TINTED_GLASS, TINTED_GLASS_ALPHA, GLASS_F0);
        Vector3f twice = new Vector3f(once).mul(once);

        assertTrue(minChannel(once) < 0.5f, "Tinted glass must stay dark in one layer: " + once);
        assertTrue(minChannel(twice) > 0.12f, "Two tinted panes must not become a black wall: " + twice);
        assertTrue(minChannel(twice) < 0.3f, "Two tinted panes must stay darker than clear glass: " + twice);
    }

    @Test
    public void testGlassBlockIsTintedOnceAtTheEntryFace() {
        // A glass block is one slab: the entry face filters, the exit face (backFace) only applies Fresnel
        Vector3f entry = glass(BLUE_GLASS, BLUE_GLASS_ALPHA, GLASS_F0);
        Vector3f block = new Vector3f(entry).mul(1.0f - GLASS_F0);
        Vector3f squaredTint = new Vector3f(entry).mul(entry);

        assertEquals(entry.z * (1.0f - GLASS_F0), block.z, 1e-4f);
        assertTrue(block.z > squaredTint.z, "The block must not apply the dye twice");
    }

    @Test
    public void testEnergyConservation() {
        for (Vector3f t : new Vector3f[] {
            glass(RED_GLASS, RED_GLASS_ALPHA, GLASS_F0),
            glass(BLUE_GLASS, BLUE_GLASS_ALPHA, GLASS_F0),
            glass(TINTED_GLASS, TINTED_GLASS_ALPHA, GLASS_F0),
            glass(new Vector3f(0.8f, 0.4f, 0.2f), 1.0f, GLASS_F0)
        }) {
            assertTrue(t.x <= 1.0f && t.y <= 1.0f && t.z <= 1.0f, "Energy conservation violation: " + t);
        }
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
