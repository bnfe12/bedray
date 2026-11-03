package io.github.andrexarlay.vulkancompat.rtx.upscaler;

/** One frame's trace, world-raster and presentation extents. Frozen before any world commands. */
public record WorldResolutionPlan(int traceWidth, int traceHeight, int worldWidth, int worldHeight,
                                  boolean rayReconstructionOutput) {
    public WorldResolutionPlan {
        if (traceWidth <= 0 || traceHeight <= 0 || worldWidth <= 0 || worldHeight <= 0)
            throw new IllegalArgumentException("non-positive world resolution");
    }

    public static WorldResolutionPlan choose(int inputWidth, int inputHeight, int outputWidth, int outputHeight,
                                             boolean rrReady, boolean dlssActive, boolean framegenOff) {
        boolean combined = rrReady && dlssActive && framegenOff;
        // Full-size raster depth/UI stays with the full-size reconstructed image. Rays still use DLSS's input extent.
        return new WorldResolutionPlan(inputWidth, inputHeight, combined ? outputWidth : inputWidth,
            combined ? outputHeight : inputHeight, combined);
    }
}
