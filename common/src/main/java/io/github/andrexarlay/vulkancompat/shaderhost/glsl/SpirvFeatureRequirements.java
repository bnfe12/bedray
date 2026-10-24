package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the SPIR-V capabilities of compiled pack shaders into the Vulkan device features they need, so a device
 * requirement can ask for exactly what the shaders use (all-or-nothing through the core registry) instead of a guessed list.
 * Capabilities that are core in Vulkan 1.2 without a feature bit map to nothing; a capability this table does not know is
 * reported by number so the caller can refuse the pack honestly.
 */
public final class SpirvFeatureRequirements {
    /** Capabilities that need no feature bit on a Vulkan 1.2 device (Shader, ImageQuery, ...). */
    private static final Set<Integer> NO_FEATURE = Set.of(
        0, 1, 13, 14, 15, 25, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 40, 42, 43, 44, 45, 46, 47, 50, 51, 52, 57,
        4427, 5301, 5303, 5304, 5305);

    private static final Map<Integer, FeatureRequest> FEATURES = new LinkedHashMap<>();
    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

    static {
        core(10, "Float64", FeatureRequest.VK10, "shaderFloat64");
        core(11, "Int64", FeatureRequest.VK10, "shaderInt64");
        core(12, "Int64Atomics", FeatureRequest.VK12, "shaderBufferInt64Atomics");
        core(22, "Int16", FeatureRequest.VK10, "shaderInt16");
        core(9, "Float16", FeatureRequest.VK12, "shaderFloat16");
        core(39, "Int8", FeatureRequest.VK12, "shaderInt8");
        core(27, "StorageImageMultisample", FeatureRequest.VK10, "shaderStorageImageMultisample");
        core(49, "StorageImageExtendedFormats", FeatureRequest.VK10, "shaderStorageImageExtendedFormats");
        core(55, "StorageImageReadWithoutFormat", FeatureRequest.VK10, "shaderStorageImageReadWithoutFormat");
        core(56, "StorageImageWriteWithoutFormat", FeatureRequest.VK10, "shaderStorageImageWriteWithoutFormat");
        core(4433, "StorageBuffer16BitAccess", FeatureRequest.VK11, "storageBuffer16BitAccess");
        core(4434, "UniformAndStorageBuffer16BitAccess", FeatureRequest.VK11, "uniformAndStorageBuffer16BitAccess");
        core(4435, "StoragePushConstant16", FeatureRequest.VK11, "storagePushConstant16");
        core(4448, "StorageBuffer8BitAccess", FeatureRequest.VK12, "storageBuffer8BitAccess");
        core(4449, "UniformAndStorageBuffer8BitAccess", FeatureRequest.VK12, "uniformAndStorageBuffer8BitAccess");
        core(4450, "StoragePushConstant8", FeatureRequest.VK12, "storagePushConstant8");
        core(5302, "RuntimeDescriptorArray", FeatureRequest.VK12, "runtimeDescriptorArray");
        core(5306, "UniformBufferArrayNonUniformIndexing", FeatureRequest.VK12, "shaderUniformBufferArrayNonUniformIndexing");
        core(5307, "SampledImageArrayNonUniformIndexing", FeatureRequest.VK12, "shaderSampledImageArrayNonUniformIndexing");
        core(5308, "StorageBufferArrayNonUniformIndexing", FeatureRequest.VK12, "shaderStorageBufferArrayNonUniformIndexing");
        core(5309, "StorageImageArrayNonUniformIndexing", FeatureRequest.VK12, "shaderStorageImageArrayNonUniformIndexing");
        core(5345, "VulkanMemoryModel", FeatureRequest.VK12, "vulkanMemoryModel");
        core(5346, "VulkanMemoryModelDeviceScope", FeatureRequest.VK12, "vulkanMemoryModelDeviceScope");
        core(5347, "PhysicalStorageBufferAddresses", FeatureRequest.VK12, "bufferDeviceAddress");
        FEATURES.put(4479, FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class, "rayTracingPipeline"));
        NAMES.put(4479, "RayTracingKHR");
        FEATURES.put(4472, FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR.class, "rayQuery"));
        NAMES.put(4472, "RayQueryKHR");
    }

    private static void core(int capability, String name, String struct, String field) {
        FEATURES.put(capability, FeatureRequest.core(struct, field));
        NAMES.put(capability, name);
    }

    /**
     * @param features    feature bits to request, in a stable order, without duplicates
     * @param unknown     capability numbers this table cannot map (the caller must not claim support for them)
     * @param description human-readable capability names that produced the features
     */
    public record Result(List<FeatureRequest> features, Set<Integer> unknown, List<String> description) {
        public Result {
            features = List.copyOf(features);
            unknown = Set.copyOf(unknown);
            description = List.copyOf(description);
        }
    }

    private SpirvFeatureRequirements() {}

    /**
     * @param capabilities        union of the modules' capabilities
     * @param scalarBlockLayout   whether the sources enable {@code GL_EXT_scalar_block_layout} (no SPIR-V capability marks it)
     */
    public static Result of(Set<Integer> capabilities, boolean scalarBlockLayout) {
        Set<FeatureRequest> features = new LinkedHashSet<>();
        Set<Integer> unknown = new TreeSet<>();
        List<String> description = new ArrayList<>();
        for (int capability : new TreeSet<>(capabilities)) {
            FeatureRequest f = FEATURES.get(capability);
            if (f != null) {
                if (features.add(f)) description.add(NAMES.get(capability));
            } else if (!NO_FEATURE.contains(capability)) {
                unknown.add(capability);
            }
        }
        if (scalarBlockLayout && features.add(FeatureRequest.core(FeatureRequest.VK12, "scalarBlockLayout"))) {
            description.add("GL_EXT_scalar_block_layout");
        }
        return new Result(new ArrayList<>(features), unknown, description);
    }
}
