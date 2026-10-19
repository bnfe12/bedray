package io.github.andrexarlay.vulkancompat.rt.material;

/**
 * Decoding of LabPBR 1.3 material textures (resource pack {@code _s} and {@code _n} maps) and the texel encoding of our
 * material atlas. Pure Java, unit-tested.
 * <p>
 * Source: LabPBR Material Standard, https://shaderlabs.org/wiki/LabPBR_Material_Standard (version 1.3):
 * <ul>
 *   <li>{@code _s} red = perceptual smoothness (linear), {@code roughness = (1 - smoothness)^2};</li>
 *   <li>{@code _s} green 0..229 = F0 (linear, 229 ~ 90%), 230..237 predefined metals (iron, gold, aluminum, chrome, copper,
 *       lead, platinum, silver; albedo tints the reflection), 255 = metal with albedo as F0; 238..254 treated as metal;</li>
 *   <li>{@code _s} blue = porosity / SSS (not used by our shaders);</li>
 *   <li>{@code _s} alpha = emission 0..254 linear, 255 = no emission;</li>
 *   <li>{@code _n} red/green = normal X/Y in DirectX convention (green points down), Z reconstructed as
 *       {@code sqrt(1 - x^2 - y^2)}; blue = AO (linear, 255 = none); alpha = height.</li>
 * </ul>
 * Material atlas texel (RGBA8, same layout as the block atlas): R = linear roughness, G = f0 (dielectric, 0..229) or 255
 * (metal), B = emission, A = source bits: 255 - 1 (LabPBR {@code _s}) - 2 (LabPBR {@code _n}) - 4 (redstone). Normal atlas texel: the LabPBR {@code _n} texel unchanged;
 * sprites without {@code _n} get {@link #FLAT_NORMAL}.
 */
public final class LabPbr {
    /** Flat normal (x = y = 0), no AO, full height, as RGBA packed little-endian (R in the low byte). */
    public static final int FLAT_NORMAL = pack(128, 128, 255, 255);
    public static final int SOURCE_AUTO = 255;
    public static final int SOURCE_LABPBR = 254;
    /** Source metadata bit identifying redstone sprites for vertex-tinted emissive sampling. */
    public static final int SOURCE_REDSTONE = 4;
    public static final int METAL_G = 255;

    private LabPbr() {
    }

    public static int pack(int r, int g, int b, int a) {
        return (r & 255) | (g & 255) << 8 | (b & 255) << 16 | (a & 255) << 24;
    }

    public static float roughnessFromSmoothness(int smoothness255) {
        float s = smoothness255 / 255f;
        return (1f - s) * (1f - s);
    }

    /** True for green values that mean a metal (230..255). */
    public static boolean isMetal(int green) {
        return green >= 230;
    }

    /** Emission 0..1 from the alpha channel (255 = none). */
    public static float emission(int alpha) {
        return alpha >= 255 ? 0f : alpha / 254f;
    }

    /** Material atlas texel from one LabPBR {@code _s} texel (channels 0..255). */
    public static int materialFromSpecular(int r, int g, int b, int a) {
        int rough = Math.round(roughnessFromSmoothness(r) * 255f);
        int g2 = isMetal(g) ? METAL_G : Math.min(g, 229);
        int emis = Math.round(emission(a) * 255f);
        return pack(rough, g2, emis, SOURCE_LABPBR);
    }

    /** Material atlas texel from an auto-PBR material and a per-pixel emission (already resolved for BRIGHT sprites). */
    public static int materialFromAuto(AutoPbr.Material m, float pixelEmission) {
        int rough = Math.round(clamp01(m.roughness()) * 255f);
        int g = m.metal() ? METAL_G : Math.min(229, Math.round(clamp01(m.f0()) * 255f));
        int emis = Math.round(clamp01(pixelEmission) * 255f);
        return pack(rough, g, emis, SOURCE_AUTO);
    }

    /** Adds a source metadata flag without disturbing the LabPBR specular/normal bits. */
    public static int withSourceFlag(int material, int flag) {
        int flags = 255 - ((material >>> 24) & 255);
        return (material & 0x00FFFFFF) | ((255 - (flags | flag)) << 24);
    }

    /** True when a material atlas texel carries {@code flag} in its source metadata. */
    public static boolean hasSourceFlag(int material, int flag) {
        return ((255 - ((material >>> 24) & 255)) & flag) != 0;
    }

    /** Tangent-space normal (x right, y up, z out) of a {@code _n} texel; the DirectX green is flipped to y up. */
    public static float[] decodeNormal(int r, int g) {
        float x = r / 255f * 2f - 1f;
        float yDown = g / 255f * 2f - 1f;
        float z = (float) Math.sqrt(Math.max(0f, 1f - x * x - yDown * yDown));
        return new float[] {x, -yDown, z};
    }

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
