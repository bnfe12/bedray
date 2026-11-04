package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssFeatureSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;

/** RR consumes the SDK decision that owns this frame's extent, never a startup request or a guessed scale ratio. */
final class RrQualityPolicy {
    private RrQualityPolicy() {}

    static DlssQuality select(int width, int height, int outputWidth, int outputHeight,
                              boolean dlssActive, DlssFeatureSettings active) {
        if (width <= 0 || height <= 0 || outputWidth <= 0 || outputHeight <= 0)
            throw new IllegalArgumentException("RR quality requires positive input/output extents");
        if (width == outputWidth && height == outputHeight) return DlssQuality.DLAA;
        if (!dlssActive) return DlssQuality.QUALITY;
        if (active == null || !active.matches(width, height, outputWidth, outputHeight))
            throw new IllegalStateException("RR quality/extent mismatch requested=" + width + "x" + height
                + " -> " + outputWidth + "x" + outputHeight + " active_dlss=" + active);
        return active.quality();
    }
}
