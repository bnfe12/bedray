#include "bedray/core_dispatch.hpp"
#include <cstdlib>

using namespace bedray::graph;

static int barrier_calls = 0;
static int dispatch_calls = 0;
static void require(bool value) { if (!value) std::abort(); }

static VKAPI_ATTR void VKAPI_CALL fake_barrier(VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
    VkDependencyFlags, std::uint32_t, const VkMemoryBarrier*, std::uint32_t, const VkBufferMemoryBarrier*,
    std::uint32_t, const VkImageMemoryBarrier*) { ++barrier_calls; }
static BedrayStatus BEDRAY_CALL fake_dispatch(BedrayCore*, const BedrayFrameDesc*, BedrayError*) {
    ++dispatch_calls;
    return BEDRAY_OK;
}

int main() {
    RenderGraph graph;
    PassDesc pass;
    pass.name = "trace";
    pass.dispatch = {2, 1, 1};
    const PassId id = graph.add_pass(pass);
    CompiledGraph compiled;
    Error graph_error;
    require(graph.compile(0, compiled, graph_error));
    VulkanRecordingPlan plan;
    RecordingError recording_error;
    require(prepare_vulkan_recording(graph, compiled, 0, {}, plan, recording_error));

    auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(1));
    auto core = reinterpret_cast<BedrayCore*>(static_cast<std::uintptr_t>(1));
    BedrayFrameDesc frame{};
    frame.abi_version = BEDRAY_CORE_ABI_VERSION;
    frame.command_buffer = 1;
    frame.group_count_x = 1;
    frame.group_count_y = 1;
    frame.group_count_z = 1;
    CoreDispatchError error;
    require(!record_core_dispatches(graph, plan, command, fake_barrier, core, fake_dispatch, {{id, &frame}}, error));
    require(error.recording == RecordingError::invalid_plan && barrier_calls == 0 && dispatch_calls == 0);

    frame.group_count_x = 2;
    require(record_core_dispatches(graph, plan, command, fake_barrier, core, fake_dispatch, {{id, &frame}}, error));
    require(barrier_calls == 1 && dispatch_calls == 1);
}
