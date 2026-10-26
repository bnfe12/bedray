#pragma once

#include <cstdint>

namespace bedray {

// CPU-side history eligibility policy for ONE signal; shader reprojection still
// validates each pixel. All generations are supplied by the resource/scene
// owners, never by a GPU name or a fixed number of elapsed frames. Zero is
// reserved as "unknown" for the mandatory generations and is rejected.
struct HistoryKey {
    std::uint64_t device_generation;   // bumps on device (re)creation and device loss
    std::uint64_t target_generation;   // render target / swapchain-derived resources
    std::uint64_t pipeline_generation; // this signal's own pipelines and their data encoding
    std::uint64_t scene_generation;    // narrowest scene revision the signal's owner can prove
    std::uint64_t camera_epoch;        // bumps on an explicit camera cut / teleport
    std::uint64_t signal_revision;     // method-owned: layout, light set, clipmap origin policy...
    std::uint32_t width;               // extent of THIS signal's history, not necessarily the target
    std::uint32_t height;
};

enum class HistoryReset : std::uint32_t {
    none = 0,
    first_frame = 1u << 0,
    device = 1u << 1,
    target = 1u << 2,
    pipeline = 1u << 3,
    scene = 1u << 4,
    camera = 1u << 5,
    extent = 1u << 6,
    invalid_input = 1u << 7,
    discontinuity = 1u << 8,
    signal = 1u << 9,      // signal_revision changed
    requested = 1u << 10,  // explicit invalidate() by the signal owner
    device_lost = 1u << 11,
    reconfigured = 1u << 12, // the signal's images were replaced
};

constexpr HistoryReset operator|(HistoryReset left, HistoryReset right) noexcept {
    return static_cast<HistoryReset>(static_cast<std::uint32_t>(left) | static_cast<std::uint32_t>(right));
}

constexpr HistoryReset operator&(HistoryReset left, HistoryReset right) noexcept {
    return static_cast<HistoryReset>(static_cast<std::uint32_t>(left) & static_cast<std::uint32_t>(right));
}

constexpr bool any(HistoryReset reset) noexcept {
    return reset != HistoryReset::none;
}

// Reasons that ALWAYS reset, whatever the signal says: without them the stored
// data is meaningless or unreadable.
inline constexpr HistoryReset kMandatoryResets =
    HistoryReset::first_frame | HistoryReset::device | HistoryReset::pipeline | HistoryReset::extent |
    HistoryReset::signal | HistoryReset::requested | HistoryReset::device_lost | HistoryReset::reconfigured;

// Per-signal sensitivity. A world-space signal (Radiance Cascades probes) may
// legitimately survive a target resize; a screen-space one may not. A bit may be
// cleared only by an owner who can prove that change cannot affect the data.
struct HistoryResetPolicy {
    HistoryReset sensitive_to = HistoryReset::target | HistoryReset::scene | HistoryReset::camera |
                                HistoryReset::discontinuity;
};

// A key becomes committed only after the host accepts the corresponding
// recording for submission. Failed/discarded recordings do not advance history.
// If evaluate() requests a reset, commit requires explicit confirmation that
// this submission does not read the previous history. A submission is not proof
// of GPU completion: resource lifetime and barriers are a separate contract.
class HistoryState final {
public:
    explicit HistoryState(HistoryResetPolicy policy = {}) noexcept : policy_(policy) {}

    // Returns the reasons the next frame must not read history (filtered by the
    // policy), or invalid_input alone when the key/frame itself is unusable.
    [[nodiscard]] HistoryReset evaluate(const HistoryKey& next, std::uint64_t frame) const noexcept;
    [[nodiscard]] bool commit(const HistoryKey& next, std::uint64_t frame, bool reset_applied) noexcept;
    [[nodiscard]] bool accepts_frame(std::uint64_t frame) const noexcept {
        return frame != 0 && (!has_committed_frame_ || frame > frame_);
    }
    // Drops the logical history. `reason` must be one of requested,
    // device_lost or reconfigured; it is reported by the next evaluate().
    void invalidate(HistoryReset reason) noexcept;
    [[nodiscard]] bool has_history() const noexcept { return valid_; }
    [[nodiscard]] std::uint64_t committed_frame() const noexcept { return frame_; }

private:
    HistoryResetPolicy policy_;
    HistoryKey committed_{};
    std::uint64_t frame_ = 0;
    HistoryReset pending_reason_ = HistoryReset::first_frame;
    bool valid_ = false;
    bool has_committed_frame_ = false;
};

} // namespace bedray
