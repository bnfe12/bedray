#include "../src/volumetric_pass.h"
#include "fake_pass_device.h"
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <limits>

struct BedrayCore {};
namespace {
bool wrong_binding = false, wrong_push = false, wrong_count = false;
}
namespace bedray::core {
bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    if (handle < 1 || handle > 3) return false;
    out = {wrong_push ? 16u : handle == 3 ? 24u : 96u, wrong_count ? 1u : handle == 3 ? 4u : 5u};
    return true;
}
bool program_has_binding(BedrayCore*, std::uint64_t handle, std::uint32_t binding, std::uint32_t type) noexcept {
    if (wrong_binding) return false;
    if (binding == 11 || binding == 12 || (handle == 3 && binding == 1)) return type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    if (binding == 16) return type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    if (handle != 3 && binding == 0) return type == VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
    return handle != 3 && binding == 2 && type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
}
}
namespace {
int failures = 0;
#define CHECK(expression) do { if (!(expression)) { std::fprintf(stderr, "FAIL %d: %s\n", __LINE__, #expression); ++failures; } } while (0)
template<class T, std::size_t N> void put(std::array<unsigned char,N>& data, std::size_t offset, T value) {
    std::memcpy(data.data() + offset, &value, sizeof(value));
}
template<class T> T get(const std::vector<unsigned char>& data, std::size_t offset) {
    T value{}; if (offset + sizeof(value) > data.size()) { CHECK(false); return value; }
    std::memcpy(&value, data.data() + offset, sizeof(value)); return value;
}
const BedrayResource& resource(const fake::RenderCall& call, std::uint32_t binding) {
    for (const auto& found : call.resources) if (found.binding == binding) return found;
    CHECK(false); static const BedrayResource missing{}; return missing;
}
std::vector<unsigned char> bytes(std::uint64_t buffer) {
    if (!fake::buffers.contains(buffer)) { CHECK(false); return {}; }
    return fake::memories.at(fake::buffers.at(buffer).memory).bytes;
}
BedrayVolumetricDesc configuration() { return {BEDRAY_CORE_ABI_VERSION, sizeof(BedrayVolumetricDesc), 1, 2, 3, 0, 0}; }
struct Input {
    std::array<unsigned char,608> constants{};
    std::array<unsigned char,96> march{};
    std::array<unsigned char,24> apply{};
    BedrayVolumetricFrameDesc frame{};
    explicit Input(std::uint64_t command = 11, std::uint32_t index = 1) {
        frame = {BEDRAY_CORE_ABI_VERSION, sizeof(frame), command, 200, 201, 202, 203, 64, 128,
            19, 11, 0, index, constants.data(), 608, 96, march.data(), apply.data(), 24, 0};
        extent(19, 11);
        put(march, 12, 0.75f); put(march, 28, 64.0f); put(march, 48, 1.1f); put(march, 56, 1.0f);
        put(march, 64, 0.01f); put(march, 68, 8u); put(march, 72, 999u); put(march, 76, 999u);
        put(march, 80, 1u); put(march, 84, 1u); put(apply, 0, 1.1f); put(apply, 4, 64.0f);
        put(apply, 8, 1u); put(apply, 12, 999u);
    }
    void extent(std::uint32_t width, std::uint32_t height) {
        frame.width = width; frame.height = height; put(constants, 400, width); put(constants, 404, height);
    }
};
void clean() {
    CHECK(fake::images.empty()); CHECK(fake::views.empty()); CHECK(fake::view_images.empty());
    CHECK(fake::buffers.empty()); CHECK(fake::memories.empty()); CHECK(fake::faults == 0);
}
void configure_and_frame_validation() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VolumetricPass pass;
    auto desc = configuration(); desc.struct_size--;
    CHECK(pass.configure(&core, make_device_fns(), desc, &error) == BEDRAY_ERROR_ABI_MISMATCH);
    desc = configuration(); desc.program_march_legacy = desc.program_march_scene = 0;
    CHECK(pass.configure(&core, make_device_fns(), desc, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    desc = configuration(); desc.program_march_scene = 99;
    CHECK(pass.configure(&core, make_device_fns(), desc, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    wrong_push = true; CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_push = false;
    wrong_count = true; CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_count = false;
    wrong_binding = true; CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_binding = false;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    CHECK(pass.uses_program(1) && pass.uses_program(2) && pass.uses_program(3) && !pass.uses_program(0));
    auto invalid = [&](auto mutate, BedrayStatus expected = BEDRAY_ERROR_INVALID_ARGUMENT) {
        Input input; mutate(input); CHECK(pass.render(input.frame, &error) == expected);
        CHECK(fake::render_calls.empty()); CHECK(fake::images.empty()); CHECK(fake::buffers.empty()); CHECK(!pass.has_recordings());
    };
    invalid([](auto& i) { --i.frame.struct_size; }, BEDRAY_ERROR_ABI_MISMATCH);
    invalid([](auto& i) { i.frame.command_buffer = 0; });
    invalid([](auto& i) { i.frame.output_view = i.frame.depth_view; });
    invalid([](auto& i) { i.frame.tlas = 0; });
    invalid([](auto& i) { i.frame.sections_bytes = 63; });
    invalid([](auto& i) { i.frame.sections_offset = std::numeric_limits<std::uint64_t>::max(); });
    invalid([](auto& i) { i.frame.flags = 4; });
    invalid([](auto& i) { i.frame.reserved = 1; });
    invalid([](auto& i) { i.frame.frame_constants_bytes = 607; });
    invalid([](auto& i) { i.frame.march_push_bytes = 95; });
    invalid([](auto& i) { i.frame.apply_push_bytes = 23; });
    invalid([](auto& i) { put(i.constants, 400, 20u); });
    invalid([](auto& i) { put(i.march, 12, std::numeric_limits<float>::quiet_NaN()); });
    invalid([](auto& i) { put(i.march, 84, 3u); }); // No entity bindings in this world-only contract.
    invalid([](auto& i) { put(i.apply, 4, 65.0f); });
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void dispatch_payloads_and_history() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VolumetricPass pass;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    Input first;
    CHECK(pass.render(first.frame, &error) == BEDRAY_OK);
    CHECK(fake::images.size() == 1 && fake::buffers.size() == 1 && fake::render_calls.size() == 2 && fake::barriers == 4);
    const auto march = fake::render_calls[0], apply = fake::render_calls[1];
    CHECK(march.program == 1 && apply.program == 3);
    CHECK(march.group_x == 2 && march.group_y == 1 && apply.group_x == 3 && apply.group_y == 2);
    CHECK(march.resources.size() == 5 && apply.resources.size() == 4 && march.push_bytes == 96 && apply.push_bytes == 24);
    CHECK(get<std::uint32_t>(march.push, 72) == 1 && get<std::uint32_t>(march.push, 76) == 0);
    CHECK(get<std::uint32_t>(apply.push, 12) == 1);
    auto expected_march = first.march; put(expected_march, 72, 1u); put(expected_march, 76, 0u);
    auto expected_apply = first.apply; put(expected_apply, 12, 1u);
    CHECK(march.push == std::vector<unsigned char>(expected_march.begin(), expected_march.end()));
    CHECK(apply.push == std::vector<unsigned char>(expected_apply.begin(), expected_apply.end()));
    CHECK(resource(march, 0).handle == 202 && resource(march, 2).handle == 203);
    CHECK(resource(march, 2).offset == 64 && resource(march, 2).range == 128);
    CHECK(resource(march, 12).handle == 201 && resource(apply, 1).handle == 200);
    CHECK(resource(march, 11).handle == resource(apply, 11).handle && resource(march, 16).handle == resource(apply, 16).handle);
    const auto image = fake::images.at(fake::view_images.at(resource(march, 11).handle));
    CHECK(image.format == VK_FORMAT_R32G32B32A32_SFLOAT && image.width == 10 && image.height == 12);
    const auto upload = bytes(resource(march, 16).handle);
    CHECK(upload.size() >= first.constants.size());
    CHECK(std::equal(first.constants.begin(), first.constants.end(), upload.begin()));
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE && pass.render(first.frame, &error) == BEDRAY_ERROR_IN_USE);
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_ERROR_IN_USE);
    Input second(12, 2); second.frame.flags = BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE;
    CHECK(pass.render(second.frame, &error) == BEDRAY_OK);
    CHECK(fake::buffers.size() == 2); // Pending first upload cannot be overwritten.
    CHECK(fake::render_calls[2].program == 2 && get<std::uint32_t>(fake::render_calls[2].push, 76) == 1);
    CHECK(pass.release_recording(11) == BEDRAY_OK && pass.release_recording(12) == BEDRAY_OK);
    Input gap(13, 4); CHECK(pass.render(gap.frame, &error) == BEDRAY_OK);
    CHECK(get<std::uint32_t>(fake::render_calls[4].push, 76) == 0);
    CHECK(pass.release_recording(13) == BEDRAY_OK);
    Input reset(14, 5); reset.frame.flags = BEDRAY_VOLUMETRIC_FRAME_RESET_HISTORY;
    CHECK(pass.render(reset.frame, &error) == BEDRAY_OK && get<std::uint32_t>(fake::render_calls[6].push, 76) == 0);
    CHECK(pass.release_recording(14) == BEDRAY_OK);
    CHECK(pass.release_recording(999) == BEDRAY_OK && pass.destroy() == BEDRAY_OK); clean();
}
void resize_discard_and_failure() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VolumetricPass pass;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    Input old(11, 1), resized(12, 2); resized.extent(11, 19);
    CHECK(pass.render(old.frame, &error) == BEDRAY_OK);
    fake::fail_allocate_after = 0;
    CHECK(pass.render(resized.frame, &error) == BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(fake::images.size() == 1);
    CHECK(pass.render(resized.frame, &error) == BEDRAY_OK && fake::images.size() == 2);
    CHECK(get<std::uint32_t>(fake::render_calls[2].push, 76) == 0);
    pass.discard_recording(11); // A discarded old generation must not invalidate the current one.
    Input next(13, 3); next.extent(11, 19);
    CHECK(pass.render(next.frame, &error) == BEDRAY_OK && fake::images.size() == 2);
    CHECK(get<std::uint32_t>(fake::render_calls[4].push, 76) == 1);
    pass.discard_recording(12); CHECK(pass.release_recording(12) == BEDRAY_OK);
    Input fresh(14, 4); fresh.extent(11, 19);
    CHECK(pass.render(fresh.frame, &error) == BEDRAY_OK && fake::images.size() == 3);
    CHECK(get<std::uint32_t>(fake::render_calls[6].push, 76) == 0);
    CHECK(pass.release_recording(11) == BEDRAY_OK && fake::images.size() == 2);
    CHECK(pass.release_recording(13) == BEDRAY_OK && fake::images.size() == 1);
    CHECK(pass.release_recording(14) == BEDRAY_OK);
    fake::fail_render_at(1, BEDRAY_ERROR_VULKAN);
    Input failed(15, 5); failed.extent(11, 19);
    CHECK(pass.render(failed.frame, &error) == BEDRAY_ERROR_VULKAN && pass.has_recordings());
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    pass.discard_recording(15); CHECK(pass.release_recording(15) == BEDRAY_OK);
    fake::fail_render_at(-1, BEDRAY_OK);
    Input retry(16, 6); retry.extent(11, 19);
    CHECK(pass.render(retry.frame, &error) == BEDRAY_OK);
    CHECK(get<std::uint32_t>(fake::render_calls.back().push, 12) == 6);
    CHECK(get<std::uint32_t>(fake::render_calls[fake::render_calls.size()-2].push, 76) == 0);
    CHECK(pass.release_recording(16) == BEDRAY_OK && pass.destroy() == BEDRAY_OK); clean();
}
void precommand_upload_failure_and_missing_scene_program() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VolumetricPass pass;
    auto config = configuration(); config.program_march_scene = 0;
    CHECK(pass.configure(&core, make_device_fns(), config, &error) == BEDRAY_OK);
    Input input; input.frame.flags = BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE;
    CHECK(pass.render(input.frame, &error) == BEDRAY_ERROR_UNSUPPORTED && fake::images.empty());
    input.frame.flags = 0; fake::fail_allocate_after = 1; // Image succeeds, 608-byte upload allocation fails.
    CHECK(pass.render(input.frame, &error) == BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(!pass.has_recordings() && fake::render_calls.empty() && fake::barriers == 0);
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void device_loss_waits_for_every_recording_proof() {
    fake::reset(); BedrayCore core; BedrayError error{}; bedray::core::VolumetricPass pass;
    CHECK(pass.configure(&core, make_device_fns(), configuration(), &error) == BEDRAY_OK);
    Input first(11, 1), second(12, 2), resized(13, 3); resized.extent(30, 20);
    CHECK(pass.render(first.frame, &error) == BEDRAY_OK && pass.render(second.frame, &error) == BEDRAY_OK);
    fake::fail_allocate_after = 0; fake::fail_result = VK_ERROR_DEVICE_LOST;
    CHECK(pass.render(resized.frame, &error) == BEDRAY_ERROR_DEVICE_LOST);
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE && fake::images.size() == 1 && fake::buffers.size() == 2);
    CHECK(pass.release_recording(11) == BEDRAY_OK && fake::images.size() == 1 && fake::buffers.size() == 2);
    CHECK(pass.release_recording(12) == BEDRAY_OK && fake::images.empty() && fake::buffers.empty());
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
}
int main() {
    configure_and_frame_validation(); dispatch_payloads_and_history(); resize_discard_and_failure();
    precommand_upload_failure_and_missing_scene_program(); device_loss_waits_for_every_recording_proof();
    return failures ? EXIT_FAILURE : EXIT_SUCCESS;
}
