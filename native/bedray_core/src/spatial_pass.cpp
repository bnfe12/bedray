#include "spatial_pass.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace bedray::core {
namespace {
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
template<class T, std::size_t N> T read(const std::array<unsigned char,N>& bytes, std::size_t offset) noexcept {
    T value; std::memcpy(&value, bytes.data() + offset, sizeof(value)); return value;
}
bool program_valid(BedrayCore* core, std::uint64_t program, std::uint32_t push) noexcept {
    ProgramContract contract{};
    return program_contract(core, program, contract) && contract.push_constant_bytes == push && contract.binding_count == 2
        && program_has_binding(core, program, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        && program_has_binding(core, program, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
}
void barrier(PFN_vkCmdPipelineBarrier function, VkCommandBuffer command) {
    VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    memory.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    function(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &memory, 0, nullptr, 0, nullptr);
}
}

BedrayStatus record_spatial(BedrayCore* core, PFN_vkCmdPipelineBarrier function,
    const BedraySpatialFrameDesc& frame, BedrayError* error) {
    if (frame.abi_version != BEDRAY_CORE_ABI_VERSION || frame.struct_size != sizeof(frame))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "spatial ABI/size mismatch");
    const bool fsr = frame.backend == BEDRAY_SPATIAL_FSR1;
    const bool sharpen = (frame.flags & BEDRAY_SPATIAL_SHARPEN) != 0;
    if (!core || !function || !frame.command_buffer || frame.backend > BEDRAY_SPATIAL_NIS
        || (frame.flags & ~BEDRAY_SPATIAL_SHARPEN) || (!fsr && sharpen) || frame.reserved
        || !frame.views[0] || !frame.views[2] || frame.views[0] == frame.views[2]
        || !frame.samplers[0] || (sharpen && !frame.samplers[1])
        || !frame.input_width || !frame.input_height || frame.output_width < frame.input_width || frame.output_height < frame.input_height
        || frame.output_width > 65535 || frame.output_height > 65535
        || (sharpen && (!frame.views[1] || frame.views[1] == frame.views[0] || frame.views[1] == frame.views[2]))
        || !frame.primary_push || frame.primary_bytes != (fsr ? 80u : 112u)
        || (sharpen ? !frame.secondary_push || frame.secondary_bytes != 32 : frame.secondary_push || frame.secondary_bytes))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid spatial frame/chain");
    std::array<unsigned char,112> primary{};
    std::array<unsigned char,32> secondary{};
    std::memcpy(primary.data(), frame.primary_push, frame.primary_bytes);
    if (sharpen) std::memcpy(secondary.data(), frame.secondary_push, secondary.size());
    if (!program_valid(core, frame.programs[fsr ? 0 : 2], frame.primary_bytes)
        || (sharpen && !program_valid(core, frame.programs[1], 32)))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "spatial program contract mismatch");
    if (fsr) {
        if (read<std::uint32_t>(primary,64) != frame.output_width || read<std::uint32_t>(primary,68) != frame.output_height
            || read<std::uint32_t>(primary,72) || read<std::uint32_t>(primary,76)
            || (sharpen && (read<std::uint32_t>(secondary,16) != frame.output_width
                || read<std::uint32_t>(secondary,20) != frame.output_height || read<std::uint32_t>(secondary,24)
                || read<std::uint32_t>(secondary,28))))
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "spatial FSR1 push extent mismatch");
    } else {
        // NVScaler's shared tile dimensions assume its documented [0.5,1] scale.
        if (frame.input_width * 2 < frame.output_width || frame.input_height * 2 < frame.output_height
            || read<std::uint32_t>(primary,72) || read<std::uint32_t>(primary,76)
            || read<std::uint32_t>(primary,80) != frame.input_width || read<std::uint32_t>(primary,84) != frame.input_height
            || read<std::uint32_t>(primary,88) || read<std::uint32_t>(primary,92)
            || read<std::uint32_t>(primary,96) != frame.output_width || read<std::uint32_t>(primary,100) != frame.output_height
            || read<std::uint32_t>(primary,104) || read<std::uint32_t>(primary,108))
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "spatial NIS viewport/scale mismatch");
        for (std::size_t offset = 0; offset < 72; offset += 4)
            if (!std::isfinite(read<float>(primary,offset)))
                return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "spatial NIS constants must be finite");
    }
    const auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    auto dispatch = [&](std::uint64_t program, std::uint64_t source, std::uint64_t target,
                        std::uint64_t sampler, const void* push, std::uint32_t bytes, bool nis) {
        std::array<BedrayResource,2> resources{};
        resources[0].binding = 0; resources[0].descriptor_type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        resources[0].handle = source; resources[0].sampler = sampler; resources[0].image_layout = VK_IMAGE_LAYOUT_GENERAL;
        resources[1].binding = 1; resources[1].descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        resources[1].handle = target; resources[1].image_layout = VK_IMAGE_LAYOUT_GENERAL;
        BedrayFrameDesc call{};
        call.abi_version = BEDRAY_CORE_ABI_VERSION; call.command_buffer = frame.command_buffer; call.program = program;
        call.group_count_x = (frame.output_width - 1) / (nis ? 32u : 8u) + 1;
        call.group_count_y = (frame.output_height - 1) / (nis ? 24u : 8u) + 1; call.group_count_z = 1;
        call.resources = resources.data(); call.resource_count = static_cast<std::uint32_t>(resources.size());
        call.push_constants = push; call.push_constant_bytes = bytes;
        return bedray_core_render_frame(core, &call, error);
    };
    barrier(function, command);
    auto status = dispatch(frame.programs[fsr ? 0 : 2], frame.views[0], sharpen ? frame.views[1] : frame.views[2],
        frame.samplers[0], primary.data(), frame.primary_bytes, !fsr);
    if (status != BEDRAY_OK) return status;
    barrier(function, command);
    if (sharpen) {
        status = dispatch(frame.programs[1], frame.views[1], frame.views[2], frame.samplers[1], secondary.data(), 32, false);
        if (status == BEDRAY_OK) barrier(function, command);
    }
    return status;
}
}
