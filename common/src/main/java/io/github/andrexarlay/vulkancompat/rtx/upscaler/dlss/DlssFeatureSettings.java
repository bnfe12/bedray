package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import java.util.Objects;

/** Immutable active SDK size/mode decision, including CUSTOM's chosen mode; independent of SR feature residency. */
public record DlssFeatureSettings(int renderWidth, int renderHeight, int outputWidth, int outputHeight,
                                  DlssQuality quality, int initEpoch) {
    public DlssFeatureSettings {
        Objects.requireNonNull(quality);
        if (renderWidth <= 0 || renderHeight <= 0 || outputWidth <= 0 || outputHeight <= 0 || initEpoch < 0)
            throw new IllegalArgumentException("invalid active DLSS feature settings");
    }

    public boolean matches(int width, int height, int outWidth, int outHeight) {
        return renderWidth == width && renderHeight == height && outputWidth == outWidth && outputHeight == outHeight;
    }
}
