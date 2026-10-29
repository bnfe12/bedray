#include "../src/nrd_pack_pass.h"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>

namespace {
void check(bool value) { if (!value) std::abort(); }
struct Push {
    std::uint32_t normal, roughness, denoiser, flags;
    float a,b,c,range;
    std::uint32_t width,height;
    float exposure;
    std::uint32_t reserved;
    std::uint64_t motion;
};
struct Recorded { std::uint64_t command, program; std::uint32_t x,y,z; std::vector<BedrayResource> resources; Push push; };
std::vector<Recorded> records;
std::vector<char> events;
bedray::core::ProgramContract contract{56,11};
bool known = true, binding_ok = true;
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
    out = contract; return known && handle == 17;
}
bool program_has_binding(BedrayCore*, std::uint64_t, std::uint32_t binding, std::uint32_t type) noexcept {
    return binding_ok && binding < 14 && type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
}
}
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(BedrayCore*, const BedrayFrameDesc* frame, BedrayError*) {
    check(frame->abi_version == BEDRAY_CORE_ABI_VERSION && frame->push_constant_bytes == sizeof(Push));
    Recorded copy{frame->command_buffer,frame->program,frame->group_count_x,frame->group_count_y,frame->group_count_z,
        {frame->resources, frame->resources + frame->resource_count}, {}};
    std::memcpy(&copy.push, frame->push_constants, sizeof(Push));
    records.push_back(copy); events.push_back('d'); return result;
}
int main() {
    auto* core = reinterpret_cast<BedrayCore*>(1);
    BedrayNrdPackFrameDesc frame{};
    frame.abi_version = BEDRAY_CORE_ABI_VERSION; frame.struct_size = sizeof(frame);
    frame.command_buffer = 11; frame.program = 17; frame.width = 19; frame.height = 9;
    frame.normal_encoding = 2; frame.roughness_encoding = 1; frame.flags = 3;
    frame.hit_dist_a = 3; frame.hit_dist_b = .1f; frame.hit_dist_c = 20;
    frame.denoising_range = 100; frame.exposure = 1.1f; frame.motion_address = 4096;
    for (std::uint32_t index = 0; index < 14; ++index) frame.views[index] = 21 + index;
    BedrayError error{};
    auto render = [&](const auto& input) { return bedray::core::record_nrd_pack(core, barrier, input, &error); };
    auto bad = [&](auto mutate, BedrayStatus expected = BEDRAY_ERROR_INVALID_ARGUMENT) {
        auto input = frame; mutate(input); check(render(input) == expected); check(records.empty() && events.empty());
    };
    bad([](auto& f) { f.abi_version = 0; }, BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& f) { --f.struct_size; }, BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& f) { f.command_buffer = 0; }); bad([](auto& f) { f.program = 0; });
    bad([](auto& f) { f.phase = 2; }); bad([](auto& f) { f.width = 0; }); bad([](auto& f) { f.height = 0; });
    bad([](auto& f) { f.width = std::numeric_limits<std::uint32_t>::max(); });
    bad([](auto& f) { f.views[5] = 0; }); bad([](auto& f) { f.views[5] = f.views[0]; });
    bad([](auto& f) { f.views[6] = f.views[5]; });
    bad([](auto& f) { f.normal_encoding = 0; }); bad([](auto& f) { f.roughness_encoding = 3; });
    bad([](auto& f) { f.denoiser = 3; }); bad([](auto& f) { f.flags = 4; }); bad([](auto& f) { f.reserved = 1; });
    bad([](auto& f) { f.hit_dist_a = 0; }); bad([](auto& f) { f.hit_dist_b = -1; });
    bad([](auto& f) { f.hit_dist_c = std::numeric_limits<float>::infinity(); });
    bad([](auto& f) { f.denoising_range = std::numeric_limits<float>::quiet_NaN(); });
    bad([](auto& f) { f.exposure = -1; }); bad([](auto& f) { f.motion_address = 1; });
    bad([](auto& f) { f.motion_address = std::numeric_limits<std::uint64_t>::max() - 15; });
    known = false; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); known = true;
    binding_ok = false; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); binding_ok = true;
    contract.push_constant_bytes = 64; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); contract.push_constant_bytes = 56;
    contract.binding_count = 14; check(render(frame) == BEDRAY_ERROR_INVALID_ARGUMENT); contract.binding_count = 11;
    check(records.empty() && events.empty());

    check(render(frame) == BEDRAY_OK && events == std::vector<char>({'b','d','b'}));
    check(records[0].x == 3 && records[0].y == 2 && records[0].z == 1 && records[0].resources.size() == 11);
    const auto& push = records[0].push;
    check(push.normal == 2 && push.roughness == 1 && push.denoiser == 0 && push.flags == 3);
    check(push.a == 3 && push.b == .1f && push.c == 20 && push.range == 100);
    check(push.width == 19 && push.height == 9 && push.exposure == 1.1f && !push.reserved && push.motion == 4096);
    for (const auto& resource : records[0].resources) {
        check(resource.handle == frame.views[resource.binding] && resource.image_layout == VK_IMAGE_LAYOUT_GENERAL);
        check(resource.descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE && !resource.sampler);
    }
    frame.views[0] = 100; frame.normal_encoding = 0; frame.denoiser = 2; contract.binding_count = 9;
    check(render(frame) == BEDRAY_OK && records.back().resources.size() == 9 && !records.back().push.motion);
    for (const auto& resource : records.back().resources) check(resource.binding != 12 && resource.binding != 3);
    check(records.front().resources.front().handle == 21); // copied identities, no mutable descriptor table
    frame.phase = BEDRAY_NRD_COMPOSE; contract.binding_count = 6;
    frame.views[11] = frame.views[7]; // RR's duplicate read view is legal
    check(render(frame) == BEDRAY_OK && records.back().resources.size() == 6);
    events.clear(); result = BEDRAY_ERROR_OUT_OF_MEMORY;
    check(render(frame) == result && events == std::vector<char>({'b','d'}));
    std::puts("native NRD phase/ABI/descriptor/push/barrier contracts passed (no GPU)");
}
