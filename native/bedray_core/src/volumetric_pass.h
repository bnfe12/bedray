#pragma once
#include "bedray_volumetric.h"
#include "core_internal.h"
#include <map>
#include <memory>

namespace bedray::core {
// Opaque march/apply group; one history/layout authority and exact host proof.
class VolumetricPass final {
public:
    ~VolumetricPass();
    BedrayStatus configure(BedrayCore*, const DeviceFns&, const BedrayVolumetricDesc&, BedrayError*);
    BedrayStatus render(const BedrayVolumetricFrameDesc&, BedrayError*);
    void discard_recording(std::uint64_t) noexcept;
    BedrayStatus release_recording(std::uint64_t) noexcept;
    BedrayStatus destroy() noexcept;
    bool has_recordings() const noexcept { return !recordings_.empty(); }
    bool uses_program(std::uint64_t) const noexcept;
private:
    struct Pins {
        gpu::UseId image{}, upload{};
        std::uint64_t generation = 0;
    };
    BedrayStatus ensure_image(std::uint32_t, std::uint32_t, BedrayError*);
    BedrayStatus gpu_status(gpu::Status, VkResult, BedrayError*) noexcept;
    BedrayStatus release_lost_owners() noexcept;
    BedrayCore* core_ = nullptr;
    DeviceFns fns_{};
    BedrayVolumetricDesc desc_{};
    std::unique_ptr<gpu::GpuImages> images_;
    std::unique_ptr<gpu::GpuBuffers> buffers_;
    gpu::ImageId history_id_{};
    gpu::ImageView history_view_{};
    std::map<std::uint64_t, Pins> recordings_;
    std::uint64_t generation_ = 0;
    std::uint32_t width_ = 0, height_ = 0, last_frame_ = 0;
    bool initialize_ = false, invalidated_ = false, history_written_ = false, lost_ = false;
};
}
