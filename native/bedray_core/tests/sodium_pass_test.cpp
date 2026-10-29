#include "../src/sodium_pass.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>

namespace {
void check(bool condition) { if (!condition) std::abort(); }
struct Push { std::uint64_t raw, arena, flags; std::uint32_t count, reserved; };
struct Recorded { std::uint64_t command, program; std::uint32_t x, y, z; Push push; };
std::vector<Recorded> records;
std::vector<char> events;
bedray::core::ProgramContract contract{32, 0};
bool known = true;
BedrayStatus result = BEDRAY_OK;
VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer command, VkPipelineStageFlags src, VkPipelineStageFlags dst,
    VkDependencyFlags flags, std::uint32_t count, const VkMemoryBarrier* memory,
    std::uint32_t buffers, const VkBufferMemoryBarrier*, std::uint32_t images, const VkImageMemoryBarrier*) {
    check(command != VK_NULL_HANDLE && src == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT && dst == src);
    check(flags == 0 && count == 1 && buffers == 0 && images == 0);
    check(memory->srcAccessMask == VK_ACCESS_MEMORY_WRITE_BIT);
    check(memory->dstAccessMask == (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT));
    events.push_back('b');
}
}
namespace bedray::core {
bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    out = contract; return known && handle == 17;
}
}
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(BedrayCore*, const BedrayFrameDesc* frame, BedrayError* error) {
    check(frame->abi_version == BEDRAY_CORE_ABI_VERSION && frame->resource_count == 0 && frame->resources == nullptr);
    check(frame->push_constant_bytes == sizeof(Push));
    Recorded copy{frame->command_buffer, frame->program, frame->group_count_x, frame->group_count_y, frame->group_count_z};
    std::memcpy(&copy.push, frame->push_constants, sizeof(copy.push));
    records.push_back(copy); events.push_back('d');
    if (error) error->status = result;
    return result;
}
int main() {
    auto* core = reinterpret_cast<BedrayCore*>(1);
    BedraySodiumDecodeDesc frame{BEDRAY_CORE_ABI_VERSION, sizeof(BedraySodiumDecodeDesc), 11, 17, 0x1000, 0x10000, 0x20000, 129, 0};
    BedrayError error{};
    auto render = [&](const auto& desc) { return bedray::core::record_sodium(core, barrier, 65535, desc, &error); };
    auto bad = [&](auto mutate, BedrayStatus expected) {
        auto desc = frame; mutate(desc); check(render(desc) == expected);
        check(records.empty() && events.empty());
    };
    bad([](auto& d) { d.abi_version = 0; }, BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& d) { --d.struct_size; }, BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& d) { d.command_buffer = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.program = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.raw_address = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.arena_address = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { ++d.raw_address; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { ++d.arena_address; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { ++d.flags_address; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.quad_count = 0; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.quad_count = static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max() / 28) + 1u; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.reserved = 1; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.arena_address = d.raw_address + 4; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.flags_address = d.raw_address; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.flags_address = d.arena_address + 4; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.raw_address = std::numeric_limits<std::uint64_t>::max() - 3; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.arena_address = std::numeric_limits<std::uint64_t>::max() - 3; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    bad([](auto& d) { d.flags_address = std::numeric_limits<std::uint64_t>::max() - 3; }, BEDRAY_ERROR_INVALID_ARGUMENT);
    check(bedray::core::record_sodium(core, barrier, 2, frame, &error) == BEDRAY_ERROR_UNSUPPORTED);
    check(bedray::core::record_sodium(core, nullptr, 65535, frame, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    known = false; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); known = true;
    contract.binding_count = 1; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); contract.binding_count = 0;
    contract.push_constant_bytes = 16; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); contract.push_constant_bytes = 32;
    check(records.empty() && events.empty());

    check(render(frame) == BEDRAY_OK && events == std::vector<char>({'b','d','b'}));
    const auto& first = records.front();
    check(first.command == 11 && first.program == 17 && first.x == 3 && first.y == 1 && first.z == 1);
    check(first.push.raw == 0x1000 && first.push.arena == 0x10000 && first.push.flags == 0x20000);
    check(first.push.count == 129 && first.push.reserved == 0);
    // Several section decodes may share a CB; each dispatch copies a separate push.
    frame.flags_address = 0; frame.quad_count = 64;
    check(render(frame) == BEDRAY_OK && records.back().x == 1 && records.back().push.flags == 0);
    check(records.front().push.count == 129 && records.front().push.flags == 0x20000);
    frame.quad_count = 65; frame.flags_address = frame.raw_address + 80u * 65u;
    check(render(frame) == BEDRAY_OK && records.back().x == 2);
    frame.raw_address = std::uint64_t{1} << 63u; frame.flags_address = 0; frame.quad_count = 1;
    check(render(frame) == BEDRAY_OK && records.back().push.raw == (std::uint64_t{1} << 63u));
    events.clear(); result = BEDRAY_ERROR_OUT_OF_MEMORY;
    check(render(frame) == result && events == std::vector<char>({'b','d'}));
    check(error.status == result);
    std::puts("native Sodium decode ranges/push/groups/barriers passed (no GPU)");
}
