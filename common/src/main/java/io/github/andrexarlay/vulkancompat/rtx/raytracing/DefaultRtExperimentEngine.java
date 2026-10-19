package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import io.github.andrexarlay.vulkancompat.rt.poc.RtTestPipeline;
import io.github.andrexarlay.vulkancompat.rt.poc.SimpleAccelerationStructureBuilder;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Implementation of {@link RtExperimentEngine} driving Hardware Ray Tracing experiments.
 * <p>
 * Integrates:
 * <ul>
 *   <li>{@link SimpleAccelerationStructureBuilder}: BLAS and TLAS construction for test geometry.</li>
 *   <li>{@link RtTestPipeline}: Ray query compute shader invocation and image overlay.</li>
 * </ul>
 * Zero overhead when hardware ray tracing is disabled.
 */
public class DefaultRtExperimentEngine implements RtExperimentEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/RtExperimentEngine");
    public static DefaultRtExperimentEngine getInstance() {
        return VulkanRtExperimentEngine.getInstance();
    }

    private boolean initialized = false;
    private SimpleAccelerationStructureBuilder asBuilder;
    private RtTestPipeline rtPipeline;
    private boolean structuresBuilt = false;
    private boolean firstDispatchLogged = false;

    @Override
    public boolean isEnabled() {
        return VulkanRtxConfig.enableHardwareRayTracing && VulkanCapabilityChecker.isHardwareRayTracingSupported();
    }

    @Override
    public synchronized void init() {
        if (!isEnabled()) {
            return;
        }
        if (initialized) {
            return;
        }
        LOGGER.info("[RtExperimentEngine] Initializing [EXPERIMENTAL] Hardware Ray Tracing engine...");

        VkDevice vkDevice = VulkanCapabilityChecker.getVkDevice();
        if (vkDevice != null) {
            try {
                if (this.asBuilder == null) {
                    this.asBuilder = new SimpleAccelerationStructureBuilder(vkDevice);
                }
                if (this.rtPipeline == null) {
                    this.rtPipeline = new RtTestPipeline(vkDevice);
                }
                LOGGER.info("[RtExperimentEngine] AS Builder and RT Pipeline initialized.");
            } catch (Throwable t) {
                LOGGER.error("[RtExperimentEngine] Error initializing RT GPU resources: {}", t.getMessage(), t);
            }
        } else {
            LOGGER.debug("[RtExperimentEngine] VkDevice not yet active during early init; lazy setup deferred to first dispatch.");
        }

        initialized = true;
    }

    @Override
    public void buildTestStructure() {
        if (!isEnabled()) {
            return;
        }
        VkDevice vkDevice = VulkanCapabilityChecker.getVkDevice();
        if (vkDevice != null && this.asBuilder == null) {
            try {
                this.asBuilder = new SimpleAccelerationStructureBuilder(vkDevice);
            } catch (Throwable t) {
                LOGGER.error("[RtExperimentEngine] Failed to build test structure: {}", t.getMessage(), t);
            }
        }
        LOGGER.debug("[RtExperimentEngine] buildTestStructure called");
    }

    @Override
    public void dispatchRayQuery() {
        if (!isEnabled()) {
            return;
        }
        LOGGER.debug("[RtExperimentEngine] dispatchRayQuery (no-op without target)");
    }

    @Override
    public void dispatchRayQuery(RenderTarget target) {
        if (!isEnabled() || target == null) {
            return;
        }
        if (!initialized) {
            init();
        }

        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            return;
        }
        VkDevice vkDevice = vulkanDevice.vkDevice();
        if (vkDevice == null) {
            return;
        }

        // Lazy initialization if needed
        if (this.asBuilder == null) {
            try {
                this.asBuilder = new SimpleAccelerationStructureBuilder(vkDevice);
            } catch (Throwable t) {
                LOGGER.error("[RtExperimentEngine] Lazy init asBuilder failed: {}", t.getMessage(), t);
                return;
            }
        }
        if (this.rtPipeline == null) {
            try {
                this.rtPipeline = new RtTestPipeline(vkDevice);
            } catch (Throwable t) {
                LOGGER.error("[RtExperimentEngine] Lazy init rtPipeline failed: {}", t.getMessage(), t);
                return;
            }
        }

        GpuTextureView colorView = target.getColorTextureView();
        if (colorView == null) {
            return;
        }

        long vkImageView = extractVkImageView(colorView);
        if (vkImageView == 0L) {
            LOGGER.debug("[RtExperimentEngine] Could not extract vkImageView from target {}", target);
            return;
        }

        try {
            // Allocate and begin transient command buffer
            VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
            VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
            if (cmd == null) {
                return;
            }

            // Build acceleration structures if not yet constructed
            final boolean buildingNow = !structuresBuilt;
            if (buildingNow) {
                this.asBuilder.buildAll(cmd);
            }

            long vkImage = extractVkImage(target);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Transition target image to VK_IMAGE_LAYOUT_GENERAL for compute storage image access
                if (vkImage != 0L) {
                    VkImageMemoryBarrier.Buffer preBarrier = VkImageMemoryBarrier.calloc(1, stack);
                    preBarrier.get(0).sType$Default()
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .image(vkImage);
                    preBarrier.get(0).subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                    VK10.vkCmdPipelineBarrier(
                        cmd,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0,
                        null,
                        null,
                        preBarrier
                    );
                }

                // Dispatch ray query compute pass
                this.rtPipeline.dispatchTest(cmd, vkImageView, this.asBuilder.getTlasHandle(), target.width, target.height);
                if (!firstDispatchLogged) {
                    firstDispatchLogged = true;
                    LOGGER.info("[RtExperimentEngine] Hardware Ray Tracing: Initialized and completed dispatch ray query pass (target={}x{})",
                        target.width, target.height);
                }

                // Pipeline barrier: compute shader write -> fragment shader read / blit transfer
                if (vkImage != 0L) {
                    VkImageMemoryBarrier.Buffer postBarrier = VkImageMemoryBarrier.calloc(1, stack);
                    postBarrier.get(0).sType$Default()
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT)
                        .image(vkImage);
                    postBarrier.get(0).subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                    VK10.vkCmdPipelineBarrier(
                        cmd,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        null,
                        null,
                        postBarrier
                    );
                } else {
                    VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack);
                    memBarrier.get(0).sType$Default()
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);

                    VK10.vkCmdPipelineBarrier(
                        cmd,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        memBarrier,
                        null,
                        null
                    );
                }
            }

            // End command buffer before execution
            int endErr = VK10.vkEndCommandBuffer(cmd);
            if (endErr != VK10.VK_SUCCESS) {
                LOGGER.error("[RtExperimentEngine] vkEndCommandBuffer failed: {}", endErr);
                return;
            }

            // Sequence the compute pass directly into RenderPearl's frame encoder
            encoder.execute(cmd);
            if (buildingNow) {
                // Marked built only after the build was actually submitted
                this.structuresBuilt = true;
                LOGGER.info("[RtExperimentEngine] Hardware Ray Tracing: Acceleration structures built on GPU (TLAS=0x{})",
                    Long.toHexString(this.asBuilder.getTlasHandle()));
            }
        } catch (Throwable t) {
            LOGGER.error("[RtExperimentEngine] Error executing ray query pass: {}", t.getMessage(), t);
        }
    }

    /**
     * Must be called when the render target passed to {@link #dispatchRayQuery(RenderTarget)} is destroyed or
     * recreated, so the cached descriptor is rewritten for the new image view.
     */
    public synchronized void invalidateTargetBinding() {
        if (rtPipeline != null) {
            rtPipeline.invalidateBinding();
        }
    }

    private static long extractVkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        try {
            Method m = view.getClass().getMethod("vkImageView");
            Object res = m.invoke(view);
            if (res instanceof Long l) {
                return l;
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    private static long extractVkImage(RenderTarget target) {
        try {
            if (target.getColorTexture() instanceof VulkanGpuTexture vulkanTex) {
                return vulkanTex.vkImage();
            }
            if (target.getColorTexture() != null) {
                Method m = target.getColorTexture().getClass().getMethod("vkImage");
                Object res = m.invoke(target.getColorTexture());
                if (res instanceof Long l) {
                    return l;
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    @Override
    public synchronized void cleanup() {
        if (!initialized && asBuilder == null && rtPipeline == null) {
            return;
        }
        LOGGER.info("[RtExperimentEngine] Cleaning up [EXPERIMENTAL] Hardware Ray Tracing engine...");
        // Frames submitted earlier may still reference the acceleration structures, pipeline and descriptors;
        // the component cleanups below also wait, but do it once up-front so destroy order cannot race.
        VkDevice idleDevice = VulkanCapabilityChecker.getVkDevice();
        if (idleDevice != null) {
            VK10.vkDeviceWaitIdle(idleDevice);
        }
        if (asBuilder != null) {
            try {
                asBuilder.cleanup();
            } catch (Throwable t) {
                LOGGER.warn("[RtExperimentEngine] Error cleaning up asBuilder: {}", t.getMessage());
            }
            asBuilder = null;
        }
        if (rtPipeline != null) {
            try {
                rtPipeline.cleanup();
            } catch (Throwable t) {
                LOGGER.warn("[RtExperimentEngine] Error cleaning up rtPipeline: {}", t.getMessage());
            }
            rtPipeline = null;
        }
        structuresBuilt = false;
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public SimpleAccelerationStructureBuilder getAsBuilder() {
        return asBuilder;
    }

    public RtTestPipeline getRtPipeline() {
        return rtPipeline;
    }

    public boolean isStructuresBuilt() {
        return structuresBuilt;
    }
}
