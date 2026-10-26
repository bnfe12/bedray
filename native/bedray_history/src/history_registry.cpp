#include "bedray/history_registry.hpp"

#include <new>

namespace bedray {
namespace {

std::uint64_t saturating_add(std::uint64_t total, std::uint64_t value) noexcept {
    return value > UINT64_MAX - total ? UINT64_MAX : total + value;
}

std::uint64_t record_bytes(const RetiredHistoryImages& record) noexcept {
    std::uint64_t total = 0;
    for (std::uint32_t i = 0; i < record.count; ++i) total = saturating_add(total, record.images[i].size);
    return total;
}

} // namespace

HistoryRegistry::Entry* HistoryRegistry::find(SignalId id) noexcept {
    for (Entry& entry : entries_) {
        if (entry.id == id) return &entry;
    }
    return nullptr;
}

std::size_t HistoryRegistry::configured_signals() const noexcept {
    std::size_t count = 0;
    for (const Entry& entry : entries_) count += entry.slots.configured() ? 1 : 0;
    return count;
}

bool HistoryRegistry::overlaps_known_image(const HistoryImage& image) const noexcept {
    for (const Entry& entry : entries_) {
        const HistoryImageSet& set = entry.slots.images();
        for (std::uint32_t i = 0; i < set.count; ++i) {
            if (history_images_overlap(image, set.images[i])) return true;
        }
    }
    for (const RetiredHistoryImages& record : retained_) {
        for (std::uint32_t i = 0; i < record.count; ++i) {
            if (history_images_overlap(image, record.images[i])) return true;
        }
    }
    return false;
}

bool HistoryRegistry::ticket_open(const SubmissionTicket& ticket) const noexcept {
    return open_valid_ && ticket.id == open_.id && ticket.device_generation == open_.device_generation &&
           ticket.queue == open_.queue && ticket.serial == open_.serial;
}

void HistoryRegistry::retain(const RetiredHistoryImages& record) noexcept {
    if (record.count == 0) return;
    // Capacity is guaranteed by the invariant documented on retained_.
    retained_.push_back(record);
}

// --- device lineage -----------------------------------------------------------

RegistryStatus HistoryRegistry::begin_device(std::uint64_t generation) noexcept {
    if (timeline_.active()) return RegistryStatus::device_unresolved;
    if (generation == 0 || generation <= last_device_generation_) return RegistryStatus::invalid_serial;
    // Records of an earlier device hold that device's handles; they must be
    // handed back before a new epoch can begin.
    if (!retained_.empty()) return RegistryStatus::device_unresolved;
    timeline_ = QueueTimeline{};
    timeline_.device_generation = generation;
    last_device_generation_ = generation;
    idle_proven_ = false;
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::device_lost() noexcept {
    if (!timeline_.active()) {
        return RegistryStatus::device_unavailable;
    }
    if (open_valid_) {
        // Whether the open submission reached the queue is unknowable now.
        for (Entry& entry : entries_) {
            if (entry.slots.attached()) entry.slots.finish(SubmitOutcome::unknown, open_.serial);
        }
        open_valid_ = false;
    }
    for (RetiredHistoryImages& record : retained_) record.completion_known = false;
    for (Entry& entry : entries_) {
        RetiredHistoryImages record;
        entry.slots.device_lost(timeline_.device_generation, record);
        retain(record);
    }
    timeline_.lost = true;
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::device_idle() noexcept {
    if (!timeline_.active()) return RegistryStatus::device_unavailable;
    if (open_valid_) return RegistryStatus::submission_open;
    timeline_.completed = timeline_.submitted;
    for (Entry& entry : entries_) entry.slots.clear_unknown_uses();
    for (RetiredHistoryImages& record : retained_) {
        for (QueueUses& uses : record.uses) uses.unknown = false;
    }
    idle_proven_ = true;
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::retire_device() noexcept {
    if (!timeline_.active()) return RegistryStatus::device_unavailable;
    if (open_valid_) return RegistryStatus::submission_open;
    if (!idle_proven_) return RegistryStatus::not_idle;
    for (const Entry& entry : entries_) {
        if (entry.slots.pending()) return RegistryStatus::signal_busy;
    }
    for (Entry& entry : entries_) {
        RetiredHistoryImages record;
        entry.slots.release(timeline_.device_generation, record);
        retain(record);
    }
    timeline_.retired = true;
    return RegistryStatus::ok;
}

// --- signals ------------------------------------------------------------------

RegistryStatus HistoryRegistry::add(std::string_view name, HistoryResetPolicy policy, SignalId& id) noexcept {
    const SignalId candidate = signal_id(name);
    if (!candidate.valid()) return RegistryStatus::invalid_signal;
    if (const Entry* existing = find(candidate)) {
        return existing->name == name ? RegistryStatus::duplicate_signal : RegistryStatus::hash_collision;
    }
    try {
        entries_.reserve(entries_.size() + 1);
        entries_.push_back(Entry{candidate, std::string(name), HistorySlots(policy)});
    } catch (const std::bad_alloc&) {
        return RegistryStatus::out_of_memory;
    }
    id = candidate;
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::remove(SignalId id) noexcept {
    for (auto it = entries_.begin(); it != entries_.end(); ++it) {
        if (it->id != id) continue;
        if (it->slots.pending()) return RegistryStatus::signal_busy;
        RetiredHistoryImages record;
        it->slots.release(timeline_.device_generation, record);
        retain(record); // one record, one fewer configured signal: invariant holds
        entries_.erase(it);
        return RegistryStatus::ok;
    }
    return RegistryStatus::unknown_signal;
}

RegistryStatus HistoryRegistry::configure(SignalId id, const HistoryImageSet& images) noexcept {
    if (!timeline_.active()) return RegistryStatus::device_unavailable;
    Entry* entry = find(id);
    if (entry == nullptr) return RegistryStatus::unknown_signal;
    if (entry->slots.pending()) return RegistryStatus::signal_busy;
    if (!history_image_set_valid(images)) return RegistryStatus::invalid_images;
    // New images may not overlap anything still known: other signals, this
    // signal's current set (it is about to become an in-flight record) and
    // every retained record.
    for (std::uint32_t i = 0; i < images.count; ++i) {
        if (overlaps_known_image(images.images[i])) return RegistryStatus::aliased_images;
    }
    // The current set stays counted: it is retained until handed back.
    const std::uint64_t resident = saturating_add(configured_bytes(), retained_bytes());
    const std::uint64_t wanted = history_image_set_bytes(images);
    if (wanted > budget_ || resident > budget_ - wanted) return RegistryStatus::over_budget;

    const std::size_t configured_after = configured_signals() + (entry->slots.configured() ? 0 : 1);
    try {
        retained_.reserve(retained_.size() + 1 + configured_after + 1);
    } catch (const std::bad_alloc&) {
        return RegistryStatus::out_of_memory;
    }
    RetiredHistoryImages old;
    entry->slots.configure(images, timeline_.device_generation, old);
    retain(old);
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::invalidate(SignalId id, HistoryStatus& detail) noexcept {
    Entry* entry = find(id);
    if (entry == nullptr) return RegistryStatus::unknown_signal;
    detail = entry->slots.invalidate();
    return detail == HistoryStatus::ok ? RegistryStatus::ok : RegistryStatus::see_detail;
}

RegistryStatus HistoryRegistry::invalidate_after_pending(SignalId id) noexcept {
    Entry* entry = find(id);
    if (entry == nullptr) return RegistryStatus::unknown_signal;
    entry->slots.invalidate_after_pending();
    return RegistryStatus::ok;
}

// --- frames -------------------------------------------------------------------

RegistryStatus HistoryRegistry::prepare(SignalId id, const HistoryKey& key, std::uint64_t frame,
                                        std::uint32_t queue, HistoryOrdering ordering,
                                        HistoryRecording& out, HistoryStatus& detail) noexcept {
    Entry* entry = find(id);
    if (entry == nullptr) return RegistryStatus::unknown_signal;
    detail = entry->slots.prepare(key, frame, queue, ordering, timeline_, next_token_, out);
    if (detail != HistoryStatus::ok) return RegistryStatus::see_detail;
    ++next_token_; // consumed only by a successful prepare; wraps to 0 = exhausted
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::cancel(SignalId id, std::uint64_t token, HistoryStatus& detail) noexcept {
    Entry* entry = find(id);
    if (entry == nullptr) return RegistryStatus::unknown_signal;
    detail = entry->slots.cancel(token);
    return detail == HistoryStatus::ok ? RegistryStatus::ok : RegistryStatus::see_detail;
}

RegistryStatus HistoryRegistry::begin_submission(std::uint32_t queue, std::uint64_t serial,
                                                 SubmissionTicket& ticket) noexcept {
    if (!timeline_.active()) return RegistryStatus::device_unavailable;
    if (open_valid_) return RegistryStatus::submission_open;
    if (queue >= kMaxHistoryQueues) return RegistryStatus::invalid_serial;
    // Strictly increasing per queue for the whole registry: every signal, also
    // one added later, sees the same order.
    if (serial == 0 || serial <= timeline_.submitted[queue]) return RegistryStatus::invalid_serial;
    if (next_ticket_ == 0) return RegistryStatus::tickets_exhausted;

    open_ = SubmissionTicket{next_ticket_++, timeline_.device_generation, queue, serial};
    open_previous_serial_ = timeline_.submitted[queue];
    timeline_.submitted[queue] = serial; // reserved: no other submission may take it
    open_valid_ = true;
    idle_proven_ = false;
    ticket = open_;
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::attach(const SubmissionTicket& ticket, SignalId id, std::uint64_t token,
                                       bool reset_applied, HistoryStatus& detail) noexcept {
    if (!ticket_open(ticket)) return RegistryStatus::no_submission;
    Entry* entry = find(id);
    if (entry == nullptr) return RegistryStatus::unknown_signal;
    detail = entry->slots.attach(token, ticket.queue, reset_applied);
    return detail == HistoryStatus::ok ? RegistryStatus::ok : RegistryStatus::see_detail;
}

RegistryStatus HistoryRegistry::end_submission(const SubmissionTicket& ticket, SubmitOutcome outcome) noexcept {
    if (!ticket_open(ticket)) return RegistryStatus::no_submission;
    if (outcome != SubmitOutcome::accepted && outcome != SubmitOutcome::rejected) {
        outcome = SubmitOutcome::unknown; // anything unrecognised fails closed
    }
    for (Entry& entry : entries_) {
        if (entry.slots.attached()) entry.slots.finish(outcome, ticket.serial);
    }
    // A provably rejected submission never references anything: release the serial.
    if (outcome == SubmitOutcome::rejected) timeline_.submitted[ticket.queue] = open_previous_serial_;
    open_valid_ = false;
    return RegistryStatus::ok;
}

RegistryStatus HistoryRegistry::complete(std::uint32_t queue, std::uint64_t serial) noexcept {
    if (timeline_.device_generation == 0 || timeline_.lost) return RegistryStatus::device_unavailable;
    if (queue >= kMaxHistoryQueues) return RegistryStatus::invalid_serial;
    // Completion cannot regress and cannot name a serial that was never reserved.
    // The open ticket's serial is reserved but not yet submitted: excluded.
    std::uint64_t limit = timeline_.submitted[queue];
    if (open_valid_ && open_.queue == queue) limit = open_previous_serial_;
    if (serial < timeline_.completed[queue] || serial > limit) return RegistryStatus::invalid_serial;
    timeline_.completed[queue] = serial;
    return RegistryStatus::ok;
}

// --- retirement ---------------------------------------------------------------

bool HistoryRegistry::pop_finished(RetiredHistoryImages& out) noexcept {
    if (timeline_.lost) return false;
    for (auto it = retained_.begin(); it != retained_.end(); ++it) {
        bool ready = it->completion_known && it->device_generation == timeline_.device_generation;
        for (std::uint32_t i = 0; ready && i < it->count; ++i) ready = timeline_.finished(it->uses[i]);
        if (!ready) continue;
        out = *it;
        retained_.erase(it);
        return true;
    }
    return false;
}

bool HistoryRegistry::pop_orphaned(RetiredHistoryImages& out) noexcept {
    for (auto it = retained_.begin(); it != retained_.end(); ++it) {
        if (it->completion_known) continue;
        out = *it;
        retained_.erase(it);
        return true;
    }
    return false;
}

std::uint64_t HistoryRegistry::configured_bytes() const noexcept {
    std::uint64_t total = 0;
    for (const Entry& entry : entries_) total = saturating_add(total, entry.slots.configured_bytes());
    return total;
}

std::uint64_t HistoryRegistry::retained_bytes() const noexcept {
    std::uint64_t total = 0;
    for (const RetiredHistoryImages& record : retained_) total = saturating_add(total, record_bytes(record));
    return total;
}

} // namespace bedray
