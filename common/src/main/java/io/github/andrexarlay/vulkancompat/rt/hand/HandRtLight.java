package io.github.andrexarlay.vulkancompat.rt.hand;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.LightCoordsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Lighting of the first-person hand and its held items from the traced scene instead of the vanilla lightmap.
 * <p>
 * The hand pass ({@code GameRenderer.renderItemInHand}) draws the arm and every held item with RenderTypes that sample the
 * lightmap (Sampler2) at the packed light of the hand. While the pass is armed, {@link #SENTINEL_LIGHT} replaces that packed
 * light (texel (0,0), see {@code include/sample_lightmap.glsl}: {@code uv / 256 + 0.5 / 16}) and {@code GameRenderer.lightmap()}
 * returns this 16x16 texture, filled with the hand's light colour. Texel (15,15) stays white: it is {@code FULL_BRIGHT},
 * used by the emissive resubmission of glowing item pixels ({@code MixinSubmitNodeCollection.HAND_FULL_LIGHT}).
 * Enchantment glint does not read the lightmap and stays as in vanilla.
 * <p>
 * STUB: no RT source until Step 2 (the hand light probe). Step 1 only proves the hook with a diagnostic colour given by
 * {@code -Dvkbridge.rt.handLight.debug=RRGGBB}; without that property nothing changes.
 * <p>
 * Render thread only.
 */
public final class HandRtLight {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    /** Packed light the hand is redirected to while armed: block 0, sky 0, texel (0,0). */
    public static final int SENTINEL_LIGHT = LightCoordsUtil.pack(0, 0);
    /** Texel the emissive resubmission samples (FULL_BRIGHT = block 15, sky 15). It is never tinted. */
    static final int FULL_BRIGHT_TEXEL = 15;
    static final int SIZE = 16;
    static final String DEBUG_PROPERTY = "vkbridge.rt.handLight.debug";

    /** Between the hand pass HEAD and RETURN: the hand's packed light is redirected to the sentinel. */
    private static boolean armed;
    /** Around FeatureRenderDispatcher.prepareFrame of the hand pass only: GameRenderer.lightmap() returns our texture. */
    private static boolean overriding;
    private static boolean failed;
    private static boolean debugParsed;
    private static Integer debugArgb;
    private static DynamicTexture texture;
    private static int uploadedArgb;
    private static String lastPath;

    private HandRtLight() {
    }

    public static boolean armed() {
        return armed;
    }

    public static boolean overriding() {
        return overriding;
    }

    /** The packed light the hand submits with: the sentinel while armed, the vanilla value otherwise. */
    public static int handLight(int vanilla) {
        return armed ? SENTINEL_LIGHT : vanilla;
    }

    /**
     * Parses {@code RRGGBB} into opaque ARGB, or null when it is not exactly six hex digits.
     */
    static Integer parseRgb(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(v.charAt(i), 16) < 0) return null;
        }
        return 0xFF000000 | Integer.parseInt(v, 16);
    }

    /** Colour of every lightmap texel for a given hand light: the tint everywhere, white at FULL_BRIGHT. */
    static int texelColor(int x, int y, int argb) {
        return x == FULL_BRIGHT_TEXEL && y == FULL_BRIGHT_TEXEL ? 0xFFFFFFFF : argb;
    }

    /** Texel coordinate the lightmap shader samples for one packed-light component (0..15 after the shift). */
    static int sampledTexel(int lightComponent) {
        double uv = Math.min(Math.max(lightComponent * 16 / 256.0 + 0.5 / 16, 0.5 / 16), 15.5 / 16);
        return (int) Math.floor(uv * SIZE);
    }

    private static Integer debugColor() {
        if (!debugParsed) {
            debugParsed = true;
            String raw = System.getProperty(DEBUG_PROPERTY);
            if (raw != null) {
                debugArgb = parseRgb(raw);
                if (debugArgb == null) {
                    LOGGER.warn("[VKB-RT] hand_light state=off reason=bad_debug_value value={}", raw);
                } else {
                    LOGGER.info("[VKB-RT] hand_light state=on source=debug color={}",
                        String.format(Locale.ROOT, "%06X", debugArgb & 0xFFFFFF));
                }
            }
        }
        return debugArgb;
    }

    /**
     * Start of a hand pass that really draws (the deferral branch has returned already). Arms the redirect only when a light
     * source is configured and the texture holds its colour.
     */
    public static void beginPass(boolean deferredReplay) {
        armed = false;
        overriding = false;
        if (failed) return;
        Integer argb = debugColor();
        if (argb == null) return;
        try {
            if (texture == null) {
                texture = new DynamicTexture("bedray hand lightmap", SIZE, SIZE, false);
                uploadedArgb = ~argb; // force the first upload
            }
            if (uploadedArgb != argb) {
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        texture.getPixels().setPixel(x, y, texelColor(x, y, argb));
                    }
                }
                texture.upload();
                uploadedArgb = argb;
            }
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("[VKB-RT] hand_light state=off reason=error (vanilla hand lighting for the rest of the session)", e);
            return;
        }
        armed = true;
        String path = deferredReplay ? "deferred" : "inworld";
        if (!path.equals(lastPath)) {
            lastPath = path;
            LOGGER.info("[VKB-RT] hand_light pass path={} armed=1", path);
        }
    }

    public static void endPass() {
        armed = false;
        overriding = false;
    }

    /** Around the hand pass's prepareFrame, where every hand RenderType reads the lightmap. */
    public static void beginPrepare() {
        overriding = armed;
    }

    public static void endPrepare() {
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
        armed = false;
        overriding = false;
    }
}
