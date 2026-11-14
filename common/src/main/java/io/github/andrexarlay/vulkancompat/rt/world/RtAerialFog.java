package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * Distance haze ("air perspective", the {@code fog_aerial} setting "Distance haze"): host side numbers for
 * {@code volumetric_apply.slang}, which blends the picture of every hit towards the horizon colour by
 * {@code 1 - exp(-sigma * distance)}. Pure functions, unit tested ({@code RtAerialFogTest}).
 * <p>
 * Before this class the setting was written to the frame buffer but no Slang shader read it, so the haze had no visible effect
 * (measured 2026-11-14: {@code fog_aerial} 1.0 and 2.0 gave identical frames).
 */
final class RtAerialFog {
    /** Share of the surface colour that is left at the render distance with the setting at 1.0 (clear air, sun high). */
    static final float TRANSMITTANCE_AT_RENDER_DISTANCE = 0.55f;
    /** Render distances (blocks) the extinction is derived for; keeps a tiny or huge value from giving no haze or a wall. */
    /** Same constant as HAZE_DISTANCE_REF in volumetric_apply.slang. */
    static final float DISTANCE_REF = 128f;
    static final float MIN_RENDER_DISTANCE = 64f;
    static final float MAX_RENDER_DISTANCE = 1024f;

    private RtAerialFog() {
    }

    /**
     * Extinction per block. {@code setting} = fog_aerial (0..2, 1 = default), {@code renderDistanceBlocks} = the loaded radius,
     * {@code rain} in 0..1, {@code keyDirY} = vertical component of the direction towards the key light. Thicker in rain and with
     * a low sun, like {@code volumetricWeather} of the shaft march. 0 when the camera is under water (water fog is separate) or the
     * setting is off.
     */
    static float sigma(float setting, float renderDistanceBlocks, float rain, float keyDirY, boolean cameraInWater) {
        if (cameraInWater || !(setting > 0f)) {
            return 0f;
        }
        float rd = Math.min(Math.max(renderDistanceBlocks, MIN_RENDER_DISTANCE), MAX_RENDER_DISTANCE);
        // the shader's tau = sigma * d * (d / DISTANCE_REF): sigma is chosen so that tau(render distance) gives the share
        float base = (float) -Math.log(TRANSMITTANCE_AT_RENDER_DISTANCE) / (rd * rd / DISTANCE_REF);
        float r = Math.min(Math.max(rain, 0f), 1f);
        return base * Math.min(setting, 2f) * weather(keyDirY, r);
    }

    /** Same shape as {@code volumetricWeather} in volumetric.slang: (1 + 1.2 lowSun)(1 + 1.4 rain). */
    static float weather(float keyDirY, float rain) {
        float x = Math.min(Math.max(keyDirY / 0.45f, 0f), 1f);
        float lowSun = 1f - x * x * (3f - 2f * x);
        return (1f + 1.2f * lowSun) * (1f + 1.4f * rain);
    }

    /** Camera heights (blocks) the 14 bits of the packed word cover: [-64, 576). */
    static final float HEIGHT_MIN = -64f;
    static final float HEIGHT_SPAN = 640f;
    static final int HEIGHT_STEPS = 16383;

    /** Packs the 18-bit haze colour ({@link #packedColor}) with the camera world height into the word the shader reads. */
    static int pack(int color18, double cameraY) {
        double h = (Math.min(Math.max(cameraY, HEIGHT_MIN), HEIGHT_MIN + HEIGHT_SPAN) - HEIGHT_MIN) / HEIGHT_SPAN;
        return (color18 & 0x3FFFF) | (int) Math.round(h * HEIGHT_STEPS) << 18;
    }

    /** Inverse of the height part of {@link #pack}, as the shader decodes it. */
    static float unpackHeight(int packed) {
        return (packed >>> 18) * (HEIGHT_SPAN / HEIGHT_STEPS) + HEIGHT_MIN;
    }

    /**
     * Haze colour in display space, 6 bits per channel packed {@code R | G << 6 | B << 12}: the visible sky colour made paler (air towards the horizon
     * is lighter and less saturated than the zenith) and, with a low sun, tinted by the key light. It follows the sky colour, so
     * it is dark at night and grey in a storm without extra rules.
     */
    static int packedColor(float[] skyColor, float[] keyColor, float keyDirY) {
        float max = Math.max(skyColor[0], Math.max(skyColor[1], skyColor[2]));
        float x = Math.min(Math.max(keyDirY / 0.45f, 0f), 1f);
        float lowSun = 1f - x * x * (3f - 2f * x);
        float keyMax = Math.max(keyColor[0], Math.max(keyColor[1], Math.max(keyColor[2], 1.0e-4f)));
        int packed = 0;
        for (int i = 0; i < 3; i++) {
            float pale = skyColor[i] + (max - skyColor[i]) * 0.45f;
            float tint = max * (keyColor[i] / keyMax);
            float c = pale + (tint - pale) * 0.35f * lowSun;
            packed |= Math.round(Math.min(Math.max(c, 0f), 1f) * 63f) << (6 * i);
        }
        return packed;
    }
}
