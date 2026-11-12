#define VK_NO_PROTOTYPES
#include "bedray_core.h"

#include <vulkan/vulkan_core.h>

#include "core_state.h"

#include "particles_pass.h"
#include "sodium_pass.h"
#include "nrd_pack_pass.h"
#include "fg_prepare_pass.h"
#include "spatial_pass.h"

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
/* The statistics program takes the former reserved0/reserved1 pair, so the 80-byte
 * layout and every neighbour offset below are unchanged; the Java host mirrors
 * these four numbers. */
static_assert(offsetof(BedrayPathTraceDesc, program_stats) == 56, "path-trace ABI: desc.program_stats offset");
static_assert(offsetof(BedrayPathTraceDesc, program_trace_scene) == 64, "path-trace ABI: desc.program_trace_scene offset");
static_assert(offsetof(BedrayPathTraceDesc, reserved) == 72, "path-trace ABI: desc.reserved offset");
static_assert(offsetof(BedrayPathTraceFrameDesc, frame_index) == 1032, "path-trace ABI: frame.frame_index offset");
static_assert(offsetof(BedrayPathTraceFrameDesc, frame_time) == 1048, "path-trace ABI: frame.frame_time offset");

namespace bedray::core {
MemoryAccess memory_access(BedrayCore* core) noexcept {
    if (!core) return {};
    return {&core->device_fns, core->instance, core->get_instance_proc_addr, core->get_device_proc_addr,
        core->properties.limits.maxMemoryAllocationCount, core->programs.empty()};
}

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
    out.ray_generation = program->ray_generation != nullptr;
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
    return BEDRAY_PATH_TRACE_FEATURE_NATIVE_DI | BEDRAY_PATH_TRACE_FEATURE_GUIDE_BINDINGS | BEDRAY_PATH_TRACE_FEATURE_GUIDE_OUTPUTS | BEDRAY_PATH_TRACE_FEATURE_NRD_MOTION
        | BEDRAY_PATH_TRACE_FEATURE_CAMERA_IN_WATER | BEDRAY_PATH_TRACE_FEATURE_WATER_MODEL
        | BEDRAY_PATH_TRACE_FEATURE_FRAME_STATS | BEDRAY_PATH_TRACE_FEATURE_NATIVE_PT
        | BEDRAY_PATH_TRACE_FEATURE_STATS_SLOT;
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
    if (core->velocity) {
        const auto status = core->velocity->destroy();
        if (status != BEDRAY_OK) return status;
        core->velocity.reset();
    }
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
    const uint64_t device_memory = bedray::gpu::device_local_bytes(core->memory_properties);
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
            || (core->volumetric && core->volumetric->uses_program(program->handle))
            || (core->velocity && core->velocity->uses_program(program->handle));
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
        [&] { return core->volumetric ? core->volumetric->release_recording(command_buffer) : BEDRAY_OK; },
        [&] { return core->velocity ? core->velocity->release_recording(command_buffer) : BEDRAY_OK; });
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
    if (core->velocity) core->velocity->discard_recording(command_buffer);
    return bedray_core_release_recording(core, command_buffer);
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}


} // extern "C"
