package io.github.andrexarlay.vulkancompat.rtx.raytracing;

/**
 * Experimental Hardware Ray Tracing engine SPI.
 * Provides the skeleton for hardware-accelerated ray tracing experiments (VK_KHR_ray_query).
 * All methods are safe no-ops when hardware ray tracing is disabled or unsupported.
 */
public interface RtExperimentEngine {

    /**
     * Initializes the experimental ray tracing engine if enabled and supported.
     * Safe no-op if {@link #isEnabled()} is false.
     */
    void init();

    /**
     * Builds acceleration structures (BLAS/TLAS test structures).
     * Safe no-op if {@link #isEnabled()} is false.
     */
    void buildTestStructure();

    /**
     * Dispatches ray query pass.
     * Safe no-op if {@link #isEnabled()} is false.
     */
    void dispatchRayQuery();

    /**
     * Dispatches ray query pass into the specified render target.
     * Safe no-op if {@link #isEnabled()} is false.
     */
    default void dispatchRayQuery(com.mojang.blaze3d.pipeline.RenderTarget target) {
        dispatchRayQuery();
    }

    /**
     * Cleans up ray tracing resources.
     */
    void cleanup();

    /**
     * Returns true if hardware ray tracing is enabled and supported by the GPU.
     */
    boolean isEnabled();
}
