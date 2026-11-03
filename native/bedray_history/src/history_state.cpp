#include "bedray/history_state.hpp"

namespace bedray {

HistoryReset HistoryState::evaluate(const HistoryKey& next, std::uint64_t frame) const noexcept {
    if (next.width == 0 || next.height == 0 || next.device_generation == 0 ||
        next.target_generation == 0 || next.pipeline_generation == 0 || frame == 0) {
        return HistoryReset::invalid_input;
    }
    // No committed history: never "none", whatever invalidate() was given.
    if (!valid_) return any(pending_reason_) ? pending_reason_ : HistoryReset::first_frame;

    HistoryReset reason = HistoryReset::none;
    if (next.device_generation != committed_.device_generation) reason = reason | HistoryReset::device;
    if (next.target_generation != committed_.target_generation) reason = reason | HistoryReset::target;
    if (next.pipeline_generation != committed_.pipeline_generation) reason = reason | HistoryReset::pipeline;
    if (next.scene_generation != committed_.scene_generation) reason = reason | HistoryReset::scene;
    if (next.camera_epoch != committed_.camera_epoch) reason = reason | HistoryReset::camera;
    if (next.signal_revision != committed_.signal_revision) reason = reason | HistoryReset::signal;
    if (next.width != committed_.width || next.height != committed_.height) reason = reason | HistoryReset::extent;
    // Subtract rather than incrementing frame_ so UINT64_MAX cannot wrap.
    if (frame <= frame_ || frame - frame_ != 1) reason = reason | HistoryReset::discontinuity;
    return reason & (kMandatoryResets | policy_.sensitive_to);
}

bool HistoryState::commit(const HistoryKey& next, std::uint64_t frame, bool reset_applied) noexcept {
    const HistoryReset reset = evaluate(next, frame);
    if (reset == HistoryReset::invalid_input) return false;
    if (any(reset) && !reset_applied) return false;
    // An older or duplicated submission may not replace newer history. A
    // forward gap is allowed, but evaluate() reports a required reset.
    if (!accepts_frame(frame)) return false;
    committed_ = next;
    frame_ = frame;
    valid_ = true;
    has_committed_frame_ = true;
    pending_reason_ = HistoryReset::none;
    return true;
}

void HistoryState::invalidate(HistoryReset reason) noexcept {
    // The committed frame number survives: an older submission still cannot
    // replace history after invalidation.
    valid_ = false;
    pending_reason_ = pending_reason_ | (any(reason) ? reason : HistoryReset::requested);
}

} // namespace bedray
