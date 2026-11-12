#define VK_NO_PROTOTYPES
#include "core_state.h"

extern "C" {

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_create(
    const BedrayCoreDesc* desc,
    BedrayCore** out_core,
    BedrayError* error) try {
    clear_error(error);
    if (out_core == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "out_core is null");
    }
    *out_core = nullptr;
    if (desc == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "desc is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (desc->instance == 0 || desc->physical_device == 0 || desc->device == 0 || desc->queue == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "instance/physical_device/device/queue must be non-zero");
    }
    if (desc->get_instance_proc_addr == 0 || desc->get_device_proc_addr == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "vkGetInstanceProcAddr/vkGetDeviceProcAddr addresses must be non-zero");
    }
    if (desc->api_version < VK_API_VERSION_1_1) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "Vulkan 1.1 or newer is required (host reported 0x%08x)", desc->api_version);
    }

    std::unique_ptr<BedrayCore> core(new (std::nothrow) BedrayCore());
    if (!core) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate BedrayCore");
    }

    core->instance = reinterpret_cast<VkInstance>(static_cast<uintptr_t>(desc->instance));
    core->physical_device = reinterpret_cast<VkPhysicalDevice>(static_cast<uintptr_t>(desc->physical_device));
    core->device = reinterpret_cast<VkDevice>(static_cast<uintptr_t>(desc->device));
    core->queue = reinterpret_cast<VkQueue>(static_cast<uintptr_t>(desc->queue));
    core->queue_family = desc->queue_family;
    core->api_version = desc->api_version;
    core->get_instance_proc_addr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        static_cast<uintptr_t>(desc->get_instance_proc_addr));
    core->get_device_proc_addr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
        static_cast<uintptr_t>(desc->get_device_proc_addr));

    if (!load_instance(core.get(), core->get_physical_device_properties2, "vkGetPhysicalDeviceProperties2")
        || !load_instance(core.get(), core->get_physical_device_features2, "vkGetPhysicalDeviceFeatures2")
        || !load_instance(core.get(), core->enumerate_device_extension_properties, "vkEnumerateDeviceExtensionProperties")
        || !load_instance(core.get(), core->get_physical_device_memory_properties, "vkGetPhysicalDeviceMemoryProperties")) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "required Vulkan 1.1 instance functions are missing");
    }

    if (!load_device(core.get(), core->create_shader_module, "vkCreateShaderModule")
        || !load_device(core.get(), core->destroy_shader_module, "vkDestroyShaderModule")
        || !load_device(core.get(), core->create_descriptor_set_layout, "vkCreateDescriptorSetLayout")
        || !load_device(core.get(), core->destroy_descriptor_set_layout, "vkDestroyDescriptorSetLayout")
        || !load_device(core.get(), core->create_pipeline_layout, "vkCreatePipelineLayout")
        || !load_device(core.get(), core->destroy_pipeline_layout, "vkDestroyPipelineLayout")
        || !load_device(core.get(), core->create_compute_pipelines, "vkCreateComputePipelines")
        || !load_device(core.get(), core->destroy_pipeline, "vkDestroyPipeline")
        || !load_device(core.get(), core->create_descriptor_pool, "vkCreateDescriptorPool")
        || !load_device(core.get(), core->destroy_descriptor_pool, "vkDestroyDescriptorPool")
        || !load_device(core.get(), core->create_sampler, "vkCreateSampler")
        || !load_device(core.get(), core->destroy_sampler, "vkDestroySampler")
        || !load_device(core.get(), core->cmd_pipeline_barrier, "vkCmdPipelineBarrier")
        || !load_device(core.get(), core->cmd_blit_image, "vkCmdBlitImage")
        || !load_device(core.get(), core->cmd_fill_buffer, "vkCmdFillBuffer")
        || !load_device(core.get(), core->allocate_descriptor_sets, "vkAllocateDescriptorSets")
        || !load_device(core.get(), core->update_descriptor_sets, "vkUpdateDescriptorSets")
        || !load_device(core.get(), core->cmd_bind_pipeline, "vkCmdBindPipeline")
        || !load_device(core.get(), core->cmd_bind_descriptor_sets, "vkCmdBindDescriptorSets")
        || !load_device(core.get(), core->cmd_push_constants, "vkCmdPushConstants")
        || !load_device(core.get(), core->cmd_dispatch, "vkCmdDispatch")) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "required Vulkan device functions are missing");
    }

    // Image/buffer owners used by the internal path-trace pass. The core still
    // creates no device, queue or submission; these are the host device's own
    // entry points.
    if (!load_instance(core.get(), core->device_fns.image_functions.image_format_properties,
            "vkGetPhysicalDeviceImageFormatProperties")
        || !load_device(core.get(), core->device_fns.image_functions.create_image, "vkCreateImage")
        || !load_device(core.get(), core->device_fns.image_functions.destroy_image, "vkDestroyImage")
        || !load_device(core.get(), core->device_fns.image_functions.get_requirements,
            "vkGetImageMemoryRequirements")
        || !load_device(core.get(), core->device_fns.image_functions.allocate_memory, "vkAllocateMemory")
        || !load_device(core.get(), core->device_fns.image_functions.free_memory, "vkFreeMemory")
        || !load_device(core.get(), core->device_fns.image_functions.bind_image_memory,
            "vkBindImageMemory")
        || !load_device(core.get(), core->device_fns.image_functions.create_view, "vkCreateImageView")
        || !load_device(core.get(), core->device_fns.image_functions.destroy_view, "vkDestroyImageView")
        || !load_device(core.get(), core->device_fns.buffer_functions.create_buffer, "vkCreateBuffer")
        || !load_device(core.get(), core->device_fns.buffer_functions.destroy_buffer, "vkDestroyBuffer")
        || !load_device(core.get(), core->device_fns.buffer_functions.get_requirements,
            "vkGetBufferMemoryRequirements")
        || !load_device(core.get(), core->device_fns.buffer_functions.allocate_memory, "vkAllocateMemory")
        || !load_device(core.get(), core->device_fns.buffer_functions.free_memory, "vkFreeMemory")
        || !load_device(core.get(), core->device_fns.buffer_functions.bind_buffer_memory,
            "vkBindBufferMemory")
        || !load_device(core.get(), core->device_fns.buffer_functions.map_memory, "vkMapMemory")
        || !load_device(core.get(), core->device_fns.buffer_functions.unmap_memory, "vkUnmapMemory")
        || !load_device(core.get(), core->device_fns.buffer_functions.flush,
            "vkFlushMappedMemoryRanges")
        || !load_device(core.get(), core->device_fns.buffer_functions.invalidate,
            "vkInvalidateMappedMemoryRanges")) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "required Vulkan image/buffer functions are missing");
    }

    VkPhysicalDeviceProperties2 properties{};
    properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    core->get_physical_device_properties2(core->physical_device, &properties);
    core->properties = properties.properties;

    core->get_physical_device_memory_properties(core->physical_device, &core->memory_properties);

    core->device_fns.physical_device = core->physical_device;
    core->device_fns.device = core->device;
    core->device_fns.memory_properties = core->memory_properties;
    core->device_fns.non_coherent_atom_size = core->properties.limits.nonCoherentAtomSize;
    core->device_fns.generation = next_resource_generation();
    core->device_fns.cmd_pipeline_barrier = core->cmd_pipeline_barrier;
    core->device_fns.cmd_fill_buffer = core->cmd_fill_buffer;

    uint32_t extension_count = 0;
    VkResult result = core->enumerate_device_extension_properties(
        core->physical_device, nullptr, &extension_count, nullptr);
    if (result != VK_SUCCESS) {
        return fail(error, vk_status(result),
            "vkEnumerateDeviceExtensionProperties(count) failed: VkResult=%d", static_cast<int>(result));
    }
    std::vector<VkExtensionProperties> extensions(extension_count);
    if (extension_count > 0) {
        result = core->enumerate_device_extension_properties(
            core->physical_device, nullptr, &extension_count, extensions.data());
        if (result != VK_SUCCESS) {
            return fail(error, vk_status(result),
                "vkEnumerateDeviceExtensionProperties(list) failed: VkResult=%d", static_cast<int>(result));
        }
    }
    std::unordered_set<std::string> extension_names;
    extension_names.reserve(extensions.size());
    for (const VkExtensionProperties& extension : extensions) {
        extension_names.emplace(extension.extensionName);
    }

    uint32_t detected = 0;

    VkPhysicalDeviceFeatures2 core_features{};
    core_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    core->get_physical_device_features2(core->physical_device, &core_features);
    if (core_features.features.shaderInt64 == VK_TRUE) {
        detected |= BEDRAY_CAP_SHADER_INT64;
    }
    if (core_features.features.shaderStorageImageExtendedFormats == VK_TRUE) {
        detected |= BEDRAY_CAP_STORAGE_IMAGE_EXTENDED_FORMATS;
    }

    if (has_extension(extension_names, VK_KHR_RAY_QUERY_EXTENSION_NAME)) {
        VkPhysicalDeviceRayQueryFeaturesKHR features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.rayQuery == VK_TRUE) {
            detected |= BEDRAY_CAP_RAY_QUERY;
        }
    }

    if (has_extension(extension_names, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)) {
        VkPhysicalDeviceAccelerationStructureFeaturesKHR features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.accelerationStructure == VK_TRUE) {
            detected |= BEDRAY_CAP_ACCELERATION_STRUCTURE;
        }
    }

    if (core->api_version >= VK_API_VERSION_1_2
        || has_extension(extension_names, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME)) {
        VkPhysicalDeviceBufferDeviceAddressFeatures features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.bufferDeviceAddress == VK_TRUE) {
            detected |= BEDRAY_CAP_BUFFER_DEVICE_ADDRESS;
        }
    }

    if (core->api_version >= VK_API_VERSION_1_2
        || has_extension(extension_names, VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME)) {
        VkPhysicalDeviceDescriptorIndexingFeatures features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.descriptorBindingPartiallyBound == VK_TRUE) {
            detected |= BEDRAY_CAP_DESCRIPTOR_INDEXING;
        }
    }

    if (core->api_version >= VK_API_VERSION_1_3
        || has_extension(extension_names, VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME)) {
        VkPhysicalDeviceSynchronization2Features features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.synchronization2 == VK_TRUE) {
            detected |= BEDRAY_CAP_SYNCHRONIZATION2;
        }
    }

    if (core->api_version >= VK_API_VERSION_1_2
        || has_extension(extension_names, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
        VkPhysicalDeviceTimelineSemaphoreFeatures features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.timelineSemaphore == VK_TRUE) {
            detected |= BEDRAY_CAP_TIMELINE_SEMAPHORE;
        }
    }

    if (core->api_version >= VK_API_VERSION_1_3
        || has_extension(extension_names, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)) {
        VkPhysicalDeviceDynamicRenderingFeatures features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.dynamicRendering == VK_TRUE) {
            detected |= BEDRAY_CAP_DYNAMIC_RENDERING;
        }
    }

    if (has_extension(extension_names, VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME)) {
        VkPhysicalDeviceOpacityMicromapFeaturesEXT features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPACITY_MICROMAP_FEATURES_EXT;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.micromap == VK_TRUE) {
            detected |= BEDRAY_CAP_OPACITY_MICROMAP;
        }
    }

    if (has_extension(extension_names, VK_EXT_DESCRIPTOR_BUFFER_EXTENSION_NAME)) {
        VkPhysicalDeviceDescriptorBufferFeaturesEXT features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_BUFFER_FEATURES_EXT;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.descriptorBuffer == VK_TRUE) {
            detected |= BEDRAY_CAP_DESCRIPTOR_BUFFER;
        }
    }

    if (has_extension(extension_names, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME)) {
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.rayTracingPipeline) detected |= BEDRAY_CAP_RAY_TRACING_PIPELINE;
    }
    if (has_extension(extension_names, VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME)) {
        VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV features{};
        features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_NV;
        VkPhysicalDeviceFeatures2 query{};
        query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        query.pNext = &features;
        core->get_physical_device_features2(core->physical_device, &query);
        if (features.rayTracingInvocationReorder) detected |= BEDRAY_CAP_INVOCATION_REORDER_NV;
    }

    const uint32_t requested = desc->capabilities;
    const uint32_t missing = requested & ~detected;
    if (missing != 0) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "host requested capabilities 0x%08x but the device does not provide 0x%08x",
            requested, missing);
    }
    core->capabilities = requested;
    if ((requested & BEDRAY_CAP_RAY_TRACING_PIPELINE) != 0)
        core->device_fns.path_shader_stages |= VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;

    *out_core = core.release();
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate core state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception creating core");
}


} // extern "C"
