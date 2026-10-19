package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * CPU mirror of the lit-mode shading equation of rt_rays.comp (unit-tested; the shader repeats these expressions):
 * <pre>
 *   lit = tonemap(albedo/cardinalShade * ( keyColor * max(n.L, 0) * visibility
 *                    + ambient(sky, day, n.y) * max(skyLight, 0.02) * ao
 *                    + BLOCK_COLOR * blockLight ))
 * </pre>
 * visibility comes from one hard shadow ray towards the sun/moon (terminate on first hit, cutout alpha-tested,
 * translucent surfaces ignored), skyLight/blockLight are the interpolated vertex light levels in 0..1.
 */
public final class RtLighting {
    public static final float[] BLOCK_COLOR = {1.0f, 0.82f, 0.60f};
    public static final float MIN_SKY_LIGHT = 0.02f;

    private RtLighting() {
    }

    /** Ambient scale CPU helper matching {@link RtSky#ambientScale(float)} and rt_common.glsl. */
    public static float ambientScale(float day) {
        return RtSky.ambientScale(day);
    }

    /** One colour channel of the lit equation. */
    public static float channel(float albedo, float keyColor, float ndotl, float visibility, float ambient,
                                float skyLight, float ao, float blockColor, float blockLight) {
        float direct = keyColor * Math.max(ndotl, 0f) * visibility;
        float sky = ambient * Math.max(skyLight, MIN_SKY_LIGHT) * ao;
        return albedo * (direct + sky + blockColor * blockLight);
    }

    /** Offset of the shadow ray origin along the viewer-facing normal; grows with the hit distance t (float precision). */
    public static float shadowBias(float t) {
        return Math.min(0.015f, 0.003f + 0.0001f * t);
    }

    /**
     * Sodium bakes the vanilla face shade (CardinalLighting: up 1.0, down 0.5, north/south 0.8, east/west 0.6; blocks
     * of the Overworld) into the vertex colour: ColorARGB.mulRGB(color, ao) with ao = light * shade. The RT shading
     * divides it out so that the face shade is not applied twice (the hemisphere ambient replaces it). For a unit normal.
     */
    public static float cardinalShade(float nx, float ny, float nz) {
        return ny * ny * (ny > 0f ? 1.0f : 0.5f) + nx * nx * 0.6f + nz * nz * 0.8f;
    }

    /** Narkowicz ACES filmic approximation, input is scene-linear (already multiplied by the exposure). */
    public static float aces(float x) {
        float v = (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f);
        return Math.max(0f, Math.min(1f, v));
    }

    public static final float EXPOSURE = 1.1f;

    /**
     * Vibrant Visuals post-processing / tone mapping:
     * 1. Highlight bloom.
     * 2. Cinematic ACES tonemapping.
     * 3. Vibrance boost with subtle grass (green) and water (cyan/blue) saturation.
     * 4. Soft S-curve contrast enhancement.
     */
    public static float[] applyVibrantVisuals(float r, float g, float b, float exposure) {
        float hr = r * exposure;
        float hg = g * exposure;
        float hb = b * exposure;

        float lumHdr = 0.2126f * hr + 0.7152f * hg + 0.0722f * hb;
        float bloom = Math.max(0.0f, lumHdr - 0.9f) * 0.18f;
        float bhr = hr + bloom * 1.0f;
        float bhg = hg + bloom * 0.96f;
        float bhb = hb + bloom * 0.88f;

        float mr = aces(bhr);
        float mg = aces(bhg);
        float mb = aces(bhb);

        float maxC = Math.max(mr, Math.max(mg, mb));
        float minC = Math.min(mr, Math.min(mg, mb));
        float sat = maxC > 1.0e-4f ? (maxC - minC) / maxC : 0.0f;
        float lum = 0.2126f * mr + 0.7152f * mg + 0.0722f * mb;

        float greenPop = Math.max(0.0f, mg - Math.max(mr, mb));
        float waterPop = Math.max(0.0f, Math.min(mg, mb) - mr * 0.9f);
        float vibrance = 1.0f + 0.15f * (1.0f - sat) + 0.08f * greenPop + 0.08f * waterPop;

        float sr = lum + (mr - lum) * vibrance;
        float sg = lum + (mg - lum) * vibrance;
        float sb = lum + (mb - lum) * vibrance;

        float cr = sCurve(clamp01(sr));
        float cg = sCurve(clamp01(sg));
        float cb = sCurve(clamp01(sb));

        return new float[]{clamp01(cr), clamp01(cg), clamp01(cb)};
    }

    private static float sCurve(float x) {
        // Soft S-curve blend (30% contrast curve, 70% linear) to prevent crushing colors and oversaturation
        float s = x * x * (3.0f - 2.0f * x);
        return x * 0.70f + s * 0.30f;
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}
