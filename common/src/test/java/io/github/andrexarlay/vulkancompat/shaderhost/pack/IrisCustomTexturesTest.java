package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pack custom textures (texture.* / texture.composite.*) must be decoded from the pack's own image files into RGBA8 pixels
 * the executor can upload to a sampled view, instead of binding the 1x1 white fallback.
 */
class IrisCustomTexturesTest {

    @TempDir
    Path tmp;

    /** A 2x2 ARGB PNG with known corner pixels, encoded losslessly so the decode round-trip is exact. */
    private static byte[] png2x2() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000); // red, opaque
        img.setRGB(1, 0, 0xFF00FF00); // green, opaque
        img.setRGB(0, 1, 0xFF0000FF); // blue, opaque
        img.setRGB(1, 1, 0x80FFFFFF); // white, half alpha
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", bo), "a PNG writer must be available");
        return bo.toByteArray();
    }

    private Path pack() throws IOException {
        Path p = tmp.resolve("Tex.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            put(z, "shaders/shaders.properties",
                "texture.noise=tex/noise.png\ntexture.composite.absent=tex/missing.png\n");
            putBytes(z, "shaders/tex/noise.png", png2x2());
            put(z, "shaders/final.fsh", "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}\n");
        }
        return p;
    }

    @Test
    void decodesCustomTextureIntoRgba8() throws IOException {
        try (PackSource src = PackSource.open(pack(), ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings s = IrisPackSettings.parse(src, report);
            IrisCustomTextures.Pixels px = s.customTexturePixels("noise");
            assertNotNull(px, "noise.png declared by the pack must be decoded");
            assertEquals(2, px.width());
            assertEquals(2, px.height());
            byte[] rgba = px.rgba();
            assertEquals(16, rgba.length, "2x2 RGBA8 = 16 bytes");
            // (0,0) red opaque
            assertEquals((byte) 0xFF, rgba[0]);
            assertEquals((byte) 0x00, rgba[1]);
            assertEquals((byte) 0x00, rgba[2]);
            assertEquals((byte) 0xFF, rgba[3]);
            // (1,1) white half-alpha
            assertEquals((byte) 0xFF, rgba[12]);
            assertEquals((byte) 0xFF, rgba[13]);
            assertEquals((byte) 0xFF, rgba[14]);
            assertEquals((byte) 0x80, rgba[15]);
        }
    }

    @Test
    void absentTextureFileIsOmittedNotFaked() throws IOException {
        try (PackSource src = PackSource.open(pack(), ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings s = IrisPackSettings.parse(src, report);
            assertNull(s.customTexturePixels("absent"), "a declared but missing file must not produce pixels");
        }
    }

    @Test
    void helperResolvesPathsRelativeToShadersRoot() throws IOException {
        try (PackSource src = PackSource.open(pack(), ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings s = IrisPackSettings.parse(src, report);
            Map<String, IrisCustomTextures.Pixels> all = IrisCustomTextures.load(src, report.shadersRoot(), s);
            assertTrue(all.containsKey("noise"));
            assertFalse(all.containsKey("absent"));
        }
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        putBytes(z, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }
}