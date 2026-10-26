package io.github.andrexarlay.bedray.api.v1;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/** Borrowed objects, valid only during GraphicsBackend.render. The target layout remains GENERAL. */
public record ComputeFrameContext(VkDevice device, VkCommandBuffer commandBuffer,
                                  long targetImage, int targetFormat,
                                  int width, int height, long frameIndex, GraphicsBackendRegistry.SceneMode sceneMode,
                                  AtlasFrame atlas, LightingFrame lighting, RasterFrame raster) {
    /** Compatibility constructor for API minor 3 atlas-aware providers. */
    public ComputeFrameContext(VkDevice device, VkCommandBuffer commandBuffer, long targetImage, int targetFormat,
                               int width, int height, long frameIndex, GraphicsBackendRegistry.SceneMode sceneMode,
                               AtlasFrame atlas) {
        this(device,commandBuffer,targetImage,targetFormat,width,height,frameIndex,sceneMode,atlas,null,null);
    }
    /** Compatibility constructor for occupancy-only providers and offscreen callers. */
    public ComputeFrameContext(VkDevice device, VkCommandBuffer commandBuffer, long targetImage, int targetFormat,
                               int width, int height, long frameIndex, GraphicsBackendRegistry.SceneMode sceneMode) {
        this(device, commandBuffer, targetImage, targetFormat, width, height, frameIndex, sceneMode, null);
    }
    /** Compatibility constructor for existing synthetic/offscreen providers. */
    public ComputeFrameContext(VkDevice device, VkCommandBuffer commandBuffer, long targetImage, int targetFormat,
                               int width, int height, long frameIndex) {
        this(device,commandBuffer,targetImage,targetFormat,width,height,frameIndex,GraphicsBackendRegistry.sceneMode());
    }
    public ComputeFrameContext {
        java.util.Objects.requireNonNull(sceneMode);
        if (device == null || commandBuffer == null || targetImage == 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A compute frame requires a device, command buffer and nonempty image");
        }
        if (atlas != null && atlas.deviceAddress() != device.address())
            throw new IllegalArgumentException("Borrowed atlas belongs to another device");
        if (raster != null && (raster.deviceAddress() != device.address() || raster.width() != width || raster.height() != height))
            throw new IllegalArgumentException("Borrowed raster must match the target device and extent");
    }
}
