#include "fg_prepare_pass.h"
#include <array>
#include <cstdio>
#include <limits>

namespace bedray::core {
namespace {
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
struct Push { std::uint32_t width, height; };
static_assert(sizeof(Push) == 8);
void barrier(PFN_vkCmdPipelineBarrier command_barrier, VkCommandBuffer command) {
    VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    memory.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    command_barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &memory, 0, nullptr, 0, nullptr);
}
}

BedrayStatus record_fg_prepare(BedrayCore* core, PFN_vkCmdPipelineBarrier command_barrier,
    PFN_vkCmdBlitImage command_blit, const BedrayFgPrepareFrameDesc& frame, BedrayError* error) {
    if (frame.abi_version != BEDRAY_CORE_ABI_VERSION || frame.struct_size != sizeof(frame))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "FG producer frame ABI/size mismatch");
    constexpr auto maximum = static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max());
    if (!core || !command_barrier || !command_blit || !frame.command_buffer || !frame.program || !frame.sampler
        || !frame.source_color_image || !frame.hudless_image || frame.source_color_image == frame.hudless_image
        || !frame.depth_view || !frame.input_motion_view || !frame.output_depth_view || !frame.output_motion_view
        || frame.output_depth_view == frame.output_motion_view || frame.output_depth_view == frame.depth_view
        || frame.output_depth_view == frame.input_motion_view || frame.output_motion_view == frame.depth_view
        || frame.output_motion_view == frame.input_motion_view || !frame.window_width || !frame.window_height
        || !frame.render_width || !frame.render_height || frame.window_width > maximum || frame.window_height > maximum
        || frame.render_width > maximum || frame.render_height > maximum || frame.reserved)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid FG producer inputs");
    ProgramContract contract{};
    if (!program_contract(core, frame.program, contract) || contract.push_constant_bytes != sizeof(Push)
        || contract.binding_count != 4
        || !program_has_binding(core, frame.program, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        || !program_has_binding(core, frame.program, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
        || !program_has_binding(core, frame.program, 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
        || !program_has_binding(core, frame.program, 3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "FG producer program contract mismatch");

    std::array<BedrayResource, 4> resources{};
    const std::array<std::uint64_t, 4> views{frame.depth_view,frame.input_motion_view,frame.output_depth_view,frame.output_motion_view};
    for (std::uint32_t index = 0; index < resources.size(); ++index) {
        resources[index].binding = index;
        resources[index].descriptor_type = index == 0 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        resources[index].handle = views[index]; resources[index].sampler = index == 0 ? frame.sampler : 0;
        resources[index].image_layout = VK_IMAGE_LAYOUT_GENERAL;
    }
    const Push push{frame.render_width, frame.render_height};
    BedrayFrameDesc dispatch{};
    dispatch.abi_version = BEDRAY_CORE_ABI_VERSION; dispatch.command_buffer = frame.command_buffer; dispatch.program = frame.program;
    dispatch.group_count_x = (frame.render_width - 1) / 8 + 1;
    dispatch.group_count_y = (frame.render_height - 1) / 8 + 1; dispatch.group_count_z = 1;
    dispatch.resources = resources.data(); dispatch.resource_count = static_cast<std::uint32_t>(resources.size());
    dispatch.push_constants = &push; dispatch.push_constant_bytes = sizeof(push);
    auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    VkImageBlit region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
    region.srcOffsets[1] = {static_cast<std::int32_t>(frame.window_width), static_cast<std::int32_t>(frame.window_height),1};
    region.dstOffsets[0] = {0,static_cast<std::int32_t>(frame.window_height),0};
    region.dstOffsets[1] = {static_cast<std::int32_t>(frame.window_width),0,1};
    barrier(command_barrier, command);
    command_blit(command, reinterpret_cast<VkImage>(static_cast<std::uintptr_t>(frame.source_color_image)), VK_IMAGE_LAYOUT_GENERAL,
        reinterpret_cast<VkImage>(static_cast<std::uintptr_t>(frame.hudless_image)), VK_IMAGE_LAYOUT_GENERAL, 1, &region, VK_FILTER_NEAREST);
    // Blit and compute write distinct host-owned images; no dependency between them.
    const auto status = bedray_core_render_frame(core, &dispatch, error);
    if (status != BEDRAY_OK) return status;
    barrier(command_barrier, command);
    return BEDRAY_OK;
}
}
