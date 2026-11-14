package io.github.andrexarlay.vulkancompat.rt.hand;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Mood of the RT scene on inventory icons (opt-in, default off: {@code -Dvkbridge.rt.iconLight=sky}).
 * <p>
 * STUB (analytic, not traced): vanilla draws every icon with {@code GuiItemAtlas.drawToSlot} into an atlas texture at full light
 * (packed 15728880 = lightmap texel (15,15)). While one icon is drawn, {@code GameRenderer.lightmap()} returns a 16x16 texture whose
 * texels all hold a bounded tint of the RT sky model ({@link HandRtProbe}): the hue of the sky colour at {@link #STRENGTH}, and a
 * brightness between {@link #MIN_BRIGHTNESS} (night) and 1.0 (day), so icons stay readable. There is no fresnel, reflection or
 * refraction: icons are not ray traced.
 * <p>
 * Render thread only.
 */
public final class IconRtLight {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    static final String MODE_PROPERTY = "vkbridge.rt.iconLight";
    private static final boolean SKY_MODE = "sky".equalsIgnoreCase(System.getProperty(MODE_PROPERTY));
    /** Share of the sky hue mixed into the white icon light. */
    static final float STRENGTH = 0.35f;
    /** Brightness factor at night (sky colour black); 1.0 when the sky colour has a full channel. */
    static final float MIN_BRIGHTNESS = 0.8f;
    private static final int SIZE = 16;

    private static boolean overriding;
    private static boolean failed;
    private static boolean logged;
    private static DynamicTexture texture;
    private static int uploadedArgb;

    private IconRtLight() {
    }

    public static boolean overriding() {
        return overriding;
    }

    /** Icon light colour (opaque ARGB) for a sky colour (any ARGB, alpha ignored). Pure: see IconRtLightTest. */
    static int iconArgb(int skyArgb) {
        float r = ((skyArgb >> 16) & 0xFF) / 255.0f;
        float g = ((skyArgb >> 8) & 0xFF) / 255.0f;
        float b = (skyArgb & 0xFF) / 255.0f;
        float max = Math.max(Math.max(r, g), Math.max(b, 1.0e-3f));
        float brightness = MIN_BRIGHTNESS + (1.0f - MIN_BRIGHTNESS) * Math.min(max, 1.0f);
        return 0xFF000000
            | channel(brightness * (1.0f - STRENGTH + STRENGTH * (r / max))) << 16
            | channel(brightness * (1.0f - STRENGTH + STRENGTH * (g / max))) << 8
            | channel(brightness * (1.0f - STRENGTH + STRENGTH * (b / max)));
    }

    private static int channel(float v) {
        return Math.round(Math.min(Math.max(v, 0.0f), 1.0f) * 255.0f);
    }

    /** Start of one icon draw (GuiItemAtlas.drawToSlot). Does nothing unless the mode is on and the RT sky model is published. */
    public static void begin() {
        overriding = false;
        if (!SKY_MODE || failed || !HandRtProbe.hasSky()) {
            return;
        }
        int argb = iconArgb(HandRtProbe.argb(15, 15));
        try {
            if (texture == null) {
                texture = new DynamicTexture("bedray icon lightmap", SIZE, SIZE, false);
                uploadedArgb = ~argb;
            }
            if (uploadedArgb != argb) {
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        texture.getPixels().setPixel(x, y, argb);
                    }
                }
                texture.upload();
                uploadedArgb = argb;
            }
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("[VKB-RT] icon_light state=off reason=error (vanilla icons for the rest of the session)", e);
            return;
        }
        overriding = true;
        if (!logged) {
            logged = true;
            LOGGER.info("[VKB-RT] icon_light state=on source=sky_model analytic=1 color={}",
                String.format(Locale.ROOT, "%06X", argb & 0xFFFFFF));
        }
    }

    public static void end() {
        overriding = false;
    }

    public static GpuTextureView view() {
        return texture.getTextureView();
    }

    public static void close() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        overriding = false;
    }
}
