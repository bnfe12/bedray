package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** L2: emission table additions (copper lantern, coloured candles, copper torch, respawn anchor) and the opaque ice blocks. */
public class MatfixRulesTest {
    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Test
    public void copperLanternsGlowGreenAndKeepTheCasing() {
        for (String n : new String[] {"copper_lantern", "exposed_copper_lantern", "weathered_copper_lantern", "oxidized_copper_lantern"}) {
            AutoPbr.Material m = AutoPbr.classify("block/" + n);
            assertTrue(m.emission() > 0f, n);
            assertEquals(AutoPbr.MixedKind.COPPER_LANTERN, AutoPbr.mixedKind("block/" + n), n);
        }
        // pixels of the vanilla 26.3 sprite: green flame / white core glow, orange copper and turquoise oxidized casing do not
        assertTrue(AutoPbr.isEmissiveTexel(argb(0x99, 0xeb, 0x99), AutoPbr.MixedKind.COPPER_LANTERN));
        assertTrue(AutoPbr.isEmissiveTexel(argb(0xff, 0xff, 0xff), AutoPbr.MixedKind.COPPER_LANTERN));
        assertTrue(AutoPbr.isEmissiveTexel(argb(0x69, 0xb2, 0x6e), AutoPbr.MixedKind.COPPER_LANTERN));
        assertFalse(AutoPbr.isEmissiveTexel(argb(0xc2, 0x6b, 0x4c), AutoPbr.MixedKind.COPPER_LANTERN), "copper casing");
        assertFalse(AutoPbr.isEmissiveTexel(argb(0x4c, 0x94, 0x84), AutoPbr.MixedKind.COPPER_LANTERN), "oxidized casing");
        assertTrue(AutoPbr.isMetalTexel(argb(0xc2, 0x6b, 0x4c), AutoPbr.MixedKind.COPPER_LANTERN));
    }

    @Test
    public void litCandlesGlowOnTheFlameOnly() {
        for (String n : new String[] {"candle_lit", "red_candle_lit", "light_blue_candle_lit"}) {
            assertEquals(AutoPbr.RULE_CANDLE, AutoPbr.classify("block/" + n).rule(), n);
        }
        assertEquals(0f, AutoPbr.classify("block/candle").emission(), 0f, "unlit candle");
        assertEquals(1f, AutoPbr.candleEmission(0xff, 0xf6, 0xe7, 255), "white candle flame");
        assertEquals(0f, AutoPbr.candleEmission(0xe6, 0xb9, 0x8c, 255), "white candle wax");
        assertEquals(1f, AutoPbr.candleEmission(0xff, 0xa1, 0x60, 255), "red candle flame");
        assertEquals(0f, AutoPbr.candleEmission(0x90, 0x21, 0x20, 255), "red candle wax");
    }

    @Test
    public void copperTorchAndAnchorAreEmissive() {
        assertEquals(AutoPbr.RULE_FLAME, AutoPbr.classify("block/copper_torch").rule());
        assertEquals(1f, AutoPbr.flameEmission(0x99, 0xeb, 0x99, 255), "green flame pixel");
        assertEquals(AutoPbr.RULE_ANCHOR, AutoPbr.classify("block/respawn_anchor_side3").rule());
        assertEquals(1f, AutoPbr.anchorEmission(0x2b, 0x01, 0x78, 255), "charge pixel");
        assertEquals(0f, AutoPbr.anchorEmission(0x48, 0x38, 0x60, 255), "stone");
    }

    @Test
    public void blueAndPackedIceAreOpaqueNotGlassLikeIce() {
        assertEquals(AutoPbr.RULE_ICE, AutoPbr.classify("block/ice").rule());
        assertEquals(AutoPbr.RULE_ICE, AutoPbr.classify("block/frosted_ice_2").rule());
        for (String n : new String[] {"packed_ice", "blue_ice"}) {
            AutoPbr.Material m = AutoPbr.classify("block/" + n);
            assertEquals(AutoPbr.RULE_ICE_OPAQUE, m.rule(), n);
            // f0 above 0.025: the shader's ice test (fetchSurface / candidateAccepted) must not match it
            assertTrue(m.f0() > 0.03f, n);
            assertFalse(MaterialRegion.isTranslucentOrGlass(m, "block/" + n), n);
        }
        assertTrue(MaterialRegion.isTranslucentOrGlass(AutoPbr.classify("block/ice"), "block/ice"));
    }
}
