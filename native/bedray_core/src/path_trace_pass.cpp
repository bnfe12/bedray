#include "path_trace_pass.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <iterator>
#include <limits>
#include <new>
#include <utility>

namespace bedray::core {
namespace {

using gpu::Status;

constexpr std::uint32_t kThreadGroup = 8u;
constexpr std::uint32_t kFrameFlagHistoryValid = 1u << 0u;
constexpr std::uint32_t kFrameFlagResetTemporal = 1u << 2u;
// restir_spatial.slang: RESTIR_SPATIAL_ENABLED, the state.w bit that lets the
// shader pool the records the trace pass just wrote.
constexpr std::uint32_t kSpatialEnabled = 1u << 0u;

// contracts/frame.slang: 608 bytes, pinned by the Slang build.
struct FrameCamera {
    float view[16];
    float projection[16];
    float view_projection[16];
    float inv_view_projection[16];
    float prev_view_projection[16];
    float origin[4];
    float forward[4];
    float right[4];
    float up[4];
    float jitter[4];
    std::uint32_t size[4];
};
static_assert(sizeof(FrameCamera) == 416, "FrameCamera must mirror CameraState");

struct FrameSun {
    float direction[4];
    float color[4];
    float moon_direction[4];
    float moon_color[4];
};
static_assert(sizeof(FrameSun) == 64, "FrameSun must mirror SunState");

struct FrameAtmosphere {
    float zenith_color[4];
    float horizon_color[4];
    float fog_color[4];
    float fog[4];
    float cloud[4];
};
static_assert(sizeof(FrameAtmosphere) == 80, "FrameAtmosphere must mirror AtmosphereState");

struct FrameExposure {
    float values[4];
    std::uint32_t mode[4];
};
static_assert(sizeof(FrameExposure) == 32, "FrameExposure must mirror ExposureState");

struct FrameConstants {
    FrameCamera camera;
    FrameSun sun;
    FrameAtmosphere atmosphere;
    FrameExposure exposure;
    std::uint32_t frame[4];
};
static_assert(sizeof(FrameConstants) == 608, "FrameConstants must stay 608 bytes");
static_assert(offsetof(FrameConstants, sun) == 416, "FrameConstants.sun offset");
static_assert(offsetof(FrameConstants, atmosphere) == 480, "FrameConstants.atmosphere offset");
static_assert(offsetof(FrameConstants, exposure) == 560, "FrameConstants.exposure offset");
static_assert(offsetof(FrameConstants, frame) == 592, "FrameConstants.frame offset");

// passes/path_trace.slang: PathTracePush, 112 bytes.
struct PathTracePush {
    std::uint32_t max_depth;
    std::uint32_t max_surface_steps;
    std::uint32_t sample_index;
    std::uint32_t material_count;
    std::uint32_t flags;
    float ray_bias;
    float alpha_cutoff;
    float max_distance;
    float max_radiance;
    float sun_probability;
    std::uint32_t emitter_count;
    float emitter_probability;
    float emitter_camera_relative[4];
    float di_camera_delta[4];
    std::uint32_t di_state[4];
    std::uint32_t entity_state[4];
};
static_assert(sizeof(PathTracePush) == 112, "PathTracePush must stay 112 bytes");
static_assert(offsetof(PathTracePush, emitter_camera_relative) == 48, "PathTracePush.emitterCameraRelative offset");
static_assert(offsetof(PathTracePush, di_camera_delta) == 64, "PathTracePush.diCameraDelta offset");
static_assert(offsetof(PathTracePush, di_state) == 80, "PathTracePush.diState offset");
static_assert(offsetof(PathTracePush, entity_state) == 96, "PathTracePush.entityState offset");

// passes/path_display.slang: PathDisplayPush, 32 bytes.
struct PathDisplayPush {
    std::uint32_t accum_frames;
    std::uint32_t flags;
    float exposure;
    float max_radiance;
    float reuse[4];
};
static_assert(sizeof(PathDisplayPush) == 32, "PathDisplayPush must stay 32 bytes");
static_assert(offsetof(PathDisplayPush, reuse) == 16, "PathDisplayPush.reuse offset");

// passes/restir_spatial.slang: RestirSpatialPush, 48 bytes. The host passes the
// program's declared push range (48 or 64) and the two checker phases overwrite
// state.z at offset 8.
struct SpatialPush {
    std::uint32_t state[4];
    float camera_relative[4];
    float max_distance;
    float pad[3];
};
static_assert(sizeof(SpatialPush) == 48, "SpatialPush must stay 48 bytes");
static_assert(offsetof(SpatialPush, state) == 0, "SpatialPush.state offset");
static_assert(offsetof(SpatialPush, camera_relative) == 16, "SpatialPush.cameraRelative offset");
static_assert(offsetof(SpatialPush, max_distance) == 32, "SpatialPush.maxDistance offset");

struct SpatialPush64 {
    SpatialPush base;
    float tail[4];
};
static_assert(sizeof(SpatialPush64) == 64, "SpatialPush64 must stay 64 bytes");

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

BedrayStatus status_of(Status status) {
    switch (status) {
        case Status::ok: return BEDRAY_OK;
        case Status::invalid_argument:
        case Status::unknown_buffer:
        case Status::unknown_use:
        case Status::unknown_image:
        case Status::retired:
        case Status::unavailable: return BEDRAY_ERROR_INVALID_ARGUMENT;
        case Status::in_use: return BEDRAY_ERROR_IN_USE;
        case Status::unsupported: return BEDRAY_ERROR_UNSUPPORTED;
        case Status::out_of_memory:
        case Status::budget_exceeded:
        case Status::exhausted: return BEDRAY_ERROR_OUT_OF_MEMORY;
        case Status::device_lost: return BEDRAY_ERROR_DEVICE_LOST;
        default: return BEDRAY_ERROR_VULKAN;
    }
}

template <typename T>
std::uint64_t handle_bits(T handle) {
    return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(handle));
}

VkBuffer as_buffer(std::uint64_t handle) {
    return reinterpret_cast<VkBuffer>(static_cast<std::uintptr_t>(handle));
}

VkImageView as_view(std::uint64_t handle) {
    return reinterpret_cast<VkImageView>(static_cast<std::uintptr_t>(handle));
}

VkSampler as_sampler(std::uint64_t handle) {
    return reinterpret_cast<VkSampler>(static_cast<std::uintptr_t>(handle));
}

VkAccelerationStructureKHR as_acceleration(std::uint64_t handle) {
    return reinterpret_cast<VkAccelerationStructureKHR>(static_cast<std::uintptr_t>(handle));
}

VkCommandBuffer as_command_buffer(std::uint64_t handle) {
    return reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(handle));
}

BedrayResource image_resource(std::uint32_t binding, VkDescriptorType type,
    VkImageView view, VkSampler sampler = VK_NULL_HANDLE) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(type);
    resource.image_layout = static_cast<std::uint32_t>(VK_IMAGE_LAYOUT_GENERAL);
    resource.handle = handle_bits(view);
    resource.sampler = handle_bits(sampler);
    return resource;
}

BedrayResource buffer_resource(std::uint32_t binding, VkBuffer buffer,
    std::uint64_t offset, std::uint64_t bytes) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    resource.handle = handle_bits(buffer);
    resource.offset = offset;
    resource.range = bytes;
    return resource;
}

BedrayResource uniform_resource(std::uint32_t binding, VkBuffer buffer,
    std::uint64_t offset, std::uint64_t bytes) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    resource.handle = handle_bits(buffer);
    resource.offset = offset;
    resource.range = bytes;
    return resource;
}

BedrayResource acceleration_resource(std::uint32_t binding, VkAccelerationStructureKHR acceleration) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
    resource.handle = handle_bits(acceleration);
    return resource;
}

BedrayPathTraceBuffer buffer_from_view(const gpu::BufferView& view) {
    BedrayPathTraceBuffer buffer{};
    buffer.handle = handle_bits(view.buffer);
    buffer.offset = 0;
    buffer.bytes = view.size;
    return buffer;
}

BedrayPathTraceRange range_from_view(const gpu::BufferView& view) {
    BedrayPathTraceRange range{};
    range.handle = handle_bits(view.buffer);
    range.offset = 0;
    range.bytes = view.size;
    return range;
}

std::uint32_t groups(std::uint32_t extent) {
    return (extent + kThreadGroup - 1u) / kThreadGroup;
}

struct ExpectedBinding {
    std::uint32_t binding;
    std::uint32_t descriptor_type;
};

// The registered program is the shader the pass will dispatch. Checking the
// descriptor type of every binding the pass writes turns a manifest/shader
// drift into a configure-time error instead of a validation-layer error or a
// wrong descriptor at dispatch time.
bool has_bindings(BedrayCore* core, std::uint64_t program,
    const ExpectedBinding* expected, std::size_t count) {
    for (std::size_t i = 0; i < count; ++i) {
        if (!program_has_binding(core, program, expected[i].binding, expected[i].descriptor_type)) {
            return false;
        }
    }
    return true;
}

void transition_image(const DeviceFns& fns, VkCommandBuffer command, VkImage image,
    VkImageLayout old_layout, VkImageLayout new_layout) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = old_layout == VK_IMAGE_LAYOUT_UNDEFINED
        ? 0u
        : static_cast<VkAccessFlags>(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    barrier.dstAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    fns.cmd_pipeline_barrier(command,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &barrier);
}

void entry_barrier(const DeviceFns& fns, VkCommandBuffer command) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_MEMORY_WRITE_BIT);
    barrier.dstAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    fns.cmd_pipeline_barrier(command,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &barrier, 0, nullptr, 0, nullptr);
}

void compute_barrier(const DeviceFns& fns, VkCommandBuffer command) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_SHADER_WRITE_BIT);
    barrier.dstAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    fns.cmd_pipeline_barrier(command,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &barrier, 0, nullptr, 0, nullptr);
}

void exit_barrier(const DeviceFns& fns, VkCommandBuffer command) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_MEMORY_WRITE_BIT);
    barrier.dstAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    fns.cmd_pipeline_barrier(command,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &barrier, 0, nullptr, 0, nullptr);
}

void copy4(float (&destination)[4], const float (&source)[4]) {
    std::memcpy(destination, source, sizeof(destination));
}

FrameConstants make_frame_constants(const BedrayPathTraceFrameDesc& desc, bool history_written) {
    FrameConstants constants{};

    std::memcpy(constants.camera.view, desc.camera.view, sizeof(constants.camera.view));
    std::memcpy(constants.camera.projection, desc.camera.projection, sizeof(constants.camera.projection));
    std::memcpy(constants.camera.view_projection, desc.camera.view_projection, sizeof(constants.camera.view_projection));
    std::memcpy(constants.camera.inv_view_projection, desc.camera.inv_view_projection,
        sizeof(constants.camera.inv_view_projection));
    std::memcpy(constants.camera.prev_view_projection, desc.camera.prev_view_projection,
        sizeof(constants.camera.prev_view_projection));
    copy4(constants.camera.origin, desc.camera.origin);
    copy4(constants.camera.forward, desc.camera.forward);
    copy4(constants.camera.right, desc.camera.right);
    copy4(constants.camera.up, desc.camera.up);
    copy4(constants.camera.jitter, desc.camera.jitter);
    constants.camera.size[0] = desc.camera.width;
    constants.camera.size[1] = desc.camera.height;
    constants.camera.size[2] = desc.camera.display_width;
    constants.camera.size[3] = desc.camera.display_height;

    copy4(constants.sun.direction, desc.lighting.sun_direction);
    copy4(constants.sun.color, desc.lighting.sun_color);
    copy4(constants.sun.moon_direction, desc.lighting.moon_direction);
    copy4(constants.sun.moon_color, desc.lighting.moon_color);

    copy4(constants.atmosphere.zenith_color, desc.atmosphere.zenith_color);
    copy4(constants.atmosphere.horizon_color, desc.atmosphere.horizon_color);
    copy4(constants.atmosphere.fog_color, desc.atmosphere.fog_color);
    copy4(constants.atmosphere.fog, desc.atmosphere.fog);
    copy4(constants.atmosphere.cloud, desc.atmosphere.cloud);

    constants.exposure.values[0] = desc.lighting.exposure;
    constants.exposure.values[1] = 0.0f;
    constants.exposure.values[2] = 0.0f;
    constants.exposure.values[3] = desc.frame_time;
    constants.exposure.mode[0] = 0;
    constants.exposure.mode[1] = 0;
    constants.exposure.mode[2] = 0;
    constants.exposure.mode[3] = 0;

    constants.frame[0] = desc.frame_index;
    constants.frame[1] = desc.path.sample_index;
    constants.frame[2] = desc.samples_per_frame == 0 ? 1u : desc.samples_per_frame;
    constants.frame[3] = history_written ? kFrameFlagHistoryValid : kFrameFlagResetTemporal;
    return constants;
}

} // namespace

PathTracePass::~PathTracePass() {
    destroy();
}

BedrayStatus PathTracePass::configure(BedrayCore* core, const DeviceFns& fns,
    const BedrayPathTraceDesc& desc, BedrayError* error) try {
    if (core == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core is null");
    }
    if (core_ != nullptr) {
        const BedrayStatus existing = destroy();
        if (existing != BEDRAY_OK) {
            return existing;
        }
    }
    if (desc.program_trace == 0 || desc.program_display == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "program_trace and program_display must be non-zero");
    }

    static constexpr ExpectedBinding kTraceBindings[] = {
        {0, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR},
        {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
        {5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
        {10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
        {12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {16, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
        {17, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {18, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {19, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {20, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {21, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {22, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {23, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {24, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        {25, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    };
    // path_trace.slang and path_trace_scene.slang share the binding set, the
    // push block and the outputs; only binding 2's record layout and the
    // traversal differ, and that is the program's own business.
    auto validate_trace_program = [&](std::uint64_t program, const char* label) -> BedrayStatus {
        ProgramContract contract{};
        if (!program_contract(core, program, contract)) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "%s is not registered", label);
        }
        if (contract.binding_count != 18u
            || contract.push_constant_bytes < sizeof(PathTracePush)) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "%s contract mismatch: %u bindings, %u push bytes (need 18, %zu)",
                label, contract.binding_count, contract.push_constant_bytes, sizeof(PathTracePush));
        }
        if (!has_bindings(core, program, kTraceBindings, std::size(kTraceBindings))) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "%s does not declare the path_trace.slang binding set", label);
        }
        return BEDRAY_OK;
    };
    BedrayStatus trace_status = validate_trace_program(desc.program_trace, "program_trace");
    if (trace_status != BEDRAY_OK) {
        return trace_status;
    }
    if (desc.program_trace_scene != 0) {
        BedrayStatus scene_status = validate_trace_program(desc.program_trace_scene, "program_trace_scene");
        if (scene_status != BEDRAY_OK) {
            return scene_status;
        }
    }

    ProgramContract display_contract{};
    if (!program_contract(core, desc.program_display, display_contract)) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program_display is not registered");
    }
    if (display_contract.binding_count != 9u
        || display_contract.push_constant_bytes < sizeof(PathDisplayPush)) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "program_display contract mismatch: %u bindings, %u push bytes (need 9, %zu)",
            display_contract.binding_count, display_contract.push_constant_bytes, sizeof(PathDisplayPush));
    }
    static constexpr ExpectedBinding kDisplayBindings[] = {
        {0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {5, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
        {6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
        {8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    };
    if (!has_bindings(core, desc.program_display, kDisplayBindings, std::size(kDisplayBindings))) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "program_display does not declare the path_display.slang binding set");
    }

    ProgramContract spatial_contract{};
    std::uint32_t spatial_push_bytes = 0;
    if (desc.program_spatial != 0) {
        if (!program_contract(core, desc.program_spatial, spatial_contract)) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program_spatial is not registered");
        }
        if (spatial_contract.binding_count != 6u
            || spatial_contract.push_constant_bytes < sizeof(SpatialPush)) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "program_spatial contract mismatch: %u bindings, %u push bytes (need 6, %zu)",
                spatial_contract.binding_count, spatial_contract.push_constant_bytes, sizeof(SpatialPush));
        }
        static constexpr ExpectedBinding kSpatialBindings[] = {
            {3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
            {9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
            {10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
            {16, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
            {18, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
            {19, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
        };
        if (!has_bindings(core, desc.program_spatial, kSpatialBindings, std::size(kSpatialBindings))) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "program_spatial does not declare the restir_spatial.slang binding set");
        }
        spatial_push_bytes = spatial_contract.push_constant_bytes >= sizeof(SpatialPush64)
            ? static_cast<std::uint32_t>(sizeof(SpatialPush64))
            : static_cast<std::uint32_t>(sizeof(SpatialPush));
    }

    if (fns.generation == 0 || fns.device == VK_NULL_HANDLE || fns.physical_device == VK_NULL_HANDLE
        || fns.non_coherent_atom_size == 0 || fns.cmd_pipeline_barrier == nullptr) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "device function table is incomplete");
    }

    const std::uint64_t image_budget = desc.image_budget_bytes == 0
        ? (512ull << 20)
        : desc.image_budget_bytes;
    const std::uint64_t buffer_budget = desc.buffer_budget_bytes == 0
        ? (16ull << 20)
        : desc.buffer_budget_bytes;

    gpu::ImageDeviceContext image_context{};
    image_context.physical_device = fns.physical_device;
    image_context.device = fns.device;
    image_context.functions = fns.image_functions;
    image_context.memory_properties = fns.memory_properties;
    image_context.dedicated_allocation_enabled = true;
    image_context.generation = fns.generation;

    auto images = std::make_unique<gpu::GpuImages>(image_context, image_budget);
    if (!images->valid()) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "GPU image owner is unavailable");
    }

    gpu::DeviceContext buffer_context{};
    buffer_context.device = fns.device;
    buffer_context.functions = fns.buffer_functions;
    buffer_context.memory_properties = fns.memory_properties;
    buffer_context.non_coherent_atom_size = fns.non_coherent_atom_size;
    buffer_context.dedicated_allocation_enabled = true;
    buffer_context.buffer_device_address_enabled = false;
    buffer_context.generation = fns.generation;

    auto buffers = std::make_unique<gpu::GpuBuffers>(buffer_context, buffer_budget);
    if (!buffers->valid()) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "GPU buffer owner is unavailable");
    }

    core_ = core;
    fns_ = fns;
    program_trace_ = desc.program_trace;
    program_trace_scene_ = desc.program_trace_scene;
    program_spatial_ = desc.program_spatial;
    program_display_ = desc.program_display;
    spatial_push_bytes_ = spatial_push_bytes;
    images_ = std::move(images);
    buffers_ = std::move(buffers);
    reset_state();

    BedrayStatus status = create_dummy(64u, block_materials_id_, block_materials_view_, error);
    if (status == BEDRAY_OK) {
        status = create_dummy(64u, reservoir_dummy_id_, reservoir_dummy_view_, error);
    }
    if (status == BEDRAY_OK) {
        status = create_dummy(4u, entity_scalar_id_, entity_scalar_view_, error);
    }
    if (status == BEDRAY_OK) {
        status = create_dummy(16u, entity_vector_id_, entity_vector_view_, error);
    }
    if (status != BEDRAY_OK) {
        destroy();
        return status;
    }
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace pass state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring path trace");
}

BedrayStatus PathTracePass::create_dummy(VkDeviceSize bytes, gpu::BufferId& id,
    gpu::BufferView& view, BedrayError* error) {
    if (!buffers_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "buffer owner is missing");
    }
    gpu::BufferDesc descriptor{};
    descriptor.size = bytes;
    descriptor.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    descriptor.required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    descriptor.preferred = VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    descriptor.mapped = true;

    VkResult result = VK_SUCCESS;
    Status status = buffers_->create(descriptor, id, view, result);
    if (status != Status::ok) {
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot create a %llu-byte dummy buffer: VkResult=%d",
            static_cast<unsigned long long>(bytes), static_cast<int>(result));
    }

    std::array<unsigned char, 64> zero{};
    status = buffers_->write(id, 0, zero.data(), static_cast<std::size_t>(bytes), result);
    if (status != Status::ok) {
        static_cast<void>(buffers_->retire(id));
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot zero a dummy buffer: VkResult=%d",
            static_cast<int>(result));
    }
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::ensure_images(std::uint32_t width, std::uint32_t height,
    BedrayError* error) {
    if (!images_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "image owner is missing");
    }
    if (images_ready_ && image_width_ == width && image_height_ == height) {
        return BEDRAY_OK;
    }
    if (images_ready_) {
        for (gpu::ImageId id : {radiance_id_, motion_id_, accum_id_[0], accum_id_[1],
                 history_depth_id_[0], history_depth_id_[1]}) {
            if (id.serial != 0) {
                static_cast<void>(images_->retire(id));
            }
        }
        static_cast<void>(images_->collect());
        images_ready_ = false;
        images_initialized_ = false;
    }

    auto create = [&](VkFormat format, gpu::ImageId& id, gpu::ImageView& view) -> BedrayStatus {
        gpu::ImageDesc descriptor{};
        descriptor.width = width;
        descriptor.height = height;
        descriptor.format = format;
        descriptor.usage = VK_IMAGE_USAGE_STORAGE_BIT;
        VkResult result = VK_SUCCESS;
        const Status status = images_->create(descriptor, id, view, result);
        if (status != Status::ok) {
            if (status == Status::device_lost) {
                device_lost();
            }
            return fail(error, status_of(status), "cannot create a %ux%u path-trace image: VkResult=%d",
                width, height, static_cast<int>(result));
        }
        return BEDRAY_OK;
    };

    BedrayStatus status = create(VK_FORMAT_R16G16B16A16_SFLOAT, radiance_id_, radiance_view_);
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R16G16B16A16_SFLOAT, motion_id_, motion_view_);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32G32B32A32_SFLOAT, accum_id_[0], accum_view_[0]);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32G32B32A32_SFLOAT, accum_id_[1], accum_view_[1]);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32_SFLOAT, history_depth_id_[0], history_depth_view_[0]);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32_SFLOAT, history_depth_id_[1], history_depth_view_[1]);
    }
    if (status != BEDRAY_OK) {
        for (gpu::ImageId id : {radiance_id_, motion_id_, accum_id_[0], accum_id_[1],
                 history_depth_id_[0], history_depth_id_[1]}) {
            if (id.serial != 0) {
                static_cast<void>(images_->retire(id));
            }
        }
        static_cast<void>(images_->collect());
        return status;
    }

    images_ready_ = true;
    images_initialized_ = false;
    image_width_ = width;
    image_height_ = height;
    history_parity_ = 0;
    history_written_ = false;
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::pin_image(gpu::ImageId id, RecordingPins& pins, BedrayError* error) {
    if (!images_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "image owner is missing");
    }
    gpu::UseId token{};
    const Status status = images_->begin_use(id, token);
    if (status != Status::ok) {
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot pin a path-trace image");
    }
    pins.image_uses.push_back(token);
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::pin_buffer(gpu::BufferId id, RecordingPins& pins, BedrayError* error) {
    if (!buffers_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "buffer owner is missing");
    }
    gpu::UseId token{};
    const Status status = buffers_->begin_use(id, token);
    if (status != Status::ok) {
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot pin a path-trace buffer");
    }
    pins.buffer_uses.push_back(token);
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::ensure_reservoirs(std::uint64_t bytes, BedrayError* error) {
    if (owned_reservoirs_id_.serial != 0 && owned_reservoirs_view_.size == bytes) return BEDRAY_OK;
    gpu::BufferId next{};
    gpu::BufferView view{};
    VkResult vk = VK_SUCCESS;
    const auto result = buffers_->create({bytes,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false}, next, view, vk);
    if (result != Status::ok)
        return fail(error, status_of(result), "cannot allocate native DI ring: VkResult=%d", static_cast<int>(vk));
    if (owned_reservoirs_id_.serial != 0) static_cast<void>(buffers_->retire(owned_reservoirs_id_));
    owned_reservoirs_id_ = next;
    owned_reservoirs_view_ = view;
    owned_di_parity_ = 0;
    owned_di_history_ = false;
    owned_di_needs_clear_ = true;
    static_cast<void>(buffers_->collect());
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::render(const BedrayPathTraceFrameDesc& input, BedrayError* error) try {
    BedrayPathTraceFrameDesc desc = input;
    if (core_ == nullptr || images_ == nullptr || buffers_ == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path trace is not configured");
    }
    if (desc.abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc.abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (desc.command_buffer == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "command_buffer is null");
    }
    constexpr std::uint32_t allowed_flags = BEDRAY_PATH_TRACE_FRAME_RESET_HISTORY
        | BEDRAY_PATH_TRACE_FRAME_ACCUMULATE | BEDRAY_PATH_TRACE_FRAME_CAMERA_STATIC
        | BEDRAY_PATH_TRACE_FRAME_FLIP_Y | BEDRAY_PATH_TRACE_FRAME_SPATIAL
        | BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE | BEDRAY_PATH_TRACE_FRAME_NATIVE_DI
        | BEDRAY_PATH_TRACE_FRAME_RESET_DI;
    if ((desc.flags & ~allowed_flags) != 0)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "unknown path-trace frame flags");
    if (desc.output_view == 0 || desc.depth_view == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "output_view/depth_view must be non-zero");
    }
    if (desc.scene.tlas == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path trace needs a TLAS");
    }
    if (desc.scene.sections.handle == 0 || desc.scene.sections.bytes == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path trace needs a section table");
    }
    // A native-scene frame binds bedray_scene's 64-byte GeometrySection table
    // and must have been configured with path_trace_scene.slang. A table that is
    // not a whole number of records would decode as a different section.
    const bool native_scene = (desc.flags & BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE) != 0;
    if (native_scene) {
        if (program_trace_scene_ == 0) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "native scene frame needs program_trace_scene (path_trace_scene.slang)");
        }
        if ((desc.scene.sections.bytes % 64u) != 0) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "native scene table is %llu bytes, not a multiple of the 64-byte section record",
                static_cast<unsigned long long>(desc.scene.sections.bytes));
        }
    }
    if (desc.scene.albedo_view == 0 || desc.scene.material_view == 0
        || desc.scene.normal_view == 0 || desc.scene.atlas_sampler == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path trace needs the three atlases and a sampler");
    }
    if (desc.camera.width == 0 || desc.camera.height == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "frame extent must be positive");
    }
    if (desc.path.max_depth == 0 || desc.path.max_surface_steps == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "max_depth/max_surface_steps must be >= 1");
    }
    if (!(desc.path.max_distance > 0.0f) || !std::isfinite(desc.path.max_distance)
        || !(desc.path.max_radiance > 0.0f) || !std::isfinite(desc.path.max_radiance)
        || !(desc.lighting.exposure >= 0.0f) || !std::isfinite(desc.lighting.exposure)) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path limits/exposure must be finite and non-negative");
    }

    const std::uint64_t command_handle = desc.command_buffer;
    if (recordings_.find(command_handle) != recordings_.end()) {
        return fail(error, BEDRAY_ERROR_IN_USE,
            "command buffer 0x%llx already holds an unreleased path-trace recording",
            static_cast<unsigned long long>(command_handle));
    }

    // Everything below is pure validation and can fail before a single command
    // is recorded, so it must run before the use pins are taken.
    if (desc.path.emitter_count > 0 && desc.scene.emitters.handle == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "the push promises %u emitter(s) but no emitter buffer is bound", desc.path.emitter_count);
    }
    // A bound buffer must carry a non-zero descriptor range: Vulkan treats a
    // zero VkDescriptorBufferInfo::range as an empty (invalid) binding, while a
    // zero handle is the documented way to select the pass's own dummy.
    const BedrayPathTraceBuffer* bound_buffers[] = {
        &desc.scene.sections, &desc.scene.block_materials, &desc.scene.emitters, &desc.scene.reservoirs};
    for (const BedrayPathTraceBuffer* buffer : bound_buffers) {
        if (buffer->flags != 0 || buffer->reserved != 0 ||
            (buffer->handle != 0 && (buffer->bytes == 0 ||
                buffer->offset > std::numeric_limits<std::uint64_t>::max() - buffer->bytes))) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "invalid scene buffer flags, reserved words or descriptor range");
        }
    }
    const BedrayPathTraceRange* bound_ranges[] = {
        &desc.scene.entity_positions, &desc.scene.entity_indices, &desc.scene.entity_attrs,
        &desc.scene.entity_rects, &desc.scene.entity_texels, &desc.scene.entity_previous};
    for (const BedrayPathTraceRange* range : bound_ranges) {
        if (range->handle != 0 && (range->bytes == 0
            || range->offset > std::numeric_limits<std::uint64_t>::max() - range->bytes)) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "a bound entity range has a zero descriptor range");
        }
    }
    const bool native_di = (desc.flags & BEDRAY_PATH_TRACE_FRAME_NATIVE_DI) != 0;
    std::uint32_t di_flags = desc.path.di_state[0];
    std::uint32_t di_parity = desc.path.di_state[1];
    const std::uint64_t pixels = static_cast<std::uint64_t>(desc.camera.width) * desc.camera.height;
    if (pixels > std::numeric_limits<std::uint64_t>::max() / 128u)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "DI extent overflows its byte range");
    const std::uint64_t di_bytes = pixels * 128u;
    if (native_di && (desc.scene.reservoirs.handle != 0 || di_parity != 0))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "native DI cannot also accept a host ring/parity");
    if (native_di && fns_.cmd_fill_buffer == nullptr)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "native DI needs vkCmdFillBuffer");
    if (!native_di && (desc.flags & BEDRAY_PATH_TRACE_FRAME_RESET_DI) != 0)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "native DI reset requires native ownership");
    if (di_parity > 1u) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "di_state.y must be the 0/1 reservoir parity, got %u", di_parity);
    }
    if (!native_di && (di_flags & BEDRAY_PATH_TRACE_DI_ENABLED) != 0
        && (desc.scene.reservoirs.handle == 0 || desc.scene.reservoirs.bytes < di_bytes)) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "ReSTIR DI needs a complete two-parity reservoir range");
    }
    if (desc.path.emitter_count > 0 && desc.scene.emitters.bytes < static_cast<std::uint64_t>(desc.path.emitter_count) * 96u)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "emitter descriptor range is smaller than emitter_count");
    if (desc.path.material_count > 0 && (desc.scene.block_materials.handle == 0
        || desc.scene.block_materials.bytes < static_cast<std::uint64_t>(desc.path.material_count) * 64u))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "material descriptor range is smaller than material_count");

    BedrayStatus status = ensure_images(desc.camera.width, desc.camera.height, error);
    if (status != BEDRAY_OK) {
        return status;
    }

    const bool native_di_active = native_di && (di_flags & BEDRAY_PATH_TRACE_DI_ENABLED) != 0
        && desc.path.emitter_count > 0;
    if (native_di_active) {
        status = ensure_reservoirs(di_bytes, error);
        if (status != BEDRAY_OK) return status;
        if ((desc.flags & BEDRAY_PATH_TRACE_FRAME_RESET_DI) != 0) owned_di_needs_clear_ = true;
        di_flags &= ~BEDRAY_PATH_TRACE_DI_HISTORY;
        if (owned_di_history_ && !owned_di_needs_clear_) di_flags |= BEDRAY_PATH_TRACE_DI_HISTORY;
        di_parity = owned_di_parity_;
        desc.path.di_state[0] = di_flags;
        desc.path.di_state[1] = di_parity;
        desc.scene.reservoirs = buffer_from_view(owned_reservoirs_view_);
    } else {
        owned_di_history_ = false;
        if (native_di) {
            di_flags &= ~(BEDRAY_PATH_TRACE_DI_ENABLED | BEDRAY_PATH_TRACE_DI_HISTORY);
            desc.path.di_state[0] = di_flags;
            desc.path.di_state[1] = 0;
        }
        if (owned_reservoirs_id_.serial != 0) {
            static_cast<void>(buffers_->retire(owned_reservoirs_id_));
            owned_reservoirs_id_ = {};
            owned_reservoirs_view_ = {};
            static_cast<void>(buffers_->collect());
        }
    }

    const bool reset_history = (desc.flags & BEDRAY_PATH_TRACE_FRAME_RESET_HISTORY) != 0
        || desc.accum_frames == 0
        || !history_written_;
    if (reset_history) {
        history_written_ = false;
        history_parity_ = 0;
    }

    const std::uint32_t write = history_parity_;
    const std::uint32_t read = write ^ 1u;

    const BedrayPathTraceBuffer block_materials = desc.scene.block_materials.handle != 0
        ? desc.scene.block_materials
        : buffer_from_view(block_materials_view_);
    const BedrayPathTraceBuffer emitters = desc.scene.emitters.handle != 0
        ? desc.scene.emitters
        : block_materials;
    const BedrayPathTraceBuffer reservoirs = desc.scene.reservoirs.handle != 0
        ? desc.scene.reservoirs
        : buffer_from_view(reservoir_dummy_view_);
    const BedrayPathTraceRange entity_positions = desc.scene.entity_positions.handle != 0
        ? desc.scene.entity_positions
        : range_from_view(entity_scalar_view_);
    const BedrayPathTraceRange entity_indices = desc.scene.entity_indices.handle != 0
        ? desc.scene.entity_indices
        : range_from_view(entity_scalar_view_);
    const BedrayPathTraceRange entity_attrs = desc.scene.entity_attrs.handle != 0
        ? desc.scene.entity_attrs
        : range_from_view(entity_vector_view_);
    const BedrayPathTraceRange entity_rects = desc.scene.entity_rects.handle != 0
        ? desc.scene.entity_rects
        : range_from_view(entity_vector_view_);
    const BedrayPathTraceRange entity_texels = desc.scene.entity_texels.handle != 0
        ? desc.scene.entity_texels
        : range_from_view(entity_scalar_view_);
    const BedrayPathTraceRange entity_previous = desc.scene.entity_previous.handle != 0
        ? desc.scene.entity_previous
        : range_from_view(entity_scalar_view_);

    RecordingPins pins{};
    pins.image_uses.reserve(6);
    pins.buffer_uses.reserve(8);
    recordings_.emplace(command_handle, std::move(pins));
    RecordingPins& recording = recordings_.at(command_handle);

    for (gpu::ImageId id : {radiance_id_, motion_id_, accum_id_[write], history_depth_id_[write],
             accum_id_[read], history_depth_id_[read]}) {
        status = pin_image(id, recording, error);
        if (status != BEDRAY_OK) {
            // No command has been recorded yet, so this recording is provably
            // discarded and its partial pins can be completed immediately.
            release_recording(command_handle);
            return status;
        }
    }

    status = pin_buffer(block_materials_id_, recording, error);
    if (status == BEDRAY_OK) {
        status = pin_buffer(reservoir_dummy_id_, recording, error);
    }
    if (status == BEDRAY_OK) {
        status = pin_buffer(entity_scalar_id_, recording, error);
    }
    if (status == BEDRAY_OK) {
        status = pin_buffer(entity_vector_id_, recording, error);
    }
    if (status == BEDRAY_OK && native_di_active)
        status = pin_buffer(owned_reservoirs_id_, recording, error);
    if (status != BEDRAY_OK) {
        release_recording(command_handle);
        return status;
    }

    const FrameConstants constants = make_frame_constants(desc, history_written_);
    gpu::BufferId frame_id{};
    gpu::BufferView frame_view{};
    gpu::UseId frame_use{};
    VkResult vk_result = VK_SUCCESS;
    Status gpu_result = buffers_->upload(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        &constants, sizeof(constants), frame_id, frame_view, frame_use, vk_result);
    if (gpu_result != Status::ok) {
        if (gpu_result == Status::device_lost) {
            device_lost();
        }
        // Nothing has been recorded into the command buffer yet, so this
        // recording is provably discarded and its pins can go immediately.
        release_recording(command_handle);
        return fail(error, status_of(gpu_result),
            "cannot upload FrameConstants: VkResult=%d", static_cast<int>(vk_result));
    }
    recording.buffer_uses.push_back(frame_use);

    PathTracePush trace_push{};
    trace_push.max_depth = desc.path.max_depth;
    trace_push.max_surface_steps = desc.path.max_surface_steps;
    trace_push.sample_index = desc.path.sample_index;
    trace_push.material_count = desc.path.material_count;
    trace_push.flags = desc.path.flags;
    trace_push.ray_bias = desc.path.ray_bias;
    trace_push.alpha_cutoff = desc.path.alpha_cutoff;
    trace_push.max_distance = desc.path.max_distance;
    trace_push.max_radiance = desc.path.max_radiance;
    trace_push.sun_probability = desc.path.sun_probability;
    trace_push.emitter_count = desc.path.emitter_count;
    trace_push.emitter_probability = desc.path.emitter_probability;
    copy4(trace_push.emitter_camera_relative, desc.path.emitter_camera_relative);
    copy4(trace_push.di_camera_delta, desc.path.di_camera_delta);
    std::memcpy(trace_push.di_state, desc.path.di_state, sizeof(trace_push.di_state));
    std::memcpy(trace_push.entity_state, desc.path.entity_state, sizeof(trace_push.entity_state));

    const VkCommandBuffer command = as_command_buffer(desc.command_buffer);

    // Publication happens only after the complete group records successfully.
    // A partial failure cannot leave the next dispatch importing its writes.
    const bool clear_di = native_di_active && owned_di_needs_clear_;
    if (native_di_active) {
        owned_di_history_ = false;
        owned_di_needs_clear_ = true;
    }

    if (!images_initialized_) {
        for (const gpu::ImageView& view : {radiance_view_, motion_view_, accum_view_[0],
                 accum_view_[1], history_depth_view_[0], history_depth_view_[1]}) {
            transition_image(fns_, command, view.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        }
        images_initialized_ = true;
    } else {
        for (const gpu::ImageView& view : {radiance_view_, motion_view_,
                 accum_view_[write], history_depth_view_[write]}) {
            transition_image(fns_, command, view.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        }
    }
    if (clear_di)
        fns_.cmd_fill_buffer(command, owned_reservoirs_view_.buffer, 0, owned_reservoirs_view_.size, 0);
    entry_barrier(fns_, command);

    std::array<BedrayResource, 18> trace_resources{};
    trace_resources[0] = acceleration_resource(0, as_acceleration(desc.scene.tlas));
    trace_resources[1] = image_resource(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, radiance_view_.view);
    trace_resources[2] = buffer_resource(2, as_buffer(desc.scene.sections.handle),
        desc.scene.sections.offset, desc.scene.sections.bytes);
    trace_resources[3] = image_resource(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        as_view(desc.scene.albedo_view), as_sampler(desc.scene.atlas_sampler));
    trace_resources[4] = image_resource(5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, motion_view_.view);
    trace_resources[5] = image_resource(9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        as_view(desc.scene.material_view), as_sampler(desc.scene.atlas_sampler));
    trace_resources[6] = image_resource(10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        as_view(desc.scene.normal_view), as_sampler(desc.scene.atlas_sampler));
    trace_resources[7] = image_resource(12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, as_view(desc.depth_view));
    trace_resources[8] = uniform_resource(16, frame_view.buffer, 0, sizeof(FrameConstants));
    trace_resources[9] = buffer_resource(17, as_buffer(block_materials.handle),
        block_materials.offset, block_materials.bytes);
    trace_resources[10] = buffer_resource(18, as_buffer(emitters.handle),
        emitters.offset, emitters.bytes);
    trace_resources[11] = buffer_resource(19, as_buffer(reservoirs.handle),
        reservoirs.offset, reservoirs.bytes);
    trace_resources[12] = buffer_resource(20, as_buffer(entity_positions.handle),
        entity_positions.offset, entity_positions.bytes);
    trace_resources[13] = buffer_resource(21, as_buffer(entity_indices.handle),
        entity_indices.offset, entity_indices.bytes);
    trace_resources[14] = buffer_resource(22, as_buffer(entity_attrs.handle),
        entity_attrs.offset, entity_attrs.bytes);
    trace_resources[15] = buffer_resource(23, as_buffer(entity_rects.handle),
        entity_rects.offset, entity_rects.bytes);
    trace_resources[16] = buffer_resource(24, as_buffer(entity_texels.handle),
        entity_texels.offset, entity_texels.bytes);
    trace_resources[17] = buffer_resource(25, as_buffer(entity_previous.handle),
        entity_previous.offset, entity_previous.bytes);

    BedrayFrameDesc trace_desc{};
    trace_desc.abi_version = BEDRAY_CORE_ABI_VERSION;
    trace_desc.command_buffer = desc.command_buffer;
    trace_desc.program = native_scene ? program_trace_scene_ : program_trace_;
    trace_desc.group_count_x = groups(desc.camera.width);
    trace_desc.group_count_y = groups(desc.camera.height);
    trace_desc.group_count_z = 1;
    trace_desc.resource_count = static_cast<std::uint32_t>(trace_resources.size());
    trace_desc.resources = trace_resources.data();
    trace_desc.push_constants = &trace_push;
    trace_desc.push_constant_bytes = static_cast<std::uint32_t>(sizeof(trace_push));

    status = bedray_core_render_frame(core_, &trace_desc, error);
    if (status != BEDRAY_OK) {
        return status;
    }
    compute_barrier(fns_, command);

    const bool spatial_enabled = program_spatial_ != 0
        && (desc.flags & BEDRAY_PATH_TRACE_FRAME_SPATIAL) != 0
        && (di_flags & BEDRAY_PATH_TRACE_DI_ENABLED) != 0
        && desc.path.emitter_count > 0
        && desc.scene.reservoirs.handle != 0;
    if (spatial_enabled) {
        SpatialPush64 spatial_push{};
        spatial_push.base.state[0] = desc.frame_index;
        spatial_push.base.state[1] = di_parity;
        spatial_push.base.state[2] = 0;
        spatial_push.base.state[3] = kSpatialEnabled;
        copy4(spatial_push.base.camera_relative, desc.path.emitter_camera_relative);
        spatial_push.base.max_distance = desc.path.max_distance;

        std::array<BedrayResource, 6> spatial_resources{};
        spatial_resources[0] = image_resource(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            as_view(desc.scene.albedo_view), as_sampler(desc.scene.atlas_sampler));
        spatial_resources[1] = image_resource(9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            as_view(desc.scene.material_view), as_sampler(desc.scene.atlas_sampler));
        spatial_resources[2] = image_resource(10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            as_view(desc.scene.normal_view), as_sampler(desc.scene.atlas_sampler));
        spatial_resources[3] = uniform_resource(16, frame_view.buffer, 0, sizeof(FrameConstants));
        spatial_resources[4] = buffer_resource(18, as_buffer(emitters.handle),
            emitters.offset, emitters.bytes);
        spatial_resources[5] = buffer_resource(19, as_buffer(reservoirs.handle),
            reservoirs.offset, reservoirs.bytes);

        BedrayFrameDesc spatial_desc{};
        spatial_desc.abi_version = BEDRAY_CORE_ABI_VERSION;
        spatial_desc.command_buffer = desc.command_buffer;
        spatial_desc.program = program_spatial_;
        spatial_desc.group_count_x = groups(desc.camera.width);
        spatial_desc.group_count_y = groups(desc.camera.height);
        spatial_desc.group_count_z = 1;
        spatial_desc.resource_count = static_cast<std::uint32_t>(spatial_resources.size());
        spatial_desc.resources = spatial_resources.data();
        spatial_desc.push_constants = &spatial_push;
        spatial_desc.push_constant_bytes = spatial_push_bytes_;

        status = bedray_core_render_phases(core_, &spatial_desc, 8, 2, error);
        if (status != BEDRAY_OK) {
            return status;
        }
        compute_barrier(fns_, command);
    }

    std::uint32_t display_flags = BEDRAY_PATH_DISPLAY_TONEMAP;
    if ((desc.flags & BEDRAY_PATH_TRACE_FRAME_ACCUMULATE) != 0) {
        display_flags |= BEDRAY_PATH_DISPLAY_ACCUMULATE;
    }
    if ((desc.flags & BEDRAY_PATH_TRACE_FRAME_CAMERA_STATIC) != 0) {
        display_flags |= BEDRAY_PATH_DISPLAY_CAMERA_STATIC;
    }
    if ((desc.flags & BEDRAY_PATH_TRACE_FRAME_FLIP_Y) != 0) {
        display_flags |= BEDRAY_PATH_DISPLAY_FLIP_Y;
    }
    if (history_written_) {
        display_flags |= BEDRAY_PATH_DISPLAY_REPROJECT;
    }
    display_flags |= desc.display_flags;

    PathDisplayPush display_push{};
    // A reset means the accumulation half is fresh; a non-zero count would let
    // the same-pixel branch read a history image that was just recreated.
    display_push.accum_frames = reset_history ? 0u : desc.accum_frames;
    display_push.flags = display_flags;
    display_push.exposure = desc.lighting.exposure;
    display_push.max_radiance = desc.lighting.max_radiance > 0.0f
        ? desc.lighting.max_radiance
        : desc.path.max_radiance;
    display_push.reuse[0] = desc.path.di_camera_delta[0];
    display_push.reuse[1] = desc.path.di_camera_delta[1];
    display_push.reuse[2] = desc.path.di_camera_delta[2];
    display_push.reuse[3] = desc.lighting.reprojection_tolerance > 0.0f
        ? desc.lighting.reprojection_tolerance
        : 0.02f;

    std::array<BedrayResource, 9> display_resources{};
    display_resources[0] = image_resource(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, radiance_view_.view);
    display_resources[1] = image_resource(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, accum_view_[write].view);
    display_resources[2] = image_resource(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, as_view(desc.output_view));
    display_resources[3] = image_resource(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, as_view(desc.depth_view));
    display_resources[4] = image_resource(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        history_depth_view_[write].view);
    display_resources[5] = uniform_resource(5, frame_view.buffer, 0, sizeof(FrameConstants));
    display_resources[6] = image_resource(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, motion_view_.view);
    display_resources[7] = image_resource(7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, accum_view_[read].view);
    display_resources[8] = image_resource(8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        history_depth_view_[read].view);

    BedrayFrameDesc display_desc{};
    display_desc.abi_version = BEDRAY_CORE_ABI_VERSION;
    display_desc.command_buffer = desc.command_buffer;
    display_desc.program = program_display_;
    display_desc.group_count_x = groups(desc.camera.width);
    display_desc.group_count_y = groups(desc.camera.height);
    display_desc.group_count_z = 1;
    display_desc.resource_count = static_cast<std::uint32_t>(display_resources.size());
    display_desc.resources = display_resources.data();
    display_desc.push_constants = &display_push;
    display_desc.push_constant_bytes = static_cast<std::uint32_t>(sizeof(display_push));

    status = bedray_core_render_frame(core_, &display_desc, error);
    if (status != BEDRAY_OK) {
        return status;
    }

    history_written_ = true;
    history_parity_ ^= 1u;
    if (native_di_active) {
        owned_di_history_ = true;
        owned_di_needs_clear_ = false;
        owned_di_parity_ ^= 1u;
    }

    exit_barrier(fns_, command);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace frame state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path trace");
}

BedrayStatus PathTracePass::release_recording(std::uint64_t command_buffer) noexcept {
    const auto it = recordings_.find(command_buffer);
    if (it == recordings_.end()) {
        return BEDRAY_OK;
    }
    if (images_) {
        for (const gpu::UseId token : it->second.image_uses) {
            static_cast<void>(images_->complete_use(token));
        }
    }
    if (buffers_) {
        for (const gpu::UseId token : it->second.buffer_uses) {
            static_cast<void>(buffers_->complete_use(token));
        }
    }
    recordings_.erase(it);
    if (images_) {
        static_cast<void>(images_->collect());
    }
    if (buffers_) {
        static_cast<void>(buffers_->collect());
    }
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::destroy() noexcept {
    if (!recordings_.empty()) {
        return BEDRAY_ERROR_IN_USE;
    }
    if (images_) {
        if (lost_) {
            static_cast<void>(images_->release_after_device_loss());
        } else {
            if (images_ready_) {
                for (const gpu::ImageId id : {radiance_id_, motion_id_, accum_id_[0], accum_id_[1],
                         history_depth_id_[0], history_depth_id_[1]}) {
                    if (id.serial != 0) {
                        static_cast<void>(images_->retire(id));
                    }
                }
            }
            static_cast<void>(images_->collect());
            const Status status = images_->close();
            if (status != Status::ok && status != Status::device_lost) {
                return BEDRAY_ERROR_IN_USE;
            }
        }
        images_.reset();
    }
    if (buffers_) {
        if (lost_) {
            static_cast<void>(buffers_->release_after_device_loss());
        } else {
            for (const gpu::BufferId id : {block_materials_id_, reservoir_dummy_id_, owned_reservoirs_id_,
                     entity_scalar_id_, entity_vector_id_}) {
                if (id.serial != 0) {
                    static_cast<void>(buffers_->retire(id));
                }
            }
            static_cast<void>(buffers_->retire_uploads());
            static_cast<void>(buffers_->collect());
            const Status status = buffers_->close();
            if (status != Status::ok && status != Status::device_lost) {
                return BEDRAY_ERROR_IN_USE;
            }
        }
        buffers_.reset();
    }
    reset_state();
    core_ = nullptr;
    fns_ = {};
    program_trace_ = 0;
    program_trace_scene_ = 0;
    program_spatial_ = 0;
    program_display_ = 0;
    spatial_push_bytes_ = 0;
    return BEDRAY_OK;
}

void PathTracePass::device_lost() noexcept {
    lost_ = true;
    if (images_) {
        images_->device_lost();
    }
    if (buffers_) {
        buffers_->device_lost();
    }
}

void PathTracePass::reset_state() noexcept {
    radiance_id_ = {};
    motion_id_ = {};
    accum_id_[0] = {};
    accum_id_[1] = {};
    history_depth_id_[0] = {};
    history_depth_id_[1] = {};
    radiance_view_ = {};
    motion_view_ = {};
    accum_view_[0] = {};
    accum_view_[1] = {};
    history_depth_view_[0] = {};
    history_depth_view_[1] = {};
    images_ready_ = false;
    images_initialized_ = false;
    image_width_ = 0;
    image_height_ = 0;
    block_materials_id_ = {};
    reservoir_dummy_id_ = {};
    owned_reservoirs_id_ = {};
    owned_reservoirs_view_ = {};
    owned_di_parity_ = 0;
    owned_di_history_ = false;
    owned_di_needs_clear_ = false;
    entity_scalar_id_ = {};
    entity_vector_id_ = {};
    block_materials_view_ = {};
    reservoir_dummy_view_ = {};
    entity_scalar_view_ = {};
    entity_vector_view_ = {};
    history_parity_ = 0;
    history_written_ = false;
    lost_ = false;
}

} // namespace bedray::core
