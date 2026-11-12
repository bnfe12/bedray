package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Device negotiation for Bedray's ray-generation backend. Each optional group includes its own dependencies,
 * so dropping SER never removes the ordinary ray pipeline or compute ray-query fallback. Registration requests features;
 * the host's enabled feature set remains authoritative for backend selection. The host already requires Vulkan 1.2.
 */
public final class RtRayPipelineDeviceRequirements {
    public static final String RAYGEN_PROPERTY = "bedray.rt.raygen";
    public static final String SER_PROPERTY = "bedray.rt.ser";
    public static final String RAYGEN_ID = "Bedray Ray Generation";
    public static final String SER_ID = "Bedray NV Invocation Reorder";

    private static final Set<String> PIPELINE_EXTENSIONS = Set.of(
        "VK_KHR_ray_tracing_pipeline", "VK_KHR_acceleration_structure", "VK_KHR_ray_query",
        "VK_KHR_buffer_device_address", "VK_KHR_deferred_host_operations");
    private static final List<FeatureRequest> PIPELINE_FEATURES = List.of(
        FeatureRequest.of(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class, "accelerationStructure"),
        FeatureRequest.of(VkPhysicalDeviceRayQueryFeaturesKHR.class, "rayQuery"),
        FeatureRequest.core(FeatureRequest.VK12, "bufferDeviceAddress"),
        FeatureRequest.core(FeatureRequest.VK10, "shaderInt64"),
        FeatureRequest.of(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class, "rayTracingPipeline"));

    private RtRayPipelineDeviceRequirements() {}

    public static DeviceRequirement raygenRequirement() {
        return new DeviceRequirement(RAYGEN_ID, PIPELINE_EXTENSIONS, PIPELINE_FEATURES, 0, true, null);
    }

    public static DeviceRequirement serRequirement() {
        var extensions = new LinkedHashSet<>(PIPELINE_EXTENSIONS);
        extensions.add("VK_NV_ray_tracing_invocation_reorder");
        var features = new ArrayList<>(PIPELINE_FEATURES);
        features.add(FeatureRequest.of(VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.class,
            "rayTracingInvocationReorder"));
        return new DeviceRequirement(SER_ID, extensions, features, 0, true, null);
    }

    /** Requests ordinary ray generation by default. An explicit false disables this request. */
    public static boolean raygenRequested() {
        return Boolean.parseBoolean(System.getProperty(RAYGEN_PROPERTY, "true"));
    }

    public static void register(DeviceFeatureRegistry registry) {
        register(registry, raygenRequested(), Boolean.getBoolean(SER_PROPERTY));
    }

    static void register(DeviceFeatureRegistry registry, boolean raygenRequested, boolean serRequested) {
        if (raygenRequested || serRequested) registry.register(raygenRequirement());
        if (serRequested) registry.register(serRequirement());
    }
}
