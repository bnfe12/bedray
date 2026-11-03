#include "bedray/history_slots.hpp"

namespace bedray {
namespace {

bool image_valid(const HistoryImage& image) noexcept {
    return image.image != 0 && image.memory != 0 && image.size != 0 &&
           image.offset <= UINT64_MAX - image.size;
}

bool ordering_valid(HistoryOrdering ordering) noexcept {
    return ordering == HistoryOrdering::completed_only || ordering == HistoryOrdering::same_queue_barrier ||
           ordering == HistoryOrdering::cross_queue_semaphore;
}

std::uint64_t saturating_add(std::uint64_t total, std::uint64_t value) noexcept {
    return value > UINT64_MAX - total ? UINT64_MAX : total + value;
}

} // namespace

// Same VkImage, or overlapping declared ranges of the same memory object.
bool history_images_overlap(const HistoryImage& a, const HistoryImage& b) noexcept {
    if (a.image == b.image) return true;
    if (a.memory != b.memory) return false;
    return a.offset < b.offset + b.size && b.offset < a.offset + a.size;
}

bool history_image_set_valid(const HistoryImageSet& set) noexcept {
    if (set.count < 2 || set.count > kMaxHistorySlots) return false;
    for (std::uint32_t i = 0; i < set.count; ++i) {
        if (!image_valid(set.images[i])) return false;
        for (std::uint32_t j = 0; j < i; ++j) {
            if (history_images_overlap(set.images[i], set.images[j])) return false;
        }
    }
    return true;
}

std::uint64_t history_image_set_bytes(const HistoryImageSet& set) noexcept {
    std::uint64_t total = 0;
    for (std::uint32_t i = 0; i < set.count && i < kMaxHistorySlots; ++i) {
        total = saturating_add(total, set.images[i].size);
    }
    return total;
}

std::uint64_t HistorySlots::configured_bytes() const noexcept {
    return history_image_set_bytes(images_);
}

void HistorySlots::hand_back(std::uint64_t device_generation, bool completion_known,
                             RetiredHistoryImages& old) const noexcept {
    old = RetiredHistoryImages{};
    old.count = images_.count;
    old.device_generation = device_generation;
    old.completion_known = completion_known;
    for (std::uint32_t i = 0; i < images_.count; ++i) {
        old.images[i] = images_.images[i];
        old.uses[i] = uses_[i];
    }
}

void HistorySlots::clear() noexcept {
    images_ = HistoryImageSet{};
    uses_ = {};
    latest_slot_ = -1;
    latest_queue_ = 0;
    latest_serial_ = 0;
}

void HistorySlots::configure(const HistoryImageSet& images, std::uint64_t device_generation,
                             RetiredHistoryImages& old) noexcept {
    hand_back(device_generation, true, old);
    clear();
    images_ = images;
    state_.invalidate(HistoryReset::reconfigured);
}

void HistorySlots::release(std::uint64_t device_generation, RetiredHistoryImages& old) noexcept {
    hand_back(device_generation, true, old);
    clear();
    state_.invalidate(HistoryReset::reconfigured);
}

HistoryStatus HistorySlots::prepare(const HistoryKey& key, std::uint64_t frame, std::uint32_t queue,
                                    HistoryOrdering ordering, const QueueTimeline& timeline,
                                    std::uint64_t token, HistoryRecording& out) noexcept {
    if (!timeline.active()) return HistoryStatus::device_unavailable;
    if (!configured()) return HistoryStatus::not_configured;
    if (pending_) return attached_ ? HistoryStatus::recording_attached : HistoryStatus::recording_pending;
    if (queue >= kMaxHistoryQueues || !ordering_valid(ordering)) return HistoryStatus::invalid_input;
    if (key.device_generation != timeline.device_generation) return HistoryStatus::invalid_input;
    if (token == 0) return HistoryStatus::token_exhausted;
    if (!state_.accepts_frame(frame)) return HistoryStatus::stale_frame;

    HistoryReset reset = state_.evaluate(key, frame);
    if (reset == HistoryReset::invalid_input) return HistoryStatus::invalid_input;
    if (!any(reset) && latest_slot_ < 0) reset = HistoryReset::first_frame; // nothing to read

    // The committed slot is never written: a cancelled recording must leave the
    // previous history intact, and in-flight frames may still read it.
    std::int32_t write = -1;
    std::int32_t ordered = -1;
    for (std::uint32_t i = 0; i < images_.count; ++i) {
        if (static_cast<std::int32_t>(i) == latest_slot_) continue;
        const QueueUses& uses = uses_[i];
        if (timeline.finished(uses)) {
            write = static_cast<std::int32_t>(i);
            break;
        }
        // An unknown use can never be ordered against: nobody knows when it ends.
        if (ordered >= 0 || uses.unknown || ordering == HistoryOrdering::completed_only) continue;
        bool allowed = true;
        if (ordering == HistoryOrdering::same_queue_barrier) {
            for (std::uint32_t q = 0; q < kMaxHistoryQueues; ++q) {
                if (q != queue && uses.serial[q] > timeline.completed[q]) allowed = false;
            }
        }
        if (allowed) ordered = static_cast<std::int32_t>(i);
    }
    if (write < 0) write = ordered;
    if (write < 0) return HistoryStatus::write_unavailable;

    HistoryRecording record;
    record.token = token;
    record.frame = frame;
    record.queue = queue;
    record.reset = reset;
    record.write_slot = static_cast<std::uint32_t>(write);
    record.write_image = images_.images[write].image;
    for (std::uint32_t q = 0; q < kMaxHistoryQueues; ++q) {
        const std::uint64_t use = uses_[write].serial[q];
        record.write_after.serial[q] = use > timeline.completed[q] ? use : 0;
    }
    if (!any(reset)) {
        record.read_slot = latest_slot_;
        record.read_image = images_.images[latest_slot_].image;
        record.read_producer_queue = latest_queue_;
        record.read_producer_serial = latest_serial_;
    }

    pending_key_ = key;
    pending_record_ = record;
    pending_ = true;
    attached_ = false;
    discard_pending_result_ = false;
    out = record;
    return HistoryStatus::ok;
}

HistoryStatus HistorySlots::attach(std::uint64_t token, std::uint32_t queue, bool reset_applied) noexcept {
    if (!pending_ || token != pending_record_.token) return HistoryStatus::unknown_token;
    if (attached_) return HistoryStatus::recording_attached;
    if (queue != pending_record_.queue) return HistoryStatus::invalid_input;
    // Refused here, before submission, so that nothing has to be undone later.
    if (any(pending_record_.reset) && !reset_applied) return HistoryStatus::reset_not_applied;
    pending_reset_applied_ = reset_applied;
    attached_ = true;
    return HistoryStatus::ok;
}

HistoryStatus HistorySlots::invalidate() noexcept {
    if (pending_) return attached_ ? HistoryStatus::recording_attached : HistoryStatus::recording_pending;
    state_.invalidate(HistoryReset::requested);
    return HistoryStatus::ok;
}

void HistorySlots::invalidate_after_pending() noexcept {
    if (pending_) {
        discard_pending_result_ = true;
        return;
    }
    state_.invalidate(HistoryReset::requested);
}

HistoryStatus HistorySlots::cancel(std::uint64_t token) noexcept {
    if (!pending_ || token != pending_record_.token) return HistoryStatus::unknown_token;
    if (attached_) return HistoryStatus::recording_attached;
    pending_ = false;
    apply_deferred_reset();
    return HistoryStatus::ok;
}

void HistorySlots::apply_deferred_reset() noexcept {
    // invalidate_after_pending() promised a reset for every frame after the
    // pending one; a recording that ends without committing must not drop it.
    if (!discard_pending_result_) return;
    discard_pending_result_ = false;
    state_.invalidate(HistoryReset::requested);
}

void HistorySlots::finish(SubmitOutcome outcome, std::uint64_t serial) noexcept {
    const HistoryRecording record = pending_record_;
    pending_ = false;
    attached_ = false;
    if (outcome == SubmitOutcome::rejected) {
        // Nothing reached the queue: no use is recorded, but a deferred reset still applies.
        apply_deferred_reset();
        return;
    }

    // The GPU may reference these images now: record the use before anything else.
    const std::uint32_t queue = record.queue;
    uses_[record.write_slot].serial[queue] = serial;
    if (record.read_slot >= 0) uses_[record.read_slot].serial[queue] = serial;

    if (outcome == SubmitOutcome::unknown) {
        uses_[record.write_slot].unknown = true;
        if (record.read_slot >= 0) uses_[record.read_slot].unknown = true;
        discard_pending_result_ = false;            // subsumed by the reset below
        state_.invalidate(HistoryReset::requested); // the written data may not exist
        latest_slot_ = -1;
        return;
    }

    if (discard_pending_result_) {
        // invalidate_after_pending(): the work ran and its use is recorded above,
        // but its output is not history.
        apply_deferred_reset();
        latest_slot_ = -1;
        return;
    }
    // Defensive: invalidate() refuses while pending, so the state cannot change
    // between prepare and finish; a refused commit still keeps the recorded use.
    if (!state_.commit(pending_key_, record.frame, pending_reset_applied_)) {
        state_.invalidate(HistoryReset::requested);
        latest_slot_ = -1;
        return;
    }
    latest_slot_ = static_cast<std::int32_t>(record.write_slot);
    latest_queue_ = queue;
    latest_serial_ = serial;
}

void HistorySlots::device_lost(std::uint64_t device_generation, RetiredHistoryImages& old) noexcept {
    pending_ = false;
    attached_ = false;
    discard_pending_result_ = false;
    hand_back(device_generation, false, old);
    clear();
    state_.invalidate(HistoryReset::device_lost);
}

void HistorySlots::clear_unknown_uses() noexcept {
    for (QueueUses& uses : uses_) uses.unknown = false;
}

} // namespace bedray
