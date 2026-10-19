package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;

/**
 * Common abstraction for temporal super resolution upscalers (DLSS 3.7, FSR 3.1, XeSS 1.3).
 */
public interface UpscalerPipeline {

    String getName();

    VulkanRtxConfig.UpscalerType getType();

    boolean isSupported();

    boolean isInitialized();

    void initialize(int renderWidth, int renderHeight, int displayWidth, int displayHeight, VulkanRtxConfig.QualityPreset preset);

    void execute(long colorInputVkImage, long depthInputVkImage, long velocityInputVkImage, long colorOutputVkImage, float jitterX, float jitterY, float sharpness);

    void reset();

    void destroy();

    int getRenderWidth();

    int getRenderHeight();

    int getDisplayWidth();

    int getDisplayHeight();
}
