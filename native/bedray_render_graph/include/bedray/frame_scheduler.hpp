#pragma once

#include "bedray/history_submission.hpp"
#include "bedray/vulkan_recording.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace bedray::graph {

inline constexpr std::uint32_t max_frame_slots = 8;

enum class FrameErrorCode : std::uint8_t {
    none,
    invalid_argument,
    out_of_memory,
    schedule_unbound,
    stale_schedule,
    schedule_busy,          // outstanding frames prevent rebinding or mutation
    frame_index_invalid,    // zero or not strictly increasing
    slot_unavailable,       // every transient slot still has an unfinished use
    invalid_ticket,         // unknown, stale or already closed ticket
    recording_already_done, // record_frame was already attempted for this ticket
    recording_failed,       // see FrameError::recording; discard the command buffer
    serial_invalid,         // zero, regressing or not above the last reserved serial
    completion_regressed,
    history_read_incomplete,// a history producer is not proven complete
    exhausted,              // recording identifiers are exhausted; fail closed
    release_failed,         // a release callback threw; all reachable state was freed
};

struct FrameError {
    FrameErrorCode code = FrameErrorCode::none;
    RecordingError recording = RecordingError::none;
    std::uint32_t slot = 0;
    std::uint64_t detail = 0;
};

enum class FrameSubmitState : std::uint8_t {
    discarded, // provably never submitted: transient use ends immediately
    submitted, // host submitted under `serial`; reuse requires completion
    unknown,   // outcome unproven; reuse requires a proven idle device
};

// `slot` identifies one set of host/scene/core-owned per-frame transient
// resources. The scheduler allocates no Vulkan memory: the owner maps slot
// indices to its own resources. `generation` rejects tickets from an earlier
// lease of the same slot.
struct FrameTicket {
    std::uint64_t frame_index = 0;
    std::uint64_t recording = 0;
    std::uint32_t slot = 0;
    std::uint64_t slot_generation = 0;
};

// Persistent history resources are owned by HistoryRegistry. Before recording,
// every supplied recording with a history read must have its producer proven
// complete by the host timeline (`completed_only` integration). Reset
// recordings (read_slot < 0) require an active timeline but no producer.
struct FrameHistoryReads {
    const HistoryRecording* recordings = nullptr;
    std::size_t count = 0;
    const QueueTimeline* timeline = nullptr;
    std::uint64_t device_generation = 0;
};

// Frame-level transaction around one host command buffer. The scheduler:
//   - validates that the compiled schedule is still current;
//   - leases one of a fixed number of per-frame transient slots;
//   - records Bedray passes into the host's already-recording command buffer;
//   - keeps a recording/slot leased until the host proves GPU completion, and
//     notifies the owner exactly once per recording that its core recording may
//     be released.
//
// It owns no VkInstance/VkDevice/VkQueue, never submits, presents or waits, and
// never treats a frame number, ring position or recording return as completion.
// Destroying the scheduler with outstanding frames does not invent completion;
// the owner must finish/complete or discard every frame first.
class FrameScheduler final {
public:
    // Invoked exactly once per recording: after an unsubmitted recording is
    // discarded, after host-proven completion, or after a proven device idle or
    // loss. It must not throw; a throw is reported as release_failed and the
    // scheduler still frees its own state. The callback may re-enter the
    // scheduler; a slot being released is not reusable until the callback ends.
    using ReleaseRecording = std::function<void(const FrameTicket&)>;

    // `slots` is clamped to [1, max_frame_slots].
    explicit FrameScheduler(std::uint32_t slots = 2, ReleaseRecording release = {});

    FrameScheduler(const FrameScheduler&) = delete;
    FrameScheduler& operator=(const FrameScheduler&) = delete;
    FrameScheduler(FrameScheduler&&) = delete;
    FrameScheduler& operator=(FrameScheduler&&) = delete;

    // Binds the immutable schedule. Requires no outstanding frames. The graph
    // must outlive the scheduler; `compiled` is copied. Rejects a snapshot that
    // is stale, from another graph or compiled for other capabilities.
    [[nodiscard]] bool bind_schedule(const RenderGraph& graph, const CompiledGraph& compiled,
                                     std::uint64_t enabled_capabilities, FrameError& error);

    // The host frame index must be non-zero and strictly increasing. A free
    // slot is leased only if its previous use was released by completion,
    // discard or a proven idle/lost device, never by a frame count.
    [[nodiscard]] bool begin_frame(std::uint64_t frame_index, FrameTicket& out, FrameError& error);

    // Records the validated graph into the host's open command buffer. Must be
    // attempted at most once per ticket. A failure means the host must discard
    // the whole command buffer, then pass the ticket to finish_frame(discarded).
    [[nodiscard]] bool record_frame(const FrameTicket& ticket, const VulkanRecordingPlan& plan,
                                    VkCommandBuffer command_buffer,
                                    PFN_vkCmdPipelineBarrier barrier, RecordPass record_pass,
                                    void* context, FrameError& error);
    [[nodiscard]] bool record_frame(const FrameTicket& ticket, const VulkanRecordingPlan& plan,
                                    const FrameHistoryReads& history,
                                    VkCommandBuffer command_buffer,
                                    PFN_vkCmdPipelineBarrier barrier, RecordPass record_pass,
                                    void* context, FrameError& error);

    // Closes the ticket. discarded requires serial 0. submitted/unknown require
    // a non-zero serial above every serial previously reserved or completed;
    // the host must use the same serial for the queue submission and for
    // HistorySubmission so both subsystems observe one contract.
    [[nodiscard]] bool finish_frame(const FrameTicket& ticket, FrameSubmitState state,
                                    std::uint64_t serial, FrameError& error);

    // Host-proven completion high-water mark. Frees submitted slots whose last
    // use is not later. Unknown submissions are never released here.
    [[nodiscard]] bool complete(std::uint64_t serial, FrameError& error);

    // Host proof that the device is idle (vkDeviceWaitIdle or every queue's
    // fence). Frees submitted and unknown slots, including their release
    // callbacks. An open recording is not made safe by GPU idleness; it must be
    // discarded first.
    [[nodiscard]] bool device_idle(FrameError& error);
    // Host observed device loss. Releases submitted and unknown CPU bookkeeping
    // without claiming completion; open recordings must still be discarded.
    [[nodiscard]] bool device_lost(FrameError& error);

    [[nodiscard]] std::uint32_t slot_count() const noexcept { return static_cast<std::uint32_t>(slots_.size()); }
    [[nodiscard]] std::uint32_t outstanding_frames() const noexcept;
    [[nodiscard]] std::uint64_t completed_serial() const noexcept { return completed_; }
    [[nodiscard]] bool schedule_bound() const noexcept { return graph_ != nullptr; }

private:
    enum class SlotState : std::uint8_t { free, recording, in_flight, unknown, releasing };

    struct Slot {
        SlotState state = SlotState::free;
        std::uint64_t generation = 0;
        std::uint64_t last_use = 0;
        FrameTicket ticket{};
        bool record_attempted = false;
    };

    [[nodiscard]] Slot* recording_slot(const FrameTicket& ticket, FrameError& error) noexcept;
    [[nodiscard]] std::size_t free_slot() const noexcept;
    [[nodiscard]] bool release_slot(Slot& slot) noexcept;
    [[nodiscard]] bool release_ready(bool include_unknown) noexcept;
    [[nodiscard]] bool history_ready(const FrameHistoryReads& history, FrameError& error) const noexcept;

    std::vector<Slot> slots_;
    ReleaseRecording release_;

    const RenderGraph* graph_ = nullptr;
    CompiledGraph compiled_{};
    std::uint64_t capabilities_ = 0;

    std::uint64_t last_frame_index_ = 0;
    std::uint64_t next_generation_ = 1;
    std::uint64_t next_recording_ = 1;
    std::uint64_t highest_serial_ = 0;
    std::uint64_t completed_ = 0;
};

} // namespace bedray::graph
