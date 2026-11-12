#pragma once

// Internal core state shared by the facade (core.cpp) and the split translation
// units (core_create/core_programs/core_render/core_path_trace/core_passes).
// The white-box tests include core.cpp directly and construct these types, so
// they keep the names and global visibility the tests expect. BedrayCore embeds
// BedrayProgram/RecordingUse, so those types are external (one type across TUs);
// the helpers are inline to stay /W4-clean when a TU uses only a subset.

#include "bedray_core.h"
#include "core_internal.h"
#include "ray_generation_pipeline.h"
#include "path_trace_pass.h"
#include "bloom_pass.h"
#include "volumetric_pass.h"
#include "velocity_pass.h"
#if defined(BEDRAY_CORE_FRAME_GRAPH)
#include "frame_graph_owner.h"
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

inline constexpr uint32_t kSpirvMagic = 0x07230203u;

inline uint64_t next_resource_generation() {
    static std::atomic<uint64_t> next{1};
    uint64_t value = next.fetch_add(1, std::memory_order_relaxed);
    if (value == 0) {
        value = next.fetch_add(1, std::memory_order_relaxed);
    }
    return value;
}

struct DescriptorAllocation {
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
};

struct BedrayProgram {
    uint64_t handle = 0;
    std::string name;
    std::string entry_point;
    VkShaderModule module = VK_NULL_HANDLE;
    VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    std::unique_ptr<bedray::core::RayGenerationPipeline> ray_generation;
    std::vector<BedrayDescriptorBinding> bindings;
    std::vector<VkDescriptorPoolSize> pool_sizes;
    uint32_t push_constant_bytes = 0;
    VkShaderStageFlags shader_stage = VK_SHADER_STAGE_COMPUTE_BIT;
    bool retired = false;
    // Only explicitly released recordings enter this bounded cache. In-flight
    // sets remain immutable and never appear here. Fixed storage keeps release
    // allocation-free, including at the C ABI boundary.
    std::array<DescriptorAllocation, 8> available_descriptors{};
    size_t available_descriptor_count = 0;
};

struct RecordingUse {
    VkCommandBuffer command_buffer;
    VkDescriptorPool pool;
    BedrayProgram* program;
    VkDescriptorSet set;
};

struct BedrayCore {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queue_family = 0;
    uint32_t api_version = 0;
    uint32_t capabilities = 0;

    VkPhysicalDeviceProperties properties{};
    VkPhysicalDeviceMemoryProperties memory_properties{};
    std::vector<BedrayProgram*> programs;
    std::vector<RecordingUse> recordings;
    uint64_t next_program_handle = 1;
    VkSampler atlas_sampler = VK_NULL_HANDLE;
    VkSampler linear_sampler = VK_NULL_HANDLE;

    bedray::core::DeviceFns device_fns{};
    std::unique_ptr<bedray::core::PathTracePass> path_trace;
    std::unique_ptr<bedray::core::BloomPass> bloom;
    std::unique_ptr<bedray::core::VolumetricPass> volumetric;
    std::unique_ptr<bedray::core::VelocityPass> velocity;
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    std::unique_ptr<bedray::core::FrameGraphOwner> frame_graph;
#endif

    PFN_vkGetInstanceProcAddr get_instance_proc_addr = nullptr;
    PFN_vkGetDeviceProcAddr get_device_proc_addr = nullptr;

    PFN_vkGetPhysicalDeviceProperties2 get_physical_device_properties2 = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 get_physical_device_features2 = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties enumerate_device_extension_properties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties get_physical_device_memory_properties = nullptr;

    PFN_vkCreateShaderModule create_shader_module = nullptr;
    PFN_vkDestroyShaderModule destroy_shader_module = nullptr;
    PFN_vkCreateDescriptorSetLayout create_descriptor_set_layout = nullptr;
    PFN_vkDestroyDescriptorSetLayout destroy_descriptor_set_layout = nullptr;
    PFN_vkCreatePipelineLayout create_pipeline_layout = nullptr;
    PFN_vkDestroyPipelineLayout destroy_pipeline_layout = nullptr;
    PFN_vkCreateComputePipelines create_compute_pipelines = nullptr;
    PFN_vkDestroyPipeline destroy_pipeline = nullptr;
    PFN_vkCreateDescriptorPool create_descriptor_pool = nullptr;
    PFN_vkDestroyDescriptorPool destroy_descriptor_pool = nullptr;
    PFN_vkCreateSampler create_sampler = nullptr;
    PFN_vkDestroySampler destroy_sampler = nullptr;
    PFN_vkAllocateDescriptorSets allocate_descriptor_sets = nullptr;
    PFN_vkUpdateDescriptorSets update_descriptor_sets = nullptr;
    PFN_vkCmdBindPipeline cmd_bind_pipeline = nullptr;
    PFN_vkCmdBindDescriptorSets cmd_bind_descriptor_sets = nullptr;
    PFN_vkCmdPushConstants cmd_push_constants = nullptr;
    PFN_vkCmdDispatch cmd_dispatch = nullptr;
    PFN_vkCmdPipelineBarrier cmd_pipeline_barrier = nullptr;
    PFN_vkCmdBlitImage cmd_blit_image = nullptr;
    PFN_vkCmdFillBuffer cmd_fill_buffer = nullptr;
};

inline void clear_error(BedrayError* error) {
    if (error == nullptr) {
        return;
    }
    error->status = BEDRAY_OK;
    error->message[0] = '\0';
}

inline BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* format, ...) {
    if (error != nullptr) {
        error->status = status;
        va_list args;
        va_start(args, format);
        std::vsnprintf(error->message, BEDRAY_ERROR_MESSAGE_BYTES, format, args);
        va_end(args);
        error->message[BEDRAY_ERROR_MESSAGE_BYTES - 1] = '\0';
    }
    return status;
}

inline BedrayStatus vk_status(VkResult result) {
    switch (result) {
        case VK_SUCCESS:
            return BEDRAY_OK;
        case VK_ERROR_OUT_OF_HOST_MEMORY:
        case VK_ERROR_OUT_OF_DEVICE_MEMORY:
            return BEDRAY_ERROR_OUT_OF_MEMORY;
        case VK_ERROR_DEVICE_LOST:
            return BEDRAY_ERROR_DEVICE_LOST;
        default:
            return BEDRAY_ERROR_VULKAN;
    }
}

template <typename T>
inline bool load_instance(BedrayCore* core, T& function, const char* name) {
    function = reinterpret_cast<T>(core->get_instance_proc_addr(core->instance, name));
    return function != nullptr;
}

template <typename T>
inline bool load_device(BedrayCore* core, T& function, const char* name) {
    function = reinterpret_cast<T>(core->get_device_proc_addr(core->device, name));
    return function != nullptr;
}

inline bool has_extension(const std::unordered_set<std::string>& extensions, const char* name) {
    return extensions.find(name) != extensions.end();
}

inline bool supported_descriptor_type(uint32_t type) {
    switch (type) {
        case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:
        case VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE:
        case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:
        case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:
        case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:
        case VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR:
            return true;
        default:
            return false;
    }
}

inline bool is_image_type(uint32_t type) {
    return type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
        || type == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
        || type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
}

inline const BedrayDescriptorBinding* find_binding(const BedrayProgram* program, uint32_t binding) {
    for (const BedrayDescriptorBinding& candidate : program->bindings) {
        if (candidate.binding == binding) {
            return &candidate;
        }
    }
    return nullptr;
}

inline void destroy_program(BedrayCore* core, BedrayProgram* program) {
    if (program == nullptr) {
        return;
    }
    for (size_t i = 0; i < program->available_descriptor_count; ++i) {
        core->destroy_descriptor_pool(core->device, program->available_descriptors[i].pool, nullptr);
    }
    if (program->pipeline != VK_NULL_HANDLE) {
        core->destroy_pipeline(core->device, program->pipeline, nullptr);
    }
    program->ray_generation.reset();
    if (program->pipeline_layout != VK_NULL_HANDLE) {
        core->destroy_pipeline_layout(core->device, program->pipeline_layout, nullptr);
    }
    if (program->set_layout != VK_NULL_HANDLE) {
        core->destroy_descriptor_set_layout(core->device, program->set_layout, nullptr);
    }
    if (program->module != VK_NULL_HANDLE) {
        core->destroy_shader_module(core->device, program->module, nullptr);
    }
    delete program;
}

inline void recycle_descriptors(BedrayCore* core, BedrayProgram* program,
    VkDescriptorPool pool, VkDescriptorSet set) noexcept {
    if (pool == VK_NULL_HANDLE) return;
    if (!program->retired && set != VK_NULL_HANDLE
        && program->available_descriptor_count < program->available_descriptors.size()) {
        program->available_descriptors[program->available_descriptor_count++] = {pool, set};
    } else {
        core->destroy_descriptor_pool(core->device, pool, nullptr);
    }
}

inline BedrayProgram* find_program(BedrayCore* core, uint64_t handle) {
    for (BedrayProgram* candidate : core->programs) {
        if (candidate->handle == handle && !candidate->retired) {
            return candidate;
        }
    }
    return nullptr;
}

// Release visits every owner even when one reports a failure. The host calls release exactly once per command buffer,
// so an early return would strand the remaining owners' recordings for the rest of the session.
template <typename... Release>
inline BedrayStatus release_every(Release&&... release) {
    BedrayStatus first = BEDRAY_OK;
    const auto visit = [&first](BedrayStatus status) {
        if (status != BEDRAY_OK && first == BEDRAY_OK) first = status;
    };
    (visit(release()), ...);
    return first;
}