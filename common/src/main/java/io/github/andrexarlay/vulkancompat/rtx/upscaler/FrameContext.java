package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Backend-neutral execution context of one frame for temporal upscalers (XeSS now; FSR and DLSS later).
 * <p>
 * All images are engine textures that the engine keeps in {@code VK_IMAGE_LAYOUT_GENERAL}; a backend that needs another
 * layout must transition before use and transition back to GENERAL afterwards.
 *
 * @param commandBuffer  Transient command buffer being recorded (spliced into the frame with {@code encoder.execute})
 * @param color          Rendered (jittered) world colour at render resolution, RGBA8_UNORM, not HDR
 * @param depth          World depth copy at render resolution, D32_SFLOAT, reverse-Z, DEPTH aspect view
 * @param motionVectors  Motion vectors at render resolution, R16G16_SFLOAT, pixels, previous minus current, jitter free
 * @param output         Output image at target resolution, RGBA8_UNORM, STORAGE usage
 * @param jitterPixelsX  Sub-pixel jitter in render-resolution pixels, [-0.5, 0.5], exactly the value added to the
 *                       projection (as {@code 2 * jitter / renderWidth} in NDC); the backend applies its own sign convention
 * @param jitterPixelsY  same for Y
 * @param renderWidth    Render (input) width in pixels
 * @param renderHeight   Render (input) height in pixels
 * @param targetWidth    Target (output) width in pixels
 * @param targetHeight   Target (output) height in pixels
 * @param scale          Super-resolution scale (target width / render width)
 * @param qualityMode    Name of the quality mode (backend specific, for logs)
 * @param exposureScale  Pre-exposure multiplier (1.0 for LDR input)
 * @param resetHistory   True if temporal history must be discarded (first frame, resize, mode change, teleport)
 */
public record FrameContext(
    VkCommandBuffer commandBuffer,
    ImageRef color,
    ImageRef depth,
    ImageRef motionVectors,
    ImageRef output,
    float jitterPixelsX,
    float jitterPixelsY,
    int renderWidth,
    int renderHeight,
    int targetWidth,
    int targetHeight,
    float scale,
    String qualityMode,
    float exposureScale,
    boolean resetHistory
) {
    /**
     * A Vulkan image with the view the backend should use.
     *
     * @param image      VkImage handle
     * @param view       VkImageView handle (single mip, single layer)
     * @param vkFormat   VkFormat number of the view
     * @param aspectMask VkImageAspectFlags (1 colour, 2 depth)
     * @param width      Width in pixels
     * @param height     Height in pixels
     */
    public record ImageRef(long image, long view, int vkFormat, int aspectMask, int width, int height) {
    }
}
