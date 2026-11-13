package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decodes a pack's custom texture images (shaders.properties {@code texture.*} / {@code texture.composite.*}) into RGBA8
 * pixels the forward executor uploads to sampled views. Paths resolve relative to the pack's shaders root. A declared file
 * that is missing or undecodable is omitted (the executor binds its white fallback), never faked.
 */
public final class IrisCustomTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/IrisCustomTextures");

    /** Decoded RGBA8 pixels, row-major top-to-bottom, 4 bytes per pixel. */
    public record Pixels(byte[] rgba, int width, int height) {}

    private IrisCustomTextures() {}

    public static Map<String, Pixels> load(PackSource src, String shadersRoot, IrisPackSettings settings) {
        Map<String, Pixels> out = new LinkedHashMap<>();
        Set<String> entries = Set.copyOf(src.entries());
        for (String name : settings.customTextureNames()) {
            String spec = settings.customTexture(name);
            if (spec == null || spec.isBlank()) {
                continue;
            }
            String path = spec.trim().split("\\s+")[0];
            String entry = shadersRoot + path;
            if (!entries.contains(entry)) {
                LOGGER.warn("[VKB-IRIS] custom texture '{}' file '{}' not in pack; white fallback", name, entry);
                continue;
            }
            try {
                Pixels px = decode(src.read(entry));
                if (px == null) {
                    LOGGER.warn("[VKB-IRIS] custom texture '{}' file '{}' not a decodable image; white fallback", name, entry);
                    continue;
                }
                out.put(name, px);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[VKB-IRIS] custom texture '{}' read failed ({}); white fallback", name, e.toString());
            }
        }
        return out;
    }

    private static Pixels decode(byte[] data) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] rgba = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                rgba[i++] = (byte) (argb >> 16);
                rgba[i++] = (byte) (argb >> 8);
                rgba[i++] = (byte) argb;
                rgba[i++] = (byte) (argb >> 24);
            }
        }
        return new Pixels(rgba, w, h);
    }
}