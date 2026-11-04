package io.github.andrexarlay.vulkancompat.rt.denoise;

/** The entire failed creation recording must be discarded before a native DLAA fallback is attempted. */
public final class RrFeatureCreationRetry extends RuntimeException {
    public RrFeatureCreationRetry(String message) { super(message); }
}
