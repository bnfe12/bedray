#pragma once

#include "bedray/vulkan_recording.hpp"
#include "bedray_core.h"

#include <cstdint>
#include <vector>

namespace bedray::graph {

// A pass references immutable dispatch data and host-owned resource handles.
// The core owns the transient descriptor allocation for each dispatch until
// release_recording; the host owns all resources referenced by those sets.
struct CorePassDispatch {
    PassId pass;
    const BedrayFrameDesc* frame;
};

using CoreRenderFrame = BedrayStatus (BEDRAY_CALL *)(BedrayCore*, const BedrayFrameDesc*, BedrayError*);

struct CoreDispatchError {
    RecordingError recording = RecordingError::none;
    PassId pass = external_pass;
    BedrayStatus core_status = BEDRAY_OK;
    BedrayError core_error{};
};

// Bridges a validated Vulkan recording plan to the existing C ABI dispatch.
// Every graph pass must have exactly one matching descriptor and all frame
// command-buffer handles, dispatch dimensions and push-constant ranges must
// match. Only compute-stage resource uses are supported. The host must validate descriptor semantics
// and resources before this call. A failure after recording begins requires
// discarding the whole command buffer, then releasing its core recording only
// when it is known not to be in flight. No submission or waiting happens here.
[[nodiscard]] bool record_core_dispatches(const RenderGraph& graph,
    const VulkanRecordingPlan& plan, VkCommandBuffer command_buffer,
    PFN_vkCmdPipelineBarrier barrier, BedrayCore* core, CoreRenderFrame dispatch,
    const std::vector<CorePassDispatch>& passes, CoreDispatchError& error);

} // namespace bedray::graph
