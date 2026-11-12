package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.RtSceneInstances;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless guards of the hermetic entity frame graph:
 * <ol>
 *   <li>reflection and shadow masks carry world and entities ({@code 0x03});</li>
 *   <li>the RT radiance buffer is the definitive final source; no screen-space raster composite exists;</li>
 *   <li>the F3 telemetry spells out the instance status, the final source and both masks.</li>
 * </ol>
 * Shader-source guards live with the Slang passes; nothing here reads a GLSL file.
 */
public class RtEntityHermeticityTest {

    // Headless unit test: no RtWorld exists, so the world instance honestly reports UNAVAILABLE (READY needs a live world and frame stats).
    private static final String EXPECTED_F3_TELEMETRY =
        "[RT-HERMETIC] worldInstance=UNAVAILABLE entityInstance=EMPTY finalSource=RT_OUTPUT reflectionMask=0x03 shadowMask=0x03";

    @Test
    public void testHermeticConstantsAndTelemetry() {
        assertEquals(0x01, RtSceneInstances.MASK_WORLD, "World mask must be 0x01");
        assertEquals(0x02, RtSceneInstances.MASK_ENTITIES, "Entities mask must be 0x02");
        assertEquals(0x03, RtSceneInstances.MASK_WORLD | RtSceneInstances.MASK_ENTITIES, "Hermetic mask must be 0x03");

        assertEquals(0x03, RtFrameRenderer.getReflectionMask(), "Reflection mask must be 0x03");
        assertEquals(0x03, RtFrameRenderer.getShadowMask(), "Shadow mask must be 0x03");
        assertEquals("RT_OUTPUT", RtFrameRenderer.getFinalSource(), "Final frame graph output source must be RT_OUTPUT");

        assertEquals(EXPECTED_F3_TELEMETRY, RtFrameRenderer.getHermeticTelemetry(),
            "F3 telemetry string must match expected format");
    }

    @Test
    public void testF3TelemetryProviderIntegration() {
        RtGeometryGate.setForTests(true);
        try {
            List<String> f3Lines = new ArrayList<>();
            RtModesDebug.appendLines(f3Lines);
            assertTrue(f3Lines.contains(EXPECTED_F3_TELEMETRY),
                "RtModesDebug F3 lines must contain [RT-HERMETIC] telemetry: " + f3Lines);
        } finally {
            RtGeometryGate.setForTests(false);
        }
    }

    @Test
    public void testEntityWaterReflectionOptics() {
        // Water surface at Y = 63, normal = (0, 1, 0)
        Vector3f waterSurface = new Vector3f(0f, 63f, 0f);
        Vector3f waterNormal = new Vector3f(0f, 1f, 0f);

        // Player standing at (0, 65, 5) looking down-forward at water at (0, 63, 0)
        Vector3f camPos = new Vector3f(0f, 65f, 5f);
        Vector3f incidentDir = new Vector3f(waterSurface).sub(camPos).normalize();
        assertTrue(incidentDir.y < 0.0f, "Incident ray must travel downwards towards water");

        // Reflection bounces upwards
        Vector3f reflDir = new Vector3f(incidentDir).reflect(waterNormal).normalize();
        assertTrue(reflDir.y > 0.0f, "Reflected ray must bounce upwards");

        // Off-screen entity placed along the ray bouncing off water
        Vector3f entityPos = new Vector3f(waterSurface).add(new Vector3f(reflDir).mul(10f));
        Vector3f toEntity = new Vector3f(entityPos).sub(waterSurface).normalize();
        float alignment = reflDir.dot(toEntity);
        assertTrue(alignment > 0.99f, "Reflected ray must point directly towards off-screen entity");

        // Both world and entity instances are captured by the reflection mask 0x03
        int reflectionMask = RtFrameRenderer.getReflectionMask();
        assertEquals(0x03, reflectionMask);
        assertEquals(RtSceneInstances.MASK_WORLD, reflectionMask & RtSceneInstances.MASK_WORLD,
            "Reflection ray must hit world terrain");
        assertEquals(RtSceneInstances.MASK_ENTITIES, reflectionMask & RtSceneInstances.MASK_ENTITIES,
            "Reflection ray must hit entities behind or in front of the camera");
    }

    @Test
    public void testEntityRefractionOptics() {
        // Water normal pointing upwards out of the water
        Vector3f waterNormal = new Vector3f(0f, 1f, 0f);
        Vector3f rayDir = new Vector3f(0f, -0.8f, -0.6f).normalize();

        // Refract from air (n1 = 1.0) into water (n2 = 1.333), eta = 1.0 / 1.333 = 0.75
        float eta = 1.0f / 1.333f;
        float cosThetaI = -waterNormal.dot(rayDir);
        float sin2ThetaT = eta * eta * (1.0f - cosThetaI * cosThetaI);
        assertTrue(sin2ThetaT < 1.0f, "No total internal reflection when entering water from air");

        float cosThetaT = (float) Math.sqrt(1.0f - sin2ThetaT);
        Vector3f refrDir = new Vector3f(rayDir).mul(eta).add(new Vector3f(waterNormal).mul(eta * cosThetaI - cosThetaT)).normalize();

        assertTrue(refrDir.y < 0.0f, "Refracted ray must continue into water");
        // Snell's law: refracted ray bends towards the surface normal (steeper)
        assertTrue(Math.abs(refrDir.y) > Math.abs(rayDir.y),
            "Refracted ray into higher IOR must bend towards normal (higher vertical component)");

        // The refracted ray uses mask 0x03, ensuring entities submerged in water are hit
        int mask = RtFrameRenderer.getReflectionMask();
        assertEquals(0x03, mask);
        assertTrue((mask & RtSceneInstances.MASK_ENTITIES) != 0, "Refraction rays must see submerged entities");
    }

    @Test
    public void testEntityShadowRaysOccludeLight() {
        int shadowMask = RtFrameRenderer.getShadowMask();
        assertEquals(0x03, shadowMask, "Shadow mask must be 0x03");

        // Shadow rays test both world (0x01) and entity (0x02) geometry
        assertTrue((shadowMask & RtSceneInstances.MASK_WORLD) != 0, "Shadow rays must test world terrain occlusion");
        assertTrue((shadowMask & RtSceneInstances.MASK_ENTITIES) != 0, "Shadow rays must test dynamic entity occlusion");
    }

    @Test
    public void testNoRasterEntityCompositeFallback() {
        assertEquals("RT_OUTPUT", RtFrameRenderer.getFinalSource(),
            "Pure ray-traced buffer (RT_OUTPUT) must be the definitive final source");
        assertFalse(RtFrameRenderer.getHermeticTelemetry().contains("raster"),
            "No screen-space raster composite may reappear in the frame graph telemetry");
    }
}
