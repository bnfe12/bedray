package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** L2: the progressive bake mirrors each baked sprite into the CPU material array that the emitter scanner reads —
 * what the GPU receives at (x0, y0, w, h) is what {@code RtEmitters} sees at the same atlas coordinates. */
public class RtMaterialAtlasMirrorTest {
    private static MaterialAtlasBaker.SpriteBake sprite(int x0, int y0, int w, int h, int emission) {
        int[] material = new int[w * h];
        for (int i = 0; i < material.length; i++) {
            material[i] = (emission << 16) | 0x0000FF;
        }
        return new MaterialAtlasBaker.SpriteBake(x0, y0, w, h, material, new int[w * h], null, false,
            false, false, false, emission > 0 ? w * h : 0, false);
    }

    @Test
    public void spriteTexelsLandAtTheirAtlasPosition() {
        int[] cpu = new int[8 * 8];
        RtMaterialAtlas.mirrorSprite(cpu, 8, sprite(3, 2, 2, 3, 255));
        // the emitter scanner reads emission as ((material >>> 16) & 255) / 255
        assertEquals(1f, ((cpu[2 * 8 + 3] >>> 16) & 255) / 255f, 1e-6f, "flame texel, top row");
        assertEquals(1f, ((cpu[4 * 8 + 4] >>> 16) & 255) / 255f, 1e-6f, "flame texel, bottom row");
        assertEquals(0, cpu[0], "outside the sprite stays at the default material");
        assertEquals(0, cpu[2 * 8 + 2], "left of the sprite");
        assertEquals(0, cpu[5 * 8 + 3], "below the sprite");
    }

    @Test
    public void adjacentSpritesDoNotOverwriteEachOther() {
        int[] cpu = new int[4 * 4];
        RtMaterialAtlas.mirrorSprite(cpu, 4, sprite(0, 0, 2, 2, 255));
        RtMaterialAtlas.mirrorSprite(cpu, 4, sprite(2, 2, 2, 2, 128));
        assertEquals(255, (cpu[0] >>> 16) & 255, "first sprite emission kept");
        assertEquals(255, (cpu[1 * 4 + 1] >>> 16) & 255, "first sprite bottom-right kept");
        assertEquals(128, (cpu[3 * 4 + 3] >>> 16) & 255, "second sprite bottom-right");
        assertEquals(0, (cpu[2] >>> 16) & 255, "default texel between the sprites");
    }

    @Test
    public void fullAtlasSpriteCoversEveryTexel() {
        int[] cpu = new int[4 * 2];
        RtMaterialAtlas.mirrorSprite(cpu, 4, sprite(0, 0, 4, 2, 64));
        for (int i = 0; i < cpu.length; i++) {
            assertEquals(64, (cpu[i] >>> 16) & 255, "texel " + i);
        }
    }
}
