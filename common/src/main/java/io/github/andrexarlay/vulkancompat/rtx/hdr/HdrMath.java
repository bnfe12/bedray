package io.github.andrexarlay.vulkancompat.rtx.hdr;

/**
 * Java reference of the colour maths in {@code passes/hdr_output.slang}: SMPTE ST 2084 (PQ) and the BT.709 to BT.2020
 * primaries conversion. The shader cannot run in a unit test, so {@code HdrMathTest} checks this class against known values and
 * checks that the shader source carries the same constants; {@link HdrDump} uses {@link #pqDecode} to measure real frames.
 */
public final class HdrMath {
    public static final double M1 = 0.1593017578125;
    public static final double M2 = 78.84375;
    public static final double C1 = 0.8359375;
    public static final double C2 = 18.8515625;
    public static final double C3 = 18.6875;

    /** Rows of the BT.709 to BT.2020 matrix (linear light), as written in the shader. */
    public static final double[][] REC709_TO_REC2020 = {
        {0.627404, 0.329283, 0.043313},
        {0.069097, 0.919540, 0.011362},
        {0.016391, 0.088013, 0.895595},
    };

    private HdrMath() {}

    /** Inverse EOTF: absolute nits (0..10000) to a code value 0..1. */
    public static double pqEncode(double nits) {
        double y = Math.max(0.0, Math.min(1.0, nits / 10000.0));
        double ym = Math.pow(y, M1);
        return Math.pow((C1 + C2 * ym) / (1.0 + C3 * ym), M2);
    }

    /** EOTF: code value 0..1 to absolute nits. */
    public static double pqDecode(double e) {
        double ep = Math.pow(Math.max(e, 0.0), 1.0 / M2);
        double num = Math.max(ep - C1, 0.0);
        double den = C2 - C3 * ep;
        return 10000.0 * Math.pow(num / den, 1.0 / M1);
    }

    public static double[] rec709ToRec2020(double r, double g, double b) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = REC709_TO_REC2020[i][0] * r + REC709_TO_REC2020[i][1] * g + REC709_TO_REC2020[i][2] * b;
        }
        return out;
    }
}
