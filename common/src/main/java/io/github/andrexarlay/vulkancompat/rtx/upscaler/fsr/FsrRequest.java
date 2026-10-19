package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import java.util.Objects;

/**
 * What a FidelityFX context is created for: quality mode and provider version. Both are bound to the context (render size,
 * provider), so a change of either re-creates it (UpscalerReconfigure compares requests with {@code equals}). Sharpness is
 * a per-dispatch value and is deliberately not part of it.
 */
public record FsrRequest(FsrQuality quality, FsrVersion version) {
    public FsrRequest {
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(version, "version");
    }

    @Override
    public String toString() {
        return quality + "@" + version;
    }
}
