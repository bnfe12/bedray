#include "bedray/frame_scheduler.hpp"

#include <new>
#include <utility>

namespace bedray::graph {

FrameScheduler::FrameScheduler(std::uint32_t slots, ReleaseRecording release)
    : release_(std::move(release)) {
    if (slots == 0) slots = 1;
    if (slots > max_frame_slots) slots = max_frame_slots;
    slots_.resize(slots);
}

bool FrameScheduler::bind_schedule(const RenderGraph& graph, const CompiledGraph& compiled,
                                   std::uint64_t enabled_capabilities, FrameError& error) {
    error = {};
    if (outstanding_frames() != 0) {
        error.code = FrameErrorCode::schedule_busy;
        return false;
    }
    if (compiled.owner != &graph || compiled.enabled_capabilities != enabled_capabilities ||
        !graph.is_current(compiled, enabled_capabilities)) {
        error.code = FrameErrorCode::stale_schedule;
        return false;
    }
    try {
        compiled_ = compiled;
    } catch (const std::bad_alloc&) {
        error.code = FrameErrorCode::out_of_memory;
        return false;
    }
    graph_ = &graph;
    capabilities_ = enabled_capabilities;
    return true;
}

bool FrameScheduler::begin_frame(std::uint64_t frame_index, FrameTicket& out, FrameError& error) {
    error = {};
    if (graph_ == nullptr) {
        error.code = FrameErrorCode::schedule_unbound;
        return false;
    }
    if (frame_index == 0 || frame_index <= last_frame_index_) {
        error.code = FrameErrorCode::frame_index_invalid;
        error.detail = last_frame_index_;
        return false;
    }
    if (!graph_->is_current(compiled_, capabilities_)) {
        error.code = FrameErrorCode::stale_schedule;
        return false;
    }
    const std::size_t index = free_slot();
    if (index >= slots_.size()) {
        error.code = FrameErrorCode::slot_unavailable;
        return false;
    }
    if (next_recording_ == 0) {
        error.code = FrameErrorCode::exhausted;
        return false;
    }

    const std::uint64_t generation = next_generation_++;
    if (next_generation_ == 0) next_generation_ = 1;

    Slot& slot = slots_[index];
    slot.state = SlotState::recording;
    slot.generation = generation;
    slot.last_use = 0;
    slot.record_attempted = false;
    slot.ticket = FrameTicket{frame_index, next_recording_++, static_cast<std::uint32_t>(index), generation};

    last_frame_index_ = frame_index;
    out = slot.ticket;
    return true;
}

bool FrameScheduler::record_frame(const FrameTicket& ticket, const VulkanRecordingPlan& plan,
                                  VkCommandBuffer command_buffer, PFN_vkCmdPipelineBarrier barrier,
                                  RecordPass record_pass, void* context, FrameError& error) {
    return record_frame(ticket, plan, FrameHistoryReads{}, command_buffer, barrier, record_pass,
                        context, error);
}

bool FrameScheduler::record_frame(const FrameTicket& ticket, const VulkanRecordingPlan& plan,
                                  const FrameHistoryReads& history, VkCommandBuffer command_buffer,
                                  PFN_vkCmdPipelineBarrier barrier, RecordPass record_pass,
                                  void* context, FrameError& error) {
    error = {};
    Slot* slot = recording_slot(ticket, error);
    if (slot == nullptr) return false;
    if (slot->record_attempted) {
        error.code = FrameErrorCode::recording_already_done;
        error.slot = ticket.slot;
        return false;
    }
    if (graph_ == nullptr || !graph_->is_current(compiled_, capabilities_) ||
        plan.owner != graph_ || plan.enabled_capabilities != capabilities_ ||
        plan.graph.owner != graph_ || plan.graph.revision != compiled_.revision ||
        plan.graph.enabled_capabilities != capabilities_) {
        error.code = FrameErrorCode::stale_schedule;
        error.slot = ticket.slot;
        return false;
    }
    if (!history_ready(history, error)) {
        error.slot = ticket.slot;
        return false;
    }

    // A failed adapter call may already have recorded commands: the host must
    // discard the whole command buffer, and this ticket cannot record again.
    slot->record_attempted = true;
    RecordingError recording = RecordingError::none;
    if (!record_vulkan_graph(*graph_, plan, command_buffer, barrier, record_pass, context, recording)) {
        error.code = FrameErrorCode::recording_failed;
        error.recording = recording;
        error.slot = ticket.slot;
        return false;
    }
    return true;
}

bool FrameScheduler::finish_frame(const FrameTicket& ticket, FrameSubmitState state,
                                  std::uint64_t serial, FrameError& error) {
    error = {};
    Slot* slot = recording_slot(ticket, error);
    if (slot == nullptr) return false;
    if (state != FrameSubmitState::discarded && state != FrameSubmitState::submitted &&
        state != FrameSubmitState::unknown) {
        error.code = FrameErrorCode::invalid_argument;
        error.slot = ticket.slot;
        return false;
    }

    if (state == FrameSubmitState::discarded) {
        if (serial != 0) {
            error.code = FrameErrorCode::serial_invalid;
            error.slot = ticket.slot;
            return false;
        }
        if (!release_slot(*slot)) {
            error.code = FrameErrorCode::release_failed;
            error.slot = ticket.slot;
            return false;
        }
        return true;
    }

    if (serial == 0 || serial <= highest_serial_ || serial <= completed_) {
        error.code = FrameErrorCode::serial_invalid;
        error.slot = ticket.slot;
        error.detail = highest_serial_ > completed_ ? highest_serial_ : completed_;
        return false;
    }
    highest_serial_ = serial;
    slot->last_use = serial;
    slot->state = state == FrameSubmitState::submitted ? SlotState::in_flight : SlotState::unknown;
    return true;
}

bool FrameScheduler::complete(std::uint64_t serial, FrameError& error) {
    error = {};
    if (serial < completed_) {
        error.code = FrameErrorCode::completion_regressed;
        error.detail = completed_;
        return false;
    }
    completed_ = serial;
    if (!release_ready(false)) {
        error.code = FrameErrorCode::release_failed;
        return false;
    }
    return true;
}

bool FrameScheduler::device_idle(FrameError& error) {
    error = {};
    if (!release_ready(true)) {
        error.code = FrameErrorCode::release_failed;
        return false;
    }
    return true;
}

bool FrameScheduler::device_lost(FrameError& error) {
    error = {};
    if (!release_ready(true)) {
        error.code = FrameErrorCode::release_failed;
        return false;
    }
    return true;
}

std::uint32_t FrameScheduler::outstanding_frames() const noexcept {
    std::uint32_t count = 0;
    for (const Slot& slot : slots_) {
        if (slot.state != SlotState::free) ++count;
    }
    return count;
}

FrameScheduler::Slot* FrameScheduler::recording_slot(const FrameTicket& ticket,
                                                    FrameError& error) noexcept {
    error = {};
    if (ticket.slot >= slots_.size()) {
        error.code = FrameErrorCode::invalid_ticket;
        error.slot = ticket.slot;
        return nullptr;
    }
    Slot& slot = slots_[ticket.slot];
    if (ticket.slot_generation == 0 || slot.generation != ticket.slot_generation ||
        slot.state != SlotState::recording || slot.ticket.recording != ticket.recording ||
        slot.ticket.frame_index != ticket.frame_index) {
        error.code = FrameErrorCode::invalid_ticket;
        error.slot = ticket.slot;
        return nullptr;
    }
    return &slot;
}

std::size_t FrameScheduler::free_slot() const noexcept {
    for (std::size_t i = 0; i < slots_.size(); ++i) {
        if (slots_[i].state == SlotState::free) return i;
    }
    return slots_.size();
}

bool FrameScheduler::release_slot(Slot& slot) noexcept {
    const SlotState previous_state = slot.state;
    slot.state = SlotState::releasing;
    const FrameTicket ticket = slot.ticket;
    bool released = true;
    if (release_) {
        try {
            release_(ticket);
        } catch (...) {
            released = false;
        }
    }
    if (released) {
        slot.ticket = {};
        slot.last_use = 0;
        slot.record_attempted = false;
        slot.state = SlotState::free;
    } else {
        slot.state = previous_state;
    }
    return released;
}

bool FrameScheduler::release_proven(const FrameTicket& ticket, FrameError& error) {
    error = {};
    if (ticket.slot >= slots_.size()) {
        error.code = FrameErrorCode::invalid_ticket;
        return false;
    }
    Slot& slot = slots_[ticket.slot];
    if (slot.state == SlotState::free || slot.state == SlotState::releasing
        || ticket.slot_generation == 0 || slot.generation != ticket.slot_generation
        || slot.ticket.recording != ticket.recording || slot.ticket.frame_index != ticket.frame_index) {
        error.code = FrameErrorCode::invalid_ticket;
        return false;
    }
    if (!release_slot(slot)) {
        error.code = FrameErrorCode::release_failed;
        return false;
    }
    return true;
}

bool FrameScheduler::release_ready(bool include_unknown) noexcept {
    bool released = true;
    for (Slot& slot : slots_) {
        const bool ready =
            include_unknown
                ? (slot.state == SlotState::in_flight || slot.state == SlotState::unknown)
                : (slot.state == SlotState::in_flight && slot.last_use <= completed_);
        if (!ready) continue;
        // release_slot re-checks nothing: the state cannot change between the
        // test above and this call on the externally synchronized render thread.
        if (!release_slot(slot)) released = false;
    }
    return released;
}

bool FrameScheduler::history_ready(const FrameHistoryReads& history,
                                   FrameError& error) const noexcept {
    if (history.count == 0) return true;
    if (history.recordings == nullptr || history.timeline == nullptr) {
        error.code = FrameErrorCode::invalid_argument;
        return false;
    }
    for (std::size_t i = 0; i < history.count; ++i) {
        if (history_read_producer_completed(history.recordings[i], *history.timeline,
                                            history.device_generation)) {
            continue;
        }
        error.code = FrameErrorCode::history_read_incomplete;
        error.detail = history.recordings[i].read_producer_serial;
        return false;
    }
    return true;
}

} // namespace bedray::graph
