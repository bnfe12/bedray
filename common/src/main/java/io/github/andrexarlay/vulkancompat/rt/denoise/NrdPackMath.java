package io.github.andrexarlay.vulkancompat.rt.denoise;

/**
 * CPU mirror of the packing formulas of {@code rt_pack_nrd.comp} (unit-tested; keep both in sync). Formats follow the
 * documentation of NVIDIA NRD 4.17/4.18 (Shaders/NRD.hlsli: _NRD_EncodeNormalRoughness101010, _NRD_LinearToYCoCg,
 * _NRD_YCoCgToLinear, _REBLUR_GetHitDistanceNormalization, _NRD_GetSpecMagicCurve); the code here is written independently.
 */
public final class NrdPackMath {
    public static final float FP16_MAX = 65504.0f;

    // nrd::NormalEncoding
    public static final int NORMAL_RGBA8_UNORM = 0, NORMAL_RGBA8_SNORM = 1, NORMAL_R10G10B10A2_UNORM = 2,
        NORMAL_RGBA16_UNORM = 3, NORMAL_RGBA16_SNORM = 4;
    // nrd::RoughnessEncoding
    public static final int ROUGHNESS_SQ_LINEAR = 0, ROUGHNESS_LINEAR = 1, ROUGHNESS_SQRT_LINEAR = 2;

    private NrdPackMath() {
    }

    /** Normal + roughness as written into IN_NORMAL_ROUGHNESS before the format conversion (xyz = first three channels, w = fourth). */
    public static float[] packNormalRoughness(float nx, float ny, float nz, float roughness, int normalEncoding, int roughnessEncoding) {
        if (roughnessEncoding == ROUGHNESS_SQRT_LINEAR) {
            roughness = (float) Math.sqrt(Math.min(Math.max(roughness, 0f), 1f));
        } else if (roughnessEncoding == ROUGHNESS_SQ_LINEAR) {
            roughness *= roughness;
        }
        if (normalEncoding == NORMAL_R10G10B10A2_UNORM) {
            float[] o = encodeOct(nx, ny, nz, roughness);
            return new float[] {o[0], o[1], o[2], 0f};
        }
        float m = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz)));
        nx /= m;
        ny /= m;
        nz /= m;
        if (normalEncoding == NORMAL_RGBA8_UNORM || normalEncoding == NORMAL_RGBA16_UNORM) {
            nx = nx * 0.5f + 0.5f;
            ny = ny * 0.5f + 0.5f;
            nz = nz * 0.5f + 0.5f;
        }
        return new float[] {nx, ny, nz, roughness};
    }

    /** Octahedral 10+10 bits in xy, roughness magnitude with the sign of n.z in z (all in 0..1 as stored in UNORM). */
    public static float[] encodeOct(float nx, float ny, float nz, float roughness) {
        float l1 = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
        nx /= l1;
        ny /= l1;
        nz /= l1;
        float ry = ny * 0.5f + 0.5f;
        float rx = nx * 0.5f + ry;
        ry -= nx * 0.5f;
        roughness = Math.max(roughness, 1.5f / 512.0f);
        float rz = (nz < 0f ? -roughness : roughness) * 0.5f + 0.5f;
        return new float[] {rx, ry, rz};
    }

    /** Inverse of {@link #encodeOct}: unit normal (xyz) and roughness (w). */
    public static float[] decodeOct(float px, float py, float pz) {
        float t = pz * 2f - 1f;
        float x = px - py;
        float y = px + py - 1f;
        float z = (t < 0f ? -1f : 1f) * (1f - Math.abs(x) - Math.abs(y));
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {x / len, y / len, z / len, Math.abs(t)};
    }

    public static float[] linearToYCoCg(float r, float g, float b) {
        return new float[] {0.25f * r + 0.5f * g + 0.25f * b, 0.5f * r - 0.5f * b, -0.25f * r + 0.5f * g - 0.25f * b};
    }

    public static float[] yCoCgToLinear(float y, float co, float cg) {
        float t = y - cg;
        return new float[] {Math.max(t + co, 0f), Math.max(y + cg, 0f), Math.max(t - co, 0f)};
    }

    public static float specMagicCurve(float roughness, float power) {
        float f = 1.0f - (float) Math.pow(2.0, -200.0 * roughness * roughness);
        return f * (float) Math.pow(Math.min(Math.max(roughness, 0f), 1f), power);
    }

    /** Hit distance normalisation length of REBLUR: (A + |viewZ| * B) * lerp(C, 1, specMagicCurve(roughness, 0.5)). */
    public static float hitDistanceNormalization(float viewZ, float a, float b, float c, float roughness) {
        float k = specMagicCurve(roughness, 0.5f);
        return (a + Math.abs(viewZ) * b) * (c + (1f - c) * k);
    }

    /** The 4 values of IN_DIFF_RADIANCE_HITDIST for REBLUR (diffuse: roughness 1). Invalid input becomes 0. */
    public static float[] packReblurDiffuse(float r, float g, float b, float hitDist, float viewZ, NrdReblurParams p) {
        float[] c = sanitize(r, g, b);
        float dist = (Float.isNaN(hitDist) || Float.isInfinite(hitDist)) ? 0f : Math.max(hitDist, 0f);
        float norm = Math.min(Math.max(dist / hitDistanceNormalization(viewZ, p.hitDistA(), p.hitDistB(), p.hitDistC(), 1.0f), 0f), 1f);
        float[] y = linearToYCoCg(c[0], c[1], c[2]);
        return new float[] {y[0], y[1], y[2], norm};
    }

    /** The 4 values of IN_DIFF_RADIANCE_HITDIST for RELAX: radiance and hit distance in world units. */
    public static float[] packRelaxDiffuse(float r, float g, float b, float hitDist) {
        float[] c = sanitize(r, g, b);
        float dist = (Float.isNaN(hitDist) || Float.isInfinite(hitDist)) ? 0f : Math.min(Math.max(hitDist, 0f), FP16_MAX);
        return new float[] {c[0], c[1], c[2], dist};
    }

    private static float[] sanitize(float r, float g, float b) {
        if (Float.isNaN(r) || Float.isNaN(g) || Float.isNaN(b) || Float.isInfinite(r) || Float.isInfinite(g) || Float.isInfinite(b)) {
            return new float[] {0f, 0f, 0f};
        }
        return new float[] {Math.min(Math.max(r, 0f), FP16_MAX), Math.min(Math.max(g, 0f), FP16_MAX), Math.min(Math.max(b, 0f), FP16_MAX)};
    }
}
