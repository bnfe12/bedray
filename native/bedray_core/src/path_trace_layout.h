#pragma once

// Shared path-trace push/frame layouts and the free helpers every path-trace
// translation unit records with. The white-box pass test includes
// path_trace_pass.cpp directly, so these keep the bedray::core names and
// visibility the pass and its test expect; inline keeps them /W4-clean when a
// translation unit uses only a subset.

#include "core_internal.h"

#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <limits>

namespace bedray::core {

using gpu::Status;

inline constexpr std::uint32_t kThreadGroup = 8u;

// restir_spatial.slang: RESTIR_SPATIAL_ENABLED, the state.w bit that lets the
// shader pool the records the trace pass just wrote.
inline constexpr std::uint32_t kSpatialEnabled = 1u << 0u;

// The four head words of the slot the borrowed range must cover: hits, samples,
// lumLogSum and histAccepted, one uint each.
inline constexpr std::uint64_t kStatsCounterBytes = 4u * sizeof(std::uint32_t);
static_assert(kStatsCounterBytes == 16, "frame_stats adds into stats[0..3]");

static_assert(sizeof(BedrayPathTraceGuides) == 64);
static_assert(offsetof(BedrayPathTraceGuides, view_z) == 8);
static_assert(offsetof(BedrayPathTraceGuides, specular_albedo) == 56);

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
    std::uint32_t entity_atlas_size; // texels per side of the captured entity atlas; 0 = no atlas uploaded
    float tail[3];
};
static_assert(sizeof(SpatialPush64) == 64, "SpatialPush64 must stay 64 bytes");
static_assert(offsetof(SpatialPush64, entity_atlas_size) == 48, "SpatialPush64.entityAtlasSize offset");

// passes/frame_stats.slang: FrameStatsPush, 8 bytes. The statistics stage meters
// one whole 4x4 tile in sixteen, rotated by the frame index, and InterlockedAdds
// the four counters into stats[0..3] of the host's slot.
struct FrameStatsPush {
    std::uint32_t frame_index;
    std::uint32_t reserved;
};
static_assert(sizeof(FrameStatsPush) == 8, "FrameStatsPush must stay 8 bytes");
static_assert(offsetof(FrameStatsPush, frame_index) == 0, "FrameStatsPush.frameIndex offset");
static_assert(offsetof(FrameStatsPush, reserved) == 4, "FrameStatsPush.reserved offset");

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

inline BedrayStatus status_of(Status status) {
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
inline std::uint64_t handle_bits(T handle) {
    return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(handle));
}

inline VkBuffer as_buffer(std::uint64_t handle) {
    return reinterpret_cast<VkBuffer>(static_cast<std::uintptr_t>(handle));
}

inline VkImageView as_view(std::uint64_t handle) {
    return reinterpret_cast<VkImageView>(static_cast<std::uintptr_t>(handle));
}

inline VkSampler as_sampler(std::uint64_t handle) {
    return reinterpret_cast<VkSampler>(static_cast<std::uintptr_t>(handle));
}

inline VkAccelerationStructureKHR as_acceleration(std::uint64_t handle) {
    return reinterpret_cast<VkAccelerationStructureKHR>(static_cast<std::uintptr_t>(handle));
}

inline VkCommandBuffer as_command_buffer(std::uint64_t handle) {
    return reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(handle));
}

inline BedrayResource image_resource(std::uint32_t binding, VkDescriptorType type,
    VkImageView view, VkSampler sampler = VK_NULL_HANDLE) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(type);
    resource.image_layout = static_cast<std::uint32_t>(VK_IMAGE_LAYOUT_GENERAL);
    resource.handle = handle_bits(view);
    resource.sampler = handle_bits(sampler);
    return resource;
}

inline BedrayResource buffer_resource(std::uint32_t binding, VkBuffer buffer,
    std::uint64_t offset, std::uint64_t bytes) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    resource.handle = handle_bits(buffer);
    resource.offset = offset;
    resource.range = bytes;
    return resource;
}

inline BedrayResource uniform_resource(std::uint32_t binding, VkBuffer buffer,
    std::uint64_t offset, std::uint64_t bytes) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    resource.handle = handle_bits(buffer);
    resource.offset = offset;
    resource.range = bytes;
    return resource;
}

inline BedrayResource acceleration_resource(std::uint32_t binding, VkAccelerationStructureKHR acceleration) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = static_cast<std::uint32_t>(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
    resource.handle = handle_bits(acceleration);
    return resource;
}

inline BedrayPathTraceBuffer buffer_from_view(const gpu::BufferView& view) {
    BedrayPathTraceBuffer buffer{};
    buffer.handle = handle_bits(view.buffer);
    buffer.offset = 0;
    buffer.bytes = view.size;
    return buffer;
}

inline BedrayPathTraceRange range_from_view(const gpu::BufferView& view) {
    BedrayPathTraceRange range{};
    range.handle = handle_bits(view.buffer);
    range.offset = 0;
    range.bytes = view.size;
    return range;
}

inline std::uint32_t groups(std::uint32_t extent) {
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
inline bool has_bindings(BedrayCore* core, std::uint64_t program,
    const ExpectedBinding* expected, std::size_t count) {
    for (std::size_t i = 0; i < count; ++i) {
        if (!program_has_binding(core, program, expected[i].binding, expected[i].descriptor_type)) {
            return false;
        }
    }
    return true;
}

inline void transition_image(const DeviceFns& fns, VkCommandBuffer command, VkImage image,
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
        fns.path_shader_stages, fns.path_shader_stages,
        0, 0, nullptr, 0, nullptr, 1, &barrier);
}

inline void entry_barrier(const DeviceFns& fns, VkCommandBuffer command) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_MEMORY_WRITE_BIT);
    barrier.dstAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
        | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    fns.cmd_pipeline_barrier(command,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, fns.path_shader_stages,
        0, 1, &barrier, 0, nullptr, 0, nullptr);
}

inline void compute_barrier(const DeviceFns& fns, VkCommandBuffer command) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_SHADER_WRITE_BIT);
    barrier.dstAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    fns.cmd_pipeline_barrier(command,
        fns.path_shader_stages, fns.path_shader_stages,
        0, 1, &barrier, 0, nullptr, 0, nullptr);
}

inline void exit_barrier(const DeviceFns& fns, VkCommandBuffer command) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_MEMORY_WRITE_BIT);
    barrier.dstAccessMask = static_cast<VkAccessFlags>(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    fns.cmd_pipeline_barrier(command,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &barrier, 0, nullptr, 0, nullptr);
}

inline void copy4(float (&destination)[4], const float (&source)[4]) {
    std::memcpy(destination, source, sizeof(destination));
}


} // namespace bedray::core
