package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import net.minecraft.network.chat.Component;

/**
 * Presentation text for the renderer and compute-scene rows of the Sodium page: the selection labels and the runtime
 * status line. These are pure functions of the selection/scene argument and the backend registry; they hold no page
 * state, so the option builders in {@link VulkanSodiumConfig} call them while building rows (the same split as
 * {@link UpscalerOptionTexts} for the upscaler rows).
 */
final class RendererOptionTexts {
    private RendererOptionTexts() {
    }

    static Component rendererName(VulkanRtxConfig.RendererSelection renderer) {
        if (renderer == VulkanRtxConfig.RendererSelection.HARDWARE_RT) {
            return Component.translatableWithFallback("bedray.renderer.hardware", "Hardware RT / Raster");
        }
        return GraphicsBackendRegistry.backend() == null
            ? Component.translatableWithFallback("bedray.renderer.voxel_missing", "Voxel (included provider unavailable)")
            : Component.translatableWithFallback("bedray.renderer.voxel", "Voxel (Experimental)");
    }

    static Component computeSceneName(GraphicsBackendRegistry.SceneMode scene) {
        return switch (scene) {
            case OFF -> Component.translatableWithFallback("bedray.compute.scene.off", "Hardware / Raster");
            case SYNTHETIC -> Component.translatableWithFallback("bedray.compute.scene.synthetic", "Synthetic diagnostic");
            case LIVE_OPAQUE -> Component.translatableWithFallback("bedray.compute.scene.live", "Live opaque debug");
        };
    }

    static String rendererStatus() {
        if (!VulkanRtxConfig.voxelRendererSelected()) {
            return Component.translatableWithFallback("bedray.renderer.status.hardware", "Hardware / raster renderer selected").getString();
        }
        if (GraphicsBackendRegistry.backend() == null) {
            return Component.translatableWithFallback("bedray.renderer.status.missing", "Unavailable: the included Compute provider is missing or incompatible").getString();
        }
        return GraphicsBackendRegistry.status();
    }
}