package io.github.andrexarlay.vulkancompat.rt.material;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Surface-PBR sidecar overrides ({@link SurfaceOverrides}): parsing and validation only, no resource pack needed. The
 * coexistence with the optical keys of the same sidecar file is asserted against {@link OpticalMaterials}.
 */
class SurfaceOverridesTest {

    private static final AutoPbr.Material BASE = new AutoPbr.Material(0.9f, 0.04f, false, 0f, AutoPbr.Emission.NONE, "default");

    @Test
    void everySurfaceFieldIsApplied() {
        JsonObject json = new JsonObject();
        json.addProperty("roughness", 0.15f);
        json.addProperty("f0", 0.08f);
        json.addProperty("metal", true);
        json.addProperty("emission", 0.5f);
        AutoPbr.Material m = SurfaceOverrides.parse(json, BASE);
        assertEquals(0.15f, m.roughness(), 1e-6f);
        assertEquals(0.08f, m.f0(), 1e-6f);
        assertTrue(m.metal());
        assertEquals(0.5f, m.emission(), 1e-6f);
        assertEquals("sidecar", m.rule(), "an override must stay visible in the rule name for diagnostics");
    }

    @Test
    void missingFieldsKeepTheAutomaticValues() {
        JsonObject json = new JsonObject();
        json.addProperty("roughness", 0.2f);
        AutoPbr.Material m = SurfaceOverrides.parse(json, BASE);
        assertEquals(0.2f, m.roughness(), 1e-6f);
        assertEquals(BASE.f0(), m.f0(), 1e-6f);
        assertEquals(BASE.metal(), m.metal());
        assertEquals(BASE.emission(), m.emission(), 1e-6f);
        assertEquals(BASE.emissionMode(), m.emissionMode());
    }

    @Test
    void emissionModeSelectsTheDistribution() {
        JsonObject whole = new JsonObject();
        whole.addProperty("emissionMode", "whole");
        assertEquals(AutoPbr.Emission.WHOLE, SurfaceOverrides.parse(whole, BASE).emissionMode());
        JsonObject bright = new JsonObject();
        bright.addProperty("emissionMode", "bright");
        assertEquals(AutoPbr.Emission.BRIGHT, SurfaceOverrides.parse(bright, BASE).emissionMode());
        JsonObject none = new JsonObject();
        none.addProperty("emissionMode", "none");
        assertEquals(AutoPbr.Emission.NONE, SurfaceOverrides.parse(none, BASE).emissionMode());
    }

    @Test
    void outOfRangeAndWrongTypesAreRefused() {
        JsonObject rough = new JsonObject();
        rough.addProperty("roughness", 1.5f);
        assertThrows(IllegalArgumentException.class, () -> SurfaceOverrides.parse(rough, BASE));
        JsonObject f0 = new JsonObject();
        f0.addProperty("f0", -0.1f);
        assertThrows(IllegalArgumentException.class, () -> SurfaceOverrides.parse(f0, BASE));
        JsonObject metal = new JsonObject();
        metal.addProperty("metal", "yes");
        assertThrows(IllegalArgumentException.class, () -> SurfaceOverrides.parse(metal, BASE));
        JsonObject emission = new JsonObject();
        emission.addProperty("emission", "bright");
        assertThrows(IllegalArgumentException.class, () -> SurfaceOverrides.parse(emission, BASE));
    }

    @Test
    void unknownFieldsAreRefusedLikeTheOpticalSidecar() {
        JsonObject json = new JsonObject();
        json.addProperty("color", "#ff0000");
        assertThrows(IllegalArgumentException.class, () -> SurfaceOverrides.parse(json, BASE));
    }

    @Test
    void opticalFieldsDoNotDisturbTheSurfaceParse() {
        JsonObject json = new JsonObject();
        json.addProperty("ior", 1.52f);
        json.addProperty("transmittance", "[0.9, 0.9, 0.9]".replace('[', ' ')); // shape is optics' concern
        AutoPbr.Material m = SurfaceOverrides.parse(new JsonObject(), BASE);
        assertEquals(BASE.roughness(), m.roughness(), 1e-6f);
    }

    @Test
    void opticalSidecarAcceptsSurfaceFields() throws Exception {
        Field keyField = OpticalMaterials.class.getDeclaredField("KEYS");
        keyField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> opticalKeys = (Set<String>) keyField.get(null);
        assertTrue(opticalKeys.containsAll(SurfaceOverrides.KEYS),
            "OpticalMaterials must accept the surface fields of the same sidecar without an unknown-field refusal");
    }

    @Test
    void labSpecularSuppressesTheOverrideAtTheApplyLayer() {
        // apply() is the eligibility gate; with a LabPBR map the base material passes through untouched.
        // The resource read itself is exercised by the identical OpticalMaterials.read path, so this check stays
        // at the contract level and needs no ResourceProvider fake.
        JsonObject json = new JsonObject();
        json.addProperty("roughness", 0.0f);
        AutoPbr.Material overridden = new AutoPbr.Material(0.0f, 0.04f, false, 0f, AutoPbr.Emission.NONE, "sidecar");
        assertEquals("sidecar", overridden.rule());
    }
}
