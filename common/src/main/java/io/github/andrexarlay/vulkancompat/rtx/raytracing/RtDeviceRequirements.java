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

    public static final String OMM_ID = "Opacity Micromaps";

    /**
     * Optional self-contained group for the cutout-leaf opacity micromaps. Requires synchronization2 for the
     * sync2 micromap-build barrier; drops as a whole on non-RT devices (duplicate feature structs collapse
     * through the engine's value-equal VulkanFeature set).
     */
    public static DeviceRequirement ommRequirement() {
        return new DeviceRequirement(
            OMM_ID,
            Set.of(
                "VK_KHR_acceleration_structure",
                "VK_KHR_ray_query",
                "VK_KHR_buffer_device_address",
                "VK_KHR_deferred_host_operations",
                "VK_EXT_opacity_micromap",
                "VK_KHR_synchronization2"
            ),
            List.of(
                FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT.class, "micromap"),
                FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceSynchronization2FeaturesKHR.class, "synchronization2")
            ),
            0,
            true,
            null
        );
    }

    public static void registerOmm(DeviceFeatureRegistry registry) {
        registry.register(ommRequirement());
    }

    public static final String SLANG_PATH_ID = "Bedray Slang Path Tracing";

    /**
     * Optional group for the AOT Slang path tracer ({@code passes/path_trace.slang}): its SPIR-V declares Int64 next to
     * RayQueryKHR and PhysicalStorageBufferAddresses, and the legacy GLSL path never needed {@code shaderInt64}. Kept
     * separate so a GPU without it loses only the Slang path tracer, never the ray tracing group above. Whether it was
     * kept is read back from the device's enabled feature set (SlangDeviceCapabilities), not assumed.
     */
    public static DeviceRequirement slangPathRequirement() {
        return new DeviceRequirement(
            SLANG_PATH_ID,
            Set.of(
                "VK_KHR_acceleration_structure",
                "VK_KHR_ray_query",
                "VK_KHR_buffer_device_address",
                "VK_KHR_deferred_host_operations"
            ),
            List.of(FeatureRequest.core(FeatureRequest.VK10, "shaderInt64")),
            0,
            true,
            null
        );
    }

    public static void registerSlangPath(DeviceFeatureRegistry registry) {
        registry.register(slangPathRequirement());
    }
}
