package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Regressions found while reviewing all vanilla 26.3 block sprites and their model UVs. */
class CatalogMaterialRegressionTest {
    private static int color(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }
    private static AutoPbr.Material material(String name) { return AutoPbr.classify("block/" + name); }

    @Test void coralNamesAreNotFlamesAndPumpkinSkinIsNotAWindow() {
        for (String name : new String[]{"fire_coral", "fire_coral_block", "fire_coral_fan", "dead_fire_coral"})
            assertEquals(AutoPbr.Emission.NONE, material(name).emissionMode(), name);
        for (String name : new String[]{"fire_0", "fire_1", "soul_fire_0", "soul_fire_1"})
            assertTrue(material(name).emission() > 0, name);
        var pumpkin = material("jack_o_lantern");
        for (int pixel : new int[]{color(196,118,20), color(227,166,75)})
            assertEquals(0, AutoPbr.pixelEmission(pumpkin, pixel), "unchanged exterior pumpkin skin");
        assertTrue(AutoPbr.pixelEmission(pumpkin, color(212,170,55)) > 0);
        assertTrue(AutoPbr.pixelEmission(pumpkin, color(254,255,161)) > 0);
    }

    @Test void lowValueLichenAndTheSeparateEyeblossomOverlaySurviveBytePacking() {
        for (String name : new String[]{"glow_lichen", "open_eyeblossom_emissive"}) {
            int[] pixels = {color(175,175,175), color(100,115,105), 0};
            int[] packed = new int[3];
            MaterialRegion.fill(packed, new int[3], 3, 0, 0, 3, 1, material(name),
                new MaterialRegion.Image(3,1,pixels), null, null, "block/" + name);
            assertTrue((packed[0] >>> 16 & 255) > 0, name);
            assertEquals(0, packed[2] >>> 16 & 255, "transparent texels remain dark");
        }
        assertEquals(AutoPbr.Emission.NONE, material("closed_eyeblossom").emissionMode());
        assertEquals(AutoPbr.Emission.NONE, material("open_eyeblossom").emissionMode());
    }

    @Test void everyLitCandleHasTheSameSmallCapAndNoEmissiveWaxSides() {
        String[] dyes = {"", "white_", "orange_", "magenta_", "light_blue_", "yellow_", "lime_", "pink_", "gray_",
            "light_gray_", "cyan_", "purple_", "blue_", "brown_", "green_", "red_", "black_"};
        for (String dye : dyes) for (int size : new int[]{16,32}) {
            String name = dye + "candle_lit";
            int[] pixels = new int[size * size];
            // Same colour on the cap and body proves location, rather than a wax-colour brightness threshold, selects emission.
            Arrays.fill(pixels, dye.equals("black_") ? color(228,110,46) : color(255,246,231));
            var auto = material(name);
            var mask = MaterialRegion.emissionMask(auto, AutoPbr.MixedKind.NONE, pixels, size, size);
            assertNotNull(mask, name);
            int[] packed = new int[pixels.length];
            MaterialRegion.fill(packed, new int[pixels.length], size, 0, 0, size, size, auto,
                new MaterialRegion.Image(size,size,pixels), null, null, "block/" + name);
            int emitting = 0;
            for (int i = 0; i < mask.length; i++) {
                assertEquals(Math.round(mask[i] * 255), packed[i] >>> 16 & 255, name);
                if (mask[i] > 0) emitting++;
            }
            assertEquals(size * size / 64, emitting, name);
            assertEquals(0, mask[12 * size / 16 * size], "wax side UVs are not a flame");
            assertEquals(AutoPbr.Emission.NONE, material(dye + "candle").emissionMode());
        }
    }

    @Test void copperCoatingDoesNotBecomeMetalOrLampGlass() {
        int patina = color(76,148,132), copper = color(194,107,76);
        for (var kind : new AutoPbr.MixedKind[]{AutoPbr.MixedKind.COPPER_BULB, AutoPbr.MixedKind.COPPER_LANTERN,
                AutoPbr.MixedKind.COPPER_PATINA}) {
            assertFalse(AutoPbr.isMetalTexel(patina, kind), kind.name());
            assertTrue(AutoPbr.isMetalTexel(copper, kind), kind.name());
        }
        assertFalse(AutoPbr.isEmissiveTexel(color(177,200,169), AutoPbr.MixedKind.COPPER_BULB));
        assertTrue(AutoPbr.isEmissiveTexel(color(255,225,86), AutoPbr.MixedKind.COPPER_BULB));
        assertFalse(material("oxidized_copper_bulb").metal());
        assertTrue(material("oxidized_copper_lantern").roughness() >= .6f);
    }

    @Test void hardwareGetsMetalWithoutTurningRailTiesIntoMetal() {
        assertTrue(material("bell_side").metal());
        assertTrue(material("lightning_rod").metal());
        assertTrue(material("lightning_rod_on").metal());
        assertEquals(AutoPbr.MixedKind.COPPER_PATINA, AutoPbr.mixedKind("block/exposed_lightning_rod"));
        assertEquals(AutoPbr.MixedKind.COPPER_PATINA, AutoPbr.mixedKind("block/exposed_lightning_rod_on"));
        assertEquals(AutoPbr.MixedKind.RAIL, AutoPbr.mixedKind("block/powered_rail_on"));
        assertTrue(AutoPbr.isMetalTexel(color(138,139,135), AutoPbr.MixedKind.RAIL));
        assertTrue(AutoPbr.isMetalTexel(color(255,222,68), AutoPbr.MixedKind.RAIL));
        assertTrue(AutoPbr.isMetalTexel(color(220,174,41), AutoPbr.MixedKind.RAIL));
        assertFalse(AutoPbr.isMetalTexel(color(108,67,42), AutoPbr.MixedKind.RAIL));
        assertFalse(AutoPbr.isMetalTexel(color(166,130,77), AutoPbr.MixedKind.RAIL));
    }

    @Test void orangeBulbGlassIsNotCopperAndItsCasingCannotEmit() {
        int orange = color(255,194,100);
        assertFalse(AutoPbr.isMetalTexel(orange, AutoPbr.MixedKind.COPPER_BULB, 4, 2, 16, 16));
        assertTrue(AutoPbr.isEmissiveTexel(orange, AutoPbr.MixedKind.COPPER_BULB, 4, 2, 16, 16));
        assertFalse(AutoPbr.isEmissiveTexel(orange, AutoPbr.MixedKind.COPPER_BULB, 8, 8, 16, 16));
        int[] albedo = new int[256], packed = new int[256];
        Arrays.fill(albedo, color(76,148,132));
        MaterialRegion.fill(packed, new int[256], 16, 0, 0, 16, 16, material("oxidized_copper_bulb"),
            new MaterialRegion.Image(16,16,albedo), null, null, "block/oxidized_copper_bulb");
        assertTrue((packed[8 * 16 + 8] & 255) >= 150, "coating keeps its rough base");
        assertTrue((packed[2 * 16 + 4] & 255) < 150, "glass aperture keeps its separate finish");
    }
}
