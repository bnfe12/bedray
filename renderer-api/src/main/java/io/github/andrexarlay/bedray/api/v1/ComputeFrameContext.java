package io.github.andrexarlay.bedray.api.v1;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/** Borrowed objects, valid only during GraphicsBackend.render. The target layout remains GENERAL. */
public record ComputeFrameContext(VkDevice device, VkCommandBuffer commandBuffer,
                                  long targetImage, int targetFormat,
                                  int width, int height, long frameIndex) {
    public ComputeFrameContext {
        if (device == null || commandBuffer == null || targetImage == 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A compute frame requires a device, command buffer and nonempty image");
        }
    }
}
