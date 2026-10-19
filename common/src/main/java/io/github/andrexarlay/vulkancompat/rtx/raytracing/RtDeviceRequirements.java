package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;

import java.util.List;
import java.util.Set;

/**
 * Hardware ray tracing device requirement (formerly hard-coded in MixinVulkanFeatureSets). Touches no Minecraft/engine
 * classes, so it is safe to call at any time. The group is registered unconditionally, as before: the engine drops
 * it itself when the GPU lacks an extension/feature, and logs "Enabling optional FeatureSet [Hardware Ray Tracing]" only when kept.
 */
public final class RtDeviceRequirements {
    public static final String ID = "Hardware Ray Tracing";

    private RtDeviceRequirements() {}

    public static DeviceRequirement requirement() {
        return new DeviceRequirement(
            ID,
            Set.of(
                "VK_KHR_acceleration_structure",
                "VK_KHR_ray_query",
                "VK_KHR_buffer_device_address",
                "VK_KHR_deferred_host_operations"
            ),
            List.of(
                FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR.class, "accelerationStructure"),
                FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR.class, "rayQuery"),
                // The engine already chains VkPhysicalDeviceVulkan12Features (apiVersion 1.2). A separate
                // VkPhysicalDeviceBufferDeviceAddressFeatures next to it violates VUID-VkDeviceCreateInfo-pNext-02829,
                // so bufferDeviceAddress goes through the existing Vulkan12 struct.
                FeatureRequest.core(FeatureRequest.VK12, "bufferDeviceAddress")
            ),
            0,
            true,
            null
        );
    }

    public static void register(DeviceFeatureRegistry registry) {
        registry.register(requirement());
    }
}
