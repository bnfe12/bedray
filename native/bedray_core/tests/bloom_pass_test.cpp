#include "../src/bloom_pass.h"
#include "fake_pass_device.h"
#include <cstdio>
#include <cstdlib>

struct BedrayCore {};
namespace bedray::core {
bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    if (handle < 1 || handle > 4) return false;
    out = {128, handle == 1 || handle == 4 ? 3u : 2u};
    return true;
}
bool program_has_binding(BedrayCore*, std::uint64_t handle, std::uint32_t binding, std::uint32_t type) noexcept {
    const auto dst = handle == 4 ? 2u : 1u;
    if (binding == dst) return type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    if (binding == 0 || (binding == 3 && (handle == 1 || handle == 4)))
        return type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    return false;
}
}
namespace {
int failures = 0;
#define CHECK(expression) do { if (!(expression)) { std::fprintf(stderr, "FAIL %d: %s\n", __LINE__, #expression); ++failures; } } while (0)
BedrayBloomDesc configuration() {
    BedrayBloomDesc desc{};
    desc.abi_version = BEDRAY_CORE_ABI_VERSION; desc.struct_size = sizeof(desc);
    for (std::size_t i = 0; i < 4; ++i) desc.programs[i] = i + 1;
    desc.linear_sampler = 100; desc.nearest_sampler = 101;
    return desc;
}
BedrayBloomFrameDesc frame(std::uint64_t command, const void* push, bool bloom = true) {
    return {BEDRAY_CORE_ABI_VERSION, sizeof(BedrayBloomFrameDesc), command, 19, 11, 200, 201, bloom ? 1u : 0u, 128, push};
}
void clean() {
    CHECK(fake::images.empty()); CHECK(fake::views.empty()); CHECK(fake::memories.empty()); CHECK(fake::faults == 0);
}
void chain_and_validation() {
    fake::reset();
    BedrayCore core;
    BedrayError error{};
    bedray::core::BloomPass pass;
    auto desc = configuration();
    desc.programs[1] = 99;
    CHECK(pass.configure(&core, make_device_fns(), desc, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    CHECK(pass.uses_program(1)); CHECK(!pass.uses_program(0)); CHECK(!pass.uses_program(99));
    std::array<unsigned char,128> push{};
    for (std::size_t i = 0; i < push.size(); ++i) push[i] = static_cast<unsigned char>(i);
    auto input = frame(1, push.data());
    input.push_bytes = 127;
    CHECK(pass.render(input, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(fake::images.empty()); CHECK(!pass.has_recordings());
    input.push_bytes = 128;
    CHECK(pass.render(input, &error) == BEDRAY_OK);
    CHECK(fake::images.size() == 6); CHECK(fake::render_calls.size() == 12); CHECK(fake::barriers == 19);
    CHECK(fake::render_calls.front().program == 1); CHECK(fake::render_calls.back().program == 4);
    const std::uint32_t widths[] = {10,5,3,2,1,1,1,2,3,5,10,19};
    const std::uint32_t heights[] = {6,3,2,1,1,1,1,1,2,3,6,11};
    for (std::size_t i = 0; i < fake::render_calls.size(); ++i) {
        const auto& call = fake::render_calls[i];
        CHECK(call.push == std::vector<unsigned char>(push.begin(), push.end()));
        CHECK(call.group_x == (widths[i] + 7) / 8); CHECK(call.group_y == (heights[i] + 7) / 8);
        CHECK(call.group_z == 1); CHECK(call.push_bytes == 128);
        if (i > 0 && i < 6) CHECK(call.program == 2);
        if (i >= 6 && i < 11) CHECK(call.program == 3);
        CHECK(call.resources.size() == (i == 0 || i == 11 ? 3u : 2u));
        CHECK(call.resources[1].binding == (i == 11 ? 2u : 1u));
        for (auto resource : call.resources) CHECK(resource.image_layout == VK_IMAGE_LAYOUT_GENERAL);
    }
    CHECK(pass.render(input, &error) == BEDRAY_ERROR_IN_USE);
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_IN_USE);
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    CHECK(pass.release_recording(999) == BEDRAY_OK);
    CHECK(pass.release_recording(1) == BEDRAY_OK);
    std::memset(push.data() + 104, 0, 4);
    const auto calls = fake::render_calls.size();
    CHECK(pass.render(frame(1, push.data(), false), &error) == BEDRAY_OK);
    CHECK(fake::render_calls.size() == calls + 1); CHECK(fake::barriers == 21);
    CHECK(pass.release_recording(1) == BEDRAY_OK);
    CHECK(pass.destroy() == BEDRAY_OK); CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void resize_discard_and_partial_failure() {
    fake::reset();
    BedrayCore core;
    BedrayError error{};
    bedray::core::BloomPass pass;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    std::array<unsigned char,128> push{};
    CHECK(pass.render(frame(1, push.data()), &error) == BEDRAY_OK);
    auto resized = frame(2, push.data()); resized.width = 40;
    fake::fail_allocate_after = 2;
    CHECK(pass.render(resized, &error) == BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(fake::images.size() == 6);
    CHECK(pass.render(resized, &error) == BEDRAY_OK);
    CHECK(fake::images.size() == 12);
    pass.discard_recording(1); // An old generation must not invalidate the current images.
    resized.command_buffer = 3;
    CHECK(pass.render(resized, &error) == BEDRAY_OK); CHECK(fake::images.size() == 12);
    pass.discard_recording(2);
    CHECK(pass.release_recording(2) == BEDRAY_OK);
    resized.command_buffer = 4;
    CHECK(pass.render(resized, &error) == BEDRAY_OK); CHECK(fake::images.size() == 18);
    CHECK(pass.release_recording(1) == BEDRAY_OK); CHECK(fake::images.size() == 12);
    CHECK(pass.release_recording(3) == BEDRAY_OK); CHECK(fake::images.size() == 6);
    CHECK(pass.release_recording(4) == BEDRAY_OK);
    fake::fail_render_at(3, BEDRAY_ERROR_VULKAN);
    resized.command_buffer = 5;
    CHECK(pass.render(resized, &error) == BEDRAY_ERROR_VULKAN); CHECK(pass.has_recordings());
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    pass.discard_recording(5); CHECK(pass.release_recording(5) == BEDRAY_OK);
    fake::fail_render_at(-1, BEDRAY_OK);
    resized.command_buffer = 6;
    CHECK(pass.render(resized, &error) == BEDRAY_OK);
    CHECK(fake::images.size() == 6);
    CHECK(pass.release_recording(6) == BEDRAY_OK); CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void device_loss_keeps_other_recordings_until_proof() {
    fake::reset();
    BedrayCore core;
    BedrayError error{};
    bedray::core::BloomPass pass;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    std::array<unsigned char,128> push{};
    CHECK(pass.render(frame(1, push.data()), &error) == BEDRAY_OK);
    auto resized = frame(2, push.data()); resized.width = 40;
    fake::fail_allocate_after = 0; fake::fail_result = VK_ERROR_DEVICE_LOST;
    CHECK(pass.render(resized, &error) == BEDRAY_ERROR_DEVICE_LOST);
    CHECK(fake::images.size() == 6); CHECK(pass.has_recordings());
    CHECK(pass.render(frame(3, push.data()), &error) == BEDRAY_ERROR_UNSUPPORTED);
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    CHECK(pass.release_recording(1) == BEDRAY_OK);
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
}
int main() {
    chain_and_validation(); resize_discard_and_partial_failure(); device_loss_keeps_other_recordings_until_proof();
    return failures ? EXIT_FAILURE : EXIT_SUCCESS;
}
