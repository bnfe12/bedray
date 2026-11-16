package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Font data the RT text capture needs: the bitmap glyph pages of Minecraft's default font providers (so Cyrillic and other
 * non-ASCII sign text gets geometry) and the colour a glow-ink glyph casts as light. Static and stateless apart from the
 * lazily loaded glyph table.
 */
final class EntityTextGlyphs {
    private static final Logger LOGGER = LoggerFactory.getLogger("VK-ENTITY-CAPTURE");

    private EntityTextGlyphs() {
    }

    static final Identifier ASCII_FONT =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "textures/font/ascii.png");
    private static final net.minecraft.resources.Identifier DEFAULT_FONT_PROVIDERS =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "font/include/default.json");
    record BitmapGlyph(Identifier texture, int column, int row, int columns, int rows) { }
    private static volatile java.util.Map<Integer, BitmapGlyph> nonAsciiGlyphs;

    /** Bitmap glyphs outside the legacy 0..255 ASCII page (notably Cyrillic sign text). */
    static java.util.Map<Integer, BitmapGlyph> nonAsciiGlyphs() {
        java.util.Map<Integer, BitmapGlyph> cached = nonAsciiGlyphs;
        if (cached != null) return cached;
        synchronized (EntityTextGlyphs.class) {
            if (nonAsciiGlyphs != null) return nonAsciiGlyphs;
            java.util.Map<Integer, BitmapGlyph> glyphs = new java.util.HashMap<>();
            var resource = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(DEFAULT_FONT_PROVIDERS);
            if (resource.isPresent()) {
                try (java.io.Reader reader = new java.io.InputStreamReader(resource.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
                    var root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    var providers = root.getAsJsonArray("providers");
                    if (providers != null) {
                        for (var element : providers) {
                            if (!element.isJsonObject()) continue;
                            var provider = element.getAsJsonObject();
                            if (!provider.has("type") || !"bitmap".equals(provider.get("type").getAsString())
                                    || !provider.has("file") || !provider.has("chars")) continue;
                            String file = provider.get("file").getAsString();
                            int separator = file.indexOf(':');
                            String namespace = separator >= 0 ? file.substring(0, separator) : "minecraft";
                            String path = separator >= 0 ? file.substring(separator + 1) : file;
                            if (!path.startsWith("textures/")) path = "textures/" + path;
                            if (!path.endsWith(".png")) path = path + ".png";
                            Identifier texture = Identifier.fromNamespaceAndPath(namespace, path);
                            var rows = provider.getAsJsonArray("chars");
                            int rowCount = rows.size();
                            for (int row = 0; row < rowCount; row++) {
                                int[] codepoints = rows.get(row).getAsString().codePoints().toArray();
                                for (int column = 0; column < codepoints.length; column++) {
                                    int codepoint = codepoints[column];
                                    if (codepoint > 255) {
                                        glyphs.put(codepoint, new BitmapGlyph(texture, column, row, codepoints.length, rowCount));
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[VK-ENTITY-CAPTURE] could not read Minecraft bitmap font providers; non-ASCII RT text is unavailable\n{}", LogPaths.stack(e));
                }
            }
            nonAsciiGlyphs = java.util.Map.copyOf(glyphs);
            return nonAsciiGlyphs;
        }
    }
    /**

     * Colour of the light a glow-ink glyph casts. The default sign text is black, and black light is no light: the glow only
     * showed once the sign was dyed (owner, 2026-11-14). Vanilla draws such text black with a bright outline, so a text colour
     * too dark to light anything casts the outline colour instead (vanilla gives black text the cream 0xF0EBCC outline).
     */
    static int glowLightColor(int textArgb, int outline) {
        int r = (textArgb >> 16) & 255;
        int g = (textArgb >> 8) & 255;
        int b = textArgb & 255;
        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        if (luma >= 0.12f || (outline & 0xFFFFFF) == 0) {
            return textArgb;
        }
        return 0xFF000000 | (outline & 0xFFFFFF);
    }
}
