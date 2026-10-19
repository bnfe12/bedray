package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Isolated interface for upscaler backends.
 * Backends handle low-resolution 3D world upscaling to native target resolution.
 */
public interface IUpscalerBackend {

    /**
     * Initializes the backend for the specified input (render) and output (display) dimensions.
     *
     * @param inW  Low-resolution input width
     * @param inH  Low-resolution input height
     * @param outW Native output width
     * @param outH Native output height
     * @return true if initialization succeeded, false otherwise
     */
    boolean init(int inW, int inH, int outW, int outH);

    /**
     * Executes upscaling from input render target to output render target.
     *
     * @param input  Source low-resolution world render target
     * @param output Destination native-resolution render target
     * @return true if successfully processed, false if fallback is needed
     */
    boolean process(RenderTarget input, RenderTarget output);

    /**
     * Optional process overload accepting camera subpixel jitter offsets.
     */
    default boolean process(RenderTarget input, RenderTarget output, float jitterX, float jitterY) {
        return process(input, output);
    }

    /**
     * Dispatches temporal upscaler execution with full hardware frame context
     * (Vulkan command buffer, native images, motion vectors, depth, and jitter).
     *
     * @param ctx Frame execution context containing Vulkan resources
     * @return true if successfully recorded into command stream, false if fallback is needed
     */
    default boolean dispatch(FrameContext ctx) {
        return false;
    }

    /**
     * Releases any GPU or native resources allocated by this backend.
     */
    void cleanup();

    /**
     * Returns true if required native libraries and hardware are available.
     */
    boolean isAvailable();

    /**
     * Returns true if the backend is currently initialized with valid dimensions.
     */
    boolean isInitialized();
}
