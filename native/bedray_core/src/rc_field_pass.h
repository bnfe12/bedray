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
};
static_assert(sizeof(RCFieldMetadata) == 272);
static_assert(offsetof(RCFieldMetadata, counts) == 16);
static_assert(offsetof(RCFieldMetadata, origins) == 96);
static_assert(offsetof(RCFieldMetadata, ranges) == 176);
static_assert(offsetof(RCFieldMetadata, tag) == 256);
bool rc_field_metadata(std::uint32_t base_axis, std::uint32_t frame, RCFieldMetadata&) noexcept;

// Per-recording immutable metadata/tails. The enclosing path owner supplies
// validated borrowed scene inputs and retains them through the same host proof.
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
private:
    struct Pins { std::array<gpu::UseId,2> uses{}; };
    BedrayStatus gpu_status(gpu::Status, VkResult, BedrayError*) noexcept;
    BedrayStatus allocate(std::uint64_t bytes, std::uint32_t usage, const void*,
        gpu::UseId&, gpu::BufferView&, BedrayError*);
    BedrayCore* core_ = nullptr;
    DeviceFns fns_{};
    BedrayRCDesc desc_{};
    std::unique_ptr<gpu::GpuBuffers> buffers_;
    std::map<std::uint64_t, Pins> recordings_;
    bool lost_ = false;
};
}
