#include "../src/spatial_pass.h"
#include "fake_pass_device.h"
#include <array>
#include <cstdio>

struct BedrayCore {};
namespace { bool wrong_binding = false, wrong_push = false, wrong_count = false; }
namespace bedray::core {
bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    out = {wrong_push ? 4u : handle == 1 ? 80u : handle == 2 ? 32u : 112u, wrong_count ? 1u : 2u};
    return handle >= 1 && handle <= 3;
}
bool program_has_binding(BedrayCore*, std::uint64_t, std::uint32_t binding, std::uint32_t type) noexcept {
    return !wrong_binding && ((binding == 0 && type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        || (binding == 1 && type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE));
}
}
namespace {
int failures = 0;
#define CHECK(expression) do { if (!(expression)) { std::fprintf(stderr,"FAIL %d: %s\n",__LINE__,#expression); ++failures; } } while (0)
template<class T,std::size_t N> void put(std::array<unsigned char,N>& bytes,std::size_t offset,T value) {
    std::memcpy(bytes.data()+offset,&value,sizeof(value));
}
struct Input {
    std::array<unsigned char,112> primary{};
    std::array<unsigned char,32> secondary{};
    BedraySpatialFrameDesc frame{};
    explicit Input(bool sharpen = true, bool nis = false) {
        frame.abi_version = BEDRAY_CORE_ABI_VERSION; frame.struct_size = sizeof(frame); frame.command_buffer = 11;
        frame.programs[0] = 1; frame.programs[1] = 2; frame.programs[2] = 3;
        frame.samplers[0] = 21; frame.samplers[1] = 22;
        frame.views[0] = 31; frame.views[1] = 32; frame.views[2] = 33;
        frame.input_width = 19; frame.input_height = 13; frame.output_width = 35; frame.output_height = 25;
        frame.backend = nis ? BEDRAY_SPATIAL_NIS : BEDRAY_SPATIAL_FSR1;
        frame.flags = sharpen && !nis ? BEDRAY_SPATIAL_SHARPEN : 0;
        frame.primary_push = primary.data(); frame.primary_bytes = nis ? 112 : 80;
        if (sharpen && !nis) { frame.secondary_push = secondary.data(); frame.secondary_bytes = 32; }
        if (nis) {
            put(primary,80,19u); put(primary,84,13u); put(primary,96,35u); put(primary,100,25u);
        } else {
            put(primary,64,35u); put(primary,68,25u); put(secondary,16,35u); put(secondary,20,25u);
        }
    }
};
void validation_is_atomic() {
    fake::reset(); BedrayCore core; BedrayError error{};
    auto invalid = [&](auto mutate, BedrayStatus expected = BEDRAY_ERROR_INVALID_ARGUMENT) {
        Input input; mutate(input);
        CHECK(bedray::core::record_spatial(&core,&fake::cmd_pipeline_barrier,input.frame,&error) == expected);
        CHECK(fake::render_calls.empty() && fake::barriers == 0 && fake::buffers.empty() && fake::images.empty());
    };
    invalid([](auto& i) { --i.frame.struct_size; },BEDRAY_ERROR_ABI_MISMATCH);
    invalid([](auto& i) { i.frame.command_buffer = 0; });
    invalid([](auto& i) { i.frame.flags = 2; });
    invalid([](auto& i) { i.frame.views[1] = i.frame.views[2]; });
    invalid([](auto& i) { i.frame.views[0] = i.frame.views[2]; });
    invalid([](auto& i) { i.frame.samplers[1] = 0; });
    invalid([](auto& i) { i.frame.output_width = 18; });
    invalid([](auto& i) { i.frame.output_width = 65536; });
    invalid([](auto& i) { i.frame.primary_bytes = 79; });
    invalid([](auto& i) { i.frame.secondary_push = nullptr; });
    invalid([](auto& i) { i.frame.programs[1] = 99; }); // Second stage validated before EASU is recorded.
    invalid([](auto& i) { put(i.primary,64,36u); });
    invalid([](auto& i) { put(i.secondary,20,26u); });
    wrong_push = true; invalid([](auto&) {}); wrong_push = false;
    wrong_count = true; invalid([](auto&) {}); wrong_count = false;
    wrong_binding = true; invalid([](auto&) {}); wrong_binding = false;
}
void chains_preserve_payload_views_samplers_and_dispatch_geometry() {
    fake::reset(); BedrayCore core; BedrayError error{}; Input fsr;
    CHECK(bedray::core::record_spatial(&core,&fake::cmd_pipeline_barrier,fsr.frame,&error) == BEDRAY_OK);
    CHECK(fake::barriers == 3 && fake::render_calls.size() == 2);
    const auto first = fake::render_calls[0], second = fake::render_calls[1];
    CHECK(first.program == 1 && second.program == 2 && first.command_buffer == 11 && second.command_buffer == 11);
    CHECK(first.group_x == 5 && first.group_y == 4 && first.group_z == 1 && second.group_x == 5 && second.group_y == 4);
    CHECK(first.push_bytes == 80 && second.push_bytes == 32);
    CHECK(first.push == std::vector<unsigned char>(fsr.primary.begin(),fsr.primary.begin()+80));
    CHECK(second.push == std::vector<unsigned char>(fsr.secondary.begin(),fsr.secondary.end()));
    CHECK(first.resources.size() == 2 && second.resources.size() == 2);
    CHECK(first.resources[0].handle == 31 && first.resources[0].sampler == 21 && first.resources[1].handle == 32);
    CHECK(second.resources[0].handle == 32 && second.resources[0].sampler == 22 && second.resources[1].handle == 33);
    CHECK(first.resources[0].descriptor_type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
    CHECK(first.resources[1].descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
    CHECK(first.resources[0].image_layout == VK_IMAGE_LAYOUT_GENERAL && first.resources[1].image_layout == VK_IMAGE_LAYOUT_GENERAL);
    fake::reset(); Input simple(false); simple.frame.views[1] = 0; simple.frame.programs[1] = simple.frame.programs[2] = 0;
    CHECK(bedray::core::record_spatial(&core,&fake::cmd_pipeline_barrier,simple.frame,&error) == BEDRAY_OK);
    CHECK(fake::render_calls.size() == 1 && fake::barriers == 2 && fake::render_calls[0].resources[1].handle == 33);
    fake::reset(); Input nis(false,true); nis.frame.programs[0] = nis.frame.programs[1] = 0; nis.frame.views[1] = 0;
    CHECK(bedray::core::record_spatial(&core,&fake::cmd_pipeline_barrier,nis.frame,&error) == BEDRAY_OK);
    CHECK(fake::render_calls.size() == 1 && fake::barriers == 2);
    const auto call = fake::render_calls[0];
    CHECK(call.program == 3 && call.push_bytes == 112 && call.group_x == 2 && call.group_y == 2 && call.group_z == 1);
    CHECK(call.push == std::vector<unsigned char>(nis.primary.begin(),nis.primary.end()));
    CHECK(call.resources.size() == 2 && call.resources[0].sampler == 21 && call.resources[1].handle == 33);
}
void invalid_nis_and_partial_failure() {
    fake::reset(); BedrayCore core; BedrayError error{}; Input nis(false,true);
    nis.frame.input_width = 17; put(nis.primary,80,17u);
    CHECK(bedray::core::record_spatial(&core,&fake::cmd_pipeline_barrier,nis.frame,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(fake::render_calls.empty() && fake::barriers == 0);
    Input fsr;
    fake::fail_render_at(0,BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(bedray::core::record_spatial(&core,&fake::cmd_pipeline_barrier,fsr.frame,&error) == BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(fake::render_calls.size() == 1 && fake::barriers == 1);
    fake::reset(); fake::fail_render_at(1,BEDRAY_ERROR_DEVICE_LOST);
    CHECK(bedray::core::record_spatial(&core,&fake::cmd_pipeline_barrier,fsr.frame,&error) == BEDRAY_ERROR_DEVICE_LOST);
    CHECK(fake::render_calls.size() == 2 && fake::barriers == 2);
    CHECK(fake::buffers.empty() && fake::images.empty() && fake::faults == 0);
}
}
int main() { validation_is_atomic(); chains_preserve_payload_views_samplers_and_dispatch_geometry(); invalid_nis_and_partial_failure(); return failures ? 1 : 0; }
