#include "bedray/core_dispatch.hpp"

#include <cstdint>

namespace bedray::graph {
namespace {

struct DispatchContext {
    BedrayCore* core;
    CoreRenderFrame dispatch;
    const std::vector<CorePassDispatch>* passes;
    CoreDispatchError* error;
};

bool record_core_pass(void* opaque, PassId pass, VkCommandBuffer) {
    auto& context = *static_cast<DispatchContext*>(opaque);
    for (const auto& entry : *context.passes) {
        if (entry.pass != pass) continue;
        context.error->pass = pass;
        context.error->core_status = context.dispatch(context.core, entry.frame, &context.error->core_error);
        return context.error->core_status == BEDRAY_OK;
    }
    return false; // rejected before recording; defensive only
}

} // namespace

bool record_core_dispatches(const RenderGraph& graph, const VulkanRecordingPlan& plan,
    VkCommandBuffer command_buffer, PFN_vkCmdPipelineBarrier barrier, BedrayCore* core,
    CoreRenderFrame dispatch, const std::vector<CorePassDispatch>& passes,
    CoreDispatchError& error) {
    error = {};
    if (core == nullptr || dispatch == nullptr || command_buffer == VK_NULL_HANDLE ||
        passes.size() != plan.passes.size()) {
        error.recording = RecordingError::invalid_plan;
        return false;
    }
    const auto command_handle = static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(command_buffer));
    for (std::size_t i = 0; i < passes.size(); ++i) {
        const auto& entry = passes[i];
        if (entry.frame == nullptr || entry.frame->abi_version != BEDRAY_CORE_ABI_VERSION ||
            entry.frame->command_buffer != command_handle) {
            error.pass = entry.pass;
            error.recording = RecordingError::invalid_plan;
            return false;
        }
        bool found = false;
        for (const auto& expected : plan.passes) found = found || expected.pass == entry.pass;
        for (std::size_t j = 0; j < i; ++j) {
            if (passes[j].pass == entry.pass) found = false;
        }
        if (!found) {
            error.pass = entry.pass;
            error.recording = RecordingError::invalid_plan;
            return false;
        }
        const PassDesc* pass = graph.pass(entry.pass);
        if (pass == nullptr || entry.frame->group_count_x != pass->dispatch.x ||
            entry.frame->group_count_y != pass->dispatch.y ||
            entry.frame->group_count_z != pass->dispatch.z ||
            (entry.frame->resource_count != 0 && entry.frame->resources == nullptr) ||
            (entry.frame->push_constant_bytes != 0 && entry.frame->push_constants == nullptr)) {
            error.pass = entry.pass;
            error.recording = RecordingError::invalid_plan;
            return false;
        }
        // This bridge records compute dispatches only. Graph declarations for
        // transfers or acceleration builds need a different recording adapter.
        for (const auto& use : pass->uses) {
            if (use.stage != Stage::compute) {
                error.pass = entry.pass;
                error.recording = RecordingError::invalid_plan;
                return false;
            }
        }
        // ABI v2 pushes one contiguous block from byte zero. Multiple graph
        // ranges are accepted only if they cover that block without holes.
        std::uint32_t total = 0;
        std::uint32_t end = 0;
        for (const auto& range : pass->push_constants) {
            total += range.size;
            const std::uint32_t range_end = range.offset + range.size;
            if (range_end > end) end = range_end;
        }
        if (total != end || end != entry.frame->push_constant_bytes) {
            error.pass = entry.pass;
            error.recording = RecordingError::invalid_plan;
            return false;
        }
    }
    DispatchContext context{core, dispatch, &passes, &error};
    return record_vulkan_graph(graph, plan, command_buffer, barrier, record_core_pass, &context, error.recording);
}

} // namespace bedray::graph
