package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** L2: per-pixel emission of the auto-PBR rules (flame, bright pixels, whole sprite) and its use for the entity atlas. */
public class EmissionMaskTest {
    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The 16x16 torch of the vanilla block atlas: flame (4 texels), stick (browns), the rest transparent. */
    private static int[] torch() {
        int[] px = new int[16 * 16];   // transparent
        px[6 * 16 + 7] = argb(255, 216, 0);
        px[6 * 16 + 8] = argb(255, 143, 0);
        px[7 * 16 + 7] = argb(255, 255, 151);
        px[7 * 16 + 8] = argb(255, 255, 255);
        int[][] stick = {{159, 127, 80}, {109, 87, 54}, {149, 117, 70}, {85, 69, 46}, {66, 53, 34}, {61, 48, 29}, {59, 46, 27}};
        for (int y = 8; y < 16; y++) {
            px[y * 16 + 7] = argb(stick[(y * 2) % stick.length][0], stick[(y * 2) % stick.length][1], stick[(y * 2) % stick.length][2]);
            px[y * 16 + 8] = argb(stick[(y * 2 + 1) % stick.length][0], stick[(y * 2 + 1) % stick.length][1], stick[(y * 2 + 1) % stick.length][2]);
        }
        return px;
    }

    @Test
    public void torchFlameGlowsEveryPixelAndTheStickDoesNot() {
        assertEquals(1f, AutoPbr.flameEmission(255, 216, 0, 255), 1e-6f, "yellow flame pixel");
        assertEquals(1f, AutoPbr.flameEmission(255, 143, 0, 255), 1e-6f, "orange flame pixel");
        assertEquals(1f, AutoPbr.flameEmission(255, 255, 255, 255), 1e-6f, "white-hot core pixel");
        assertEquals(0f, AutoPbr.flameEmission(159, 127, 80, 255), 1e-6f, "brightest stick pixel");
        assertEquals(0f, AutoPbr.flameEmission(255, 255, 255, 0), 1e-6f, "transparent");
        // the generic rule keeps grey / white pixels dark (skins, eyes, bone) and lights saturated bright ones, also red/orange
        assertEquals(0f, AutoPbr.brightPixelEmission(255, 255, 255, 255), 1e-6f);
        assertTrue(AutoPbr.brightPixelEmission(253, 0, 0, 255) > 0.9f, "pure red is bright in value");
        assertTrue(AutoPbr.brightPixelEmission(230, 120, 30, 255) > 0.9f, "orange flame");
        assertEquals(0f, AutoPbr.brightPixelEmission(159, 127, 80, 255), 1e-6f, "wood");
    }

    @Test
    public void entityMaskMatchesTheBlockAtlasFill() {
        int[] px = torch();
        AutoPbr.Material m = AutoPbr.classify("block/torch");
        assertEquals(AutoPbr.RULE_FLAME, m.rule());
        float[] mask = MaterialRegion.emissionMask(m, AutoPbr.mixedKind("block/torch"), px, 16, 16);
        assertNotNull(mask);
        // the same sprite through the block atlas bake (fill): emission byte = bits 16..23 of the material
        int[] material = new int[16 * 16];
        int[] normals = new int[16 * 16];
        MaterialRegion.fill(material, normals, 16, 0, 0, 16, 16, m, new MaterialRegion.Image(16, 16, px), null, null, "block/torch");
        for (int i = 0; i < 256; i++) {
            assertEquals(((material[i] >>> 16) & 255) / 255f, mask[i], 1.0f / 255f, "texel " + i);
        }
        int glowing = 0;
        for (float e : mask) {
            glowing += e > 0f ? 1 : 0;
        }
        assertEquals(4, glowing, "the 4 flame texels, nothing else");
    }

    @Test
    public void glowstoneIsAGradientNotWhite() {
        AutoPbr.Material m = AutoPbr.classify("block/glowstone");
        assertEquals(AutoPbr.Emission.WHOLE, m.emissionMode());
        int[] px = new int[16 * 16];
        for (int i = 0; i < px.length; i++) {
            int v = i % 16;
            px[i] = argb(120 + v * 8, 80 + v * 8, 20 + v * 3);   // dark to bright ramp
        }
        float[] mask = MaterialRegion.emissionMask(m, AutoPbr.MixedKind.NONE, px, 16, 16);
        assertNotNull(mask);
        assertTrue(mask[0] < mask[15], "darker texels glow less than brighter ones");
        assertTrue(mask[0] >= 0.15f - 1e-6f, "floor of 15 %");
        assertTrue(mask[15] <= 1f);
    }

    @Test
    public void itemSpritesOfLightSourcesAreEmissive() {
        for (String n : new String[] {"item/lava_bucket", "item/fire_charge", "item/glowstone_dust", "block/torch", "block/soul_torch",
            "block/redstone_torch", "block/glowstone", "block/sea_lantern", "item/lantern", "block/lantern"}) {
            assertNotEquals(AutoPbr.Emission.NONE, AutoPbr.classify(n).emissionMode(), n);
        }
        assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("entity/player/wide/steve").emissionMode());
        assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("block/stone_bricks").emissionMode());
    }

    @Test
    public void onlyKnownFlameAndLavaParticleSpritesAreEmissive() {
        for (String sprite : new String[] {"particle/flame", "minecraft:particle/lava", "particle/soul_fire_flame", "particle/glow", "particle/spark_0"}) {
            AutoPbr.Material material = AutoPbr.classify(sprite);
            assertEquals(AutoPbr.RULE_FLAME, material.rule(), sprite);
            assertEquals(AutoPbr.Emission.BRIGHT, material.emissionMode(), sprite);
        }
        assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("particle/smoke").emissionMode());
        assertEquals(AutoPbr.Emission.NONE, AutoPbr.classify("particle/generic_0").emissionMode());

        int[] pixels = {argb(255, 120, 20), 0x00FF7814};
        float[] mask = MaterialRegion.emissionMask(AutoPbr.classify("particle/flame"), AutoPbr.MixedKind.NONE, pixels, 2, 1);
        assertNotNull(mask);
        assertTrue(mask[0] > 0f, "opaque hot texel should emit");
        assertEquals(0f, mask[1], 1e-6f, "transparent RGB must not emit");
    }
}
