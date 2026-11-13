package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** L2 material behaviour using representative unlit texels from the vanilla Nether/End palettes. */
class DimensionMaterialTest {
    private static int rgba(int r, int g, int b) {
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    private static float emission(String name, int pixel) {
        return AutoPbr.pixelEmission(AutoPbr.classify("block/" + name), pixel);
    }

    @Test
    void stemsEmitFromVeinsWhileBarkAndCutWoodStayDark() {
        for (int bark : new int[] {rgba(82, 24, 16), rgba(68, 33, 49), rgba(75, 39, 55), rgba(86, 44, 62)}) {
            assertEquals(0f, emission("crimson_stem", bark));
            assertEquals(0f, emission("warped_stem", bark));
        }
        assertTrue(emission("crimson_stem", rgba(123, 0, 0)) > 0f);
        assertTrue(emission("warped_stem", rgba(22, 97, 91)) > 0f);
        assertEquals(0f, emission("crimson_stem_top", rgba(134, 62, 90)), "magenta end grain is wood");
        assertEquals(0f, emission("warped_stem_top", rgba(58, 142, 140)), "cyan end grain is wood");
        assertTrue(emission("warped_stem_top", rgba(22, 126, 134)) > 0f);
        for (String name : new String[] {"stripped_crimson_stem", "stripped_warped_stem", "crimson_planks",
                "warped_planks", "crimson_door_top", "warped_trapdoor", "crimson_sign", "warped_shelf"}) {
            assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("block/" + name).emissionMode(), name);
        }
    }

    @Test
    void growthMasksKeepDarkFleshSeparateFromSpores() {
        assertTrue(emission("weeping_vines", rgba(123, 0, 0)) > 0f);
        assertTrue(emission("twisting_vines", rgba(20, 180, 133)) > 0f);
        assertEquals(0f, emission("nether_wart_block", rgba(123, 0, 0)));
        assertTrue(emission("nether_wart_block", rgba(172, 32, 32)) > 0f);
        assertEquals(0f, emission("warped_wart_block", rgba(22, 126, 134)));
        assertTrue(emission("warped_wart_block", rgba(20, 180, 133)) > 0f);
        for (String fungus : new String[] {"crimson_fungus", "warped_fungus"}) {
            assertTrue(emission(fungus, rgba(255, 101, 0)) > 0f);
            assertEquals(0f, emission(fungus, rgba(82, 24, 16)));
            assertEquals(0f, emission(fungus, rgba(20, 180, 133)), "cyan cap is not an orange spore");
        }
    }

    @Test
    void endAndObsidianKeepTheirNonLuminousParts() {
        assertTrue(emission("chorus_flower", rgba(251, 243, 254)) > 0f);
        assertEquals(0f, emission("chorus_flower", rgba(91, 52, 91)));
        assertTrue(emission("crying_obsidian", rgba(96, 6, 171)) > 0f);
        assertEquals(0f, emission("crying_obsidian", rgba(59, 39, 84)));
        for (String name : new String[] {"chorus_flower_dead", "chorus_plant", "end_stone", "end_stone_bricks",
                "purpur_block", "obsidian", "soul_sand", "nether_quartz_ore", "ancient_debris_side", "respawn_anchor_top_off"}) {
            assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("block/" + name).emissionMode(), name);
        }
        assertTrue(emission("respawn_anchor_side1", rgba(251, 218, 116)) > 0f, "charge lamp");
        assertTrue(emission("end_portal_frame_eye", rgba(180, 210, 180)) > 0f, "eye iris");
        assertEquals(0f, emission("end_portal_frame_eye", rgba(24, 52, 57)), "eye housing");
    }

    @Test
    void blockAndItemMasksAgreeAndTransparentTexelsNeverEmit() {
        for (String name : new String[] {"crimson_stem", "warped_stem", "warped_wart_block", "chorus_flower",
                "crying_obsidian", "crimson_fungus", "torch", "end_rod", "magma"}) {
            int[] albedo = {rgba(123, 0, 0), rgba(20, 180, 133), rgba(251, 243, 254), rgba(255, 101, 0), rgba(96, 6, 171), 0x00ffffff};
            AutoPbr.Material auto = AutoPbr.classify("block/" + name);
            float[] mask = MaterialRegion.emissionMask(auto, AutoPbr.MixedKind.NONE, albedo, albedo.length, 1);
            assertNotNull(mask, name);
            int[] packed = new int[albedo.length];
            MaterialRegion.fill(packed, new int[albedo.length], albedo.length, 0, 0, albedo.length, 1,
                auto, new MaterialRegion.Image(albedo.length, 1, albedo), null, null, "block/" + name);
            for (int i = 0; i < albedo.length; i++) {
                assertEquals(Math.round(mask[i] * 255f), packed[i] >>> 16 & 255, name + " texel " + i);
            }
            assertEquals(0f, mask[albedo.length - 1]);
        }
    }

    @Test
    void authoredSpecularMapOverridesGeneratedEmissionAndEmissionToggleIsRespected() {
        int[] pixel = {rgba(123, 0, 0)};
        MaterialRegion.Image albedo = new MaterialRegion.Image(1, 1, pixel);
        int[] result = new int[1];
        AutoPbr.Material auto = AutoPbr.classify("block/crimson_stem");
        MaterialRegion.fill(result, new int[1], 1, 0, 0, 1, 1, auto, albedo,
            new MaterialRegion.Image(1, 1, new int[] {0xff000000}), null, "block/crimson_stem");
        assertEquals(0, result[0] >>> 16 & 255, "255 means no emission in authored LabPBR");
        MaterialRegion.fill(result, new int[1], 1, 0, 0, 1, 1, auto, albedo, null, null,
            new MaterialRegion.AutoOptions(true, true, true, false, 1f), "block/crimson_stem");
        assertEquals(0, result[0] >>> 16 & 255);
    }

    @Test
    void netherGoldDoesNotTurnSaturatedHostRockIntoMetal() {
        for (String name : new String[] {"nether_gold_ore", "gilded_blackstone"}) {
            int[] pixels = {rgba(100, 40, 40), rgba(39, 30, 61), rgba(251, 218, 116)};
            int[] result = new int[pixels.length];
            MaterialRegion.fill(result, new int[pixels.length], pixels.length, 0, 0, pixels.length, 1,
                AutoPbr.classify("block/" + name), new MaterialRegion.Image(pixels.length, 1, pixels), null, null, "block/" + name);
            assertFalse(LabPbr.isMetal(result[0] >>> 8 & 255), "netherrack");
            assertFalse(LabPbr.isMetal(result[1] >>> 8 & 255), "blackstone");
            assertTrue(LabPbr.isMetal(result[2] >>> 8 & 255), "gold inclusion");
        }
    }
}
