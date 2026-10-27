#include "bedray/vulkan_recording.hpp"

#include <cstdint>
#include <cstdio>
#include <utility>
#include <vector>

using namespace bedray::graph;

namespace {

int failures = 0;

void check(bool condition, const char* what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

constexpr std::uint32_t layout_general = static_cast<std::uint32_t>(VK_IMAGE_LAYOUT_GENERAL);
constexpr std::uint32_t layout_shader_read =
    static_cast<std::uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
constexpr std::uint32_t layout_transfer_src =
    static_cast<std::uint32_t>(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

VkImage fake_image(std::uintptr_t value) {
    return reinterpret_cast<VkImage>(value);
}

VkCommandBuffer fake_command_buffer() {
    return reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(1));
}

VkImageSubresourceRange colour_range() {
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;
    return range;
}

VulkanImageBinding bind(ResourceId resource, VkImage image, VkImageLayout initial_layout) {
    return VulkanImageBinding{resource, image, initial_layout, colour_range()};
}

struct Pipeline {
    ResourceId radiance = 0;
    PassId trace = 0;
    PassId display = 0;
};

Pipeline make_pipeline(RenderGraph& graph, std::uint32_t read_layout = layout_shader_read) {
    Pipeline p;
    p.radiance = graph.add_resource({"history.radiance", ResourceKind::image, 0, false});

    PassDesc trace;
    trace.name = "scene.trace";
    trace.uses = {{p.radiance, Access::write, Stage::compute, layout_general}};
    p.trace = graph.add_pass(std::move(trace));

    PassDesc display;
    display.name = "display";
    display.uses = {{p.radiance, Access::read, Stage::compute, read_layout}};
    display.after = {p.trace};
    p.display = graph.add_pass(std::move(display));
    return p;
}

bool compile_and_prepare(RenderGraph& graph, const std::vector<VulkanImageBinding>& images,
    VulkanRecordingPlan& plan, RecordingError& error) {
    CompiledGraph compiled;
    Error graph_error;
    if (!graph.compile(0, compiled, graph_error)) {
        std::fprintf(stderr, "graph unexpectedly failed to compile\n");
        return false;
    }
    return prepare_vulkan_recording(graph, compiled, 0, images, plan, error);
}

struct BarrierCall {
    VkPipelineStageFlags source_stage = 0;
    VkPipelineStageFlags destination_stage = 0;
    std::uint32_t memory_count = 0;
    std::vector<VkImageMemoryBarrier> images;
};

struct RecordLog {
    std::vector<PassId> order;
    std::vector<BarrierCall> barriers;
    PassId fail_on = external_pass;
    RenderGraph* mutate = nullptr;
};

RecordLog* active_log = nullptr;

void VKAPI_CALL fake_barrier(VkCommandBuffer command_buffer, VkPipelineStageFlags source_stage,
    VkPipelineStageFlags destination_stage, VkDependencyFlags dependency_flags,
    std::uint32_t memory_count, const VkMemoryBarrier* memory_barriers,
    std::uint32_t buffer_count, const VkBufferMemoryBarrier* buffer_barriers,
    std::uint32_t image_count, const VkImageMemoryBarrier* image_barriers) {
    (void)command_buffer;
    (void)dependency_flags;
    (void)memory_barriers;
    (void)buffer_count;
    (void)buffer_barriers;
    if (active_log == nullptr) {
        return;
    }
    BarrierCall call;
    call.source_stage = source_stage;
    call.destination_stage = destination_stage;
    call.memory_count = memory_count;
    if (image_count != 0) {
        call.images.assign(image_barriers, image_barriers + image_count);
    }
    active_log->barriers.push_back(std::move(call));
}

bool record_pass(void* opaque, PassId pass, VkCommandBuffer command_buffer) {
    (void)command_buffer;
    auto& log = *static_cast<RecordLog*>(opaque);
    log.order.push_back(pass);
    if (log.mutate != nullptr) {
        RenderGraph* graph = log.mutate;
        log.mutate = nullptr;
        graph->add_pass(PassDesc{"mutated", {}, {}, 0, any_queue, {1, 1, 1}, {}});
    }
    return log.fail_on != pass;
}

void prepares_image_transitions() {
    RenderGraph graph;
    const Pipeline p = make_pipeline(graph);
    VulkanRecordingPlan plan;
    RecordingError error;
    check(compile_and_prepare(graph, {bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED)},
              plan, error),
        "valid image graph prepares a recording plan");
    check(error == RecordingError::none, "preparation reports no error");
    check(plan.passes.size() == 2, "one barrier step per pass");
    check(plan.passes[0].pass == p.trace && plan.passes[1].pass == p.display,
        "recording plan follows graph order");
    check(plan.passes[0].images.size() == 1 &&
        plan.passes[0].images[0].oldLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
        plan.passes[0].images[0].newLayout == VK_IMAGE_LAYOUT_GENERAL,
        "undefined image transitions to the first write layout");
    check(plan.passes[1].images.size() == 1 &&
        plan.passes[1].images[0].oldLayout == VK_IMAGE_LAYOUT_GENERAL &&
        plan.passes[1].images[0].newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        "layout change before the consumer pass is planned");
    check(plan.final_images.size() == 1 && plan.final_images[0].resource == p.radiance &&
        plan.final_images[0].layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        "final image layout is reported to the host");

    RecordLog log;
    active_log = &log;
    check(record_vulkan_graph(graph, plan, fake_command_buffer(), fake_barrier, record_pass, &log,
              error),
        "recording a prepared plan succeeds");
    active_log = nullptr;
    check(error == RecordingError::none, "recording reports no error");
    check(log.order.size() == 2 && log.order[0] == p.trace && log.order[1] == p.display,
        "passes are recorded in plan order");
    check(log.barriers.size() == 2, "one barrier per pass");
    if (log.barriers.size() == 2) {
        check(log.barriers[0].source_stage == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT &&
            log.barriers[0].destination_stage == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            "barriers use the conservative all-commands dependency");
        check(log.barriers[0].memory_count == 1, "a global memory barrier precedes every pass");
        check(log.barriers[0].images.size() == 1 && log.barriers[1].images.size() == 1,
            "recorded image barriers match the prepared plan");
        if (log.barriers[1].images.size() == 1) {
            check(log.barriers[1].images[0].oldLayout == VK_IMAGE_LAYOUT_GENERAL &&
                log.barriers[1].images[0].newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                "recorded transition matches the tracked layout");
        }
    }
}

void rejects_invalid_bindings() {
    RenderGraph graph;
    const Pipeline p = make_pipeline(graph);
    VulkanRecordingPlan plan;
    RecordingError error;

    check(!compile_and_prepare(graph, {}, plan, error) && error == RecordingError::missing_image,
        "an unbound image resource is rejected");

    check(!compile_and_prepare(graph, {bind(p.radiance, VK_NULL_HANDLE, VK_IMAGE_LAYOUT_UNDEFINED)},
              plan, error) &&
        error == RecordingError::invalid_image,
        "a null image handle is rejected");

    VulkanImageBinding wrong_aspect = bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED);
    wrong_aspect.range.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
    check(!compile_and_prepare(graph, {wrong_aspect}, plan, error) &&
        error == RecordingError::invalid_image,
        "a non-colour aspect is rejected");

    VulkanImageBinding no_levels = bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED);
    no_levels.range.levelCount = 0;
    check(!compile_and_prepare(graph, {no_levels}, plan, error) &&
        error == RecordingError::invalid_image,
        "an empty subresource range is rejected");

    VulkanImageBinding remaining_levels =
        bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED);
    remaining_levels.range.levelCount = VK_REMAINING_MIP_LEVELS;
    check(!compile_and_prepare(graph, {remaining_levels}, plan, error) &&
        error == RecordingError::invalid_image,
        "VK_REMAINING_MIP_LEVELS is not a concrete range");

    check(!compile_and_prepare(graph,
              {bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)},
              plan, error) &&
        error == RecordingError::invalid_image,
        "an unsupported initial layout is rejected");

    check(!compile_and_prepare(graph,
              {bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED),
                  bind(p.radiance, fake_image(0x2000), VK_IMAGE_LAYOUT_UNDEFINED)},
              plan, error) &&
        error == RecordingError::duplicate_image,
        "duplicate bindings for one resource are rejected");

    RenderGraph shared;
    const ResourceId first = shared.add_resource({"a", ResourceKind::image, 0, false});
    const ResourceId second = shared.add_resource({"b", ResourceKind::image, 0, true});
    const VkImage handle = fake_image(0x3000);
    check(!compile_and_prepare(shared,
              {bind(first, handle, VK_IMAGE_LAYOUT_UNDEFINED),
                  bind(second, handle, VK_IMAGE_LAYOUT_UNDEFINED)},
              plan, error) &&
        error == RecordingError::duplicate_image,
        "one Vulkan image cannot represent two graph resources");
}

void rejects_invalid_uses() {
    {
        RenderGraph graph;
        const ResourceId image = graph.add_resource({"r", ResourceKind::image, 0, true});
        graph.add_pass(PassDesc{"read", {{image, Access::read, Stage::compute, layout_transfer_src}},
            {}, 0, any_queue, {1, 1, 1}, {}});
        VulkanRecordingPlan plan;
        RecordingError error;
        check(!compile_and_prepare(graph,
                  {bind(image, fake_image(0x1000), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)},
                  plan, error) &&
            error == RecordingError::unsupported_image_use,
            "a compute read with a transfer layout is rejected");
    }
    {
        RenderGraph graph;
        const ResourceId image = graph.add_resource({"r", ResourceKind::image, 0, true});
        graph.add_pass(PassDesc{"read", {{image, Access::read, Stage::compute, layout_general}},
            {}, 0, any_queue, {1, 1, 1}, {}});
        VulkanRecordingPlan plan;
        RecordingError error;
        check(!compile_and_prepare(graph,
                  {bind(image, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED)},
                  plan, error) &&
            error == RecordingError::uninitialized_image,
            "an undefined image read without a producer is rejected");
    }
    {
        RenderGraph graph;
        const ResourceId image = graph.add_resource({"r", ResourceKind::image, 0, true});
        graph.add_pass(PassDesc{"build", {{image, Access::read, Stage::acceleration_build, layout_general}},
            {}, 0, any_queue, {1, 1, 1}, {}});
        VulkanRecordingPlan plan;
        RecordingError error;
        check(!compile_and_prepare(graph,
                  {bind(image, fake_image(0x1000), VK_IMAGE_LAYOUT_GENERAL)},
                  plan, error) &&
            error == RecordingError::unsupported_image_use,
            "acceleration-build-stage image use is not silently supported");
    }
}

void rejects_invalid_recording() {
    RenderGraph graph;
    const Pipeline p = make_pipeline(graph);
    VulkanRecordingPlan plan;
    RecordingError error;
    check(compile_and_prepare(graph, {bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED)},
              plan, error),
        "recording fixture prepares");

    RecordLog log;
    check(!record_vulkan_graph(graph, plan, VK_NULL_HANDLE, fake_barrier, record_pass, &log, error) &&
        error == RecordingError::invalid_plan,
        "a null command buffer is rejected");
    check(!record_vulkan_graph(graph, plan, fake_command_buffer(), nullptr, record_pass, &log,
              error) &&
        error == RecordingError::invalid_plan,
        "a null barrier function is rejected");
    check(!record_vulkan_graph(graph, plan, fake_command_buffer(), fake_barrier, nullptr, &log,
              error) &&
        error == RecordingError::invalid_plan,
        "a null pass callback is rejected");

    RenderGraph other;
    make_pipeline(other);
    check(!record_vulkan_graph(other, plan, fake_command_buffer(), fake_barrier, record_pass, &log,
              error) &&
        error == RecordingError::stale_graph,
        "a plan cannot be replayed against another graph");

    RecordLog failing;
    failing.fail_on = p.display;
    active_log = &failing;
    check(!record_vulkan_graph(graph, plan, fake_command_buffer(), fake_barrier, record_pass,
              &failing, error) &&
        error == RecordingError::callback_failed,
        "a failed pass callback aborts the recording");
    active_log = nullptr;
    check(failing.order.size() == 2, "recording stops after the failing callback");

    RecordLog mutating;
    mutating.mutate = &graph;
    active_log = &mutating;
    check(!record_vulkan_graph(graph, plan, fake_command_buffer(), fake_barrier, record_pass,
              &mutating, error) &&
        error == RecordingError::stale_graph,
        "graph mutation during recording invalidates the plan");
    active_log = nullptr;
    check(mutating.order.size() == 1, "recording stops before the next stale pass");
}

void rejects_stale_plans() {
    RenderGraph graph;
    const Pipeline p = make_pipeline(graph);
    VulkanRecordingPlan plan;
    RecordingError error;
    check(compile_and_prepare(graph, {bind(p.radiance, fake_image(0x1000), VK_IMAGE_LAYOUT_UNDEFINED)},
              plan, error),
        "stale fixture prepares");

    graph.add_resource({"late", ResourceKind::buffer, 0, true});
    RecordLog log;
    active_log = &log;
    check(!record_vulkan_graph(graph, plan, fake_command_buffer(), fake_barrier, record_pass, &log,
              error) &&
        error == RecordingError::stale_graph,
        "mutating the graph invalidates a prepared recording plan");
    active_log = nullptr;
    check(log.order.empty() && log.barriers.empty(), "no commands are recorded for a stale plan");
}

} // namespace

int main() {
    prepares_image_transitions();
    rejects_invalid_bindings();
    rejects_invalid_uses();
    rejects_invalid_recording();
    rejects_stale_plans();
    if (failures != 0) {
        std::fprintf(stderr, "%d Vulkan recording check(s) failed\n", failures);
        return 1;
    }
    std::printf("bedray Vulkan recording test passed\n");
    return 0;
}
