#pragma once

#include "bedray_core.h"
#include "core_internal.h"

#include <bedray/gpu_buffers.hpp>
#include <bedray/gpu_images.hpp>

#include <cstdint>
#include <map>
#include <memory>
#include <vector>

namespace bedray::core {

// Internal owner of the wired path-tracing frame:
//
//   path_trace.slang  ->  restir_spatial.slang (optional)  ->  path_display.slang
//
// The pass owns the radiance/motion/accumulation/history images and the
// per-recording FrameConstants upload. It records all dispatches through the
// core's existing bedray_core_render_frame/render_phases path, so descriptor
// pools, program retention and the host command buffer stay in one place.
//
// It never submits, waits for the device, creates a queue/swapchain or destroys
// a host-owned view. A recording's image/buffer uses are completed only by
// release_recording(), which the host calls after proven GPU completion or
// proven discard.
class PathTracePass final {
public:
    PathTracePass() = default;
    ~PathTracePass();

    PathTracePass(const PathTracePass&) = delete;
    PathTracePass& operator=(const PathTracePass&) = delete;

    BedrayStatus configure(BedrayCore* core, const DeviceFns& fns,
        const BedrayPathTraceDesc& desc, BedrayError* error);

    BedrayStatus render(const BedrayPathTraceFrameDesc& desc, BedrayError* error);

    // Idempotent for an unknown command buffer. Completes the pass's own
    // image/buffer uses for that recording and then collects retired objects.
    BedrayStatus release_recording(std::uint64_t command_buffer) noexcept;

    [[nodiscard]] bool has_recordings() const noexcept { return !recordings_.empty(); }

    // True while the configured pipeline still needs this registered program.
    // The core defers destroying such a program instead of leaving the pass
    // with a dangling handle.
    [[nodiscard]] bool uses_program(std::uint64_t program) const noexcept {
        return program != 0
            && (program == program_trace_ || program == program_trace_scene_
                || program == program_spatial_ || program == program_display_);
    }

    // Refuses while any recording still pins a resource. The core already
    // refuses destruction while its own recordings exist.
    BedrayStatus destroy() noexcept;

private:
    struct RecordingPins {
        std::vector<gpu::UseId> image_uses;
        std::vector<gpu::UseId> buffer_uses;
    };

    BedrayStatus ensure_images(std::uint32_t width, std::uint32_t height, BedrayError* error);
    BedrayStatus create_dummy(VkDeviceSize bytes, gpu::BufferId& id,
        gpu::BufferView& view, BedrayError* error);
    BedrayStatus ensure_reservoirs(std::uint64_t bytes, BedrayError* error);
    BedrayStatus pin_image(gpu::ImageId id, RecordingPins& pins, BedrayError* error);
    BedrayStatus pin_buffer(gpu::BufferId id, RecordingPins& pins, BedrayError* error);
    void device_lost() noexcept;
    void reset_state() noexcept;

    BedrayCore* core_ = nullptr;
    DeviceFns fns_{};
    std::uint64_t program_trace_ = 0;
    std::uint64_t program_trace_scene_ = 0;
    std::uint64_t program_spatial_ = 0;
    std::uint64_t program_display_ = 0;
    std::uint32_t spatial_push_bytes_ = 0;

    std::unique_ptr<gpu::GpuImages> images_;
    std::unique_ptr<gpu::GpuBuffers> buffers_;

    gpu::ImageId radiance_id_{};
    gpu::ImageId motion_id_{};
    gpu::ImageId accum_id_[2]{};
    gpu::ImageId history_depth_id_[2]{};
    gpu::ImageView radiance_view_{};
    gpu::ImageView motion_view_{};
    gpu::ImageView accum_view_[2]{};
    gpu::ImageView history_depth_view_[2]{};
    bool images_ready_ = false;
    bool images_initialized_ = false;
    std::uint32_t image_width_ = 0;
    std::uint32_t image_height_ = 0;

    gpu::BufferId block_materials_id_{};
    gpu::BufferId reservoir_dummy_id_{};
    gpu::BufferId entity_scalar_id_{};
    gpu::BufferId entity_vector_id_{};
    gpu::BufferView block_materials_view_{};
    gpu::BufferView reservoir_dummy_view_{};
    gpu::BufferView entity_scalar_view_{};
    gpu::BufferView entity_vector_view_{};
    gpu::BufferId owned_reservoirs_id_{};
    gpu::BufferView owned_reservoirs_view_{};
    std::uint32_t owned_di_parity_ = 0;
    bool owned_di_history_ = false;
    bool owned_di_needs_clear_ = false;

    std::uint32_t history_parity_ = 0;
    bool history_written_ = false;
    bool lost_ = false;

    std::map<std::uint64_t, RecordingPins> recordings_;
};

} // namespace bedray::core
