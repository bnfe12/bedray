#include "nrd_pack_pass.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <limits>
#include <span>

namespace bedray::core {
namespace {
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
struct Push {
    std::uint32_t normal, roughness, denoiser, flags;
    float a, b, c, range;
    std::uint32_t width, height;
    float exposure;
    std::uint32_t reserved;
    std::uint64_t motion;
};
static_assert(sizeof(Push) == 56);
static_assert(offsetof(Push, a) == 16 && offsetof(Push, width) == 32);
static_assert(offsetof(Push, exposure) == 40 && offsetof(Push, motion) == 48);
constexpr std::array<std::uint32_t, 11> pack_bindings{0,1,2,3,4,5,6,9,10,12,13};
constexpr std::array<std::uint32_t, 9> rr_bindings{0,1,2,4,5,6,9,10,13};
constexpr std::array<std::uint32_t, 6> compose_bindings{0,3,4,7,8,11};

void barrier(PFN_vkCmdPipelineBarrier command_barrier, VkCommandBuffer command) {
    VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    memory.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    command_barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &memory, 0, nullptr, 0, nullptr);
}

bool aliases_written_view(const BedrayNrdPackFrameDesc& frame, std::span<const std::uint32_t> bindings) {
    for (auto binding : bindings) {
        const bool writes = frame.phase == BEDRAY_NRD_COMPOSE ? binding == 8 :
            (binding == 5 || binding == 6 || binding == 10 || binding == 12);
        if (!writes) continue;
        for (auto other : bindings)
            if (other != binding && frame.views[binding] == frame.views[other]) return true;
    }
    return false;
}
}

BedrayStatus record_nrd_pack(BedrayCore* core, PFN_vkCmdPipelineBarrier command_barrier,
    const BedrayNrdPackFrameDesc& frame, BedrayError* error) {
    if (frame.abi_version != BEDRAY_CORE_ABI_VERSION || frame.struct_size != sizeof(frame))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "NRD pack frame ABI/size mismatch");
    const auto pixels = std::uint64_t{frame.width} * frame.height;
    const auto maximum = std::numeric_limits<std::uint64_t>::max();
    if (!core || !command_barrier || !frame.command_buffer || !frame.program
        || frame.phase > BEDRAY_NRD_COMPOSE || !frame.width || !frame.height
        || pixels > std::numeric_limits<std::uint32_t>::max()
        || frame.roughness_encoding > 2 || frame.denoiser > 2 || (frame.flags & ~3u) || frame.reserved
        || (frame.denoiser == 2 ? frame.normal_encoding != 0 :
            (frame.normal_encoding != 2 && frame.normal_encoding != 4))
        || !std::isfinite(frame.hit_dist_a) || !(frame.hit_dist_a > 0)
        || !std::isfinite(frame.hit_dist_b) || frame.hit_dist_b < 0
        || !std::isfinite(frame.hit_dist_c) || !(frame.hit_dist_c > 0)
        || !std::isfinite(frame.denoising_range) || !(frame.denoising_range > 0)
        || !std::isfinite(frame.exposure) || frame.exposure < 0
        || (frame.motion_address && ((frame.motion_address & 15u) || pixels > (maximum - frame.motion_address) / 16)))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid NRD pack inputs");

    const std::span<const std::uint32_t> bindings = frame.phase == BEDRAY_NRD_COMPOSE
        ? std::span<const std::uint32_t>{compose_bindings}
        : frame.denoiser == 2 ? std::span<const std::uint32_t>{rr_bindings}
        : std::span<const std::uint32_t>{pack_bindings};
    ProgramContract contract{};
    if (!program_contract(core, frame.program, contract) || contract.push_constant_bytes != sizeof(Push)
        || contract.binding_count != bindings.size())
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "NRD pack program contract mismatch");
    for (auto binding : bindings) {
        if (!frame.views[binding] || !program_has_binding(core, frame.program, binding, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE))
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "NRD pack image/binding contract mismatch");
    }
    if (aliases_written_view(frame, bindings))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "NRD pack input/output alias");

    std::array<BedrayResource, pack_bindings.size()> resources{};
    for (std::size_t index = 0; index < bindings.size(); ++index) {
        resources[index].binding = bindings[index];
        resources[index].descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        resources[index].handle = frame.views[bindings[index]];
        resources[index].image_layout = VK_IMAGE_LAYOUT_GENERAL;
    }
    const Push push{frame.normal_encoding, frame.roughness_encoding, frame.denoiser, frame.flags,
        frame.hit_dist_a, frame.hit_dist_b, frame.hit_dist_c, frame.denoising_range,
        frame.width, frame.height, frame.exposure, 0, frame.denoiser == 2 ? 0 : frame.motion_address};
    BedrayFrameDesc dispatch{};
    dispatch.abi_version = BEDRAY_CORE_ABI_VERSION;
    dispatch.command_buffer = frame.command_buffer; dispatch.program = frame.program;
    dispatch.group_count_x = (frame.width - 1) / 8 + 1;
    dispatch.group_count_y = (frame.height - 1) / 8 + 1; dispatch.group_count_z = 1;
    dispatch.resources = resources.data(); dispatch.resource_count = static_cast<std::uint32_t>(bindings.size());
    dispatch.push_constants = &push; dispatch.push_constant_bytes = sizeof(push);
    auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    barrier(command_barrier, command);
    auto status = bedray_core_render_frame(core, &dispatch, error);
    if (status != BEDRAY_OK) return status;
    barrier(command_barrier, command);
    return BEDRAY_OK;
}
}
