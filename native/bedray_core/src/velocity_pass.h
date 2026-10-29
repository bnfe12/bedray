#pragma once
#include "bedray_velocity.h"
#include "core_internal.h"
#include <map>
#include <memory>
#include <vector>

namespace bedray::core {
// Owns immutable per-recording uploads. Host owns all images/BDA allocations,
// command buffers and submission; no frame-count assumptions or queue waits.
class VelocityPass final {
public:
    ~VelocityPass();
    BedrayStatus configure(BedrayCore*, const DeviceFns&, const BedrayVelocityDesc&, BedrayError*);
    BedrayStatus render(const BedrayVelocityFrameDesc&, BedrayError*);
    BedrayStatus release_recording(std::uint64_t) noexcept;
    void discard_recording(std::uint64_t) noexcept {} // Stateless math; exact proof releases the upload.
    BedrayStatus destroy() noexcept;
    bool has_recordings() const noexcept { return !recordings_.empty(); }
    bool uses_program(std::uint64_t program) const noexcept { return program && program == desc_.program; }
private:
    BedrayStatus gpu_status(gpu::Status, VkResult, BedrayError*) noexcept;
    BedrayCore* core_ = nullptr;
    DeviceFns fns_{};
    BedrayVelocityDesc desc_{};
    std::unique_ptr<gpu::GpuBuffers> buffers_;
    std::map<std::uint64_t, std::vector<gpu::UseId>> recordings_;
    bool lost_ = false;
};
}
