#include "bedray/history_submission.hpp"

namespace bedray::graph {

HistorySubmission::~HistorySubmission() {
    if (open_) {
        // If device_lost already closed the ticket the registry returns
        // no_submission and owns its orphaned-resource bookkeeping.
        (void)registry_.end_submission(ticket_, SubmitOutcome::unknown);
    }
}

RegistryStatus HistorySubmission::begin(std::uint32_t queue, std::uint64_t serial) noexcept {
    if (open_) return RegistryStatus::submission_open;
    SubmissionTicket ticket{};
    const RegistryStatus result = registry_.begin_submission(queue, serial, ticket);
    if (result == RegistryStatus::ok) {
        ticket_ = ticket;
        open_ = true;
        submit_attempted_ = false;
    }
    return result;
}

RegistryStatus HistorySubmission::attach(SignalId signal, std::uint64_t token,
                                          bool reset_applied, HistoryStatus& detail) noexcept {
    if (!open_) return RegistryStatus::no_submission;
    if (submit_attempted_) return RegistryStatus::submission_open;
    return registry_.attach(ticket_, signal, token, reset_applied, detail);
}

RegistryStatus HistorySubmission::mark_submit_attempted() noexcept {
    if (!open_) return RegistryStatus::no_submission;
    if (submit_attempted_) return RegistryStatus::submission_open;
    if (!registry_.timeline().active() || registry_.timeline().device_generation != ticket_.device_generation)
        return RegistryStatus::device_unavailable;
    submit_attempted_ = true;
    return RegistryStatus::ok;
}

RegistryStatus HistorySubmission::finish(SubmitOutcome outcome) noexcept {
    if (!open_) return RegistryStatus::no_submission;
    // Before the submission call only a known rejection is meaningful. Once a
    // call was attempted, the host is responsible for reporting its result.
    if (!submit_attempted_ && outcome != SubmitOutcome::rejected) return RegistryStatus::invalid_serial;
    const RegistryStatus result = registry_.end_submission(ticket_, outcome);
    if (result == RegistryStatus::ok || result == RegistryStatus::no_submission) open_ = false;
    return result;
}

bool history_read_producer_completed(const HistoryRecording& recording, const QueueTimeline& timeline,
                                     std::uint64_t expected_device_generation) noexcept {
    if (!timeline.active() || expected_device_generation == 0 ||
        timeline.device_generation != expected_device_generation) return false;
    if (recording.read_slot < 0) return true;
    if (recording.read_image == 0 || recording.read_producer_serial == 0 ||
        recording.read_producer_queue >= kMaxHistoryQueues) return false;
    return recording.read_producer_serial <= timeline.completed[recording.read_producer_queue];
}

} // namespace bedray::graph
