package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-policy's recommendation is staged into the pending settings model as ordinary option values. The mapping is a
 * pure static function so it is checked here without a running game; the screen only iterates it and calls Apply. Every
 * staged row must exist in the catalog (otherwise the value would be written to an option the player never sees).
 */
class GiPlanSettingsTest {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final int NV_SER = BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE | BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV;
    private static final int RT_BASE = BedrayCoreLayouts.CAP_RAY_QUERY | BedrayCoreLayouts.CAP_ACCELERATION_STRUCTURE;

    @Test
    void aMaxPlanStagesTheFullGiStack() {
        GiStackPolicy.Plan plan = GiStackPolicy.recommend(new GiStackPolicy.Device(8L * GIB, RT_BASE | NV_SER), 1920, 1080);
        Map<String, Object> values = SettingsDraft.giPlanValues(plan);
        assertEquals(VulkanSodiumConfig.QualityProfile.CUSTOM, values.get("path_quality_profile"));
        assertEquals(1, values.get("path_spp"));
        assertEquals(3, values.get("path_bounces"));
        assertEquals(512, values.get("path_distance"));
        assertEquals(true, values.get("path_restir_di"));
        assertEquals(true, values.get("path_restir_pt"));
        assertEquals(true, values.get("path_restir_pt_spatial"));
    }

    @Test
    void aBaselinePlanStagesTheCheapestStack() {
        GiStackPolicy.Plan plan = GiStackPolicy.forTier(GiStackPolicy.Tier.BASELINE,
            new GiStackPolicy.Device(8L * GIB, RT_BASE | NV_SER), 1920, 1080);
        Map<String, Object> values = SettingsDraft.giPlanValues(plan);
        assertEquals(2, values.get("path_bounces"));
        assertEquals(256, values.get("path_distance"));
        assertEquals(true, values.get("path_restir_di"));
        assertEquals(false, values.get("path_restir_pt"));
        assertEquals(false, values.get("path_restir_pt_spatial"));
    }

    @Test
    void everyStagedRowExistsInTheCatalog() {
        GiStackPolicy.Plan plan = GiStackPolicy.recommend(new GiStackPolicy.Device(8L * GIB, RT_BASE | NV_SER), 1920, 1080);
        for (String path : SettingsDraft.giPlanValues(plan).keySet()) {
            assertNotNull(SettingsCatalog.get(path), path + " is staged by the auto-policy but missing from the catalog");
        }
    }

    @Test
    void theMappingAlwaysUnlocksTheProfileForFurtherTuning() {
        for (GiStackPolicy.Tier tier : GiStackPolicy.Tier.values()) {
            GiStackPolicy.Plan plan = GiStackPolicy.forTier(tier, new GiStackPolicy.Device(8L * GIB, RT_BASE), 1920, 1080);
            assertEquals(VulkanSodiumConfig.QualityProfile.CUSTOM, SettingsDraft.giPlanValues(plan).get("path_quality_profile"),
                tier + " must leave the profile Custom so the player can tune the staged values");
        }
    }

    @Test
    void spatialAlwaysRidesOnPt() {
        for (long mem : new long[] {1L * GIB, 2L * GIB, 8L * GIB, 16L * GIB}) {
            GiStackPolicy.Plan plan = GiStackPolicy.recommend(new GiStackPolicy.Device(mem, RT_BASE | NV_SER), 1920, 1080);
            Map<String, Object> values = SettingsDraft.giPlanValues(plan);
            assertEquals(values.get("path_restir_pt"), values.get("path_restir_pt_spatial"),
                "PT spatial must equal PT at " + mem + " bytes");
        }
    }

    @Test
    void theStagedValuesAreOptionCompatibleTypes() {
        GiStackPolicy.Plan plan = GiStackPolicy.recommend(new GiStackPolicy.Device(8L * GIB, RT_BASE | NV_SER), 1920, 1080);
        Map<String, Object> values = SettingsDraft.giPlanValues(plan);
        assertTrue(values.get("path_spp") instanceof Integer);
        assertTrue(values.get("path_bounces") instanceof Integer);
        assertTrue(values.get("path_distance") instanceof Integer);
        assertTrue(values.get("path_restir_di") instanceof Boolean);
        assertTrue(values.get("path_restir_pt") instanceof Boolean);
        assertTrue(values.get("path_quality_profile") instanceof VulkanSodiumConfig.QualityProfile);
    }
}