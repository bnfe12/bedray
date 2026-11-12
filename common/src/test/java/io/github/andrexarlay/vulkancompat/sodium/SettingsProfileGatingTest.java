package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Fast/Beautiful profile only locks the rows it sets. It used to lock the whole page, so picking a profile also froze the
 * upscaler, frame generation and lighting: the player could not configure how the frame is upscaled at all.
 */
class SettingsProfileGatingTest {

    private static final VulkanSodiumConfig.QualityProfile FAST = VulkanSodiumConfig.QualityProfile.FAST;
    private static final VulkanSodiumConfig.QualityProfile BEAUTIFUL = VulkanSodiumConfig.QualityProfile.BEAUTIFUL;
    private static final VulkanSodiumConfig.QualityProfile CUSTOM = VulkanSodiumConfig.QualityProfile.CUSTOM;

    @Test
    void aProfileLeavesTheUpscalerEditable() {
        // The reported bug: Fast/Beautiful froze these rows, so the upscaler could not be configured at all.
        for (String path : List.of("upscaler_backend", "quality_preset", "custom_scale", "dlss_mode", "xess_mode",
            "fsr_mode", "frame_generation_mode", "frame_generation_provider", "frame_generation_reflex",
            "path_denoiser", "rays_pixel_shadows", "atm_godrays", "water_style", "renderer", "rt_mode")) {
            assertTrue(SettingsDraft.editable(false, FAST, path), "path profile locks " + path);
            assertTrue(SettingsDraft.editable(true, BEAUTIFUL, path), "voxel profile locks " + path);
        }
    }

    @Test
    void aProfileLocksOnlyItsOwnTracingKnobs() {
        for (String path : List.of("path_spp", "path_bounces", "path_distance")) {
            assertFalse(SettingsDraft.editable(false, FAST, path), "path profile must lock " + path);
            assertTrue(SettingsDraft.editable(false, CUSTOM, path), "Custom must unlock " + path);
        }
        for (String path : List.of("voxel_distance_auto", "voxel_memory_budget_mib", "voxel_diffuse_bounce", "voxel_translucent_optics")) {
            assertFalse(SettingsDraft.editable(true, BEAUTIFUL, path), "voxel profile must lock " + path);
            assertTrue(SettingsDraft.editable(true, CUSTOM, path), "Custom must unlock " + path);
        }
    }

    @Test
    void customLocksNothingAndTheProfilesDoNotCross() {
        List<String> lockedByPath = new ArrayList<>();
        for (String path : SettingsCatalog.all().keySet()) {
            assertTrue(SettingsDraft.editable(false, CUSTOM, path), "Custom locks " + path);
            assertTrue(SettingsDraft.editable(true, CUSTOM, path), "Custom locks " + path);
            if (!SettingsDraft.editable(false, FAST, path)) lockedByPath.add(path);
        }
        assertEquals(SettingsDraft.ownedPaths(false).stream().sorted().toList(),
            lockedByPath.stream().sorted().toList(), "Fast locks rows other than the path tracing knobs");
        for (String path : SettingsDraft.ownedPaths(true)) {
            assertTrue(SettingsDraft.editable(false, FAST, path), "the path profile locks the voxel knob " + path);
        }
    }

    @Test
    void everyOwnedRowExistsInTheCatalogAndIsStagedByTheProfile() throws Exception {
        // A typo in an owned set would silently lock nothing, and a row named here but never staged would be locked for
        // no reason: both have to match the values SettingsDraft.choose() writes. The staged rows are read out of the
        // source of choose(), so a row added to a profile fails here until it is added to the owned set as well.
        List<String> expected = List.of("voxel_distance_auto", "voxel_memory_budget_mib", "voxel_diffuse_bounce",
            "voxel_translucent_optics", "path_spp", "path_bounces", "path_distance");
        assertEquals(expected.stream().sorted().toList(),
            java.util.stream.Stream.concat(SettingsDraft.ownedPaths(false).stream(), SettingsDraft.ownedPaths(true).stream())
                .sorted().toList(),
            "the owned sets and the rows the profiles write have to be the same list");
        for (boolean voxel : new boolean[] {false, true}) {
            for (String path : SettingsDraft.ownedPaths(voxel)) {
                assertNotNull(SettingsCatalog.get(path), path + " is owned by a profile but missing from the catalog");
                assertTrue(expected.contains(path), path + " is locked by a profile but no profile sets it");
            }
        }
        String draft = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/sodium/SettingsDraft.java"));
        int choose = draft.indexOf("void choose(Entry entry, Enum<?> value) {");
        assertTrue(choose > 0, "choose() moved");
        String body = draft.substring(choose, draft.indexOf("private void stage(", choose));
        for (String path : expected) {
            assertTrue(body.contains("stage(\"" + path + "\""), path + " is owned by a profile but choose() never stages it");
        }
    }

    @Test
    void theScreenAsksTheSameRuleForEveryRow() throws Exception {
        // The static rule above is only worth anything if the screen's per-row decision actually calls it. The old blanket
        // lock lived in editable(Entry) itself, so a revert there would leave every test above green.
        String draft = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/sodium/SettingsDraft.java"));
        assertTrue(draft.contains("return editable(voxel, active, e.path());"),
            "editable(Entry) has to delegate to the tested rule instead of re-deciding");
        assertTrue(draft.contains("return !profileOwned(voxel, path) || active == VulkanSodiumConfig.QualityProfile.CUSTOM;"),
            "the rule is: only the rows a non-Custom profile sets are locked");
        String screen = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/sodium/BedrayConfigScreen.java"));
        assertTrue(screen.contains("draft.hasQualityProfile() && draft.profileOwned(entry.path())"),
            "the locked tooltip must name the profile only for a row the profile owns");
    }
}
