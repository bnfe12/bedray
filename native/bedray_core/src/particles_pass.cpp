#include "particles_pass.h"
#include <array>
#include <cmath>
#include <cstdio>

namespace bedray::core {
namespace {
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
struct Push { std::uint32_t width, height; float strength, pad; };
static_assert(sizeof(Push) == 16);
void barrier(PFN_vkCmdPipelineBarrier command_barrier, VkCommandBuffer command) {
    VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    memory.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    command_barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &memory, 0, nullptr, 0, nullptr);
}
}

BedrayStatus record_particles(BedrayCore* core, PFN_vkCmdPipelineBarrier command_barrier,
    const BedrayParticlesFrameDesc& frame, BedrayError* error) {
    if (frame.abi_version != BEDRAY_CORE_ABI_VERSION || frame.struct_size != sizeof(frame))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "particle frame ABI/size mismatch");
    if (!core || !command_barrier || !frame.command_buffer || !frame.program || !frame.layer_view
        || !frame.output_view || frame.layer_view == frame.output_view || !frame.width || !frame.height
        || !std::isfinite(frame.strength) || frame.strength < 0 || frame.reserved)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid particle composite inputs");
    ProgramContract contract{};
    if (!program_contract(core, frame.program, contract) || contract.push_constant_bytes != sizeof(Push)
        || contract.binding_count != 2
        || !program_has_binding(core, frame.program, 0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
        || !program_has_binding(core, frame.program, 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "particle composite program contract mismatch");

    std::array<BedrayResource, 2> resources{};
    resources[0].binding = 0; resources[0].descriptor_type = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    resources[0].handle = frame.layer_view; resources[0].image_layout = VK_IMAGE_LAYOUT_GENERAL;
    resources[1].binding = 2; resources[1].descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    resources[1].handle = frame.output_view; resources[1].image_layout = VK_IMAGE_LAYOUT_GENERAL;
    const Push push{frame.width, frame.height, frame.strength, 0};
    BedrayFrameDesc dispatch{};
    dispatch.abi_version = BEDRAY_CORE_ABI_VERSION;
    dispatch.command_buffer = frame.command_buffer; dispatch.program = frame.program;
    dispatch.group_count_x = (frame.width - 1) / 8 + 1;
    dispatch.group_count_y = (frame.height - 1) / 8 + 1; dispatch.group_count_z = 1;
    dispatch.resources = resources.data(); dispatch.resource_count = static_cast<std::uint32_t>(resources.size());
    dispatch.push_constants = &push; dispatch.push_constant_bytes = sizeof(push);
    auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    barrier(command_barrier, command);
    const auto status = bedray_core_render_frame(core, &dispatch, error);
    if (status != BEDRAY_OK) return status; // core retains any partial recording for host discard
    barrier(command_barrier, command);
    return BEDRAY_OK;
}
}
