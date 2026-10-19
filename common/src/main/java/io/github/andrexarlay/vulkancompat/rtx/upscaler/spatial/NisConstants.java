package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Java port of {@code NVScalerUpdateConfig} (NVIDIA Image Scaling SDK v1.0.3, {@code NIS_Config.h} lines 154-252, MIT) for
 * SDR input (NISHDRMode::None), whole-texture viewports. Produces the 112-byte {@code NISConfig} block in the member order
 * of the struct (= the push constant block of {@code nis_scaler.comp}). No Minecraft classes.
 */
public final class NisConstants {
    /** sizeof(NISConfig) without the NIS_ALIGNED(256) padding: 28 four-byte members. */
    public static final int BYTES = 28 * 4;
    /** NVScalerUpdateConfig refuses kScaleX/kScaleY outside [0.5, 1]: NIS upscales at most 2x per axis. */
    public static final double MIN_RENDER_SCALE = 0.5;

    private NisConstants() {}

    /** True when NIS accepts the size pair (render scale 50%..100% per axis, NIS_Config.h line 249). */
    public static boolean supports(int inW, int inH, int outW, int outH) {
        if (inW <= 0 || inH <= 0 || outW <= 0 || outH <= 0) {
            return false;
        }
        float sx = inW / (float) outW;
        float sy = inH / (float) outH;
        return sx >= 0.5f && sx <= 1.0f && sy >= 0.5f && sy <= 1.0f;
    }

    /**
     * @param sharpness 0..1 (the SDK slider; 0 is "no extra sharpening", not "off")
     * @throws IllegalArgumentException when the SDK function would return false
     */
    public static ByteBuffer scaler(float sharpness, int inW, int inH, int outW, int outH) {
        if (!supports(inW, inH, outW, outH)) {
            throw new IllegalArgumentException("NIS supports render scale 50%..100% per axis (NIS_Config.h NVScalerUpdateConfig); got "
                + inW + "x" + inH + " -> " + outW + "x" + outH);
        }
        sharpness = Math.max(Math.min(1.0f, sharpness), 0.0f);
        float sharpenSlider = sharpness - 0.5f;
        float maxScale = sharpenSlider >= 0.0f ? 1.25f : 1.75f;
        float minScale = sharpenSlider >= 0.0f ? 1.25f : 1.0f;
        float limitScale = sharpenSlider >= 0.0f ? 1.25f : 1.0f;

        float kDetectRatio = 2 * 1127.f / 1024.f;
        float kDetectThres = 64.0f / 1024.0f;
        float kMinContrastRatio = 2.0f;
        float kMaxContrastRatio = 10.0f;
        float kSharpStartY = 0.45f;
        float kSharpEndY = 0.9f;
        float kSharpStrengthMin = Math.max(0.0f, 0.4f + sharpenSlider * minScale * 1.2f);
        float kSharpStrengthMax = 1.6f + sharpenSlider * maxScale * 1.8f;
        float kSharpLimitMin = Math.max(0.1f, 0.14f + sharpenSlider * limitScale * 0.32f);
        float kSharpLimitMax = 0.5f + sharpenSlider * limitScale * 0.6f;

        float kRatioNorm = 1.0f / (kMaxContrastRatio - kMinContrastRatio);
        float kSharpScaleY = 1.0f / (kSharpEndY - kSharpStartY);
        float kSharpStrengthScale = kSharpStrengthMax - kSharpStrengthMin;
        float kSharpLimitScale = kSharpLimitMax - kSharpLimitMin;

        ByteBuffer b = ByteBuffer.allocate(BYTES).order(ByteOrder.nativeOrder());
        b.putFloat(kDetectRatio).putFloat(kDetectThres).putFloat(kMinContrastRatio).putFloat(kRatioNorm);
        b.putFloat(1.0f).putFloat(1.0f / 255.0f).putFloat(kSharpStartY).putFloat(kSharpScaleY); // kContrastBoost, kEps
        b.putFloat(kSharpStrengthMin).putFloat(kSharpStrengthScale).putFloat(kSharpLimitMin).putFloat(kSharpLimitScale);
        b.putFloat(inW / (float) outW).putFloat(inH / (float) outH);                         // kScaleX, kScaleY
        b.putFloat(1.0f / outW).putFloat(1.0f / outH).putFloat(1.0f / inW).putFloat(1.0f / inH); // kDstNorm, kSrcNorm
        b.putInt(0).putInt(0).putInt(inW).putInt(inH);                                       // input viewport
        b.putInt(0).putInt(0).putInt(outW).putInt(outH);                                     // output viewport
        b.putFloat(0.0f).putFloat(0.0f);                                                     // reserved
        b.flip();
        return b;
    }
}
