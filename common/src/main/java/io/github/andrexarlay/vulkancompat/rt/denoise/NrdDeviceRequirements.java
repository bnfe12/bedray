package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;

import java.util.List;
import java.util.Set;

/**
 * Device features the NRD path needs (vkb_nrd.h "Contract for the caller", SPIR-V capabilities of the NRD shaders measured in
 * NRD integration notes): shaderStorageImageReadWithoutFormat, shaderStorageImageWriteWithoutFormat,
 * shaderStorageImageExtendedFormats (all Vulkan 1.0 core features, no extension). Registered ONLY when
 * {@code -Dvkrtx.denoiser=nrd*} is set, so a default run does not change the device. Touches no engine classes.
 */
public final class NrdDeviceRequirements {
    public static final String ID = "VKB NRD";

    private NrdDeviceRequirements() {
    }

    public static DeviceRequirement requirement() {
        return new DeviceRequirement(
            ID,
            Set.of(),
            List.of(
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageReadWithoutFormat"),
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageWriteWithoutFormat"),
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageExtendedFormats")
            ),
            0,
            true,
            null
        );
    }

    /** Adds the group when a denoiser was requested; does nothing (device unchanged) otherwise. */
    public static void register(DeviceFeatureRegistry registry) {
        // explicit -Dvkrtx.denoiser=nrd*, or automatic (no flag): the features are optional Vulkan 1.0 core features, so a
        // device without them just keeps NRD off (Bedray Core logs whether the optional group was enabled)
        if (DenoiserConfig.requested().isNrd() || DenoiserConfig.automatic()) {
            registry.register(requirement());
        }
    }
}
