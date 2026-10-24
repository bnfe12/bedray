package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Device requirements and properties for Dirt RT ray-tracing pipeline ({@code VK_KHR_ray_tracing_pipeline}).
 * <p>
 * Dirt RT uses the hardware ray-tracing pipeline (raygen, miss, closest-hit, any-hit) rather than ray queries.
 * This class encapsulates:
 * <ul>
 *   <li>The device requirement for {@code VK_KHR_ray_tracing_pipeline} and {@code rayTracingPipeline} feature.</li>
 *   <li>Querying physical device ray tracing properties ({@code shaderGroupHandleSize},
 *       {@code shaderGroupHandleAlignment}, {@code shaderGroupBaseAlignment}).</li>
 *   <li>Late provider registration with Bedray Core's {@link DeviceFeatureRegistry} and
 *       exposure to {@link io.github.andrexarlay.vulkancompat.shaderhost.ShaderHost#extraDeviceExtensions()}.</li>
 * </ul>
 */
public final class DirtRtDeviceRequirements {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/DirtRt");

    public static final String ID = "VKB Dirt RT Ray Tracing Pipeline";
    public static final String LATE_PROVIDER_ID = "bedray.dirtrt_pipeline";
    public static final String EXTENSION_NAME = DirtRtPipelineConfig.REQUIRED_EXTENSION;
    public static final Set<String> DEVICE_EXTENSIONS = Set.of(EXTENSION_NAME);

    // Standard Vulkan default properties expected for ray-tracing pipelines
    public static final int DEFAULT_SHADER_GROUP_HANDLE_SIZE = 32;
    public static final int DEFAULT_SHADER_GROUP_HANDLE_ALIGNMENT = 32;
    public static final int DEFAULT_SHADER_GROUP_BASE_ALIGNMENT = 64;

    private DirtRtDeviceRequirements() {}

    /**
     * Physical device properties relevant to SBT layout and pipeline dispatch.
     *
     * @param shaderGroupHandleSize          Handle size in bytes (typically 32B).
     * @param shaderGroupHandleAlignment     Alignment of handles inside SBT records (typically 32B).
     * @param shaderGroupBaseAlignment       Base alignment of SBT regions in device memory (typically 64B).
     * @param maxRayRecursionDepth           Maximum ray recursion depth supported by hardware.
     * @param maxShaderGroupStride           Maximum stride between shader group records.
     */
    public record DirtRtProperties(
            int shaderGroupHandleSize,
            int shaderGroupHandleAlignment,
            int shaderGroupBaseAlignment,
            int maxRayRecursionDepth,
            int maxShaderGroupStride
    ) {
        public static DirtRtProperties defaults() {
            return new DirtRtProperties(
                    DEFAULT_SHADER_GROUP_HANDLE_SIZE,
                    DEFAULT_SHADER_GROUP_HANDLE_ALIGNMENT,
                    DEFAULT_SHADER_GROUP_BASE_ALIGNMENT,
                    31,
                    4096
            );
        }

        public DirtRtPipelineConfig.SbtLayout toSbtLayout() {
            return new DirtRtPipelineConfig.SbtLayout(
                    shaderGroupHandleSize,
                    shaderGroupHandleAlignment,
                    shaderGroupBaseAlignment
            );
        }

        public boolean isValid() {
            return shaderGroupHandleSize > 0
                    && shaderGroupHandleAlignment > 0
                    && shaderGroupBaseAlignment > 0
                    && (shaderGroupBaseAlignment % shaderGroupHandleAlignment == 0);
        }
    }

    /**
     * Builds the device requirement requesting {@code VK_KHR_ray_tracing_pipeline} and the
     * {@code rayTracingPipeline} boolean feature from {@link VkPhysicalDeviceRayTracingPipelineFeaturesKHR}.
     */
    public static DeviceRequirement requirement() {
        return requirement(() -> Boolean.TRUE);
    }

    /**
     * Builds the device requirement with an optional runtime activation condition.
     */
    public static DeviceRequirement requirement(Supplier<Boolean> condition) {
        return new DeviceRequirement(
                ID,
                DEVICE_EXTENSIONS,
                List.of(
                        FeatureRequest.of(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class, "rayTracingPipeline")
                ),
                0,
                true,
                condition
        );
    }

    /**
     * Requirement built from the compiled pack: the ray-tracing pipeline extension plus every device feature the pack's
     * SPIR-V needs ({@link DirtRtPackProgram.Result#features()}). The acceleration-structure group is repeated here so this
     * group is self-consistent: {@code VK_KHR_ray_tracing_pipeline} depends on it, and an all-or-nothing group must never
     * enable an extension whose dependency another group might have dropped.
     */
    public static DeviceRequirement requirement(List<FeatureRequest> packFeatures) {
        java.util.LinkedHashSet<FeatureRequest> features = new java.util.LinkedHashSet<>();
        features.add(FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR.class, "accelerationStructure"));
        features.add(FeatureRequest.core(FeatureRequest.VK12, "bufferDeviceAddress"));
        features.add(FeatureRequest.of(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class, "rayTracingPipeline"));
        features.addAll(packFeatures);
        return new DeviceRequirement(
                ID,
                Set.of(EXTENSION_NAME, "VK_KHR_acceleration_structure", "VK_KHR_deferred_host_operations",
                    "VK_KHR_buffer_device_address"),
                List.copyOf(features),
                0,
                true,
                null
        );
    }

    /**
     * Unconditionally registers the Dirt RT pipeline requirement into the given Core registry.
     */
    public static void register(DeviceFeatureRegistry registry) {
        register(registry, () -> Boolean.TRUE);
    }

    /**
     * Registers the Dirt RT pipeline requirement with a conditional predicate.
     */
    public static void register(DeviceFeatureRegistry registry, Supplier<Boolean> condition) {
        registry.register(requirement(condition));
        LOGGER.info("[VKB-DIRTRT] Registered device requirement '{}' with extensions {}", ID, DEVICE_EXTENSIONS);
    }

    /**
     * Registers this requirement as a late provider in the device feature registry.
     */
    public static void registerLateProvider(DeviceFeatureRegistry registry) {
        registry.addLateProvider(LATE_PROVIDER_ID, DirtRtDeviceRequirements::register);
    }

    /**
     * Extra device extensions required by Dirt RT in addition to the shared RT core.
     * Compatible with {@link io.github.andrexarlay.vulkancompat.shaderhost.ShaderHost#extraDeviceExtensions()}.
     */
    public static Set<String> extraDeviceExtensions() {
        return DEVICE_EXTENSIONS;
    }

    /**
     * Checks if {@code VK_KHR_ray_tracing_pipeline} is present in the set of device extensions.
     */
    public static boolean isExtensionSupported(Set<String> supportedExtensions) {
        return supportedExtensions != null && supportedExtensions.contains(EXTENSION_NAME);
    }

    /**
     * Queries {@link VkPhysicalDeviceRayTracingPipelineFeaturesKHR} from the physical device.
     */
    public static boolean checkFeatures(VkPhysicalDevice physicalDevice) {
        Objects.requireNonNull(physicalDevice, "physicalDevice must not be null");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtFeatures =
                    VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack);
            rtFeatures.sType(KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR);

            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack);
            features2.sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
            features2.pNext(rtFeatures);

            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features2);
            return rtFeatures.rayTracingPipeline();
        }
    }

    /**
     * Queries {@link VkPhysicalDeviceRayTracingPipelinePropertiesKHR} from the physical device.
     */
    public static DirtRtProperties queryProperties(VkPhysicalDevice physicalDevice) {
        Objects.requireNonNull(physicalDevice, "physicalDevice must not be null");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                    VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack);
            rtProps.sType(KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);

            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack);
            props2.sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
            props2.pNext(rtProps);

            VK11.vkGetPhysicalDeviceProperties2(physicalDevice, props2);

            return new DirtRtProperties(
                    rtProps.shaderGroupHandleSize(),
                    rtProps.shaderGroupHandleAlignment(),
                    rtProps.shaderGroupBaseAlignment(),
                    rtProps.maxRayRecursionDepth(),
                    rtProps.maxShaderGroupStride()
            );
        }
    }

    /**
     * Full support verification: checks that the extension is available and {@code rayTracingPipeline = true}.
     */
    public static boolean isSupported(VkPhysicalDevice physicalDevice, Set<String> supportedExtensions) {
        if (!isExtensionSupported(supportedExtensions)) {
            return false;
        }
        return checkFeatures(physicalDevice);
    }
}
