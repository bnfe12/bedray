#include "../src/velocity_pass.h"
#include "fake_pass_device.h"
#include <algorithm>
#include <array>
#include <cstdio>
#include <limits>

struct BedrayCore {};
namespace { bool wrong_push = false, wrong_count = false, wrong_binding = false; }
namespace bedray::core {
bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    out = {wrong_push ? 4u : 0u, wrong_count ? 2u : 3u}; return handle == 1;
}
bool program_has_binding(BedrayCore*, std::uint64_t, std::uint32_t binding, std::uint32_t type) noexcept {
    if (wrong_binding) return false;
    return (binding == 0 && type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        || (binding == 1 && type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
        || (binding == 2 && type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
}
}
namespace {
int failures = 0;
#define CHECK(expression) do { if (!(expression)) { std::fprintf(stderr, "FAIL %d: %s\n", __LINE__, #expression); ++failures; } } while (0)
template<class T> void put(std::array<unsigned char,368>& bytes, std::size_t offset, T value) {
    std::memcpy(bytes.data() + offset, &value, sizeof(value));
}
BedrayVelocityDesc configuration() { return {BEDRAY_CORE_ABI_VERSION, sizeof(BedrayVelocityDesc), 1, 22, 1ull << 20}; }
struct Input {
    std::array<unsigned char,368> uniforms{};
    BedrayVelocityFrameDesc frame{};
    explicit Input(std::uint64_t command = 11) {
        frame = {BEDRAY_CORE_ABI_VERSION, sizeof(frame), command, 201, 202, 19, 11, uniforms.data(), 368, 0};
        put(uniforms, 160, 19.0f); put(uniforms, 164, 11.0f);
        put(uniforms, 168, 1.0f / 19.0f); put(uniforms, 172, 1.0f / 11.0f);
        put(uniforms, 140, 1.0f);
    }
};
std::vector<unsigned char> upload(const fake::RenderCall& call) {
    CHECK(call.resources.size() == 3);
    const auto& resource = call.resources[2];
    CHECK(fake::buffers.contains(resource.handle));
    return fake::memories.at(fake::buffers.at(resource.handle).memory).bytes;
}
void clean() {
    CHECK(fake::buffers.empty()); CHECK(fake::memories.empty()); CHECK(fake::images.empty());
    CHECK(fake::views.empty()); CHECK(fake::faults == 0);
}
void validation() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VelocityPass pass;
    auto desc = configuration(); --desc.struct_size;
    CHECK(pass.configure(&core, make_device_fns(), desc, &error) == BEDRAY_ERROR_ABI_MISMATCH);
    desc = configuration(); desc.nearest_sampler = 0;
    CHECK(pass.configure(&core, make_device_fns(), desc, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    wrong_push = true; CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_push = false;
    wrong_count = true; CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_count = false;
    wrong_binding = true; CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_binding = false;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    CHECK(pass.uses_program(1) && !pass.uses_program(0) && !pass.uses_program(2));
    auto invalid = [&](auto mutate, BedrayStatus expected = BEDRAY_ERROR_INVALID_ARGUMENT) {
        Input input; mutate(input); CHECK(pass.render(input.frame, &error) == expected);
        CHECK(fake::render_calls.empty() && fake::buffers.empty() && !pass.has_recordings() && fake::barriers == 0);
    };
    invalid([](auto& i) { --i.frame.struct_size; }, BEDRAY_ERROR_ABI_MISMATCH);
    invalid([](auto& i) { i.frame.command_buffer = 0; });
    invalid([](auto& i) { i.frame.output_view = i.frame.depth_view; });
    invalid([](auto& i) { i.frame.depth_view = 0; });
    invalid([](auto& i) { i.frame.uniforms = nullptr; });
    invalid([](auto& i) { i.frame.uniform_bytes = 367; });
    invalid([](auto& i) { i.frame.reserved = 1; });
    invalid([](auto& i) { i.frame.width = 0; });
    invalid([](auto& i) { i.frame.width = i.frame.height = 65535; }); // signed shader pixel index overflow
    invalid([](auto& i) { put(i.uniforms, 160, 20.0f); });
    invalid([](auto& i) { put(i.uniforms, 168, 1.0f); });
    invalid([](auto& i) { put(i.uniforms, 140, 3.0f); });
    invalid([](auto& i) { put(i.uniforms, 352, std::numeric_limits<float>::infinity()); });
    invalid([](auto& i) { put(i.uniforms, 320, std::uint64_t(0x101)); });
    invalid([](auto& i) { put(i.uniforms, 328, std::uint64_t(0x102)); });
    invalid([](auto& i) { put(i.uniforms, 336, std::numeric_limits<std::uint64_t>::max() - 3); });
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void immutable_uploads_and_exact_proof() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VelocityPass pass;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    std::array<std::uint64_t,5> buffers{};
    for (std::uint64_t n = 0; n < buffers.size(); ++n) {
        Input input(11 + n);
        put(input.uniforms, 128, static_cast<float>(n));
        // An address word may look like a NaN float; it must remain raw bits.
        put(input.uniforms, 320, std::uint64_t(0x7fc00000));
        CHECK(pass.render(input.frame, &error) == BEDRAY_OK);
        const auto& call = fake::render_calls.back();
        CHECK(call.command_buffer == 11 + n && call.program == 1 && call.group_x == 3 && call.group_y == 2 && call.group_z == 1);
        CHECK(call.push.empty() && call.push_bytes == 0 && call.resources.size() == 3);
        CHECK(call.resources[0].binding == 0 && call.resources[0].handle == 201 && call.resources[0].sampler == 22);
        CHECK(call.resources[0].descriptor_type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        CHECK(call.resources[1].binding == 1 && call.resources[1].handle == 202 && call.resources[1].image_layout == VK_IMAGE_LAYOUT_GENERAL);
        CHECK(call.resources[2].binding == 2 && call.resources[2].range == 368 && call.resources[2].offset == 0);
        const auto bytes = upload(call);
        CHECK(bytes.size() >= input.uniforms.size() && std::equal(input.uniforms.begin(), input.uniforms.end(), bytes.begin()));
        buffers[n] = call.resources[2].handle;
        for (std::size_t old = 0; old < n; ++old) CHECK(buffers[old] != buffers[n]);
        input.uniforms.fill(0xff); // Synchronous copy must not borrow this payload.
    }
    CHECK(fake::buffers.size() == 5 && fake::barriers == 10);
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    auto different = configuration(); ++different.nearest_sampler;
    CHECK(pass.configure(&core, make_device_fns(), different, &error) == BEDRAY_ERROR_IN_USE);
    Input duplicate;
    CHECK(pass.render(duplicate.frame, &error) == BEDRAY_OK && fake::render_calls.size() == 6);
    CHECK(fake::render_calls.back().resources[2].handle != buffers[0] && fake::buffers.size() == 6);
    CHECK(pass.release_recording(999) == BEDRAY_OK && pass.has_recordings());
    CHECK(pass.release_recording(12) == BEDRAY_OK);
    Input reused(19); CHECK(pass.render(reused.frame, &error) == BEDRAY_OK);
    CHECK(fake::render_calls.back().resources[2].handle == buffers[1] && fake::buffers.size() == 6);
    for (std::uint64_t command : {11ull, 13ull, 14ull, 15ull, 19ull}) {
        pass.discard_recording(command); CHECK(pass.release_recording(command) == BEDRAY_OK);
    }
    CHECK(!pass.has_recordings() && pass.destroy() == BEDRAY_OK); clean();
}
void failures_keep_recorded_commands_pinned() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VelocityPass pass;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    Input input;
    fake::fail_allocate_after = 0;
    CHECK(pass.render(input.frame, &error) == BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(!pass.has_recordings() && fake::render_calls.empty() && fake::barriers == 0);
    fake::fail_render_at(0, BEDRAY_ERROR_VULKAN);
    CHECK(pass.render(input.frame, &error) == BEDRAY_ERROR_VULKAN);
    CHECK(pass.has_recordings() && fake::barriers == 1 && fake::buffers.size() == 1);
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    pass.discard_recording(11); CHECK(pass.release_recording(11) == BEDRAY_OK);
    CHECK(pass.destroy() == BEDRAY_OK); clean();

    fake::reset();
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    Input first(21), lost(22);
    CHECK(pass.render(first.frame, &error) == BEDRAY_OK);
    fake::fail_render_at(0, BEDRAY_ERROR_DEVICE_LOST);
    CHECK(pass.render(lost.frame, &error) == BEDRAY_ERROR_DEVICE_LOST);
    CHECK(pass.release_recording(22) == BEDRAY_OK && fake::buffers.size() == 2 && pass.has_recordings());
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    CHECK(pass.release_recording(21) == BEDRAY_OK && fake::buffers.empty());
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
}
int main() {
    validation(); immutable_uploads_and_exact_proof(); failures_keep_recorded_commands_pinned();
    if (failures) std::fprintf(stderr, "%d velocity checks failed\n", failures);
    return failures ? 1 : 0;
}
