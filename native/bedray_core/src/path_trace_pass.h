#pragma once

#include "bedray_core.h"
#include "core_internal.h"
#include "rc_field_pass.h"

#include <bedray/gpu_buffers.hpp>
#include <bedray/gpu_images.hpp>

#include <cstdint>
#include <array>
#include <map>
#include <memory>
#include <vector>

namespace bedray::core {

// Internal owner of the wired path-tracing frame:
//
//   path_trace.slang  ->  restir_spatial.slang (optional)  ->  path_display.slang
//     ->  frame_stats.slang (additive call, only when the host lends the program)
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

    BedrayStatus configure_rc(const BedrayRCDesc& desc, std::uint32_t max_groups_x, BedrayError* error);

    BedrayStatus render(const BedrayPathTraceFrameDesc& desc, BedrayError* error,
        const BedrayPathTraceGuides* guides = nullptr, const BedrayPathTraceBuffer* motion = nullptr);

    // Statistics stage of a recording the render call above just made: one
    // frame_stats dispatch over the recording's own radiance, the accumulation
    // image the display stage folded and the borrowed depth view, adding into
    // the borrowed StatsBuf range. Refuses instead of skipping when the pass has
    // no statistics program or the recording is gone, because a silent skip is
    // indistinguishable from a frame whose counters were always zero.
    BedrayStatus record_stats(std::uint64_t command_buffer, std::uint64_t stats_buffer,
        std::uint64_t stats_offset, std::uint64_t stats_bytes, std::uint32_t frame_index,
        BedrayError* error);

    // Idempotent for an unknown command buffer. Completes the pass's own
    // image/buffer uses for that recording and then collects retired objects.
    void discard_recording(std::uint64_t command_buffer) noexcept;
    BedrayStatus release_recording(std::uint64_t command_buffer) noexcept;

    [[nodiscard]] bool has_recordings() const noexcept {
        return !recordings_.empty() || (rc_field_ && rc_field_->has_recordings());
    }

    // True while the configured pipeline still needs this registered program.
    // The core defers destroying such a program instead of leaving the pass
    // with a dangling handle.
    [[nodiscard]] bool uses_program(std::uint64_t program) const noexcept {
        return program != 0
            && (program == program_trace_ || program == program_trace_scene_
                || program == program_spatial_ || program == program_display_
                || program == program_stats_
                || (rc_field_ && rc_field_->uses_program(program)));
    }

    // Refuses while any recording still pins a resource. The core already
    // refuses destruction while its own recordings exist.
    BedrayStatus destroy() noexcept;

private:
    struct RecordingPins {
        std::vector<gpu::UseId> image_uses;
        std::vector<gpu::UseId> buffer_uses;
        // What the statistics stage reads back. The views are copies of handles
        // this recording already pins, so a later resize cannot make the stage
        // bind an image newer than the one the display dispatch folded; the two
        // borrowed handles are never retained and must not outlive the call.
        gpu::ImageView radiance{};
        gpu::ImageView accum{};
        std::uint64_t depth_view = 0;
        std::uint32_t width = 0;
        std::uint32_t height = 0;
    };

    BedrayStatus ensure_images(std::uint32_t width, std::uint32_t height, BedrayError* error);
    BedrayStatus create_guide_dummies(BedrayError* error);
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
    std::uint64_t program_stats_ = 0;
    std::uint32_t spatial_push_bytes_ = 0;

    std::unique_ptr<gpu::GpuImages> images_;
    std::unique_ptr<gpu::GpuBuffers> buffers_;
    std::unique_ptr<RCFieldPass> rc_field_;

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
    bool trace_guide_bindings_ = false, scene_guide_bindings_ = false;
    bool trace_motion_bindings_ = false, scene_motion_bindings_ = false;
    bool scene_field_bindings_ = false;
    // The program declares the ReSTIR PT ring at binding 36; the core binds pt_dummy_ and clears the PT push bits.
    bool trace_pt_binding_ = false, scene_pt_binding_ = false;
    bool guide_dummies_initialized_ = false;
    std::array<gpu::ImageId, 3> guide_dummy_ids_{};
    std::array<gpu::ImageView, 3> guide_dummy_views_{};
    std::uint32_t image_width_ = 0;
    std::uint32_t image_height_ = 0;

    gpu::BufferId block_materials_id_{};
    gpu::BufferId reservoir_dummy_id_{};
    gpu::BufferId entity_scalar_id_{};
    gpu::BufferId entity_vector_id_{};
    // One-texel stand-ins for the seven DLSS-RR guide images and a 16-byte stand-in for NRD's IN_MV vec4 the Slang
    // pass declares. The C++ host never sets the option bits that would write them, so they only keep the
    // descriptor set complete.
    gpu::BufferId motion_dummy_id_{};
    gpu::BufferId pt_dummy_id_{};
    gpu::BufferView block_materials_view_{};
    gpu::BufferView reservoir_dummy_view_{};
    gpu::BufferView entity_scalar_view_{};
    gpu::BufferView entity_vector_view_{};
    gpu::BufferView motion_dummy_view_{};
    gpu::BufferView pt_dummy_view_{};
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
