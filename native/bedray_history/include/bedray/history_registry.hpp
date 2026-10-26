#pragma once

#include "bedray/history_signal.hpp"
#include "bedray/history_slots.hpp"
#include "bedray/history_timeline.hpp"

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace bedray {

enum class RegistryStatus : std::uint32_t {
    ok = 0,
    invalid_signal,
    duplicate_signal,   // same name registered twice
    hash_collision,     // different name, same SignalId: rename one of them
    unknown_signal,
    invalid_images,
    aliased_images,     // overlaps an image of another signal, its own current set, or a retained record
    over_budget,        // nothing changed: the previous configuration (if any) is still active
    signal_busy,        // a recording of that signal is pending
    out_of_memory,      // nothing changed
    device_unavailable, // no device, device lost, or device retired
    device_unresolved,  // begin_device refused: device still active or records of the old device remain
    submission_open,
    no_submission,      // unknown, stale or mismatched ticket
    invalid_serial,
    see_detail,         // the per-signal HistoryStatus explains the refusal
    not_idle,           // retire_device without a proof of idleness since the last submission
    tickets_exhausted,
};

// One host submission that may carry recordings of several signals. Created
// before the host submits; closed with the actual outcome afterwards.
struct SubmissionTicket {
    std::uint64_t id = 0;
    std::uint64_t device_generation = 0;
    std::uint32_t queue = 0;
    std::uint64_t serial = 0;
};

// Independent histories of many signals plus the authoritative queue timeline
// of the current device. One instance per renderer and device lineage; no
// global state. Calls are externally synchronized on the render thread. Only
// borrowed integer identifiers are stored, never Java objects or FFM memory.
//
// Host order for one submission:
//   prepare (per signal) -> begin_submission -> attach (per signal) -> vkQueueSubmit
//   -> end_submission(accepted | rejected | unknown)
// Everything refusable is refused before vkQueueSubmit; end_submission cannot fail
// for a valid ticket, so an accepted submission is never lost from the accounting.
class HistoryRegistry final {
public:
    // `budget_bytes` bounds configured images PLUS retained (replaced but not yet
    // handed back) images, i.e. every history image the host may still hold.
    explicit HistoryRegistry(std::uint64_t budget_bytes) noexcept : budget_(budget_bytes) {}

    // --- device lineage -------------------------------------------------------
    // Starts a device epoch. Requires: no active device, `generation` greater than
    // every previous one, and no retained record of an earlier device.
    [[nodiscard]] RegistryStatus begin_device(std::uint64_t generation) noexcept;
    // Host observed VK_ERROR_DEVICE_LOST (or equivalent). An open submission is
    // closed as `unknown`; every signal loses its images into orphaned records.
    [[nodiscard]] RegistryStatus device_lost() noexcept;
    // Host proof that the device is idle (successful vkDeviceWaitIdle or every
    // queue's fence). Marks all submissions complete, including unknown ones.
    [[nodiscard]] RegistryStatus device_idle() noexcept;
    // Orderly teardown after device_idle(): every signal's images become finished
    // records; the device epoch is closed.
    [[nodiscard]] RegistryStatus retire_device() noexcept;

    // --- signals --------------------------------------------------------------
    [[nodiscard]] RegistryStatus add(std::string_view name, HistoryResetPolicy policy, SignalId& id) noexcept;
    [[nodiscard]] RegistryStatus remove(SignalId id) noexcept;
    // Transactional: on any refusal the signal keeps its previous configuration.
    [[nodiscard]] RegistryStatus configure(SignalId id, const HistoryImageSet& images) noexcept;
    // Reset that must hold for the NEXT recording (the data is wrong now).
    // Refused with see_detail (recording_pending / recording_attached) while
    // the signal has a recording. The caller first cancels it, or closes the
    // open ticket (rejected if not submitted, accepted if it was), then
    // invalidates and prepares again.
    [[nodiscard]] RegistryStatus invalidate(SignalId id, HistoryStatus& detail) noexcept;
    // Reset for frames after the pending one: a pending or attached recording
    // may still read the old history and be submitted, but its output is never
    // committed. Only when reading the old data for that one frame is harmless.
    [[nodiscard]] RegistryStatus invalidate_after_pending(SignalId id) noexcept;

    // --- frames ---------------------------------------------------------------
    [[nodiscard]] RegistryStatus prepare(SignalId id, const HistoryKey& key, std::uint64_t frame,
                                         std::uint32_t queue, HistoryOrdering ordering,
                                         HistoryRecording& out, HistoryStatus& detail) noexcept;
    [[nodiscard]] RegistryStatus cancel(SignalId id, std::uint64_t token, HistoryStatus& detail) noexcept;

    [[nodiscard]] RegistryStatus begin_submission(std::uint32_t queue, std::uint64_t serial,
                                                  SubmissionTicket& ticket) noexcept;
    [[nodiscard]] RegistryStatus attach(const SubmissionTicket& ticket, SignalId id, std::uint64_t token,
                                        bool reset_applied, HistoryStatus& detail) noexcept;
    [[nodiscard]] RegistryStatus end_submission(const SubmissionTicket& ticket, SubmitOutcome outcome) noexcept;

    // Host-proven completion of every submission up to `serial` on `queue`.
    [[nodiscard]] RegistryStatus complete(std::uint32_t queue, std::uint64_t serial) noexcept;

    // --- retirement -----------------------------------------------------------
    // Hands back one record of the current device whose uses are all finished;
    // the host may destroy those images now. False when none is ready.
    [[nodiscard]] bool pop_finished(RetiredHistoryImages& out) noexcept;
    // Hands back one record of a lost device. No completion is known; the Vulkan
    // owner must destroy it as part of its device-loss teardown, before the
    // parent VkDevice is destroyed. This module makes no claim that is safe earlier.
    [[nodiscard]] bool pop_orphaned(RetiredHistoryImages& out) noexcept;

    [[nodiscard]] std::uint64_t configured_bytes() const noexcept;
    [[nodiscard]] std::uint64_t retained_bytes() const noexcept;
    [[nodiscard]] std::uint64_t budget_bytes() const noexcept { return budget_; }
    [[nodiscard]] std::size_t signal_count() const noexcept { return entries_.size(); }
    [[nodiscard]] std::size_t retained_count() const noexcept { return retained_.size(); }
    [[nodiscard]] const QueueTimeline& timeline() const noexcept { return timeline_; }

private:
    struct Entry {
        SignalId id;
        std::string name;
        HistorySlots slots;
    };
    [[nodiscard]] Entry* find(SignalId id) noexcept;
    [[nodiscard]] std::size_t configured_signals() const noexcept;
    [[nodiscard]] bool overlaps_known_image(const HistoryImage& image) const noexcept;
    [[nodiscard]] bool ticket_open(const SubmissionTicket& ticket) const noexcept;
    void retain(const RetiredHistoryImages& record) noexcept;

    std::uint64_t budget_;
    QueueTimeline timeline_;
    std::uint64_t last_device_generation_ = 0;
    bool idle_proven_ = false;
    std::vector<Entry> entries_;
    // configure() reserves capacity >= size + configured_signals() + 1 before it
    // retains anything, so device_lost(), retire_device() and remove() can retain
    // records without allocating.
    std::vector<RetiredHistoryImages> retained_;
    SubmissionTicket open_{};
    bool open_valid_ = false;
    std::uint64_t open_previous_serial_ = 0;
    std::uint64_t next_ticket_ = 1;
    // Registry-wide and never reset: survives signal removal/re-add and device
    // changes, so a stale (SignalId, token) can never match a later recording.
    // 0 after wrap-around means exhausted.
    std::uint64_t next_token_ = 1;
};

} // namespace bedray
