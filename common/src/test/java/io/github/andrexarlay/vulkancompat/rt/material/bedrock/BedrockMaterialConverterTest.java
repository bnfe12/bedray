package io.github.andrexarlay.vulkancompat.rt.material.bedrock;

import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockMaterialConverterTest {

    private static byte[] tga(int type, int w, int h, boolean topLeft, byte[] pixels) {
        byte[] out = new byte[18 + pixels.length];
        out[2] = (byte) type;
        out[12] = (byte) w;
        out[14] = (byte) h;
        out[16] = 32;
        out[17] = (byte) (topLeft ? 0x28 : 0x08);
        System.arraycopy(pixels, 0, out, 18, pixels.length);
        return out;
    }

    @Test
    void rawTgaIsReadBottomUpAndBgra() throws IOException {
        // 1x2, bottom-left origin: the first stored pixel is the bottom row. BGRA order.
        byte[] px = {10, 20, 30, 40, 1, 2, 3, 4};
        TgaReader.Image img = TgaReader.read(tga(2, 1, 2, false, px));
        assertEquals(4 << 24 | 3 << 16 | 2 << 8 | 1, img.argb()[0], "top row is the second stored pixel");
        assertEquals(40 << 24 | 30 << 16 | 20 << 8 | 10, img.argb()[1]);
    }

    @Test
    void rleTgaRepeatsAndCopies() throws IOException {
        byte[] px = {(byte) 0x81, 5, 6, 7, 8, 0x00, 9, 9, 9, 9};   // run of 2, then one raw pixel
        TgaReader.Image img = TgaReader.read(tga(10, 3, 1, true, px));
        assertArrayEquals(new int[] {8 << 24 | 7 << 16 | 6 << 8 | 5, 8 << 24 | 7 << 16 | 6 << 8 | 5, 9 << 24 | 9 << 16 | 9 << 8 | 9},
            img.argb());
    }

    @Test
    void unsupportedTgaIsRefusedNotGuessed() {
        assertThrows(IOException.class, () -> TgaReader.read(tga(1, 1, 1, true, new byte[4])));
        assertThrows(IOException.class, () -> TgaReader.read(new byte[5]));
    }

    @Test
    void legacyBedrockNamesFindTheJavaTexture() {
        assertTrue(BedrockMaterialConverter.javaCandidates("stonebrick").contains("stone_bricks"));
        assertTrue(BedrockMaterialConverter.javaCandidates("planks_big_oak").contains("dark_oak_planks"));
        assertTrue(BedrockMaterialConverter.javaCandidates("glass_silver").contains("light_gray_stained_glass"));
        assertTrue(BedrockMaterialConverter.javaCandidates("wool_colored_light_blue").contains("light_blue_wool"));
        assertTrue(BedrockMaterialConverter.javaCandidates("torch_on").contains("torch"));
        assertEquals("iron_block", BedrockMaterialConverter.javaCandidates("iron_block").get(0));
        assertTrue(BedrockMaterialConverter.isWater("water_still"));
    }

    @Test
    void mersBecomesTheLabPbrBedrayDecodes() {
        int iron = 0 << 24 | 240 << 16 | 0 << 8 | 41;          // metal 0.94, no emission, roughness 0.16
        int torch = 0 << 24 | 0 << 16 | 64 << 8 | 128;         // emissive 0.25
        int leaf = 150 << 24 | 0 << 16 | 0 << 8 | 153;         // subsurface 0.59
        int grey = 0xFF808080;
        int[] s = BedrockMaterialConverter.specular(new int[] {iron, torch, leaf}, new int[] {grey, grey, grey});
        assertEquals(LabPbr.METAL_G, (s[0] >>> 8) & 255, "metalness 0.9+ is an albedo metal");
        // LabPbr.roughnessFromSmoothness returns GGX alpha (perceptual roughness squared); sqrt recovers the perceptual value.
        assertEquals(0.16f, (float) Math.sqrt(LabPbr.roughnessFromSmoothness((s[0] >>> 16) & 255)), 0.01f, "perceptual roughness survives the round trip");
        assertEquals(255, s[0] >>> 24, "no emission is alpha 255");
        assertEquals(10, (s[1] >>> 8) & 255, "a dielectric gets F0 0.04");
        assertEquals(0.75f, LabPbr.emission(s[1] >>> 24), 0.01f, "emission is scaled into LabPBR's range");
        assertTrue((s[2] & 255) >= 65, "subsurface lands in LabPBR's SSS range");
    }

    @Test
    void aFlatHeightmapIsAFlatNormal() {
        int[] flat = new int[16];
        java.util.Arrays.fill(flat, 0xFF808080);
        int[] n = BedrockMaterialConverter.normals(flat, 4, 4, 1f);
        float[] d = LabPbr.decodeNormal((n[5] >>> 16) & 255, (n[5] >>> 8) & 255);
        assertEquals(0f, d[0], 0.01f);
        assertEquals(0f, d[1], 0.01f);
        assertEquals(1f, d[2], 0.01f);
    }

    @Test
    void aRiseToTheRightTiltsTheNormalLeftAndARiseDownTiltsItUp() {
        int[] rampX = new int[9];
        int[] rampY = new int[9];
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
            rampX[y * 3 + x] = 0xFF000000 | (x * 100) << 16;
            rampY[y * 3 + x] = 0xFF000000 | (y * 100) << 16;
        }
        int nx = BedrockMaterialConverter.normals(rampX, 3, 3, 1f)[4];
        int ny = BedrockMaterialConverter.normals(rampY, 3, 3, 1f)[4];
        assertTrue(LabPbr.decodeNormal((nx >>> 16) & 255, (nx >>> 8) & 255)[0] < -0.1f, "height rising to +x faces -x");
        assertTrue(LabPbr.decodeNormal((ny >>> 16) & 255, (ny >>> 8) & 255)[1] > 0.1f,
            "height rising down the image (towards -y) faces +y after the DirectX flip");
    }
}
