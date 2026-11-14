package io.github.andrexarlay.vulkancompat.rt.hand;

/**
 * CPU light estimate for the first-person hand, built from the same sky model the RT shader mirrors ({@code RtSky}) and the
 * vanilla light levels at the player, which stand in for occlusion. It is NOT a traced probe: no ray is cast, so shadows of the
 * scene and coloured bounce light are absent. L2 only until a screenshot shows it in game.
 * <p>
 * Pure functions, render thread publishes the sky snapshot once per RT frame ({@link #publishSky}).
 */
public final class HandRtProbe {
    /** Warm tint of block light (torch-like); the RT emitter colours are not read here. */
    static final float[] BLOCK_TINT = {1.00f, 0.72f, 0.42f};
    /** Share of the key light that reaches a hand with an unknown orientation (average diffuse cosine). */
    static final float KEY_SHARE = 0.35f;
    /** Floor so a hand in a pitch-dark cave stays readable, like vanilla's minimum lightmap. */
    static final float FLOOR = 0.04f;

    private static volatile float[] keyColor = {0f, 0f, 0f};
    private static volatile float[] skyAmbient = {0.3f, 0.3f, 0.3f};
    private static volatile float exposure = 1f;
    private static volatile boolean published;

    private HandRtProbe() {
    }

    /** Called from the RT frame setup: key light colour and the horizon ambient of the sky model (already weather-aware). */
    public static void publishSky(float[] key, float[] horizon, float[] zenith, float appliedExposure) {
        exposure = Float.isFinite(appliedExposure) && appliedExposure > 0f ? appliedExposure : 1f;
        float[] amb = new float[3];
        for (int i = 0; i < 3; i++) amb[i] = 0.5f * (horizon[i] + zenith[i]);
        keyColor = key.clone();
        skyAmbient = amb;
        published = true;
    }

    public static boolean hasSky() {
        return published;
    }

    /** Opaque ARGB of the hand light for packed vanilla block and sky levels (0..15 each), using the published sky. */
    public static int argb(int blockLevel, int skyLevel) {
        return argb(blockLevel, skyLevel, keyColor, skyAmbient, exposure);
    }

    static int argb(int blockLevel, int skyLevel, float[] key, float[] ambient, float exposure) {
        float sky = clamp01(skyLevel / 15f);
        float block = clamp01(blockLevel / 15f);
        float blockPower = block * block * 1.2f;
        int out = 0xFF000000;
        for (int i = 0; i < 3; i++) {
            float c = sky * (ambient[i] + KEY_SHARE * key[i]) + blockPower * BLOCK_TINT[i];
            // Same exposure the traced frame is tone mapped with, so the hand matches the scene brightness (owner screenshot
            // 2026-11-14: the unexposed estimate made the hand near-white on a dim scene).
            c *= exposure;
            c = Math.max(c, FLOOR);
            // Reinhard keeps the HDR sum inside the LDR lightmap; 1 maps to white only asymptotically.
            c = c / (1f + c);
            int v = Math.round(clamp01(c) * 255f);
            out |= v << (16 - 8 * i);
        }
        return out;
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
