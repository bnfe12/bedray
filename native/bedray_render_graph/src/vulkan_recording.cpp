#include "bedray/vulkan_recording.hpp"

#include <utility>

namespace bedray::graph {
namespace {

bool fail(RecordingError& error, RecordingError value) noexcept {
    error = value;
    return false;
}

bool supported_image_use(const ResourceUse& use) noexcept {
    const auto layout = static_cast<VkImageLayout>(use.image_layout);
    if (use.stage == Stage::acceleration_build) return false;
    if (layout == VK_IMAGE_LAYOUT_GENERAL) return true;
    if (use.stage == Stage::transfer) {
        return (use.access == Access::read && layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) ||
               (use.access == Access::write && layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    }
    return use.access == Access::read && layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
}

bool supported_initial_layout(VkImageLayout layout) noexcept {
    return layout == VK_IMAGE_LAYOUT_UNDEFINED || layout == VK_IMAGE_LAYOUT_GENERAL ||
           layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ||
           layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ||
           layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
}

} // namespace

bool prepare_vulkan_recording(const RenderGraph& graph, const CompiledGraph& compiled,
    std::uint64_t enabled_capabilities, const std::vector<VulkanImageBinding>& images,
    VulkanRecordingPlan& out, RecordingError& error) {
    error = RecordingError::none;
    if (!graph.is_current(compiled, enabled_capabilities)) return fail(error, RecordingError::stale_graph);
    CompiledGraph canonical;
    Error graph_error;
    if (!graph.compile(enabled_capabilities, canonical, graph_error) || canonical.order != compiled.order)
        return fail(error, RecordingError::invalid_plan);

    for (std::size_t i = 0; i < images.size(); ++i) {
        const auto& image = images[i];
        const ResourceDesc* desc = graph.resource(image.resource);
        if (desc == nullptr || desc->kind != ResourceKind::image || image.image == VK_NULL_HANDLE ||
            image.range.aspectMask != VK_IMAGE_ASPECT_COLOR_BIT || image.range.levelCount == 0 ||
            image.range.layerCount == 0 || image.range.levelCount == VK_REMAINING_MIP_LEVELS ||
            image.range.layerCount == VK_REMAINING_ARRAY_LAYERS ||
            !supported_initial_layout(image.initial_layout))
            return fail(error, RecordingError::invalid_image);
        for (std::size_t j = 0; j < i; ++j) {
            if (images[j].resource == image.resource || images[j].image == image.image)
                return fail(error, RecordingError::duplicate_image);
        }
    }

    VulkanRecordingPlan result;
    result.owner = &graph;
    result.enabled_capabilities = enabled_capabilities;
    result.graph = std::move(canonical);
    result.final_images.reserve(images.size());
    for (const auto& image : images) result.final_images.push_back({image.resource, image.initial_layout});

    for (PassId pass_id : result.graph.order) {
        const PassDesc* pass = graph.pass(pass_id);
        if (pass == nullptr) return fail(error, RecordingError::invalid_plan);
        VulkanPassBarriers step{pass_id, {}};
        for (const ResourceUse& use : pass->uses) {
            const ResourceDesc* desc = graph.resource(use.resource);
            if (desc == nullptr) return fail(error, RecordingError::invalid_plan);
            if (desc->kind != ResourceKind::image) continue;
            if (!supported_image_use(use)) return fail(error, RecordingError::unsupported_image_use);
            std::size_t index = 0;
            while (index < images.size() && images[index].resource != use.resource) ++index;
            if (index == images.size()) return fail(error, RecordingError::missing_image);
            auto& state = result.final_images[index];
            if (state.layout == VK_IMAGE_LAYOUT_UNDEFINED && use.access != Access::write)
                return fail(error, RecordingError::uninitialized_image);
            const auto desired = static_cast<VkImageLayout>(use.image_layout);
            if (state.layout != desired) {
                VkImageMemoryBarrier transition{};
                transition.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                transition.srcAccessMask = state.layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 :
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
                transition.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
                transition.oldLayout = state.layout;
                transition.newLayout = desired;
                transition.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                transition.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                transition.image = images[index].image;
                transition.subresourceRange = images[index].range;
                step.images.push_back(transition);
            }
            state.layout = desired;
        }
        result.passes.push_back(std::move(step));
    }
    out = std::move(result);
    return true;
}

bool record_vulkan_graph(const RenderGraph& graph, const VulkanRecordingPlan& plan,
    VkCommandBuffer command_buffer, PFN_vkCmdPipelineBarrier barrier, RecordPass record_pass,
    void* context, RecordingError& error) {
    error = RecordingError::none;
    if (plan.owner != &graph || !graph.is_current(plan.graph, plan.enabled_capabilities))
        return fail(error, RecordingError::stale_graph);
    if (command_buffer == VK_NULL_HANDLE || barrier == nullptr || record_pass == nullptr)
        return fail(error, RecordingError::invalid_plan);
    // A broad same-queue dependency before every pass is intentionally
    // conservative; layout transitions use the immediately preceding state.
    VkMemoryBarrier memory{};
    memory.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memory.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    for (const auto& step : plan.passes) {
        if (!graph.is_current(plan.graph, plan.enabled_capabilities))
            return fail(error, RecordingError::stale_graph);
        barrier(command_buffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, 1, &memory, 0, nullptr, static_cast<std::uint32_t>(step.images.size()), step.images.data());
        if (!record_pass(context, step.pass, command_buffer)) return fail(error, RecordingError::callback_failed);
    }
    if (!graph.is_current(plan.graph, plan.enabled_capabilities))
        return fail(error, RecordingError::stale_graph);
    return true;
}

} // namespace bedray::graph
