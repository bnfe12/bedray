#include "sodium_pass.h"
#include <cstdio>
#include <limits>

namespace bedray::core {
namespace {
constexpr std::uint32_t max_quads = std::numeric_limits<std::int32_t>::max() / 28;
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
struct Push {
    std::uint64_t raw, arena, flags;
    std::uint32_t count, reserved;
};
static_assert(sizeof(Push) == 32);
static_assert(offsetof(Push, count) == 24);
bool valid_range(std::uint64_t address, std::uint64_t bytes) noexcept {
    return address && (address & 3u) == 0 && bytes <= std::numeric_limits<std::uint64_t>::max() - address;
}
bool overlaps(std::uint64_t a, std::uint64_t a_bytes, std::uint64_t b, std::uint64_t b_bytes) noexcept {
    return a < b + b_bytes && b < a + a_bytes;
}
void barrier(PFN_vkCmdPipelineBarrier command_barrier, VkCommandBuffer command) {
    VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    memory.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    command_barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &memory, 0, nullptr, 0, nullptr);
}
}

BedrayStatus record_sodium(BedrayCore* core, PFN_vkCmdPipelineBarrier command_barrier,
    std::uint32_t max_groups_x, const BedraySodiumDecodeDesc& desc, BedrayError* error) {
    if (desc.abi_version != BEDRAY_CORE_ABI_VERSION || desc.struct_size != sizeof(desc))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "Sodium decode ABI/size mismatch");
    if (!core || !command_barrier || !desc.command_buffer || !desc.program
        || !desc.quad_count || desc.quad_count > max_quads || desc.reserved)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid Sodium decode recording/count");
    const auto raw_bytes = static_cast<std::uint64_t>(desc.quad_count) * 80u;
    const auto arena_bytes = static_cast<std::uint64_t>(desc.quad_count) * 112u;
    const auto flag_bytes = static_cast<std::uint64_t>(desc.quad_count) * 4u;
    if (!valid_range(desc.raw_address, raw_bytes) || !valid_range(desc.arena_address, arena_bytes)
        || overlaps(desc.raw_address, raw_bytes, desc.arena_address, arena_bytes)
        || (desc.flags_address && (!valid_range(desc.flags_address, flag_bytes)
            || overlaps(desc.flags_address, flag_bytes, desc.raw_address, raw_bytes)
            || overlaps(desc.flags_address, flag_bytes, desc.arena_address, arena_bytes))))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "Sodium decode ranges must be aligned, nonoverlapping and nonwrapping");
    const auto groups = (desc.quad_count - 1u) / 64u + 1u;
    if (!max_groups_x || groups > max_groups_x)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "Sodium decode exceeds device dispatch limits");
    ProgramContract contract{};
    if (!program_contract(core, desc.program, contract) || contract.push_constant_bytes != sizeof(Push)
        || contract.binding_count != 0)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "Sodium decode program contract mismatch");

    const Push push{desc.raw_address, desc.arena_address, desc.flags_address, desc.quad_count, 0};
    BedrayFrameDesc dispatch{};
    dispatch.abi_version = BEDRAY_CORE_ABI_VERSION;
    dispatch.command_buffer = desc.command_buffer; dispatch.program = desc.program;
    dispatch.group_count_x = groups; dispatch.group_count_y = dispatch.group_count_z = 1;
    dispatch.push_constants = &push; dispatch.push_constant_bytes = sizeof(push);
    const auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(desc.command_buffer));
    barrier(command_barrier, command);
    const auto status = bedray_core_render_frame(core, &dispatch, error);
    if (status != BEDRAY_OK) return status;
    barrier(command_barrier, command);
    return BEDRAY_OK;
}
}
