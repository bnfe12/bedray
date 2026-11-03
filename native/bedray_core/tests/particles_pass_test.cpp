#include "../src/particles_pass.h"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>

namespace {
void check(bool value) { if (!value) std::abort(); }
struct Push { std::uint32_t width, height; float strength, pad; };
struct Recorded { std::uint64_t command, program; std::uint32_t x, y, z; std::array<BedrayResource, 2> resources; Push push; };
std::vector<Recorded> records;
std::vector<char> events;
bool known = true, binding_ok = true;
bedray::core::ProgramContract contract{16, 2};
BedrayStatus result = BEDRAY_OK;
VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer command, VkPipelineStageFlags src, VkPipelineStageFlags dst,
    VkDependencyFlags flags, std::uint32_t count, const VkMemoryBarrier* memory, std::uint32_t buffers,
    const VkBufferMemoryBarrier*, std::uint32_t images, const VkImageMemoryBarrier*) {
    check(command != VK_NULL_HANDLE && src == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT && dst == src);
    check(flags == 0 && count == 1 && buffers == 0 && images == 0);
    check(memory->srcAccessMask == VK_ACCESS_MEMORY_WRITE_BIT);
    check(memory->dstAccessMask == (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT));
    events.push_back('b');
}
}
namespace bedray::core {
bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    out = contract;
    return known && handle == 17;
}
bool program_has_binding(BedrayCore*, std::uint64_t, std::uint32_t binding, std::uint32_t type) noexcept {
    return binding_ok && ((binding == 0 && type == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
        || (binding == 2 && type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
}
}
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(BedrayCore*, const BedrayFrameDesc* frame, BedrayError*) {
    check(frame->abi_version == BEDRAY_CORE_ABI_VERSION && frame->resource_count == 2);
    check(frame->push_constant_bytes == sizeof(Push));
    Recorded copy{frame->command_buffer, frame->program, frame->group_count_x, frame->group_count_y, frame->group_count_z};
    std::memcpy(copy.resources.data(), frame->resources, sizeof(copy.resources));
    std::memcpy(&copy.push, frame->push_constants, sizeof(copy.push));
    records.push_back(copy); events.push_back('d');
    return result;
}
int main() {
    auto* core = reinterpret_cast<BedrayCore*>(1);
    BedrayParticlesFrameDesc frame{BEDRAY_CORE_ABI_VERSION, sizeof(BedrayParticlesFrameDesc), 11, 17, 21, 22, 19, 9, 0.75f, 0};
    BedrayError error{};
    auto render = [&](const auto& input) { return bedray::core::record_particles(core, barrier, input, &error); };
    auto bad = [&](auto mutate, BedrayStatus expected) {
        auto input = frame; mutate(input); check(render(input) == expected); check(records.empty() && events.empty());
    };
    bad([](auto& f) { f.abi_version = 0; }, BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& f) { --f.struct_size; }, BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& f) { f.command_buffer = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.program = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.layer_view = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.output_view = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.output_view = f.layer_view; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.width = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.height = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.strength = -1; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.strength = std::numeric_limits<float>::quiet_NaN(); }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.strength = std::numeric_limits<float>::infinity(); }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& f) { f.reserved = 1; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    known = false; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); known = true;
    contract.binding_count = 3; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); contract.binding_count = 2;
    contract.push_constant_bytes = 32; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); contract.push_constant_bytes = 16;
    binding_ok = false; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); binding_ok = true;
    check(records.empty() && events.empty());

    check(render(frame) == BEDRAY_OK && events == std::vector<char>({'b','d','b'}));
    const auto first = records.front();
    check(first.command == 11 && first.program == 17 && first.x == 3 && first.y == 2 && first.z == 1);
    check(first.push.width == 19 && first.push.height == 9 && first.push.strength == 0.75f && first.push.pad == 0);
    check(first.resources[0].binding == 0 && first.resources[0].descriptor_type == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE);
    check(first.resources[1].binding == 2 && first.resources[1].descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
    check(first.resources[0].handle == 21 && first.resources[1].handle == 22);
    for (const auto& resource : first.resources) check(resource.image_layout == VK_IMAGE_LAYOUT_GENERAL && resource.sampler == 0);
    frame.command_buffer = 12; frame.layer_view = 31; frame.output_view = 32; frame.strength = 0;
    check(render(frame) == BEDRAY_OK);
    check(records.front().resources[0].handle == 21 && records.back().resources[0].handle == 31);
    check(records.front().push.strength == 0.75f && records.back().push.strength == 0);
    events.clear(); result = BEDRAY_ERROR_OUT_OF_MEMORY;
    check(render(frame) == result && events == std::vector<char>({'b','d'}));
    std::puts("native particle contract/push/barrier validation passed (no GPU)");
}
