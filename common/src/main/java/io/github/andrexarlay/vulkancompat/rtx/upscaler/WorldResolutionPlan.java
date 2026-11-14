package io.github.andrexarlay.vulkancompat.rtx.upscaler;

/** One frame's trace, world-raster and presentation extents. Frozen before any world commands. */
public record WorldResolutionPlan(int traceWidth, int traceHeight, int worldWidth, int worldHeight,
                                  boolean rayReconstructionOutput) {
    public WorldResolutionPlan {
        if (traceWidth <= 0 || traceHeight <= 0 || worldWidth <= 0 || worldHeight <= 0)
            throw new IllegalArgumentException("non-positive world resolution");
    }

    /** A vendor input size is valid only for the full output extent it was queried for. */
    public static boolean matchesOutput(int outputWidth, int outputHeight, int configuredWidth, int configuredHeight) {
        return outputWidth > 0 && outputHeight > 0 && outputWidth == configuredWidth && outputHeight == configuredHeight;
    }

    public int jitterPhaseCount(int outputWidth, int outputHeight) {
        double upscale = Math.max((double) outputWidth / traceWidth, (double) outputHeight / traceHeight);
        return CameraJitterManager.phaseCountFor(upscale);
    }

    public double rasterScale(int outputWidth, int outputHeight) {
        return Math.min((double) worldWidth / outputWidth, (double) worldHeight / outputHeight);
    }

    public static WorldResolutionPlan choose(int inputWidth, int inputHeight, int outputWidth, int outputHeight,
                                             boolean rrReady, UpscalerBackend backend, boolean spatialSelected) {
        boolean nativeWithoutUpscaler = (backend == null || backend == UpscalerBackend.BILINEAR)
            && !spatialSelected && inputWidth == outputWidth && inputHeight == outputHeight;
        boolean combined = rrReady && (backend == UpscalerBackend.DLSS || nativeWithoutUpscaler);
        // Raster depth/UI stays on RR's unjittered output grid; rays retain their requested input extent.
        return new WorldResolutionPlan(inputWidth, inputHeight, combined ? outputWidth : inputWidth,
            combined ? outputHeight : inputHeight, combined);
    }
}
