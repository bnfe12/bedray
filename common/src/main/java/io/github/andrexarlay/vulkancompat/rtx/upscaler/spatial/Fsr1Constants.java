package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

/**
 * Java port of the CPU constant setup of AMD FidelityFX FSR 1 ({@code ffx_fsr1.h}, FidelityFX SDK v2.3.0, MIT):
 * {@code ffxFsrPopulateEasuConstants} (lines 42-90) and {@code FsrRcasCon} (lines 661-672). The values are bit patterns of
 * floats packed into uvec4 exactly as the GPU code reads them ({@code ffxAsFloat(con0.xy)} etc.). No Minecraft classes.
 */
public final class Fsr1Constants {
    private Fsr1Constants() {}

    private static int bits(float f) {
        return Float.floatToRawIntBits(f);
    }

    /**
     * {@code ffxFsrPopulateEasuConstants} for an input image that fills its whole texture (viewport = texture size).
     *
     * @return 16 ints: con0[4], con1[4], con2[4], con3[4]
     */
    public static int[] easu(float inW, float inH, float outW, float outH) {
        return easu(inW, inH, inW, inH, outW, outH);
    }

    public static int[] easu(float viewportW, float viewportH, float inputSizeW, float inputSizeH, float outW, float outH) {
        if (viewportW <= 0 || viewportH <= 0 || inputSizeW <= 0 || inputSizeH <= 0 || outW <= 0 || outH <= 0) {
            throw new IllegalArgumentException("EASU sizes must be positive: viewport=" + viewportW + "x" + viewportH
                + " input=" + inputSizeW + "x" + inputSizeH + " output=" + outW + "x" + outH);
        }
        int[] c = new int[16];
        // con0: output integer position -> viewport pixel position
        c[0] = bits(viewportW * (1.0f / outW));
        c[1] = bits(viewportH * (1.0f / outH));
        c[2] = bits(0.5f * viewportW * (1.0f / outW) - 0.5f);
        c[3] = bits(0.5f * viewportH * (1.0f / outH) - 0.5f);
        // con1: viewport pixel position -> normalized image space, and the first gather4 centre offset
        c[4] = bits(1.0f / inputSizeW);
        c[5] = bits(1.0f / inputSizeH);
        c[6] = bits(1.0f * (1.0f / inputSizeW));
        c[7] = bits(-1.0f * (1.0f / inputSizeH));
        // con2, con3: the other gather4 centres, relative to (0)
        c[8] = bits(-1.0f * (1.0f / inputSizeW));
        c[9] = bits(2.0f * (1.0f / inputSizeH));
        c[10] = bits(1.0f * (1.0f / inputSizeW));
        c[11] = bits(2.0f * (1.0f / inputSizeH));
        c[12] = bits(0.0f * (1.0f / inputSizeW));
        c[13] = bits(4.0f * (1.0f / inputSizeH));
        c[14] = 0;
        c[15] = 0;
        return c;
    }

    /**
     * {@code FsrRcasCon}: {@code sharpness} in stops, 0.0 = maximum sharpening, each +1.0 halves it.
     *
     * @return con[4]: con.x = exp2(-stops) as float bits, con.y = the same as two packed halves (only read by the fp16 paths)
     */
    public static int[] rcas(float stops) {
        if (!(stops >= 0.0f)) {
            throw new IllegalArgumentException("RCAS stops must be >= 0: " + stops);
        }
        float s = (float) Math.pow(2.0, -stops);
        int half = Float.floatToFloat16(s) & 0xFFFF;
        return new int[] {bits(s), half | (half << 16), 0, 0};
    }

    /**
     * Maps the user sharpness slider 0..1 (0 = off is handled by the caller: RCAS is skipped) to RCAS stops. 1.0 -> 0 stops
     * (maximum), 0.5 -> 1 stop, small values -> more stops (weaker). Our mapping, not from AMD: stops = 2 * (1 - s).
     */
    public static float stopsForSlider(float sharpness) {
        float s = Math.max(0.0f, Math.min(1.0f, sharpness));
        return 2.0f * (1.0f - s);
    }
}
