#pragma once

#include "bedray/history_registry.hpp"

namespace bedray::graph {

// One registry-wide submission ticket. The owner must outlive this object and
// serialize all registry calls on the render thread. This is CPU bookkeeping;
// it neither submits Vulkan commands nor supplies memory/layout dependencies.
class HistorySubmission final {
public:
    explicit HistorySubmission(HistoryRegistry& registry) noexcept : registry_(registry) {}
    ~HistorySubmission();
    HistorySubmission(const HistorySubmission&) = delete;
    HistorySubmission& operator=(const HistorySubmission&) = delete;
    HistorySubmission(HistorySubmission&&) = delete;
    HistorySubmission& operator=(HistorySubmission&&) = delete;

    [[nodiscard]] RegistryStatus begin(std::uint32_t queue, std::uint64_t serial) noexcept;
    [[nodiscard]] RegistryStatus attach(SignalId signal, std::uint64_t token,
                                        bool reset_applied, HistoryStatus& detail) noexcept;

    // Call immediately before entering vkQueueSubmit. Destruction of an open
    // ticket always records unknown, including when this mark was forgotten.
    // Explicit finish(rejected) is required for a proven unsubmitted recording.
    [[nodiscard]] RegistryStatus mark_submit_attempted() noexcept;
    // accepted/rejected require the host to know the actual outcome. Use unknown
    // when uncertain. Attached recordings are always settled as one submission.
    [[nodiscard]] RegistryStatus finish(SubmitOutcome outcome) noexcept;
    [[nodiscard]] bool open() const noexcept { return open_; }

private:
    HistoryRegistry& registry_;
    SubmissionTicket ticket_{};
    bool open_ = false;
    bool submit_attempted_ = false;
};

// This proves only producer completion; it does not create visibility, layout
// transitions, queue ownership transfers or lifetime guarantees. A reset
// recording has no history read and therefore needs no producer completion.
[[nodiscard]] bool history_read_producer_completed(const HistoryRecording& recording,
    const QueueTimeline& timeline, std::uint64_t expected_device_generation) noexcept;

} // namespace bedray::graph
