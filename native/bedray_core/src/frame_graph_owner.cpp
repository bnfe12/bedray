#include "frame_graph_owner.h"
#include <cstdio>
#include <limits>
#include <utility>

namespace bedray::core {
namespace {
constexpr std::uint64_t bit(graph::PlanCapability value) { return static_cast<std::uint64_t>(value); }
constexpr std::uint64_t required_capabilities = bit(graph::PlanCapability::ray_query)
    | bit(graph::PlanCapability::hardware_ray_tracing) | bit(graph::PlanCapability::buffer_device_address)
    | bit(graph::PlanCapability::shader_int64);
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) {
    if (error) {
        error->status = status;
        std::snprintf(error->message, sizeof(error->message), "%s", message);
    }
    return status;
}
}

FrameGraphOwner::FrameGraphOwner(std::uint32_t slots)
    : scheduler_(slots, [this](const graph::FrameTicket& ticket) { release_ticket(ticket); }) {}

std::uint64_t FrameGraphOwner::plan_capabilities(std::uint32_t mask) noexcept {
    std::uint64_t result = 0;
    if (mask & BEDRAY_CAP_RAY_QUERY) result |= bit(graph::PlanCapability::ray_query);
    if ((mask & (BEDRAY_CAP_RAY_QUERY | BEDRAY_CAP_ACCELERATION_STRUCTURE))
        == (BEDRAY_CAP_RAY_QUERY | BEDRAY_CAP_ACCELERATION_STRUCTURE))
        result |= bit(graph::PlanCapability::hardware_ray_tracing);
    if (mask & BEDRAY_CAP_BUFFER_DEVICE_ADDRESS) result |= bit(graph::PlanCapability::buffer_device_address);
    if (mask & BEDRAY_CAP_SHADER_INT64) result |= bit(graph::PlanCapability::shader_int64);
    return result;
}

BedrayStatus FrameGraphOwner::configure(std::uint32_t core_mask, std::uint32_t queue_family,
    PFN_vkCmdPipelineBarrier barrier, Record record, Release release, void* context, BedrayError* error) {
    if (ready_ || graph_.pass_count() != 0 || !barrier || !record || !release)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid/already configured native frame graph");
    const auto enabled = plan_capabilities(core_mask);
    if ((enabled & required_capabilities) != required_capabilities)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "native frame graph needs enabled ray-query/AS/BDA/Int64");
    graph::PassDesc node{};
    node.name = "path_trace.frame";
    node.queue_family = queue_family;
    node.required_capabilities = required_capabilities;
    node.recording_kind = graph::RecordingKind::opaque_group;
    node.dispatch = {0, 0, 0}; // a group has no pretend single dispatch shape
    graph_.add_pass(std::move(node));
    graph::CompiledGraph compiled;
    graph::Error graph_error;
    graph::RecordingError recording_error;
    graph::FrameError frame_error;
    if (!graph_.compile(enabled, compiled, graph_error)
        || !graph::prepare_vulkan_recording(graph_, compiled, enabled, {}, plan_, recording_error)
        || !scheduler_.bind_schedule(graph_, compiled, enabled, frame_error))
        return fail(error, BEDRAY_ERROR_INTERNAL, "cannot compile native frame graph");
    barrier_ = barrier;
    record_ = record;
    release_ = release;
    context_ = context;
    ready_ = true;
    return BEDRAY_OK;
}

BedrayStatus FrameGraphOwner::record(const BedrayPathTraceFrameDesc& desc, BedrayError* error,
    const BedrayPathTraceGuides* guides, const BedrayPathTraceBuffer* motion) {
    if (!ready_ || desc.command_buffer == 0)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "native frame graph is not configured or command buffer is null");
    if (recordings_.contains(desc.command_buffer))
        return fail(error, BEDRAY_ERROR_IN_USE, "command buffer still has a leased native recording");
    if (next_frame_ == std::numeric_limits<std::uint64_t>::max())
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "native recording identities exhausted");
    // Allocate the lookup before leasing a slot: allocation failure cannot orphan a ticket.
    auto [entry, inserted] = recordings_.emplace(desc.command_buffer, graph::FrameTicket{});
    (void)inserted;
    graph::FrameError frame_error;
    if (!scheduler_.begin_frame(next_frame_++, entry->second, frame_error)) {
        recordings_.erase(entry);
        return fail(error, frame_error.code == graph::FrameErrorCode::slot_unavailable
            ? BEDRAY_ERROR_IN_USE : BEDRAY_ERROR_INTERNAL, "native frame slots unavailable");
    }
    struct Call {
        FrameGraphOwner* owner;
        const BedrayPathTraceFrameDesc* desc;
        const BedrayPathTraceGuides* guides;
        const BedrayPathTraceBuffer* motion;
        BedrayError* error;
        BedrayStatus status = BEDRAY_OK;
    } call{this, &desc, guides, motion, error};
    const auto callback = [](void* pointer, graph::PassId pass, VkCommandBuffer) -> bool {
        auto& c = *static_cast<Call*>(pointer);
        if (pass != 0) { c.status = BEDRAY_ERROR_INTERNAL; return false; }
        c.status = c.owner->record_(c.owner->context_, *c.desc, c.guides, c.motion, c.error);
        return c.status == BEDRAY_OK;
    };
    if (!scheduler_.record_frame(entry->second, plan_,
            reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(desc.command_buffer)),
            barrier_, callback, &call, frame_error)) {
        // Even a failed group may contain commands. Retain ticket and slot until
        // the host deliberately discards the whole CB or proves completion.
        if (call.status != BEDRAY_OK) return call.status;
        return fail(error, BEDRAY_ERROR_INTERNAL, "native graph recording failed; discard the command buffer");
    }
    return BEDRAY_OK;
}

void FrameGraphOwner::release_ticket(const graph::FrameTicket& ticket) {
    for (auto it = recordings_.begin(); it != recordings_.end(); ++it) {
        if (it->second.recording != ticket.recording) continue;
        struct Guard {
            std::uint64_t& value;
            ~Guard() { value = 0; }
        } guard{releasing_command_};
        releasing_command_ = it->first;
        // Core installs bedray_core_release_recording as this callback. Its
        // reentrant path only drops descriptors/pins, never releases this slot twice.
        release_status_ = release_(context_, it->first);
        if (release_status_ != BEDRAY_OK) throw release_status_;
        recordings_.erase(it);
        return;
    }
    release_status_ = BEDRAY_ERROR_INTERNAL;
    throw release_status_;
}

BedrayStatus FrameGraphOwner::release_proven(std::uint64_t command, BedrayError* error) {
    const auto entry = recordings_.find(command);
    if (entry == recordings_.end()) return BEDRAY_OK;
    const auto ticket = entry->second;
    release_status_ = BEDRAY_OK;
    graph::FrameError frame_error;
    if (!scheduler_.release_proven(ticket, frame_error))
        return fail(error, release_status_ != BEDRAY_OK ? release_status_ : BEDRAY_ERROR_INTERNAL,
            "native frame release failed; recording and slot retained");
    return BEDRAY_OK;
}
}
