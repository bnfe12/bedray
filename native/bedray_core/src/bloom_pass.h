#pragma once
#include "bedray_bloom.h"
#include "core_internal.h"
#include <array>
#include <map>
#include <memory>

namespace bedray::core {
// The complete existing bloom chain. No submissions/waits, host-owned views,
// shader math or image formats are introduced by this recorder.
class BloomPass final {
public:
    ~BloomPass();
    BedrayStatus configure(BedrayCore*, const DeviceFns&, const BedrayBloomDesc&, BedrayError*);
    BedrayStatus render(const BedrayBloomFrameDesc&, BedrayError*);
    BedrayStatus release_recording(std::uint64_t) noexcept;
    void discard_recording(std::uint64_t) noexcept;
    BedrayStatus destroy() noexcept;
    bool has_recordings() const noexcept { return !recordings_.empty(); }
    bool uses_program(std::uint64_t) const noexcept;
    static std::uint32_t mip_size(std::uint32_t size, std::uint32_t level) noexcept;
private:
    static constexpr std::size_t levels = 6;
    struct Pins {
        std::array<gpu::UseId, levels> uses{};
        std::uint64_t generation = 0;
    };
    BedrayStatus ensure_images(std::uint32_t, std::uint32_t, BedrayError*);
    BedrayStatus gpu_status(gpu::Status, VkResult, BedrayError*) noexcept;
    BedrayCore* core_ = nullptr;
    DeviceFns fns_{};
    BedrayBloomDesc desc_{};
    std::unique_ptr<gpu::GpuImages> images_;
    std::array<gpu::ImageId, levels> ids_{};
    std::array<gpu::ImageView, levels> views_{};
    std::map<std::uint64_t, Pins> recordings_;
    std::uint32_t width_ = 0, height_ = 0;
    std::uint64_t generation_ = 0;
    bool initialize_ = false, invalidated_ = false, lost_ = false;
};
}
