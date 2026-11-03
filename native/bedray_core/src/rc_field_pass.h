#pragma once
#include "bedray_rc.h"
#include "core_internal.h"
#include <array>
#include <map>
#include <memory>
#include <span>

namespace bedray::core {
struct RCFieldMetadata {
    std::uint32_t header[4];
    std::uint32_t counts[5][4];
    float origins[5][4];
    float ranges[5][4];
    std::uint32_t tag[4];
    float history[4];   // xyz: this raw camera minus the history field's, w: history weight (0 = none)
};
static_assert(sizeof(RCFieldMetadata) == 288);
static_assert(offsetof(RCFieldMetadata, counts) == 16);
static_assert(offsetof(RCFieldMetadata, origins) == 96);
static_assert(offsetof(RCFieldMetadata, ranges) == 176);
static_assert(offsetof(RCFieldMetadata, tag) == 256);
static_assert(offsetof(RCFieldMetadata, history) == 272);
// Weight of the previous recording's field in the temporal blend (batch 5.6): an exponential mean of ~5 frames.
inline constexpr float kRCFieldHistoryWeight = 0.8f;
bool rc_field_metadata(std::uint32_t base_axis, std::uint32_t frame, RCFieldMetadata&) noexcept;

// Per-recording immutable metadata/tails. The enclosing path owner supplies
// validated borrowed scene inputs and retains them through the same host proof.
// The tails of the last successful recording stay alive (not retired) as the next
// recording's history at binding 38; each recording that reads them pins them, and
// they are retired when a newer field replaces them, a recording fails, or the pass
// is destroyed.
class RCFieldPass final {
public:
    ~RCFieldPass();
    BedrayStatus configure(BedrayCore*, const DeviceFns&, const BedrayRCDesc&,
        std::uint32_t max_groups_x, BedrayError*);
    BedrayStatus record(const BedrayPathTraceFrameDesc&, std::span<const BedrayResource>,
        std::array<BedrayResource,2>& outputs, BedrayError*);
    BedrayStatus release_recording(std::uint64_t) noexcept;
    BedrayStatus destroy() noexcept;
    bool has_recordings() const noexcept { return !recordings_.empty(); }
    void device_lost() noexcept { lost_ = true; if (buffers_) buffers_->device_lost(); }
    bool uses_program(std::uint64_t h) const noexcept { return h && h == desc_.program_build; }
    // The recording that built the current history will not run (discarded, or failed after the field was recorded):
    // its tails were never written, so the next recording must start the field over.
    void forget_history() noexcept { drop_history(); if (buffers_ && !lost_) (void)buffers_->collect(); }
    bool has_history() const noexcept { return history_id_.serial != 0; }
private:
    struct Pins { std::array<gpu::UseId,3> uses{}; };   // metadata, tails, history
    BedrayStatus gpu_status(gpu::Status, VkResult, BedrayError*) noexcept;
    BedrayStatus allocate(std::uint64_t bytes, std::uint32_t usage, const void*,
        gpu::UseId&, gpu::BufferView&, BedrayError*, gpu::BufferId* keep = nullptr);
    void drop_history() noexcept;
    BedrayCore* core_ = nullptr;
    DeviceFns fns_{};
    BedrayRCDesc desc_{};
    std::unique_ptr<gpu::GpuBuffers> buffers_;
    std::map<std::uint64_t, Pins> recordings_;
    gpu::BufferId history_id_{};
    gpu::BufferView history_view_{};
    std::uint32_t history_frame_ = 0;
    bool lost_ = false;
};
}
