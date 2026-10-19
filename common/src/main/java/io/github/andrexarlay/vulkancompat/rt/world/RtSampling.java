package io.github.andrexarlay.vulkancompat.rt.world;

/** CPU mirror of the sampling helpers of rt_rays.comp (unit-tested; the shader repeats these expressions). */
public final class RtSampling {
    private RtSampling() {
    }

    /** Cosine-weighted direction in the hemisphere around +Z for u1,u2 in [0,1). */
    public static void cosineHemisphere(float u1, float u2, float[] out) {
        float r = (float) Math.sqrt(u1);
        float phi = 6.28318530718f * u2;
        out[0] = r * (float) Math.cos(phi);
        out[1] = r * (float) Math.sin(phi);
        out[2] = (float) Math.sqrt(Math.max(1f - u1, 0f));
    }

    /** PCG hash used by the shader to derive per-pixel random numbers. */
    public static int pcgHash(int v) {
        int state = v * 747796405 + 0xAC564B05; // 2891336453u
        int word = ((state >>> ((state >>> 28) + 4)) ^ state) * 277803737;
        return (word >>> 22) ^ word;
    }

    /** Duff et al. orthonormal basis: fills t and b around the unit vector n. */
    public static void basisAround(float[] n, float[] t, float[] b) {
        float sgn = n[2] >= 0f ? 1f : -1f;
        float a = -1f / (sgn + n[2]);
        float c = n[0] * n[1] * a;
        t[0] = 1f + sgn * n[0] * n[0] * a; t[1] = sgn * c; t[2] = -sgn * n[0];
        b[0] = c; b[1] = sgn + n[1] * n[1] * a; b[2] = -n[1];
    }
}
