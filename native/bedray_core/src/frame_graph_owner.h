#pragma once

#include "bedray_core.h"
#include <bedray/frame_scheduler.hpp>
#include <bedray/render_plan.hpp>
#include <map>

namespace bedray::core {

// One opaque graph node wraps the complete native path-trace recording. The
// pass owns its images, layouts/history and barriers; graph owns ordering and
// scheduler leases recordings. No second history or barrier stream is created.
class FrameGraphOwner final {
public:
    using Record = BedrayStatus (*)(void*, const BedrayPathTraceFrameDesc&, const BedrayPathTraceGuides*, const BedrayPathTraceBuffer*, BedrayError*);
    using Release = BedrayStatus (*)(void*, std::uint64_t);

    explicit FrameGraphOwner(std::uint32_t slots = graph::max_frame_slots);
    BedrayStatus configure(std::uint32_t core_capabilities, std::uint32_t queue_family,
        PFN_vkCmdPipelineBarrier barrier, Record record, Release release, void* context, BedrayError* error);
    BedrayStatus record(const BedrayPathTraceFrameDesc&, BedrayError* error,
        const BedrayPathTraceGuides* guides = nullptr, const BedrayPathTraceBuffer* motion = nullptr);
    // Existing ABI-v2 release supplies host fence/discard proof for this exact CB.
    BedrayStatus release_proven(std::uint64_t command_buffer, BedrayError* error);
    bool has_recordings() const noexcept { return !recordings_.empty(); }
    bool releasing(std::uint64_t command_buffer) const noexcept {
        return command_buffer != 0 && releasing_command_ == command_buffer;
    }
    static std::uint64_t plan_capabilities(std::uint32_t core_mask) noexcept;

private:
    void release_ticket(const graph::FrameTicket&);
    graph::RenderGraph graph_;
    graph::VulkanRecordingPlan plan_;
    std::map<std::uint64_t, graph::FrameTicket> recordings_;
    graph::FrameScheduler scheduler_;
    PFN_vkCmdPipelineBarrier barrier_ = nullptr;
    Record record_ = nullptr;
    Release release_ = nullptr;
    void* context_ = nullptr;
    std::uint64_t next_frame_ = 1;
    std::uint64_t releasing_command_ = 0;
    BedrayStatus release_status_ = BEDRAY_OK;
    bool ready_ = false;
};
}
