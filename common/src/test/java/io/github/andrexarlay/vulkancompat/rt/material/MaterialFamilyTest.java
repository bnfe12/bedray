package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialFamilyTest {
    private static int argb(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }

    @Test
    void fibresGrainsWoodAndGlazeHaveDifferentSurfaceCharacter() {
        assertTrue(AutoPbr.classify("block/red_wool").roughness() > 0.95f);
        assertTrue(AutoPbr.classify("block/white_concrete_powder").roughness() > 0.95f);
        assertTrue(AutoPbr.classify("block/oak_log").roughness() > AutoPbr.classify("block/oak_planks").roughness());
        assertTrue(AutoPbr.classify("block/stripped_oak_log").roughness() < AutoPbr.classify("block/oak_log").roughness());
        assertTrue(AutoPbr.classify("block/blue_glazed_terracotta").roughness() < AutoPbr.classify("block/blue_terracotta").roughness());
        assertTrue(AutoPbr.classify("block/blue_concrete").roughness() > 0.8f);
        for (String name : new String[] {"red_wool", "oak_log", "birch_planks", "oak_leaves", "blue_concrete", "blue_glazed_terracotta"}) {
            assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("block/" + name).emissionMode(), name);
        }
    }

    @Test
    void copperPatinaIsDielectricWhileExposedCopperTexelsRemainMetallic() {
        for (String name : new String[] {"exposed_copper", "weathered_cut_copper", "oxidized_chiseled_copper"}) {
            int[] pixels = {argb(174, 105, 72), argb(59, 143, 122)};
            int[] result = new int[2];
            MaterialRegion.fill(result, new int[2], 2, 0, 0, 2, 1, AutoPbr.classify("block/" + name),
                new MaterialRegion.Image(2, 1, pixels), null, null, "block/" + name);
            assertTrue(LabPbr.isMetal(result[0] >>> 8 & 255), name + " bare copper");
            assertFalse(LabPbr.isMetal(result[1] >>> 8 & 255), name + " patina");
            assertEquals(0, result[1] >>> 16 & 255, "green corrosion is not a glow mask");
        }
        assertTrue(AutoPbr.classify("block/copper_bars").metal());
        assertTrue(AutoPbr.classify("block/copper_chain").metal());
    }

    @Test
    void crystalsAndSculkSelectBrightDetailsInsteadOfTheirHousing() {
        for (String name : new String[] {"amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud"}) {
            var material = AutoPbr.classify("block/" + name);
            assertTrue(AutoPbr.pixelEmission(material, argb(229, 192, 255)) > 0f, name);
            assertEquals(0f, AutoPbr.pixelEmission(material, argb(91, 53, 143)));
        }
        var sculk = AutoPbr.classify("block/sculk_catalyst_side_bloom");
        assertTrue(AutoPbr.pixelEmission(sculk, argb(12, 170, 175)) > 0f);
        assertEquals(0f, AutoPbr.pixelEmission(sculk, argb(190, 195, 150)), "bone is not bioluminescent");
        assertEquals(0f, AutoPbr.pixelEmission(sculk, argb(5, 20, 25)), "dark tissue stays dark");
    }

    @Test
    void activeMagicCoresEmitWhileInactiveVariantsDoNot() {
        for (String name : new String[] {"trial_spawner_side_active", "trial_spawner_top_active_ominous",
                "vault_side_on", "vault_front_ejecting_ominous", "creaking_heart_awake"}) {
            var material = AutoPbr.classify("block/" + name);
            assertTrue(AutoPbr.pixelEmission(material, argb(255, 150, 30)) > 0f, name + " warm core");
            assertTrue(AutoPbr.pixelEmission(material, argb(20, 215, 210)) > 0f, name + " soul core");
            assertEquals(0f, AutoPbr.pixelEmission(material, argb(80, 110, 130)), name + " cage");
        }
        for (String name : new String[] {"trial_spawner_side_inactive", "trial_spawner_top_inactive_ominous",
                "vault_side_off", "vault_front_off_ominous", "creaking_heart_dormant", "creaking_heart"}) {
            assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("block/" + name).emissionMode(), name);
        }
    }
}
