package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerReconfigure;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessQuality;

import java.nio.ByteBuffer;

/**
 * Java-level checks (L2) of the built-in spatial upscalers: the GLSL sources (third-party headers + checked patches) compile
 * with Shaderc, and the constant ports match the SDK formulas. No GPU: NOT evidence that FSR 1 / NIS work in game.
 */
public final class SpatialChecks {
    private SpatialChecks() {}

    public static void runAll() {
        shadersCompile();
        nisCoefficients();
        easuConstants();
        rcasConstants();
        nisConstants();
        customRequests();
    }

    private static void customRequests() {
        UpscalerMode c = UpscalerMode.CUSTOM;
        UpscalerRequest.Request x = UpscalerRequest.build(UpscalerBackend.XESS, c, null, null, null, null, 60);
        check(x.xessQuality() == XessQuality.ULTRA_PERFORMANCE && x.customPercentFor(UpscalerBackend.XESS) == 60,
            "XeSS CUSTOM opens the widest dynamic range (Ultra Performance) with the percent");
        check(x.customPercentFor(UpscalerBackend.DLSS) == 0, "percent belongs to the selected backend only");
        UpscalerRequest.Request q = UpscalerRequest.build(UpscalerBackend.XESS, UpscalerMode.QUALITY, null, null, null, null, 60);
        check(q.customPercent() == 0, "preset mode ignores the stored percent");
        UpscalerRequest.Request n = UpscalerRequest.build(UpscalerBackend.NIS, null, null, null, null, null, 30);
        check(n.customPercent() == 50 && n.spatialMode() == c, "NIS percent clamped to 50 (NVScalerUpdateConfig)");
        UpscalerRequest.Request f1 = UpscalerRequest.build(UpscalerBackend.FSR1, null, null, null, null, UpscalerMode.PERFORMANCE, 70);
        check(f1.customPercent() == 0 && f1.spatialMode() == UpscalerMode.PERFORMANCE, "FSR1 preset");
        check(!x.equals(UpscalerRequest.build(UpscalerBackend.XESS, c, null, null, null, null, 61)), "percent change = new request");
        int[] s = UpscalerReconfigure.customSize(1920, 1080, 50, new int[] {1100, 600}, new int[] {1920, 1080});
        check(s[0] == 1100 && s[1] == 600, "custom size clamped into the library range");
        check(!UpscalerReconfigure.target(XessQuality.QUALITY, 0).equals(UpscalerReconfigure.target(XessQuality.QUALITY, 60)),
            "a custom percent change re-creates the context");
        boolean threw = false;
        try {
            UpscalerRequest.build(UpscalerBackend.DLSS, null, c, null, null, null, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "CUSTOM without a percent is refused");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void shadersCompile() {
        ByteBuffer easu = SpatialComputePass.compile(SpatialShaderSource.easuSource(), "fsr1_easu.comp");
        ByteBuffer rcas = SpatialComputePass.compile(SpatialShaderSource.rcasSource(), "fsr1_rcas.comp");
        ByteBuffer nis = SpatialComputePass.compile(SpatialShaderSource.nisSource(), "nis_scaler.comp");
        check(easu.remaining() > 0 && rcas.remaining() > 0 && nis.remaining() > 0, "spatial shaders must compile to SPIR-V");
        check(easu.getInt(0) == 0x07230203, "EASU SPIR-V magic");
    }

    private static void nisCoefficients() {
        String cfg = SpatialShaderSource.load(SpatialShaderSource.NIS_CONFIG_HEADER);
        float[] scale = SpatialShaderSource.parseCoefficients(cfg, "coef_scale");
        float[] usm = SpatialShaderSource.parseCoefficients(cfg, "coef_usm");
        check(scale.length == 512 && usm.length == 512, "64x8 coefficients each");
        // NIS_Config.h row 0 of coef_scale: {0.0f, 0.0f, 1.0000f, 0.0f, ...}; row 1 starts 0.0029f, -0.0127f
        check(scale[2] == 1.0f && scale[8] == 0.0029f && scale[9] == -0.0127f, "coef_scale rows parsed in order");
        // every scaler phase sums to ~1 (interpolation filter)
        for (int p = 0; p < 64; p++) {
            float s = 0;
            for (int i = 0; i < 8; i++) {
                s += scale[p * 8 + i];
            }
            check(Math.abs(s - 1.0f) < 0.01f, "coef_scale phase " + p + " sums to " + s);
        }
        boolean threw = false;
        try {
            SpatialShaderSource.patchNisScaler("no patterns here");
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check(threw, "a missing patch pattern must throw");
    }

    private static void easuConstants() {
        int[] c = Fsr1Constants.easu(960, 540, 1920, 1080);
        check(Float.intBitsToFloat(c[0]) == 0.5f && Float.intBitsToFloat(c[1]) == 0.5f, "con0.xy = input/output");
        check(Float.intBitsToFloat(c[2]) == -0.25f, "con0.z = 0.5*in/out - 0.5");
        check(Float.intBitsToFloat(c[4]) == 1.0f / 960 && Float.intBitsToFloat(c[7]) == -1.0f / 540, "con1");
        check(Float.intBitsToFloat(c[13]) == 4.0f / 540 && c[14] == 0 && c[15] == 0, "con3");
    }

    private static void rcasConstants() {
        check(Float.intBitsToFloat(Fsr1Constants.rcas(0.0f)[0]) == 1.0f, "0 stops = 1.0");
        check(Float.intBitsToFloat(Fsr1Constants.rcas(1.0f)[0]) == 0.5f, "1 stop = 0.5");
        int half = Fsr1Constants.rcas(1.0f)[1];
        check((half & 0xFFFF) == 0x3800 && (half >>> 16) == 0x3800, "packed halves of 0.5");
        check(Fsr1Constants.stopsForSlider(1.0f) == 0.0f && Fsr1Constants.stopsForSlider(0.5f) == 1.0f, "slider mapping");
    }

    private static void nisConstants() {
        ByteBuffer b = NisConstants.scaler(0.5f, 960, 540, 1920, 1080);
        check(b.remaining() == 112, "NISConfig is 28 x 4 bytes");
        check(Math.abs(b.getFloat(0) - 2 * 1127f / 1024f) < 1e-6f, "kDetectRatio");
        check(b.getFloat(12 * 4) == 0.5f && b.getFloat(13 * 4) == 0.5f, "kScaleX/Y");
        check(b.getInt(20 * 4) == 960 && b.getInt(25 * 4) == 1080, "viewports");
        // sharpness 0.5 -> slider 0: strength min 0.4, max 1.6
        check(Math.abs(b.getFloat(8 * 4) - 0.4f) < 1e-6f && Math.abs(b.getFloat(9 * 4) - 1.2f) < 1e-6f, "sharp strength");
        check(!NisConstants.supports(900, 500, 1920, 1080), "below 50% is refused (NVScalerUpdateConfig)");
        check(NisConstants.supports(1920, 1080, 1920, 1080), "100% is allowed");
    }
}
