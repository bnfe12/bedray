package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * One compute pass of the world renderer that turns the TLAS into the colour image of a frame. The frame renderer owns the
 * world, the output image, the atlases and the denoiser and drives the active pass in this order per frame:
 * {@link #prepare} (pass-owned images), {@link #configure} (push constants from the settings of this pass),
 * {@link #bind} (descriptor set, only rewritten when a view changed), {@link #dispatch}.
 * <p>
 * Implementations: {@link RtRayEffectsPass} (Ray Tracing mode) and {@link RtPathTracerPass} (Path Tracing mode). Each reads only
 * its own settings group (RtSettings), so the two modes stay independent.
 */
public interface RtPass {
    RtMode mode();

    /** Compact list of what the pass computes this frame, for the {@code [VKB-RT] mode=.. passes=..} log line. */
    String describe(RtFrameParams params);

    /** Creates or resizes images owned by the pass (recorded into {@code cmd}). */
    default void prepare(VkCommandBuffer cmd, RtMemory memory, int width, int height) {
    }

    /**
     * Adds the settings of this pass to {@code params} (view mode, flags, sample counts, distances). The renderer has already
     * set the camera, sky, material flags and {@link RtFrameParams#sceneChanged}. {@code denoising}: the NRD gbuffer variant
     * is active.
     */
    void configure(RtFrameParams params, boolean denoising);

    /** Points the descriptor set at the views of this frame ({@code accum} of {@code common} is 0; the pass adds its own). */
    void bind(RtPipeline.Views common);

    void dispatch(VkCommandBuffer cmd, RtFrameParams params, int width, int height);

    /** True when the pipeline writes the NRD G-buffer (bindings 4..8). */
    boolean usesGbuffer();

    /** Resets temporal accumulation history (if any) when settings, presets, or denoisers change. */
    default void resetHistory() {
    }

    /** Waits for the device to go idle and frees the pipeline objects and pass-owned images. */
    void destroy();
}
