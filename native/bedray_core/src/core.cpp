#define VK_NO_PROTOTYPES
#include "bedray_core.h"

#include <vulkan/vulkan_core.h>

#include "path_trace_pass.h"
#include "bloom_pass.h"
#include "particles_pass.h"
#include "sodium_pass.h"
#include "volumetric_pass.h"
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
#include <map>
#include <memory>
#include <new>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

static_assert(sizeof(BedrayError) == 1028, "ABI v2: BedrayError must stay 1028 bytes");
static_assert(sizeof(BedrayAbiInfo) == 32, "ABI v2: BedrayAbiInfo must stay 32 bytes");
static_assert(sizeof(BedrayCoreDesc) == 72, "ABI v2: BedrayCoreDesc must stay 72 bytes");
static_assert(sizeof(BedrayCapabilities) == 56, "ABI v2: BedrayCapabilities must stay 56 bytes");
static_assert(sizeof(BedrayDescriptorBinding) == 16, "ABI v2: BedrayDescriptorBinding must stay 16 bytes");
static_assert(sizeof(BedrayProgramDesc) == 56, "ABI v2: BedrayProgramDesc must stay 56 bytes");
static_assert(sizeof(BedrayResource) == 48, "ABI v2: BedrayResource must stay 48 bytes");
static_assert(sizeof(BedrayFrameDesc) == 64, "ABI v2: BedrayFrameDesc must stay 64 bytes");

static_assert(sizeof(BedrayPathTraceBuffer) == 32, "path-trace ABI: buffer range must stay 32 bytes");
static_assert(sizeof(BedrayPathTraceRange) == 24, "path-trace ABI: entity range must stay 24 bytes");
static_assert(sizeof(BedrayPathTraceScene) == 312, "path-trace ABI: scene must stay 312 bytes");
static_assert(offsetof(BedrayPathTraceScene, sections) == 8, "path-trace ABI: scene.sections offset");
static_assert(offsetof(BedrayPathTraceScene, albedo_view) == 40, "path-trace ABI: scene.albedo_view offset");
static_assert(offsetof(BedrayPathTraceScene, block_materials) == 72, "path-trace ABI: scene.block_materials offset");
static_assert(offsetof(BedrayPathTraceScene, emitters) == 104, "path-trace ABI: scene.emitters offset");
static_assert(offsetof(BedrayPathTraceScene, reservoirs) == 136, "path-trace ABI: scene.reservoirs offset");
static_assert(offsetof(BedrayPathTraceScene, entity_positions) == 168, "path-trace ABI: scene.entity_positions offset");
static_assert(sizeof(BedrayPathTraceCamera) == 416, "path-trace ABI: camera must stay 416 bytes");
static_assert(sizeof(BedrayPathTraceLighting) == 80, "path-trace ABI: lighting must stay 80 bytes");
static_assert(sizeof(BedrayPathTraceAtmosphere) == 80, "path-trace ABI: atmosphere must stay 80 bytes");
static_assert(sizeof(BedrayPathTracePath) == 112, "path-trace ABI: path must stay 112 bytes");
static_assert(sizeof(BedrayPathTraceFrameDesc) == 1072, "path-trace ABI: frame desc must stay 1072 bytes");
static_assert(offsetof(BedrayPathTraceFrameDesc, scene) == 32, "path-trace ABI: frame.scene offset");
static_assert(offsetof(BedrayPathTraceFrameDesc, camera) == 344, "path-trace ABI: frame.camera offset");
static_assert(offsetof(BedrayPathTraceFrameDesc, lighting) == 760, "path-trace ABI: frame.lighting offset");
static_assert(offsetof(BedrayPathTraceFrameDesc, atmosphere) == 840, "path-trace ABI: frame.atmosphere offset");
static_assert(offsetof(BedrayPathTraceFrameDesc, path) == 920, "path-trace ABI: frame.path offset");
static_assert(sizeof(BedrayPathTraceDesc) == 80, "path-trace ABI: configure desc must stay 80 bytes");

namespace {

constexpr uint32_t kSpirvMagic = 0x07230203u;

uint64_t next_resource_generation() {
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
    std::vector<BedrayDescriptorBinding> bindings;
    std::vector<VkDescriptorPoolSize> pool_sizes;
    uint32_t push_constant_bytes = 0;
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

} // namespace

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
    PFN_vkCmdFillBuffer cmd_fill_buffer = nullptr;
};

namespace {

void clear_error(BedrayError* error) {
    if (error == nullptr) {
        return;
    }
    error->status = BEDRAY_OK;
    error->message[0] = '\0';
}

BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* format, ...) {
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

BedrayStatus vk_status(VkResult result) {
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
bool load_instance(BedrayCore* core, T& function, const char* name) {
    function = reinterpret_cast<T>(core->get_instance_proc_addr(core->instance, name));
    return function != nullptr;
}

template <typename T>
bool load_device(BedrayCore* core, T& function, const char* name) {
    function = reinterpret_cast<T>(core->get_device_proc_addr(core->device, name));
    return function != nullptr;
}

bool has_extension(const std::unordered_set<std::string>& extensions, const char* name) {
    return extensions.find(name) != extensions.end();
}

bool supported_descriptor_type(uint32_t type) {
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

bool is_image_type(uint32_t type) {
    return type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
        || type == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
        || type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
}

const BedrayDescriptorBinding* find_binding(const BedrayProgram* program, uint32_t binding) {
    for (const BedrayDescriptorBinding& candidate : program->bindings) {
        if (candidate.binding == binding) {
            return &candidate;
        }
    }
    return nullptr;
}

void destroy_program(BedrayCore* core, BedrayProgram* program) {
    if (program == nullptr) {
        return;
    }
    for (size_t i = 0; i < program->available_descriptor_count; ++i) {
        core->destroy_descriptor_pool(core->device, program->available_descriptors[i].pool, nullptr);
    }
    if (program->pipeline != VK_NULL_HANDLE) {
        core->destroy_pipeline(core->device, program->pipeline, nullptr);
    }
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

void recycle_descriptors(BedrayCore* core, BedrayProgram* program,
    VkDescriptorPool pool, VkDescriptorSet set) noexcept {
    if (pool == VK_NULL_HANDLE) return;
    if (!program->retired && set != VK_NULL_HANDLE
        && program->available_descriptor_count < program->available_descriptors.size()) {
        program->available_descriptors[program->available_descriptor_count++] = {pool, set};
    } else {
        core->destroy_descriptor_pool(core->device, pool, nullptr);
    }
}

BedrayProgram* find_program(BedrayCore* core, uint64_t handle) {
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
BedrayStatus release_every(Release&&... release) {
    BedrayStatus first = BEDRAY_OK;
    const auto visit = [&first](BedrayStatus status) {
        if (status != BEDRAY_OK && first == BEDRAY_OK) first = status;
    };
    (visit(release()), ...);
    return first;
}

} // namespace

namespace bedray::core {

bool program_contract(BedrayCore* core, uint64_t handle, ProgramContract& out) noexcept {
    out = {};
    if (core == nullptr || handle == 0) {
        return false;
    }
    BedrayProgram* program = find_program(core, handle);
    if (program == nullptr) {
        return false;
    }
    out.push_constant_bytes = program->push_constant_bytes;
    out.binding_count = static_cast<uint32_t>(program->bindings.size());
    return true;
}

bool program_has_binding(BedrayCore* core, uint64_t handle,
    uint32_t binding, uint32_t descriptor_type) noexcept {
    if (core == nullptr || handle == 0) {
        return false;
    }
    BedrayProgram* program = find_program(core, handle);
    if (program == nullptr) {
        return false;
    }
    const BedrayDescriptorBinding* declared = find_binding(program, binding);
    return declared != nullptr && declared->descriptor_type == descriptor_type;
}

} // namespace bedray::core

extern "C" {

BEDRAY_API uint32_t BEDRAY_CALL bedray_core_abi_version(void) try {
    return BEDRAY_CORE_ABI_VERSION;
} catch (...) {
    return 0;
}

BEDRAY_API uint32_t BEDRAY_CALL bedray_core_path_trace_feature_mask(void) try {
    return BEDRAY_PATH_TRACE_FEATURE_NATIVE_DI | BEDRAY_PATH_TRACE_FEATURE_GUIDE_BINDINGS | BEDRAY_PATH_TRACE_FEATURE_GUIDE_OUTPUTS | BEDRAY_PATH_TRACE_FEATURE_NRD_MOTION;
} catch (...) {
    return 0;
}

BEDRAY_API void BEDRAY_CALL bedray_core_abi_info(BedrayAbiInfo* out_info) try {
    if (out_info == nullptr) {
        return;
    }
    out_info->abi_version = BEDRAY_CORE_ABI_VERSION;
    out_info->sizeof_core_desc = static_cast<uint32_t>(sizeof(BedrayCoreDesc));
    out_info->sizeof_capabilities = static_cast<uint32_t>(sizeof(BedrayCapabilities));
    out_info->sizeof_program_desc = static_cast<uint32_t>(sizeof(BedrayProgramDesc));
    out_info->sizeof_frame_desc = static_cast<uint32_t>(sizeof(BedrayFrameDesc));
    out_info->sizeof_resource = static_cast<uint32_t>(sizeof(BedrayResource));
    out_info->sizeof_error = static_cast<uint32_t>(sizeof(BedrayError));
    out_info->reserved = 0;
} catch (...) {
    // Legacy void ABI cannot report a status; never unwind into the host.
}

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

    const uint32_t requested = desc->capabilities;
    const uint32_t missing = requested & ~detected;
    if (missing != 0) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "host requested capabilities 0x%08x but the device does not provide 0x%08x",
            requested, missing);
    }
    core->capabilities = requested;

    *out_core = core.release();
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate core state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception creating core");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy(BedrayCore* core) try {
    if (core == nullptr) {
        return BEDRAY_ERROR_INVALID_ARGUMENT;
    }
    if (!core->recordings.empty() || (core->bloom && core->bloom->has_recordings())
        || (core->volumetric && core->volumetric->has_recordings())
        || (core->path_trace && core->path_trace->has_recordings())) return BEDRAY_ERROR_IN_USE;
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    if (core->frame_graph && core->frame_graph->has_recordings()) return BEDRAY_ERROR_IN_USE;
#endif
    if (core->volumetric) {
        const auto status = core->volumetric->destroy();
        if (status != BEDRAY_OK) return status;
        core->volumetric.reset();
    }
    if (core->bloom) {
        const BedrayStatus status = core->bloom->destroy();
        if (status != BEDRAY_OK) return status;
        core->bloom.reset();
    }
    if (core->path_trace) {
        const BedrayStatus status = core->path_trace->destroy();
        if (status != BEDRAY_OK) return status;
        core->path_trace.reset();
    }
    for (BedrayProgram* program : core->programs) {
        destroy_program(core, program);
    }
    core->programs.clear();
    if (core->atlas_sampler != VK_NULL_HANDLE)
        core->destroy_sampler(core->device, core->atlas_sampler, nullptr);
    if (core->linear_sampler != VK_NULL_HANDLE)
        core->destroy_sampler(core->device, core->linear_sampler, nullptr);
    delete core;
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

static BedrayStatus acquire_sampler(
    BedrayCore* core, bool linear, uint64_t* out_sampler, BedrayError* error) try {
    clear_error(error);
    if (out_sampler != nullptr) *out_sampler = 0;
    if (core == nullptr || out_sampler == nullptr)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/out_sampler is null");
    VkSampler& cached = linear ? core->linear_sampler : core->atlas_sampler;
    if (cached == VK_NULL_HANDLE) {
        VkSamplerCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        info.magFilter = info.minFilter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        info.addressModeU = info.addressModeV = info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        info.maxAnisotropy = 1.0f;
        info.compareOp = VK_COMPARE_OP_NEVER;
        // Zero LOD range, normalized coordinates, no comparison/anisotropy.
        VkSampler sampler = VK_NULL_HANDLE;
        VkResult result = core->create_sampler(core->device, &info, nullptr, &sampler);
        if (result != VK_SUCCESS)
            return fail(error, vk_status(result), "vkCreateSampler failed: VkResult=%d", static_cast<int>(result));
        cached = sampler;
    }
    *out_sampler = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(cached));
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate sampler state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception creating sampler");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_atlas_sampler(
    BedrayCore* core, uint64_t* out_sampler, BedrayError* error) try {
    return acquire_sampler(core, false, out_sampler, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate atlas sampler state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception acquiring atlas sampler");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_linear_sampler(
    BedrayCore* core, uint64_t* out_sampler, BedrayError* error) try {
    return acquire_sampler(core, true, out_sampler, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate linear sampler state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception acquiring linear sampler");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_query_capabilities(
    BedrayCore* core,
    BedrayCapabilities* out_capabilities) try {
    if (core == nullptr || out_capabilities == nullptr) {
        return BEDRAY_ERROR_INVALID_ARGUMENT;
    }
    uint64_t device_memory = 0;
    for (uint32_t i = 0; i < core->memory_properties.memoryHeapCount; ++i) {
        device_memory += core->memory_properties.memoryHeaps[i].size;
    }
    out_capabilities->abi_version = BEDRAY_CORE_ABI_VERSION;
    out_capabilities->flags = core->capabilities;
    out_capabilities->max_push_constants = core->properties.limits.maxPushConstantsSize;
    out_capabilities->max_compute_work_group_count_x = core->properties.limits.maxComputeWorkGroupCount[0];
    out_capabilities->max_compute_work_group_count_y = core->properties.limits.maxComputeWorkGroupCount[1];
    out_capabilities->max_compute_work_group_count_z = core->properties.limits.maxComputeWorkGroupCount[2];
    out_capabilities->max_compute_work_group_invocations = core->properties.limits.maxComputeWorkGroupInvocations;
    out_capabilities->reserved = 0;
    out_capabilities->max_storage_buffer_range = core->properties.limits.maxStorageBufferRange;
    out_capabilities->max_uniform_buffer_range = core->properties.limits.maxUniformBufferRange;
    out_capabilities->device_memory_bytes = device_memory;
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_register_program(
    BedrayCore* core,
    const BedrayProgramDesc* desc,
    uint64_t* out_program,
    BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || desc == nullptr || out_program == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc/out_program is null");
    }
    *out_program = 0;
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (desc->name == nullptr || desc->name[0] == '\0') {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program name is empty");
    }
    if (desc->entry_point == nullptr || desc->entry_point[0] == '\0') {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "entry point is empty");
    }
    if (desc->spirv == nullptr || desc->spirv_word_count == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "SPIR-V words are empty");
    }
    if (desc->spirv_word_count > (SIZE_MAX / sizeof(uint32_t))) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "SPIR-V word count overflows size_t");
    }
    if (desc->spirv[0] != kSpirvMagic) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "SPIR-V magic is missing");
    }
    if (desc->binding_count > 0 && desc->bindings == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "binding_count > 0 but bindings is null");
    }
    if (desc->push_constant_bytes > core->properties.limits.maxPushConstantsSize) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "push constants %u exceed the device limit %u",
            desc->push_constant_bytes, core->properties.limits.maxPushConstantsSize);
    }
    if ((desc->push_constant_bytes & 3u) != 0u) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "push constant range must be a multiple of four bytes");
    }

    std::vector<BedrayDescriptorBinding> bindings;
    try {
        if (desc->binding_count != 0) {
            bindings.assign(desc->bindings, desc->bindings + desc->binding_count);
        }
    } catch (const std::bad_alloc&) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot copy descriptor bindings");
    }
    std::sort(bindings.begin(), bindings.end(),
        [](const BedrayDescriptorBinding& a, const BedrayDescriptorBinding& b) {
            return a.binding < b.binding;
        });
    for (size_t i = 0; i < bindings.size(); ++i) {
        const BedrayDescriptorBinding& binding = bindings[i];
        if (i > 0 && bindings[i - 1].binding == binding.binding) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "duplicate descriptor binding %u", binding.binding);
        }
        if (binding.descriptor_count != 1) {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: descriptor arrays are not supported in ABI v2", binding.binding);
        }
        if (!supported_descriptor_type(binding.descriptor_type)) {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: descriptor type %u is not supported in ABI v2",
                binding.binding, binding.descriptor_type);
        }
        if ((binding.stage_flags & VK_SHADER_STAGE_COMPUTE_BIT) == 0) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "binding %u: stage flags must include VK_SHADER_STAGE_COMPUTE_BIT", binding.binding);
        }
        if (binding.descriptor_type == VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
            && (core->capabilities & BEDRAY_CAP_ACCELERATION_STRUCTURE) == 0) {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: acceleration structure capability is not enabled", binding.binding);
        }
    }

    auto deleter = [core](BedrayProgram* owned) { destroy_program(core, owned); };
    std::unique_ptr<BedrayProgram, decltype(deleter)> owner(new (std::nothrow) BedrayProgram(), deleter);
    auto* program = owner.get();
    if (program == nullptr) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate program");
    }
    if (core->next_program_handle == 0) {
        return fail(error, BEDRAY_ERROR_INTERNAL, "program handle space exhausted");
    }
    program->handle = core->next_program_handle++;
    program->name = desc->name;
    program->entry_point = desc->entry_point;
    program->bindings = std::move(bindings);
    program->push_constant_bytes = desc->push_constant_bytes;

    VkShaderModuleCreateInfo module_info{};
    module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    module_info.codeSize = static_cast<size_t>(desc->spirv_word_count) * sizeof(uint32_t);
    module_info.pCode = desc->spirv;
    VkResult result = core->create_shader_module(core->device, &module_info, nullptr, &program->module);
    if (result != VK_SUCCESS) {
        const BedrayStatus status = vk_status(result);
        return fail(error, status, "vkCreateShaderModule(%s) failed: VkResult=%d",
            desc->name, static_cast<int>(result));
    }

    std::vector<VkDescriptorSetLayoutBinding> layout_bindings;
    layout_bindings.reserve(program->bindings.size());
    for (const BedrayDescriptorBinding& binding : program->bindings) {
        VkDescriptorSetLayoutBinding layout_binding{};
        layout_binding.binding = binding.binding;
        layout_binding.descriptorType = static_cast<VkDescriptorType>(binding.descriptor_type);
        layout_binding.descriptorCount = binding.descriptor_count;
        layout_binding.stageFlags = binding.stage_flags;
        layout_bindings.push_back(layout_binding);
    }

    if (!layout_bindings.empty()) {
        VkDescriptorSetLayoutCreateInfo layout_info{};
        layout_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layout_info.bindingCount = static_cast<uint32_t>(layout_bindings.size());
        layout_info.pBindings = layout_bindings.data();
        result = core->create_descriptor_set_layout(
            core->device, &layout_info, nullptr, &program->set_layout);
        if (result != VK_SUCCESS) {
            const BedrayStatus status = vk_status(result);
            return fail(error, status, "vkCreateDescriptorSetLayout(%s) failed: VkResult=%d",
                desc->name, static_cast<int>(result));
        }
    }

    VkPushConstantRange push_range{};
    push_range.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push_range.offset = 0;
    push_range.size = program->push_constant_bytes;
    VkPipelineLayoutCreateInfo pipeline_layout_info{};
    pipeline_layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    if (program->set_layout != VK_NULL_HANDLE) {
        pipeline_layout_info.setLayoutCount = 1;
        pipeline_layout_info.pSetLayouts = &program->set_layout;
    }
    if (program->push_constant_bytes > 0) {
        pipeline_layout_info.pushConstantRangeCount = 1;
        pipeline_layout_info.pPushConstantRanges = &push_range;
    }
    result = core->create_pipeline_layout(
        core->device, &pipeline_layout_info, nullptr, &program->pipeline_layout);
    if (result != VK_SUCCESS) {
        const BedrayStatus status = vk_status(result);
        return fail(error, status, "vkCreatePipelineLayout(%s) failed: VkResult=%d",
            desc->name, static_cast<int>(result));
    }

    if (program->set_layout != VK_NULL_HANDLE) {
        std::map<uint32_t, uint32_t> pool_sizes;
        for (const BedrayDescriptorBinding& binding : program->bindings) {
            pool_sizes[binding.descriptor_type] += binding.descriptor_count;
        }
        program->pool_sizes.reserve(pool_sizes.size());
        for (const auto& entry : pool_sizes) {
            VkDescriptorPoolSize size{};
            size.type = static_cast<VkDescriptorType>(entry.first);
            size.descriptorCount = entry.second;
            program->pool_sizes.push_back(size);
        }
    }

    VkComputePipelineCreateInfo pipeline_info{};
    pipeline_info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeline_info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeline_info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeline_info.stage.module = program->module;
    pipeline_info.stage.pName = program->entry_point.c_str();
    pipeline_info.layout = program->pipeline_layout;
    result = core->create_compute_pipelines(
        core->device, VK_NULL_HANDLE, 1, &pipeline_info, nullptr, &program->pipeline);
    if (result != VK_SUCCESS) {
        const BedrayStatus status = vk_status(result);
        return fail(error, status, "vkCreateComputePipelines(%s) failed: VkResult=%d",
            desc->name, static_cast<int>(result));
    }

    try {
        core->programs.push_back(program);
    } catch (const std::bad_alloc&) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot store program");
    }
    *out_program = program->handle;
    owner.release();
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate program state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception registering program");
}

BEDRAY_API void BEDRAY_CALL bedray_core_destroy_program(
    BedrayCore* core,
    uint64_t program_handle) try {
    if (core == nullptr || program_handle == 0) {
        return;
    }
    BedrayProgram* program = find_program(core, program_handle);
    if (program == nullptr) {
        return;
    }
    program->retired = true;
    const bool in_use = std::any_of(core->recordings.begin(), core->recordings.end(),
        [program](const RecordingUse& use) { return use.program == program; })
        || (core->path_trace && core->path_trace->uses_program(program_handle))
        || (core->bloom && core->bloom->uses_program(program_handle))
        || (core->volumetric && core->volumetric->uses_program(program_handle));
    if (!in_use) {
        core->programs.erase(std::remove(core->programs.begin(), core->programs.end(), program), core->programs.end());
        destroy_program(core, program);
    }
} catch (...) {
    // Legacy void ABI cannot report a status; never unwind into the host.
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_release_recording(
    BedrayCore* core, uint64_t command_buffer) try {
    if (core == nullptr || command_buffer == 0) return BEDRAY_ERROR_INVALID_ARGUMENT;
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    if (core->frame_graph && !core->frame_graph->releasing(command_buffer)) {
        const BedrayStatus released = core->frame_graph->release_proven(command_buffer, nullptr);
        if (released != BEDRAY_OK) return released;
    }
#endif
    const auto command = reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(command_buffer));
    for (auto it = core->recordings.begin(); it != core->recordings.end();) {
        if (it->command_buffer == command) {
            recycle_descriptors(core, it->program, it->pool, it->set);
            it = core->recordings.erase(it);
        } else {
            ++it;
        }
    }
    for (auto it = core->programs.begin(); it != core->programs.end();) {
        BedrayProgram* program = *it;
        const bool in_use = std::any_of(core->recordings.begin(), core->recordings.end(),
            [program](const RecordingUse& use) { return use.program == program; })
            || (core->path_trace && core->path_trace->uses_program(program->handle))
            || (core->bloom && core->bloom->uses_program(program->handle))
            || (core->volumetric && core->volumetric->uses_program(program->handle));
        if (program->retired && !in_use) {
            it = core->programs.erase(it);
            destroy_program(core, program);
        } else {
            ++it;
        }
    }
    return release_every(
        [&] { return core->path_trace ? core->path_trace->release_recording(command_buffer) : BEDRAY_OK; },
        [&] { return core->bloom ? core->bloom->release_recording(command_buffer) : BEDRAY_OK; },
        [&] { return core->volumetric ? core->volumetric->release_recording(command_buffer) : BEDRAY_OK; });
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_discard_recording(
    BedrayCore* core, uint64_t command_buffer) try {
    if (core == nullptr || command_buffer == 0) return BEDRAY_ERROR_INVALID_ARGUMENT;
    if (core->path_trace) core->path_trace->discard_recording(command_buffer);
    if (core->bloom) core->bloom->discard_recording(command_buffer);
    if (core->volumetric) core->volumetric->discard_recording(command_buffer);
    return bedray_core_release_recording(core, command_buffer);
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(
    BedrayCore* core,
    const BedrayFrameDesc* desc,
    BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || desc == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (desc->command_buffer == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "command_buffer is null");
    }
    if (desc->program == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program handle is zero");
    }
    BedrayProgram* program = find_program(core, desc->program);
    if (program == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program handle is not registered");
    }
    if (desc->group_count_x == 0 || desc->group_count_y == 0 || desc->group_count_z == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "dispatch group counts must be non-zero");
    }
    const uint32_t* limits = core->properties.limits.maxComputeWorkGroupCount;
    if (desc->group_count_x > limits[0]
        || desc->group_count_y > limits[1]
        || desc->group_count_z > limits[2]) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "dispatch %ux%ux%u exceeds the device limit %ux%ux%u",
            desc->group_count_x, desc->group_count_y, desc->group_count_z,
            limits[0], limits[1], limits[2]);
    }
    if (desc->push_constant_bytes > program->push_constant_bytes) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "push constants %u exceed the program range %u",
            desc->push_constant_bytes, program->push_constant_bytes);
    }
    if ((desc->push_constant_bytes & 3u) != 0u) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "push constant size must be a multiple of four bytes");
    }
    if (desc->push_constant_bytes > 0 && desc->push_constants == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "push_constants is null");
    }
    if (desc->resource_count > 0 && desc->resources == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "resources is null");
    }
    if (desc->resource_count != program->bindings.size()) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "resource count %u does not match program binding count %zu",
            desc->resource_count, program->bindings.size());
    }
    // Validate the whole binding set before changing any descriptor state.
    // A missing binding or duplicate must never reuse a prior frame's value.
    for (uint32_t i = 0; i < desc->resource_count; ++i) {
        for (uint32_t j = 0; j < i; ++j) {
            if (desc->resources[i].binding == desc->resources[j].binding) {
                return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                    "duplicate resource binding %u", desc->resources[i].binding);
            }
        }
    }

    std::vector<VkWriteDescriptorSet> writes;
    std::vector<VkDescriptorBufferInfo> buffer_infos;
    std::vector<VkDescriptorImageInfo> image_infos;
    std::vector<VkWriteDescriptorSetAccelerationStructureKHR> acceleration_infos;
    std::vector<VkAccelerationStructureKHR> acceleration_handles;
    try {
        writes.reserve(desc->resource_count);
        buffer_infos.reserve(desc->resource_count);
        image_infos.reserve(desc->resource_count);
        acceleration_infos.reserve(desc->resource_count);
        acceleration_handles.reserve(desc->resource_count);
    } catch (const std::bad_alloc&) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate descriptor write list");
    }

    VkDescriptorPool dispatch_pool = VK_NULL_HANDLE;
    VkDescriptorSet dispatch_set = VK_NULL_HANDLE;
    auto pool_deleter = [core, program, &dispatch_set](VkDescriptorPool_T* pool) {
        recycle_descriptors(core, program, pool, dispatch_set);
    };
    std::unique_ptr<VkDescriptorPool_T, decltype(pool_deleter)> pool_owner(nullptr, pool_deleter);
    if (program->set_layout != VK_NULL_HANDLE && program->available_descriptor_count > 0) {
        const DescriptorAllocation cached = program->available_descriptors[--program->available_descriptor_count];
        dispatch_pool = cached.pool;
        dispatch_set = cached.set;
        pool_owner.reset(dispatch_pool);
    } else if (program->set_layout != VK_NULL_HANDLE) {
        VkDescriptorPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pool_info.maxSets = 1;
        pool_info.poolSizeCount = static_cast<uint32_t>(program->pool_sizes.size());
        pool_info.pPoolSizes = program->pool_sizes.data();
        VkResult result = core->create_descriptor_pool(core->device, &pool_info, nullptr, &dispatch_pool);
        if (result != VK_SUCCESS) return fail(error, vk_status(result), "vkCreateDescriptorPool failed: VkResult=%d", static_cast<int>(result));
        pool_owner.reset(dispatch_pool);
        VkDescriptorSetAllocateInfo allocate_info{};
        allocate_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate_info.descriptorPool = dispatch_pool;
        allocate_info.descriptorSetCount = 1;
        allocate_info.pSetLayouts = &program->set_layout;
        result = core->allocate_descriptor_sets(core->device, &allocate_info, &dispatch_set);
        if (result != VK_SUCCESS) return fail(error, vk_status(result), "vkAllocateDescriptorSets failed: VkResult=%d", static_cast<int>(result));
    }

    for (uint32_t i = 0; i < desc->resource_count; ++i) {
        const BedrayResource& resource = desc->resources[i];
        const BedrayDescriptorBinding* binding = find_binding(program, resource.binding);
        if (binding == nullptr) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "resource binding %u is not declared by program %s",
                resource.binding, program->name.c_str());
        }
        if (resource.descriptor_type != binding->descriptor_type) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "binding %u: resource type %u does not match program type %u",
                resource.binding, resource.descriptor_type, binding->descriptor_type);
        }
        if (resource.handle == 0) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "binding %u: resource handle is zero", resource.binding);
        }

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = dispatch_set;
        write.dstBinding = resource.binding;
        write.dstArrayElement = 0;
        write.descriptorCount = 1;
        write.descriptorType = static_cast<VkDescriptorType>(resource.descriptor_type);

        if (is_image_type(resource.descriptor_type)) {
            if (resource.image_layout == VK_IMAGE_LAYOUT_UNDEFINED) {
                return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                    "binding %u: image layout is UNDEFINED", resource.binding);
            }
            if (resource.descriptor_type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                && resource.sampler == 0) {
                return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                    "binding %u: combined image sampler has no sampler", resource.binding);
            }
            VkDescriptorImageInfo info{};
            info.sampler = reinterpret_cast<VkSampler>(static_cast<uintptr_t>(resource.sampler));
            info.imageView = reinterpret_cast<VkImageView>(static_cast<uintptr_t>(resource.handle));
            info.imageLayout = static_cast<VkImageLayout>(resource.image_layout);
            image_infos.push_back(info);
            write.pImageInfo = &image_infos.back();
        } else if (resource.descriptor_type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
            || resource.descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) {
            VkDescriptorBufferInfo info{};
            info.buffer = reinterpret_cast<VkBuffer>(static_cast<uintptr_t>(resource.handle));
            info.offset = resource.offset;
            info.range = resource.range;
            if (info.range == 0) {
                info.range = VK_WHOLE_SIZE;
            }
            buffer_infos.push_back(info);
            write.pBufferInfo = &buffer_infos.back();
        } else if (resource.descriptor_type == VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR) {
            if ((core->capabilities & BEDRAY_CAP_ACCELERATION_STRUCTURE) == 0) {
                return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                    "binding %u: acceleration structure capability is not enabled", resource.binding);
            }
            VkAccelerationStructureKHR handle =
                reinterpret_cast<VkAccelerationStructureKHR>(static_cast<uintptr_t>(resource.handle));
            acceleration_handles.push_back(handle);
            VkWriteDescriptorSetAccelerationStructureKHR info{};
            info.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;
            info.accelerationStructureCount = 1;
            info.pAccelerationStructures = &acceleration_handles.back();
            acceleration_infos.push_back(info);
            write.pNext = &acceleration_infos.back();
        } else {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: descriptor type %u is not supported in ABI v2",
                resource.binding, resource.descriptor_type);
        }
        writes.push_back(write);
    }

    VkCommandBuffer command_buffer =
        reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(desc->command_buffer));
    // Register before recording: allocation failure must leave the caller's
    // command buffer untouched. This also retains programs without bindings.
    // Descriptor-free kernels can run for many sections in one command buffer.
    // One retained program reference covers all those dispatches until release;
    // descriptor-bearing dispatches still require one exclusive allocation each.
    const bool already_retained = dispatch_pool == VK_NULL_HANDLE
        && std::any_of(core->recordings.begin(), core->recordings.end(),
            [command_buffer, program](const RecordingUse& use) {
                return use.command_buffer == command_buffer && use.program == program && use.pool == VK_NULL_HANDLE;
            });
    if (!already_retained)
        core->recordings.push_back(RecordingUse{command_buffer, dispatch_pool, program, dispatch_set});
    pool_owner.release();
    if (!writes.empty()) {
        core->update_descriptor_sets(core->device,
            static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    core->cmd_bind_pipeline(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, program->pipeline);
    if (dispatch_set != VK_NULL_HANDLE) {
        core->cmd_bind_descriptor_sets(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE,
            program->pipeline_layout, 0, 1, &dispatch_set, 0, nullptr);
    }
    if (desc->push_constant_bytes > 0) {
        core->cmd_push_constants(command_buffer, program->pipeline_layout,
            VK_SHADER_STAGE_COMPUTE_BIT, 0, desc->push_constant_bytes, desc->push_constants);
    }
    core->cmd_dispatch(command_buffer,
        desc->group_count_x, desc->group_count_y, desc->group_count_z);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate frame state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording frame");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_phases(
    BedrayCore* core, const BedrayFrameDesc* desc, uint32_t phase_offset,
    uint32_t phase_count, BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || desc == nullptr || desc->push_constants == nullptr
        || phase_count == 0 || phase_count > 8 || (phase_offset & 3u) != 0
        || desc->push_constant_bytes < 4 || phase_offset > desc->push_constant_bytes - 4)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid compute phase range");
    BedrayProgram* program = find_program(core, desc->program);
    if (program == nullptr || desc->push_constant_bytes > program->push_constant_bytes)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid compute phase program/push size");
    std::vector<unsigned char> push(desc->push_constant_bytes);
    std::memcpy(push.data(), desc->push_constants, push.size());
    BedrayFrameDesc phase_desc = *desc;
    phase_desc.push_constants = push.data();
    const VkCommandBuffer command = reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(desc->command_buffer));
    VkMemoryBarrier memory{};
    memory.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memory.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    for (uint32_t phase = 0; phase < phase_count; ++phase) {
        std::memcpy(push.data() + phase_offset, &phase, sizeof(phase));
        BedrayStatus status = bedray_core_render_frame(core, &phase_desc, error);
        if (status != BEDRAY_OK) return status;
        core->cmd_pipeline_barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &memory, 0, nullptr, 0, nullptr);
    }
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate compute phase state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording compute phases");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_path_trace(
    BedrayCore* core,
    const BedrayPathTraceDesc* desc,
    BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || desc == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (core->path_trace && core->path_trace->has_recordings()) {
        return fail(error, BEDRAY_ERROR_IN_USE,
            "path-trace recordings must be released before reconfiguration");
    }
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    if (core->frame_graph && core->frame_graph->has_recordings())
        return fail(error, BEDRAY_ERROR_IN_USE, "native graph recordings must be released before reconfiguration");
#endif
    if (core->path_trace) {
        const BedrayStatus status = core->path_trace->destroy();
        if (status != BEDRAY_OK) {
            return status;
        }
        core->path_trace.reset();
    }

    auto pass = std::make_unique<bedray::core::PathTracePass>();
    const BedrayStatus status = pass->configure(core, core->device_fns, *desc, error);
    if (status != BEDRAY_OK) {
        return status;
    }
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    auto frame_graph = std::make_unique<bedray::core::FrameGraphOwner>();
    const auto record = [](void* pointer, const BedrayPathTraceFrameDesc& frame,
        const BedrayPathTraceGuides* guides, const BedrayPathTraceBuffer* motion, BedrayError* err) {
        return static_cast<BedrayCore*>(pointer)->path_trace->render(frame, err, guides, motion);
    };
    const auto release = [](void* pointer, std::uint64_t command) {
        return bedray_core_release_recording(static_cast<BedrayCore*>(pointer), command);
    };
    const BedrayStatus graph_status = frame_graph->configure(core->capabilities, core->queue_family,
        core->cmd_pipeline_barrier, record, release, core, error);
    if (graph_status != BEDRAY_OK) return graph_status;
    core->frame_graph = std::move(frame_graph);
#endif
    core->path_trace = std::move(pass);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace pass");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring path trace");
}

static BedrayStatus record_path_trace(BedrayCore* core, const BedrayPathTraceFrameDesc* desc,
    const BedrayPathTraceGuides* guides, const BedrayPathTraceBuffer* motion, BedrayError* error) {
    clear_error(error);
    if (core == nullptr || desc == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (!core->path_trace) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path trace is not configured");
    }
    if (guides && (guides->abi_version != BEDRAY_CORE_ABI_VERSION || guides->struct_size != sizeof(*guides)))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "path-trace guide ABI/size mismatch");
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    return core->frame_graph->record(*desc, error, guides, motion);
#else
    return core->path_trace->render(*desc, error, guides, motion);
#endif
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace(
    BedrayCore* core, const BedrayPathTraceFrameDesc* desc, BedrayError* error) try {
    return record_path_trace(core, desc, nullptr, nullptr, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace frame");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path trace");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace_guides(
    BedrayCore* core, const BedrayPathTraceFrameDesc* desc,
    const BedrayPathTraceGuides* guides, BedrayError* error) try {
    clear_error(error);
    if (!guides) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "guide outputs are null");
    return record_path_trace(core, desc, guides, nullptr, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate guided path-trace frame");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording guided path trace");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace_motion(
    BedrayCore* core, const BedrayPathTraceFrameDesc* desc, const BedrayPathTraceGuides* guides,
    const BedrayPathTraceBuffer* motion, BedrayError* error) try {
    clear_error(error);
    if (!guides || !motion) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "guide/motion outputs are null");
    return record_path_trace(core, desc, guides, motion, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace motion frame");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path-trace motion");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_path_trace(BedrayCore* core) try {
    if (core == nullptr) {
        return BEDRAY_ERROR_INVALID_ARGUMENT;
    }
    if (!core->path_trace) {
        return BEDRAY_OK;
    }
    if (core->path_trace->has_recordings()) {
        return BEDRAY_ERROR_IN_USE;
    }
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    if (core->frame_graph && core->frame_graph->has_recordings()) return BEDRAY_ERROR_IN_USE;
#endif
    const BedrayStatus status = core->path_trace->destroy();
    if (status != BEDRAY_OK) {
        return status;
    }
    core->path_trace.reset();
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    core->frame_graph.reset();
#endif
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_bloom(
    BedrayCore* core, const BedrayBloomDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/bloom desc is null");
    if (core->bloom && core->bloom->has_recordings()) return fail(error, BEDRAY_ERROR_IN_USE, "bloom recordings pending");
    auto pass = std::make_unique<bedray::core::BloomPass>();
    auto status = pass->configure(core, core->device_fns, *desc, error);
    if (status != BEDRAY_OK) return status;
    if (core->bloom) {
        status = core->bloom->destroy();
        if (status != BEDRAY_OK) return status;
    }
    core->bloom = std::move(pass);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate bloom pass");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring bloom");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_particles(
    BedrayCore* core, const BedrayParticlesFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/particle frame is null");
    return bedray::core::record_particles(core, core->cmd_pipeline_barrier, *desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate particle recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording particles");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_decode_sodium(
    BedrayCore* core, const BedraySodiumDecodeDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/Sodium decode desc is null");
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION || desc->struct_size != sizeof(*desc))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "Sodium decode ABI/size mismatch");
    constexpr auto required = BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64;
    if ((core->capabilities & required) != required)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "Sodium decode requires enabled BDA/Int64");
    return bedray::core::record_sodium(core, core->cmd_pipeline_barrier,
        core->properties.limits.maxComputeWorkGroupCount[0], *desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate Sodium decode recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording Sodium decode");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_bloom(
    BedrayCore* core, const BedrayBloomFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc || !core->bloom) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "bloom not configured");
    return core->bloom->render(*desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate bloom recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording bloom");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_bloom(BedrayCore* core) try {
    if (!core) return BEDRAY_ERROR_INVALID_ARGUMENT;
    if (!core->bloom) return BEDRAY_OK;
    const auto status = core->bloom->destroy();
    if (status == BEDRAY_OK) core->bloom.reset();
    return status;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_volumetric(
    BedrayCore* core, const BedrayVolumetricDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/volumetric desc is null");
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION || desc->struct_size != sizeof(*desc))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "volumetric ABI/size mismatch");
    constexpr auto required = BEDRAY_CAP_RAY_QUERY | BEDRAY_CAP_ACCELERATION_STRUCTURE
        | BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64;
    if ((core->capabilities & required) != required)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "volumetric ray-query/BDA/Int64 capabilities are not enabled");
    if (core->volumetric && core->volumetric->has_recordings())
        return fail(error, BEDRAY_ERROR_IN_USE, "volumetric recordings pending");
    auto pass = std::make_unique<bedray::core::VolumetricPass>();
    auto status = pass->configure(core, core->device_fns, *desc, error);
    if (status != BEDRAY_OK) return status;
    if (core->volumetric) {
        status = core->volumetric->destroy();
        if (status != BEDRAY_OK) return status;
    }
    core->volumetric = std::move(pass);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate volumetric pass");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring volumetric pass");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_volumetric(
    BedrayCore* core, const BedrayVolumetricFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc || !core->volumetric)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "volumetric pass is not configured");
    return core->volumetric->render(*desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate volumetric recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording volumetric pass");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_volumetric(BedrayCore* core) try {
    if (!core) return BEDRAY_ERROR_INVALID_ARGUMENT;
    if (!core->volumetric) return BEDRAY_OK;
    const auto status = core->volumetric->destroy();
    if (status == BEDRAY_OK) core->volumetric.reset();
    return status;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

} // extern "C"
