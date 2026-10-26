#pragma once

#include "bedray/render_graph.hpp"
#include <vulkan/vulkan.h>
#include <cstdint>
#include <vector>

namespace bedray::graph {

// One complete colour image per resource. The host must prove that all images,
// buffers and acceleration structures bound to the graph do not alias, that
// images are owned by this queue family and are alive until GPU completion,
// and that earlier cross-queue work has been made available via submission
// synchronization. The range must cover every subresource used by the graph.
struct VulkanImageBinding {
    ResourceId resource;
    VkImage image;
    VkImageLayout initial_layout;
    VkImageSubresourceRange range;
};

enum class RecordingError : std::uint8_t {
    none, stale_graph, invalid_plan, missing_image, duplicate_image,
    invalid_image, unsupported_image_use, uninitialized_image, callback_failed
};

struct VulkanPassBarriers {
    PassId pass;
    std::vector<VkImageMemoryBarrier> images;
};

struct VulkanFinalImage {
    ResourceId resource;
    VkImageLayout layout;
};

struct VulkanRecordingPlan {
    const RenderGraph* owner = nullptr;
    std::uint64_t enabled_capabilities = 0;
    CompiledGraph graph;
    std::vector<VulkanPassBarriers> passes;
    std::vector<VulkanFinalImage> final_images;
};

// CPU preparation validates a canonical graph plan before recording commands.
// On failure `out` is unchanged. The host still validates physical resources,
// descriptor contracts, device identity and all external synchronization.
[[nodiscard]] bool prepare_vulkan_recording(const RenderGraph& graph,
    const CompiledGraph& compiled, std::uint64_t enabled_capabilities,
    const std::vector<VulkanImageBinding>& images, VulkanRecordingPlan& out,
    RecordingError& error);

// The callback records a pass in the provided command buffer and must not
// begin/end/submit it or change tracked image layouts. If it fails, the host
// must discard the entire recording. All graph work is outside render passes.
using RecordPass = bool (*)(void*, PassId, VkCommandBuffer);
[[nodiscard]] bool record_vulkan_graph(const RenderGraph& graph,
    const VulkanRecordingPlan& plan, VkCommandBuffer command_buffer,
    PFN_vkCmdPipelineBarrier barrier, RecordPass record_pass, void* context,
    RecordingError& error);

} // namespace bedray::graph
