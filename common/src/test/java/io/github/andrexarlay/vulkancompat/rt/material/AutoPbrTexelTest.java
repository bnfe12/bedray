package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 JUnit tests for texel-level PBR classification:
 * - Mixed blocks (lantern, soul lantern, copper bulb, redstone lamp): metallic frame/casing + emissive fire/lamp texels.
 * - Ores: metallic mineral veins for iron/gold/copper, smooth dielectrics for gems (diamond, emerald).
 * - Per-pixel roughness: cracks/seams rougher, bright smooth highlights smoother.
 */
class AutoPbrTexelTest {

    @Test
    void lanternSeparatesIntoMetalFrameAndEmissiveFlame() {
        // 2x2 sprite: (0,0) dark iron frame, (1,0) bright flame, (0,1) wood handle, (1,1) dark iron frame
        int[] argb = {
            0xFF3A3A3A, 0xFFFFD050,
            0xFF5A4628, 0xFF3A3A3A
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 2, argb);
        int[] mat = new int[4];
        int[] nrm = new int[4];

        AutoPbr.Material auto = AutoPbr.classify("block/lantern");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 2, auto, albedo, null, null, "block/lantern");

        assertEquals(1, emissive, "exactly 1 emissive texel (flame)");

        // Texel (0,0): metallic frame
        int frameG = (mat[0] >>> 8) & 255;
        int frameE = (mat[0] >>> 16) & 255;
        float frameRough = (mat[0] & 255) / 255f;
        assertTrue(LabPbr.isMetal(frameG), "lantern frame must be metal: g=" + frameG);
        assertEquals(0, frameE, "lantern frame must not emit");
        assertTrue(frameRough >= 0.25f && frameRough <= 0.45f, "lantern frame roughness near 0.35: " + frameRough);

        // Texel (1,0): emissive flame
        int flameG = (mat[1] >>> 8) & 255;
        int flameE = (mat[1] >>> 16) & 255;
        assertFalse(LabPbr.isMetal(flameG), "flame must be dielectric (not metal): g=" + flameG);
        assertEquals(255, flameE, "flame must have full emission (1.0)");

        // Texel (0,1): wood handle (dielectric, non-emissive)
        int woodG = (mat[2] >>> 8) & 255;
        int woodE = (mat[2] >>> 16) & 255;
        assertFalse(LabPbr.isMetal(woodG), "wood handle must not be metal: g=" + woodG);
        assertEquals(0, woodE, "wood handle must not emit");

        // Texel (1,1): second frame pixel
        assertTrue(LabPbr.isMetal((mat[3] >>> 8) & 255), "bottom frame must be metal");
        assertEquals(0, (mat[3] >>> 16) & 255, "bottom frame must not emit");
    }

    @Test
    void soulLanternSeparatesIntoMetalFrameAndSoulFlame() {
        int[] argb = {
            0xFF363636, 0xFF3CC8F0
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/soul_lantern");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/soul_lantern");

        assertEquals(1, emissive, "1 soul flame emissive texel");

        // Frame
        assertTrue(LabPbr.isMetal((mat[0] >>> 8) & 255), "soul lantern frame is metal");
        assertEquals(0, (mat[0] >>> 16) & 255, "soul lantern frame does not emit");

        // Soul flame
        assertFalse(LabPbr.isMetal((mat[1] >>> 8) & 255), "soul flame is not metal");
        assertEquals(255, (mat[1] >>> 16) & 255, "soul flame emits 1.0");
    }

    @Test
    void ironOreMetallizesMineralVeins() {
        // 2x1 sprite: (0,0) neutral grey stone host rock, (1,0) beige/tan iron ore vein
        int[] argb = {
            0xFF737373, 0xFFD8AF93
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/iron_ore");
        MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/iron_ore");

        // Stone pixel: not metal, rough
        assertFalse(LabPbr.isMetal((mat[0] >>> 8) & 255), "stone background is not metal");
        float stoneRough = (mat[0] & 255) / 255f;
        assertTrue(stoneRough >= 0.70f, "stone background has high roughness: " + stoneRough);

        // Iron vein: metal, roughness near 0.35
        assertTrue(LabPbr.isMetal((mat[1] >>> 8) & 255), "iron vein pixel must be metal");
        float ironRough = (mat[1] & 255) / 255f;
        assertTrue(ironRough >= 0.25f && ironRough <= 0.45f, "iron vein roughness near 0.35: " + ironRough);
    }

    @Test
    void goldOreMetallizesMineralVeins() {
        // 2x1 sprite: (0,0) stone host rock, (1,0) yellow gold ore vein
        int[] argb = {
            0xFF737373, 0xFFFFE650
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/gold_ore");
        MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/gold_ore");

        // Stone pixel
        assertFalse(LabPbr.isMetal((mat[0] >>> 8) & 255), "stone background is not metal");

        // Gold vein
        assertTrue(LabPbr.isMetal((mat[1] >>> 8) & 255), "gold vein pixel must be metal");
        float goldRough = (mat[1] & 255) / 255f;
        assertTrue(goldRough >= 0.25f && goldRough <= 0.45f, "gold vein roughness near 0.35: " + goldRough);
    }

    @Test
    void gemOreProducesSmoothDielectricVein() {
        // 2x1 sprite: (0,0) stone host rock, (1,0) cyan diamond vein
        int[] argb = {
            0xFF737373, 0xFF4AEDD9
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/diamond_ore");
        MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/diamond_ore");

        // Stone pixel
        assertFalse(LabPbr.isMetal((mat[0] >>> 8) & 255), "stone background is not metal");

        // Diamond vein: smooth dielectric (not metal, low roughness)
        assertFalse(LabPbr.isMetal((mat[1] >>> 8) & 255), "diamond vein is dielectric (not metal)");
        float diamondRough = (mat[1] & 255) / 255f;
        assertTrue(diamondRough < 0.20f, "diamond vein is smooth dielectric: " + diamondRough);
    }

    @Test
    void copperBulbLitSeparatesCasingAndBulb() {
        // 2x1 sprite: (0,0) copper casing, (1,0) glowing bulb
        int[] argb = {
            0xFFC86E46, 0xFFFFDC64
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/copper_bulb_lit");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/copper_bulb_lit");

        assertEquals(1, emissive, "1 bulb emissive texel");

        // Copper casing: metal, no emission
        assertTrue(LabPbr.isMetal((mat[0] >>> 8) & 255), "copper bulb casing must be metal");
        assertEquals(0, (mat[0] >>> 16) & 255, "copper bulb casing must not emit");

        // Lit bulb: not metal, emits
        assertFalse(LabPbr.isMetal((mat[1] >>> 8) & 255), "lit bulb must be dielectric");
        assertEquals(255, (mat[1] >>> 16) & 255, "lit bulb must have full emission");
    }

    @Test
    void redstoneLampOnSeparatesFrameAndLitGlass() {
        // 2x1 sprite: (0,0) dark border frame, (1,0) glowing lamp glass
        int[] argb = {
            0xFF463C32, 0xFFFFB43C
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/redstone_lamp_on");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/redstone_lamp_on");

        assertEquals(1, emissive, "1 glowing lamp glass texel");

        // Border frame
        assertTrue(LabPbr.isMetal((mat[0] >>> 8) & 255), "lamp frame is metal");
        assertEquals(0, (mat[0] >>> 16) & 255, "lamp frame does not emit");

        // Lit glass
        assertFalse(LabPbr.isMetal((mat[1] >>> 8) & 255), "lit glass is dielectric");
        assertEquals(255, (mat[1] >>> 16) & 255, "lit glass emits");
    }

    @Test
    void pixelRoughnessModulatesWithLuminanceAndContrast() {
        float base = 0.5f;
        float meanLum = 0.5f;

        // Dark crack: lum < meanLum -> rougher
        float roughCrack = AutoPbr.pixelRoughness(base, 0.2f, meanLum, 0.1f, 1.0f);
        assertTrue(roughCrack > base, "crack must be rougher than base: " + roughCrack);

        // Bright highlight: lum > meanLum -> smoother
        float roughHighlight = AutoPbr.pixelRoughness(base, 0.8f, meanLum, 0.0f, 1.0f);
        assertTrue(roughHighlight < base, "highlight must be smoother than base: " + roughHighlight);
    }

    @Test
    void redstoneWirePowerAndCutoutTransparentSafety() {
        // Redstone power formula: power 0 -> strictly 0 emission, power 15 -> strictly 1.0
        assertEquals(0.0f, AutoPbr.redstoneEmission(0), 1e-6f, "power 0 must have zero emission");
        assertEquals(1.0f, AutoPbr.redstoneEmission(15), 1e-6f, "power 15 must have 1.0 emission");
        float prev = -1.0f;
        for (int p = 0; p <= 15; p++) {
            float e = AutoPbr.redstoneEmission(p);
            assertTrue(e > prev, "redstone emission must be strictly monotonic: power " + p + "=" + e);
            prev = e;
        }

        // 2x1 sprite: (0,0) transparent cutout pixel (alpha=0), (1,0) redstone wire pixel (opaque red, alpha=255)
        int[] argb = {
            0x00000000, 0xFFFF0A00
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/redstone_dust_line0");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/redstone_dust_line0");
        assertEquals(1, emissive, "only opaque wire pixel is emissive, transparent cutout pixel is not");

        // Transparent cutout texel: must NOT have specular reflection (roughness 1.0, metal 0, f0 0, emission 0)
        int transRough = mat[0] & 255;
        int transMetal = (mat[0] >>> 8) & 255;
        int transEmission = (mat[0] >>> 16) & 255;
        assertEquals(255, transRough, "transparent texel roughness must be packed as 255 (1.0f)");
        assertEquals(0, transMetal, "transparent texel must not be metal");
        assertEquals(0, transEmission, "transparent texel must not emit");

        // Transparent cutout normal: must be FLAT_NORMAL
        AutoNormals.fill(nrm, 2, 0, 0, 2, 1, albedo);
        assertEquals(LabPbr.FLAT_NORMAL, nrm[0], "transparent texel normal must be flat to prevent spurious normal gradients");
    }

    @Test
    void cutoutHolesStayNonReflectiveWithLabPbrSpecularMap() {
        int[] albedo = {0x00000000, 0xFFFFFFFF};
        int[] specular = {0xFE00FFFF, 0xFE00FFFF}; // maximum LabPBR emission on both pixels (alpha 254)
        int[] material = new int[2];

        MaterialRegion.fill(material, new int[2], 2, 0, 0, 2, 1, AutoPbr.DEFAULT,
            new MaterialRegion.Image(2, 1, albedo), new MaterialRegion.Image(2, 1, specular), null,
            "block/test_cutout");

        assertEquals(0, (material[0] >>> 16) & 255, "transparent albedo hole must not retain _s emission");
        assertEquals(255, material[0] & 255, "transparent albedo hole must remain fully rough");
        assertEquals(255, (material[1] >>> 16) & 255, "opaque texel must keep _s emission");
    }

    @Test
    void redstoneMarkerPreservesLabPbrFlags() {
        int tagged = LabPbr.withSourceFlag(LabPbr.materialFromSpecular(0, 0, 0, 255), LabPbr.SOURCE_REDSTONE);
        assertTrue(LabPbr.hasSourceFlag(tagged, LabPbr.SOURCE_REDSTONE));
        assertTrue(((255 - (tagged >>> 24)) & 1) != 0, "specular source bit must be preserved");
        assertFalse(((255 - (tagged >>> 24)) & 2) != 0, "normal-map bit must stay clear until a normal map is present");
    }

    @Test
    void endRodSeparatesBulbAndDarkBase() {
        // 2x1 sprite: (0,0) dark base, (1,0) bright bulb
        int[] argb = {
            0xFF404040, 0xFFF0F0FF
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/end_rod");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/end_rod");
        assertEquals(1, emissive, "only bulb is emissive");

        // Dark base
        int baseRough = mat[0] & 255;
        int baseEmission = (mat[0] >>> 16) & 255;
        assertEquals(0, baseEmission, "end rod base does not emit");
        assertTrue(baseRough > 200, "end rod base is rough: " + baseRough);

        // Bulb
        int bulbRough = mat[1] & 255;
        int bulbEmission = (mat[1] >>> 16) & 255;
        assertEquals(255, bulbEmission, "end rod bulb emits full (1.0)");
        assertTrue(bulbRough < 40, "end rod bulb is smooth: " + bulbRough);
    }

    @Test
    void magmaBlockSeparatesCracksAndCrust() {
        // 2x1 sprite: (0,0) dark basalt crust, (1,0) bright lava crack
        int[] argb = {
            0xFF2C1608, 0xFFFF7800
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/magma");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/magma");
        assertEquals(1, emissive, "only lava crack emits");

        // Crust: rough stone, no emission
        int crustRough = mat[0] & 255;
        int crustEmission = (mat[0] >>> 16) & 255;
        assertEquals(0, crustEmission, "magma crust does not emit");
        assertTrue(crustRough > 200, "magma crust is rough: " + crustRough);

        // Lava crack: smooth, full emission
        int crackRough = mat[1] & 255;
        int crackEmission = (mat[1] >>> 16) & 255;
        assertEquals(255, crackEmission, "magma crack emits full (1.0)");
        assertTrue(crackRough < 50, "magma crack is smooth (liquid): " + crackRough);
    }

    @Test
    void enchantingTableCyanDiamondCornersGlowAndReflect() {
        // 2x1 sprite: (0,0) dark obsidian body, (1,0) cyan diamond corner
        int[] argb = {
            0xFF151020, 0xFF33DDAA
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/enchanting_table_top");
        int emissive = MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/enchanting_table_top");
        assertEquals(1, emissive, "cyan diamond corner emits");

        // Obsidian: smooth dark dielectric, no emission
        int obsRough = mat[0] & 255;
        int obsEmission = (mat[0] >>> 16) & 255;
        assertEquals(0, obsEmission, "obsidian does not emit");
        assertTrue(obsRough < 50, "obsidian is glassy smooth: " + obsRough);

        // Diamond corner: smooth, f0=0.17 (diamond IOR 2.42), glows ~0.25 flux
        int diaRough = mat[1] & 255;
        int diaEmission = (mat[1] >>> 16) & 255;
        int diaG = (mat[1] >>> 8) & 255;
        assertTrue(diaEmission > 50 && diaEmission < 80, "diamond corner glows ~0.25: " + diaEmission);
        assertTrue(diaRough < 30, "diamond corner is very smooth: " + diaRough);
        assertFalse(LabPbr.isMetal(diaG), "diamond corner is dielectric, not metal");
        assertTrue(diaG > 35 && diaG < 50, "diamond f0 corresponds to IOR ~2.42: diaG=" + diaG);
    }

    @Test
    void heldItemsPbrClassification() {
        // Metallic weapons & tools
        var ironSword = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/iron_sword");
        assertEquals(1.0f, ironSword.metal(), 1e-4f, "iron sword must be metal 1.0");
        assertTrue(ironSword.roughness() >= 0.30f && ironSword.roughness() <= 0.40f, "iron roughness near 0.35");

        var goldAxe = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/golden_axe");
        assertEquals(1.0f, goldAxe.metal(), 1e-4f, "golden axe must be metal 1.0");
        assertTrue(goldAxe.roughness() >= 0.20f && goldAxe.roughness() <= 0.30f, "gold roughness near 0.25");

        var netheriteIngot = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/netherite_ingot");
        assertEquals(1.0f, netheriteIngot.metal(), 1e-4f, "netherite must be metal 1.0");
        assertTrue(netheriteIngot.roughness() >= 0.35f && netheriteIngot.roughness() <= 0.45f, "netherite roughness near 0.40");

        var copperIngot = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/copper_ingot");
        assertEquals(1.0f, copperIngot.metal(), 1e-4f, "copper must be metal 1.0");
        assertTrue(copperIngot.roughness() >= 0.25f && copperIngot.roughness() <= 0.35f, "copper roughness near 0.30");

        // Diamond gem tool: dielectric, low roughness, high f0 (IOR 2.42)
        var diamondPick = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/diamond_pickaxe");
        assertEquals(0.0f, diamondPick.metal(), 1e-4f, "diamond pickaxe must be dielectric");
        assertTrue(diamondPick.roughness() <= 0.10f, "diamond pickaxe is very smooth: " + diamondPick.roughness());
        assertEquals(0.17f, diamondPick.f0(), 1e-2f, "diamond pickaxe f0 matches diamond IOR 2.42");

        // Default item (wood stick)
        var stick = io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/stick");
        assertEquals(0.0f, stick.metal(), 1e-4f, "stick is dielectric");
        assertTrue(stick.roughness() >= 0.80f, "stick is rough dielectric");
    }

    @Test
    void glassPreservesSmoothnessAndF0EvenWithAlphaBelow128() {
        // 2x1 sprite: (0,0) transparent glass centre (a=0), (1,0) semi-transparent tinted glass (a=80)
        int[] argb = {
            0x00FFFFFF, 0x50E0F0FF
        };
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];

        AutoPbr.Material auto = AutoPbr.classify("block/glass");
        MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, auto, albedo, null, null, "block/glass");

        // Transparent centre (a=0): must remain smooth (~0.02) and dielectric (f0 ~0.04), NOT roughness 1.0!
        int rough0 = mat[0] & 255;
        int g0 = (mat[0] >>> 8) & 255;
        assertTrue(rough0 <= 15, "glass transparent centre must be smooth (roughness <= 15/255): " + rough0);
        assertFalse(LabPbr.isMetal(g0), "glass must be dielectric");
        assertTrue(g0 >= 8 && g0 <= 20, "glass f0 near 0.04 (10/255): " + g0);

        // Tinted glass (a=80): must remain smooth and dielectric
        int rough1 = mat[1] & 255;
        int g1 = (mat[1] >>> 8) & 255;
        assertTrue(rough1 <= 15, "tinted glass must be smooth (roughness <= 15/255): " + rough1);
        assertFalse(LabPbr.isMetal(g1), "tinted glass must be dielectric");
        assertTrue(g1 >= 8 && g1 <= 20, "tinted glass f0 near 0.04 (10/255): " + g1);
    }

    @Test
    void glassAndWaterClassificationAndTexelProperties() {
        // Water
        var water = AutoPbr.classify("block/water_still");
        assertEquals(AutoPbr.RULE_WATER, water.rule(), "water rule must be RULE_WATER");
        assertEquals(0.02f, water.roughness(), 1e-4f, "water roughness must be 0.02");
        assertEquals(0.02f, water.f0(), 1e-4f, "water f0 must be 0.02");
        assertFalse(water.metal(), "water is not metal");

        // Plain glass
        var glass = AutoPbr.classify("block/glass");
        assertEquals(AutoPbr.RULE_GLASS, glass.rule(), "glass rule must be RULE_GLASS");
        assertEquals(0.02f, glass.roughness(), 1e-4f, "glass roughness must be 0.02");
        assertEquals(0.04f, glass.f0(), 1e-4f, "glass f0 must be 0.04");
        assertFalse(glass.metal(), "glass is not metal");

        // Stained glasses (cyan, blue, white)
        var cyanGlass = AutoPbr.classify("block/cyan_stained_glass");
        assertEquals(AutoPbr.RULE_GLASS, cyanGlass.rule(), "cyan stained glass must be RULE_GLASS");
        assertEquals(0.04f, cyanGlass.f0(), 1e-4f, "cyan stained glass f0 must be 0.04 (not water f0 0.02)");

        var blueGlass = AutoPbr.classify("block/blue_stained_glass");
        assertEquals(AutoPbr.RULE_GLASS, blueGlass.rule(), "blue stained glass must be RULE_GLASS");
        assertEquals(0.04f, blueGlass.f0(), 1e-4f, "blue stained glass f0 must be 0.04");

        // Verify MaterialRegion.fill keeps glass flat smooth and preserves f0
        int[] argb = { 0x664AEDD9, 0x664AEDD9 }; // translucent cyan stained glass texels
        MaterialRegion.Image albedo = new MaterialRegion.Image(2, 1, argb);
        int[] mat = new int[2];
        int[] nrm = new int[2];
        MaterialRegion.fill(mat, nrm, 2, 0, 0, 2, 1, cyanGlass, albedo, null, null, "block/cyan_stained_glass");

        int g = (mat[0] >>> 8) & 255;
        float rough = (mat[0] & 255) / 255f;
        assertFalse(LabPbr.isMetal(g), "glass is dielectric");
        assertTrue(rough <= 0.03f, "glass roughness must be smooth near 0.02: " + rough);
        float f0 = g / 255f;
        assertTrue(f0 > 0.035f && f0 < 0.05f, "glass f0 must be near 0.04: " + f0);
    }
}
