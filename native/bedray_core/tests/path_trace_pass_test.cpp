// Contract and lifetime tests for the internal PathTracePass.
//
// The host reaches the pass through bedray_core_configure_path_trace and
// bedray_core_render_path_trace; those wrappers live in core.cpp. This test
// includes the pass implementation directly and supplies the two core render
// entry points plus a fake buffer/image driver, so it exercises the pass's own
// validation, program selection and recording lifetime without a Vulkan
// loader, device, driver or GPU.
//
// Covered contracts:
//   - configure rejects missing, unregistered and contract-mismatched programs;
//   - a native-scene frame needs program_trace_scene and a 64-byte table;
//   - program_trace_scene is selected only when BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE
//     is set, and the host-only flag never reaches the shader push block;
//   - the spatial stage runs only with a registered program, the frame flag,
//     ReSTIR DI enabled, a non-zero emitter count and a bound reservoir ring;
//   - every frame validation error happens before a pin is taken;
//   - pins survive until release_recording when the core failed after a dispatch
//     was already recorded, and are released immediately when the failure is
//     provably before any recording;
//   - a failed frame never publishes history, and resizing retires the old
//     images without destroying them while a recording still pins them;
//   - destroy/configure refuse while a recording is outstanding, and
//     release_recording is idempotent for unknown command buffers.

#include "../src/path_trace_pass.cpp"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iterator>
#include <limits>
#include <map>
#include <set>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

int g_failures = 0;
BedrayError g_error{};

} // namespace

#define CHECK(condition) \
    do { \
        if (!(condition)) { \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition); \
            ++g_failures; \
        } \
    } while (0)

#define CHECK_STATUS(expression, expected) \
    do { \
        const BedrayStatus check_status_ = (expression); \
        if (check_status_ != (expected)) { \
            std::fprintf(stderr, "FAIL %s:%d: %s = %d, expected %d (%s)\n", \
                __FILE__, __LINE__, #expression, static_cast<int>(check_status_), \
                static_cast<int>(expected), g_error.message); \
            ++g_failures; \
        } \
    } while (0)

// The pass only stores the core pointer and hands it back to the render entry
// points, so the test's stand-ins do not need any core state.
struct BedrayCore {
    std::uint64_t marker = 0;
};

namespace {

struct ProgramBinding {
    std::uint32_t binding;
    std::uint32_t descriptor_type;
};

struct ProgramInfo {
    std::uint32_t push_bytes;
    std::vector<ProgramBinding> bindings;
};

std::map<std::uint64_t, ProgramInfo> g_programs;

constexpr std::uint64_t kTraceProgram = 0x1001;
constexpr std::uint64_t kTraceSceneProgram = 0x1002;
constexpr std::uint64_t kSpatialProgram = 0x1003;
constexpr std::uint64_t kDisplayProgram = 0x1004;

constexpr std::uint64_t kBadTraceCount = 0x2001;
constexpr std::uint64_t kBadTracePush = 0x2002;
constexpr std::uint64_t kBadTraceBinding = 0x2003;
constexpr std::uint64_t kBadSceneBinding = 0x2004;
constexpr std::uint64_t kBadDisplayPush = 0x2005;
constexpr std::uint64_t kBadSpatialPush = 0x2006;

// Mirrors the binding set the pass requires from path_trace.slang.
constexpr ProgramBinding kTraceBindings[] = {
    {0, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR},
    {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
    {5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
    {10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
    {12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {16, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
    {17, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {18, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {19, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {20, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {21, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {22, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {23, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {24, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {25, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
};

constexpr ProgramBinding kDisplayBindings[] = {
    {0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {5, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
    {6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
    {8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
};

constexpr ProgramBinding kSpatialBindings[] = {
    {3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
    {9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
    {10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
    {16, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
    {18, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
    {19, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER},
};

void clear_programs() {
    g_programs.clear();
}

void register_program(std::uint64_t handle, std::uint32_t push_bytes,
    const ProgramBinding* bindings, std::size_t count) {
    ProgramInfo info{};
    info.push_bytes = push_bytes;
    info.bindings.assign(bindings, bindings + count);
    g_programs[handle] = std::move(info);
}

std::vector<ProgramBinding> altered_copy(const ProgramBinding* table, std::size_t count,
    std::size_t index, std::uint32_t descriptor_type) {
    std::vector<ProgramBinding> copy(table, table + count);
    copy[index].descriptor_type = descriptor_type;
    return copy;
}

void install_valid_programs() {
    register_program(kTraceProgram, 112, kTraceBindings, std::size(kTraceBindings));
    register_program(kTraceSceneProgram, 112, kTraceBindings, std::size(kTraceBindings));
    register_program(kSpatialProgram, 64, kSpatialBindings, std::size(kSpatialBindings));
    register_program(kDisplayProgram, 32, kDisplayBindings, std::size(kDisplayBindings));
}

void install_bad_programs() {
    register_program(kBadTraceCount, 112, kTraceBindings, std::size(kTraceBindings) - 1u);
    register_program(kBadTracePush, 111, kTraceBindings, std::size(kTraceBindings));

    const std::vector<ProgramBinding> wrong_trace = altered_copy(
        kTraceBindings, std::size(kTraceBindings), 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    register_program(kBadTraceBinding, 112, wrong_trace.data(), wrong_trace.size());

    // Trace binding 25 is a storage buffer; a uniform buffer is the wrong type.
    const std::vector<ProgramBinding> wrong_scene = altered_copy(
        kTraceBindings, std::size(kTraceBindings), std::size(kTraceBindings) - 1u,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    register_program(kBadSceneBinding, 112, wrong_scene.data(), wrong_scene.size());

    register_program(kBadDisplayPush, 31, kDisplayBindings, std::size(kDisplayBindings));
    register_program(kBadSpatialPush, 47, kSpatialBindings, std::size(kSpatialBindings));
}

} // namespace

namespace bedray::core {

bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    const auto it = g_programs.find(handle);
    if (it == g_programs.end()) {
        return false;
    }
    out.push_constant_bytes = it->second.push_bytes;
    out.binding_count = static_cast<std::uint32_t>(it->second.bindings.size());
    return true;
}

bool program_has_binding(BedrayCore*, std::uint64_t handle,
    std::uint32_t binding, std::uint32_t descriptor_type) noexcept {
    const auto it = g_programs.find(handle);
    if (it == g_programs.end()) {
        return false;
    }
    for (const ProgramBinding& candidate : it->second.bindings) {
        if (candidate.binding == binding && candidate.descriptor_type == descriptor_type) {
            return true;
        }
    }
    return false;
}

} // namespace bedray::core

#include "fake_pass_device.h"

namespace {

using bedray::core::DeviceFns;
using bedray::core::PathTracePass;

BedrayPathTraceDesc make_config(std::uint64_t scene_program = kTraceSceneProgram,
    std::uint64_t spatial_program = kSpatialProgram) {
    BedrayPathTraceDesc desc{};
    desc.abi_version = BEDRAY_CORE_ABI_VERSION;
    desc.program_trace = kTraceProgram;
    desc.program_trace_scene = scene_program;
    desc.program_spatial = spatial_program;
    desc.program_display = kDisplayProgram;
    return desc;
}

BedrayPathTraceFrameDesc valid_frame(std::uint64_t command_buffer) {
    BedrayPathTraceFrameDesc desc{};
    desc.abi_version = BEDRAY_CORE_ABI_VERSION;
    desc.command_buffer = command_buffer;
    desc.output_view = 0x3001;
    desc.depth_view = 0x3002;
    desc.scene.tlas = 0x3003;
    desc.scene.sections.handle = 0x3004;
    desc.scene.sections.bytes = 64;
    desc.scene.albedo_view = 0x3005;
    desc.scene.material_view = 0x3006;
    desc.scene.normal_view = 0x3007;
    desc.scene.atlas_sampler = 0x3008;
    desc.camera.width = 64;
    desc.camera.height = 64;
    desc.camera.display_width = 64;
    desc.camera.display_height = 64;
    desc.path.max_depth = 4;
    desc.path.max_surface_steps = 2;
    desc.path.max_distance = 64.0f;
    desc.path.max_radiance = 1000.0f;
    desc.path.ray_bias = 0.001f;
    desc.path.alpha_cutoff = 0.5f;
    desc.lighting.exposure = 1.0f;
    desc.samples_per_frame = 1;
    desc.accum_frames = 1;
    desc.frame_index = 1;
    return desc;
}

const BedrayResource* find_resource(
    const std::vector<BedrayResource>& resources, std::uint32_t binding) {
    for (const BedrayResource& resource : resources) {
        if (resource.binding == binding) {
            return &resource;
        }
    }
    return nullptr;
}

std::uint64_t resource_handle(
    const std::vector<BedrayResource>& resources, std::uint32_t binding) {
    const BedrayResource* resource = find_resource(resources, binding);
    return resource != nullptr ? resource->handle : 0;
}

std::uint64_t resource_range(
    const std::vector<BedrayResource>& resources, std::uint32_t binding) {
    const BedrayResource* resource = find_resource(resources, binding);
    return resource != nullptr ? resource->range : 0;
}

std::uint32_t push_u32(const std::vector<unsigned char>& push, std::size_t offset) {
    std::uint32_t value = 0;
    if (offset + sizeof(value) <= push.size()) {
        std::memcpy(&value, push.data() + offset, sizeof(value));
    }
    return value;
}

bool read_buffer_bytes(std::uint64_t buffer_handle, std::vector<unsigned char>& out) {
    const auto buffer = fake::buffers.find(fake::handle_id(buffer_handle));
    if (buffer == fake::buffers.end()) {
        return false;
    }
    const auto memory = fake::memories.find(buffer->second.memory);
    if (memory == fake::memories.end()) {
        return false;
    }
    out = memory->second.bytes;
    return true;
}

void reset_all() {
    fake::reset();
    clear_programs();
    install_valid_programs();
    install_bad_programs();
    g_error = BedrayError{};
}

struct Fixture {
    BedrayCore core{};
    PathTracePass pass;
    std::set<std::uint64_t> attempted;

    explicit Fixture(std::uint64_t scene_program = kTraceSceneProgram,
        std::uint64_t spatial_program = kSpatialProgram) {
        reset_all();
        BedrayError error{};
        const BedrayStatus status = pass.configure(
            &core, make_device_fns(), make_config(scene_program, spatial_program), &error);
        g_error = error;
        if (status != BEDRAY_OK) {
            std::fprintf(stderr, "FAIL fixture configure: %d (%s)\n",
                static_cast<int>(status), error.message);
            ++g_failures;
        }
    }

    ~Fixture() {
        for (const std::uint64_t command_buffer : attempted) {
            pass.release_recording(command_buffer);
        }
        const BedrayStatus status = pass.destroy();
        if (status != BEDRAY_OK) {
            std::fprintf(stderr, "FAIL fixture destroy: %d\n", static_cast<int>(status));
            ++g_failures;
        }
        CHECK(fake::buffers.empty());
        CHECK(fake::memories.empty());
        CHECK(fake::images.empty());
        CHECK(fake::views.empty());
        CHECK(fake::faults == 0);
    }

    BedrayStatus render(const BedrayPathTraceFrameDesc& frame) {
        attempted.insert(frame.command_buffer);
        BedrayError error{};
        const BedrayStatus status = pass.render(frame, &error);
        g_error = error;
        return status;
    }

    BedrayStatus release(std::uint64_t command_buffer) {
        return pass.release_recording(command_buffer);
    }
};

void expect_invalid_frame(Fixture& fixture, const BedrayPathTraceFrameDesc& frame) {
    const std::size_t renders = fake::render_calls.size();
    const std::size_t phases = fake::phase_calls.size();
    CHECK_STATUS(fixture.render(frame), BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(!fixture.pass.has_recordings());
    CHECK(fake::render_calls.size() == renders);
    CHECK(fake::phase_calls.size() == phases);
}

void test_configure_validation() {
    reset_all();
    BedrayCore core{};

    {
        PathTracePass pass;
        CHECK_STATUS(pass.configure(nullptr, make_device_fns(),
            make_config(kTraceSceneProgram, kSpatialProgram), &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
        CHECK_STATUS(pass.render(valid_frame(0x2101), &g_error), BEDRAY_ERROR_INVALID_ARGUMENT);
    }

    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kSpatialProgram);
        desc.program_trace = 0;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kSpatialProgram);
        desc.program_display = 0;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kSpatialProgram);
        desc.program_trace = 0x9999;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kSpatialProgram);
        desc.program_trace = kBadTraceCount;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kSpatialProgram);
        desc.program_trace = kBadTracePush;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kSpatialProgram);
        desc.program_trace = kBadTraceBinding;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kBadSceneBinding, kSpatialProgram);
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kSpatialProgram);
        desc.program_display = kBadDisplayPush;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    {
        PathTracePass pass;
        BedrayPathTraceDesc desc = make_config(kTraceSceneProgram, kBadSpatialPush);
        CHECK_STATUS(pass.configure(&core, make_device_fns(), desc, &g_error),
            BEDRAY_ERROR_INVALID_ARGUMENT);
    }
    CHECK(fake::buffers.empty());

    {
        PathTracePass pass;
        DeviceFns fns = make_device_fns();
        fns.generation = 0;
        CHECK_STATUS(pass.configure(&core, fns, make_config(), &g_error),
            BEDRAY_ERROR_UNSUPPORTED);
    }
    {
        PathTracePass pass;
        DeviceFns fns = make_device_fns();
        fns.cmd_pipeline_barrier = nullptr;
        CHECK_STATUS(pass.configure(&core, fns, make_config(), &g_error),
            BEDRAY_ERROR_UNSUPPORTED);
    }
    {
        PathTracePass pass;
        DeviceFns fns = make_device_fns();
        fns.non_coherent_atom_size = 0;
        CHECK_STATUS(pass.configure(&core, fns, make_config(), &g_error),
            BEDRAY_ERROR_UNSUPPORTED);
    }
    {
        PathTracePass pass;
        DeviceFns fns = make_device_fns();
        fns.device = VK_NULL_HANDLE;
        CHECK_STATUS(pass.configure(&core, fns, make_config(), &g_error),
            BEDRAY_ERROR_UNSUPPORTED);
    }
    CHECK(fake::buffers.empty());

    {
        PathTracePass pass;
        CHECK_STATUS(pass.configure(&core, make_device_fns(), make_config(), &g_error), BEDRAY_OK);
        CHECK(pass.uses_program(kTraceProgram));
        CHECK(pass.uses_program(kTraceSceneProgram));
        CHECK(pass.uses_program(kSpatialProgram));
        CHECK(pass.uses_program(kDisplayProgram));
        CHECK(!pass.uses_program(0));
        CHECK(!pass.uses_program(kBadTraceCount));
        CHECK(fake::buffers.size() == 4);

        // Reconfiguring replaces the previous owner without leaking it.
        CHECK_STATUS(pass.configure(&core, make_device_fns(), make_config(), &g_error), BEDRAY_OK);
        CHECK(fake::buffers.size() == 4);
        CHECK(pass.destroy() == BEDRAY_OK);
        CHECK(fake::buffers.empty());
        CHECK(!pass.uses_program(kTraceProgram));
    }
}

void test_render_validation() {
    {
        Fixture fixture;
        const BedrayPathTraceFrameDesc good = valid_frame(0x2201);
        const auto reject = [&](auto mutate) {
            BedrayPathTraceFrameDesc frame = good;
            mutate(frame);
            expect_invalid_frame(fixture, frame);
        };

        BedrayPathTraceFrameDesc wrong_abi = good;
        wrong_abi.abi_version = BEDRAY_CORE_ABI_VERSION - 1u;
        CHECK_STATUS(fixture.render(wrong_abi), BEDRAY_ERROR_ABI_MISMATCH);

        reject([](BedrayPathTraceFrameDesc& frame) { frame.command_buffer = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.output_view = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.depth_view = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.scene.tlas = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.scene.sections.handle = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.scene.sections.bytes = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.scene.albedo_view = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.scene.material_view = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.scene.normal_view = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.scene.atlas_sampler = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.camera.width = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.camera.height = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.path.max_depth = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.path.max_surface_steps = 0; });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.path.max_distance = 0.0f; });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.path.max_distance = std::numeric_limits<float>::quiet_NaN();
        });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.path.max_radiance = 0.0f; });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.path.max_radiance = std::numeric_limits<float>::quiet_NaN();
        });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.lighting.exposure = -1.0f; });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.lighting.exposure = std::numeric_limits<float>::quiet_NaN();
        });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.path.emitter_count = 1; });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.scene.block_materials.handle = 0x4001;
            frame.scene.block_materials.bytes = 0;
        });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.scene.emitters.handle = 0x4002;
            frame.scene.emitters.bytes = 0;
        });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.scene.reservoirs.handle = 0x4003;
            frame.scene.reservoirs.bytes = 0;
        });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.scene.entity_positions.handle = 0x4004;
            frame.scene.entity_positions.bytes = 0;
        });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.scene.entity_previous.handle = 0x4005;
            frame.scene.entity_previous.bytes = 0;
        });
        reject([](BedrayPathTraceFrameDesc& frame) { frame.path.di_state[1] = 2; });
        reject([](BedrayPathTraceFrameDesc& frame) {
            frame.path.di_state[0] = BEDRAY_PATH_TRACE_DI_ENABLED;
        });

        // A legacy section table is opaque: only the native-scene flag promises
        // 64-byte records, so a non-multiple size must still pass here.
        BedrayPathTraceFrameDesc legacy = valid_frame(0x22FF);
        legacy.scene.sections.bytes = 65;
        CHECK_STATUS(fixture.render(legacy), BEDRAY_OK);
        CHECK(fixture.release(legacy.command_buffer) == BEDRAY_OK);
    }

    {
        // Without path_trace_scene.slang a native-scene frame must be rejected,
        // while the legacy program still serves frames without the flag.
        Fixture plain(0);
        BedrayPathTraceFrameDesc native = valid_frame(0x2301);
        native.flags = BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE;
        CHECK_STATUS(plain.render(native), BEDRAY_ERROR_INVALID_ARGUMENT);
        CHECK(!plain.pass.has_recordings());

        const BedrayPathTraceFrameDesc legacy = valid_frame(0x2302);
        CHECK_STATUS(plain.render(legacy), BEDRAY_OK);
        CHECK(fake::render_calls.size() == 2);
        if (fake::render_calls.size() == 2) {
            CHECK(fake::render_calls[0].program == kTraceProgram);
        }
        CHECK(plain.release(legacy.command_buffer) == BEDRAY_OK);
    }

    {
        Fixture fixture;
        BedrayPathTraceFrameDesc native = valid_frame(0x2311);
        native.flags = BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE;
        CHECK_STATUS(fixture.render(native), BEDRAY_OK);
        CHECK(fake::render_calls.size() == 2);
        if (fake::render_calls.size() == 2) {
            CHECK(fake::render_calls[0].program == kTraceSceneProgram);
        }
        CHECK(fixture.release(native.command_buffer) == BEDRAY_OK);

        BedrayPathTraceFrameDesc bad = native;
        bad.command_buffer = 0x2312;
        bad.scene.sections.bytes = 65;
        CHECK_STATUS(fixture.render(bad), BEDRAY_ERROR_INVALID_ARGUMENT);
        CHECK(!fixture.pass.has_recordings());

        BedrayPathTraceFrameDesc two_records = native;
        two_records.command_buffer = 0x2313;
        two_records.scene.sections.bytes = 128;
        CHECK_STATUS(fixture.render(two_records), BEDRAY_OK);
        CHECK(fixture.release(two_records.command_buffer) == BEDRAY_OK);
    }
}

void test_successful_frames_and_payloads() {
    Fixture fixture;
    BedrayPathTraceFrameDesc frame = valid_frame(0xC001);
    frame.frame_index = 3;
    frame.path.sample_index = 5;

    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fixture.pass.has_recordings());
    CHECK(fake::barriers > 0);
    CHECK(fake::render_calls.size() == 2);
    if (fake::render_calls.size() == 2) {
        const fake::RenderCall& trace = fake::render_calls[0];
        CHECK(trace.command_buffer == frame.command_buffer);
        CHECK(trace.program == kTraceProgram);
        CHECK(trace.resources.size() == 18);
        CHECK(trace.push.size() == 112);
        CHECK(trace.push_bytes == 112);
        CHECK(trace.group_x == 8);
        CHECK(trace.group_y == 8);
        CHECK(trace.group_z == 1);
        CHECK(resource_handle(trace.resources, 0) == frame.scene.tlas);
        CHECK(resource_handle(trace.resources, 2) == frame.scene.sections.handle);
        CHECK(resource_range(trace.resources, 2) == 64);
        CHECK(resource_handle(trace.resources, 12) == frame.depth_view);
        CHECK(resource_handle(trace.resources, 1) != 0);
        // Unbound emitters fall back to the block-material dummy; the
        // reservoir binding falls back to the pass's own dummy.
        CHECK(resource_handle(trace.resources, 17) != 0);
        CHECK(resource_handle(trace.resources, 17) == resource_handle(trace.resources, 18));
        CHECK(resource_handle(trace.resources, 19) != 0);
        CHECK(push_u32(trace.push, 0) == frame.path.max_depth);
        CHECK(push_u32(trace.push, 8) == frame.path.sample_index);
        CHECK(push_u32(trace.push, 16) == frame.path.flags);

        std::vector<unsigned char> constants;
        CHECK(read_buffer_bytes(resource_handle(trace.resources, 16), constants));
        CHECK(constants.size() >= 608);
        if (constants.size() >= 608) {
            CHECK(push_u32(constants, 400) == frame.camera.width);
            CHECK(push_u32(constants, 592) == frame.frame_index);
            CHECK(push_u32(constants, 596) == frame.path.sample_index);
            CHECK(push_u32(constants, 600) == 1);
            // First frame: no history yet, so the shader must reset temporal state.
            CHECK(push_u32(constants, 604) == 4);
        }

        const fake::RenderCall& display = fake::render_calls[1];
        CHECK(display.program == kDisplayProgram);
        CHECK(display.resources.size() == 9);
        CHECK(display.push.size() == 32);
        CHECK(resource_handle(display.resources, 2) == frame.output_view);
        CHECK(resource_handle(display.resources, 3) == frame.depth_view);
        CHECK(push_u32(display.push, 0) == 0);
        CHECK((push_u32(display.push, 4) & BEDRAY_PATH_DISPLAY_TONEMAP) != 0);
        CHECK((push_u32(display.push, 4) & BEDRAY_PATH_DISPLAY_REPROJECT) == 0);
    }
    CHECK(fixture.release(frame.command_buffer) == BEDRAY_OK);

    // The second frame has history, so display reprojects and forwards flags.
    BedrayPathTraceFrameDesc second = valid_frame(0xC002);
    second.flags = BEDRAY_PATH_TRACE_FRAME_ACCUMULATE
        | BEDRAY_PATH_TRACE_FRAME_CAMERA_STATIC
        | BEDRAY_PATH_TRACE_FRAME_FLIP_Y;
    CHECK_STATUS(fixture.render(second), BEDRAY_OK);
    CHECK(fake::render_calls.size() == 4);
    if (fake::render_calls.size() == 4) {
        const fake::RenderCall& display = fake::render_calls[3];
        const std::uint32_t flags = push_u32(display.push, 4);
        CHECK((flags & BEDRAY_PATH_DISPLAY_TONEMAP) != 0);
        CHECK((flags & BEDRAY_PATH_DISPLAY_ACCUMULATE) != 0);
        CHECK((flags & BEDRAY_PATH_DISPLAY_CAMERA_STATIC) != 0);
        CHECK((flags & BEDRAY_PATH_DISPLAY_FLIP_Y) != 0);
        CHECK((flags & BEDRAY_PATH_DISPLAY_REPROJECT) != 0);
        CHECK(push_u32(display.push, 0) == second.accum_frames);

        std::vector<unsigned char> constants;
        CHECK(read_buffer_bytes(resource_handle(fake::render_calls[2].resources, 16), constants));
        if (constants.size() >= 608) {
            // History is valid: the shader may reuse the previous frame.
            CHECK(push_u32(constants, 604) == 1);
        }
    }
    CHECK(fixture.release(second.command_buffer) == BEDRAY_OK);
}

void test_spatial_stage() {
    {
        Fixture fixture;
        BedrayPathTraceFrameDesc frame = valid_frame(0xE001);
        frame.frame_index = 7;
        frame.flags = BEDRAY_PATH_TRACE_FRAME_SPATIAL;
        frame.path.di_state[0] = BEDRAY_PATH_TRACE_DI_ENABLED;
        frame.path.di_state[1] = 1;
        frame.path.emitter_count = 3;
        frame.path.emitter_probability = 0.5f;
        frame.scene.emitters.handle = 0x5001;
        frame.scene.emitters.bytes = 288;
        frame.scene.reservoirs.handle = 0x5002;
        frame.scene.reservoirs.bytes = 64 * 64 * 128;

        CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
        CHECK(fake::render_calls.size() == 2);
        CHECK(fake::phase_calls.size() == 1);
        if (fake::phase_calls.size() == 1) {
            const fake::PhaseCall& call = fake::phase_calls[0];
            CHECK(call.command_buffer == frame.command_buffer);
            CHECK(call.program == kSpatialProgram);
            CHECK(call.resources.size() == 6);
            CHECK(call.push.size() == 64);
            CHECK(call.push_bytes == 64);
            CHECK(call.phase_offset == 8);
            CHECK(call.phase_count == 2);
            CHECK(push_u32(call.push, 0) == frame.frame_index);
            CHECK(push_u32(call.push, 4) == 1);
            // state.w carries RESTIR_SPATIAL_ENABLED.
            CHECK(push_u32(call.push, 12) == 1);
            CHECK(resource_handle(call.resources, 18) == frame.scene.emitters.handle);
            CHECK(resource_handle(call.resources, 19) == frame.scene.reservoirs.handle);
        }
        CHECK(fixture.release(frame.command_buffer) == BEDRAY_OK);

        // The frame flag alone does not run the spatial stage.
        BedrayPathTraceFrameDesc no_di = valid_frame(0xE002);
        no_di.flags = BEDRAY_PATH_TRACE_FRAME_SPATIAL;
        CHECK_STATUS(fixture.render(no_di), BEDRAY_OK);
        CHECK(fake::phase_calls.size() == 1);
        CHECK(fake::render_calls.size() == 4);
        CHECK(fixture.release(no_di.command_buffer) == BEDRAY_OK);

        // ReSTIR DI with no emitters has nothing to pool.
        BedrayPathTraceFrameDesc no_emitters = valid_frame(0xE003);
        no_emitters.flags = BEDRAY_PATH_TRACE_FRAME_SPATIAL;
        no_emitters.path.di_state[0] = BEDRAY_PATH_TRACE_DI_ENABLED;
        no_emitters.path.di_state[1] = 1;
        no_emitters.scene.reservoirs.handle = 0x5005;
        no_emitters.scene.reservoirs.bytes = 64 * 64 * 128;
        CHECK_STATUS(fixture.render(no_emitters), BEDRAY_OK);
        CHECK(fake::phase_calls.size() == 1);
        CHECK(fixture.release(no_emitters.command_buffer) == BEDRAY_OK);
    }

    {
        // A pass configured without restir_spatial.slang never runs the stage
        // even when every other spatial precondition holds.
        Fixture plain(kTraceSceneProgram, 0);
        BedrayPathTraceFrameDesc spatial = valid_frame(0xE101);
        spatial.flags = BEDRAY_PATH_TRACE_FRAME_SPATIAL;
        spatial.path.di_state[0] = BEDRAY_PATH_TRACE_DI_ENABLED;
        spatial.path.di_state[1] = 1;
        spatial.path.emitter_count = 1;
        spatial.scene.emitters.handle = 0x5007;
        spatial.scene.emitters.bytes = 96;
        spatial.scene.reservoirs.handle = 0x5008;
        spatial.scene.reservoirs.bytes = 64 * 64 * 128;
        CHECK_STATUS(plain.render(spatial), BEDRAY_OK);
        CHECK(fake::phase_calls.empty());
        CHECK(fake::render_calls.size() == 2);
        CHECK(plain.release(spatial.command_buffer) == BEDRAY_OK);
    }
}

void test_native_reservoir_owner() {
    Fixture fixture;
    auto frame = valid_frame(0xD001);
    frame.flags = BEDRAY_PATH_TRACE_FRAME_NATIVE_DI;
    frame.path.di_state[0] = BEDRAY_PATH_TRACE_DI_ENABLED | BEDRAY_PATH_TRACE_DI_HISTORY;
    frame.path.emitter_count = 1;
    frame.scene.emitters.handle = 0x7001;
    frame.scene.emitters.bytes = 96;
    auto invalid = frame;
    invalid.path.di_state[1] = 1;
    expect_invalid_frame(fixture, invalid);
    invalid = frame;
    invalid.scene.reservoirs.handle = 0x7002;
    expect_invalid_frame(fixture, invalid);
    invalid = frame;
    invalid.scene.emitters.bytes = 95;
    expect_invalid_frame(fixture, invalid);
    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fake::fills == 1);
    const auto ring = resource_handle(fake::render_calls[0].resources, 19);
    CHECK(resource_range(fake::render_calls[0].resources, 19) == 64 * 64 * 128);
    CHECK(push_u32(fake::render_calls[0].push, 80) == BEDRAY_PATH_TRACE_DI_ENABLED);
    CHECK(push_u32(fake::render_calls[0].push, 84) == 0);
    fixture.pass.discard_recording(0xDEAD);
    frame.command_buffer++;
    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fake::fills == 1);
    CHECK(resource_handle(fake::render_calls[2].resources, 19) == ring);
    CHECK(push_u32(fake::render_calls[2].push, 80) & BEDRAY_PATH_TRACE_DI_HISTORY);
    CHECK(push_u32(fake::render_calls[2].push, 84) == 1);
    fixture.pass.discard_recording(frame.command_buffer);
    CHECK_STATUS(fixture.release(frame.command_buffer), BEDRAY_OK);
    frame.command_buffer++;
    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fake::fills == 2);
    CHECK(!(push_u32(fake::render_calls[4].push, 80) & BEDRAY_PATH_TRACE_DI_HISTORY));
    CHECK(push_u32(fake::render_calls[4].push, 84) == 0);
    frame.command_buffer++;
    frame.flags &= ~BEDRAY_PATH_TRACE_FRAME_RESET_DI;
    frame.camera.width = 32;
    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fake::fills == 3);
    CHECK(resource_handle(fake::render_calls[6].resources, 19) != ring);
    CHECK(push_u32(fake::render_calls[6].push, 84) == 0);
    CHECK(fake::buffers.count(ring) == 1);
    CHECK_STATUS(fixture.release(0xD001), BEDRAY_OK);
    CHECK_STATUS(fixture.release(0xD002), BEDRAY_OK);
    CHECK(fake::buffers.count(ring) == 1);
    CHECK_STATUS(fixture.release(0xD003), BEDRAY_OK);
    CHECK(fake::buffers.count(ring) == 0);
    const auto resized = resource_handle(fake::render_calls[6].resources, 19);
    frame.command_buffer++;
    frame.path.di_state[0] = 0;
    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fake::buffers.count(resized) == 1);
    CHECK_STATUS(fixture.release(0xD004), BEDRAY_OK);
    CHECK(fake::buffers.count(resized) == 0);
    frame.command_buffer++;
    frame.path.di_state[0] = BEDRAY_PATH_TRACE_DI_ENABLED;
    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fake::fills == 4);
    frame.command_buffer++;
    frame.flags |= BEDRAY_PATH_TRACE_FRAME_RESET_DI;
    CHECK_STATUS(fixture.render(frame), BEDRAY_OK);
    CHECK(fake::fills == 5);
    CHECK(!(push_u32(fake::render_calls[fake::render_calls.size() - 2].push, 80) & BEDRAY_PATH_TRACE_DI_HISTORY));
}

void test_extent_change_keeps_pinned_images() {
    Fixture fixture;
    const BedrayPathTraceFrameDesc first = valid_frame(0x1301);
    CHECK_STATUS(fixture.render(first), BEDRAY_OK);
    CHECK(fake::images.size() == 6);

    // A new extent retires the old images, but the live recording still pins
    // them: they may only leave the owner after release_recording proves the
    // command buffer is done with them.
    BedrayPathTraceFrameDesc resized = valid_frame(0x1302);
    resized.camera.width = 32;
    resized.camera.height = 32;
    CHECK_STATUS(fixture.render(resized), BEDRAY_OK);
    CHECK(fake::images.size() == 12);

    CHECK(fixture.release(first.command_buffer) == BEDRAY_OK);
    CHECK(fake::images.size() == 6);

    CHECK(fixture.release(resized.command_buffer) == BEDRAY_OK);
    CHECK(fixture.pass.destroy() == BEDRAY_OK);
    CHECK(fake::images.empty());
    CHECK(fake::views.empty());
}

void test_recording_lifetime() {
    Fixture fixture;
    const BedrayPathTraceFrameDesc first = valid_frame(0xF001);
    const BedrayPathTraceFrameDesc second = valid_frame(0xF002);

    CHECK_STATUS(fixture.render(first), BEDRAY_OK);
    CHECK(fixture.pass.has_recordings());

    // The same command buffer cannot be recorded twice before release.
    const std::size_t calls = fake::render_calls.size();
    CHECK_STATUS(fixture.render(first), BEDRAY_ERROR_IN_USE);
    CHECK(fake::render_calls.size() == calls);

    CHECK_STATUS(fixture.render(second), BEDRAY_OK);

    // A pass with live recordings refuses destruction and reconfiguration.
    CHECK(fixture.pass.destroy() == BEDRAY_ERROR_IN_USE);
    BedrayError error{};
    const BedrayStatus reconfigure = fixture.pass.configure(
        &fixture.core, make_device_fns(), make_config(), &error);
    CHECK(reconfigure == BEDRAY_ERROR_IN_USE);
    CHECK(fixture.pass.has_recordings());

    CHECK(fixture.release(first.command_buffer) == BEDRAY_OK);
    CHECK(fixture.pass.has_recordings());
    CHECK(fixture.release(first.command_buffer) == BEDRAY_OK); // idempotent
    CHECK(fixture.pass.release_recording(0xDEAD) == BEDRAY_OK); // unknown

    CHECK(fixture.release(second.command_buffer) == BEDRAY_OK);
    CHECK(!fixture.pass.has_recordings());
    CHECK(fixture.pass.destroy() == BEDRAY_OK);
    CHECK(fake::buffers.empty());
    CHECK(fake::memories.empty());
    CHECK(fake::images.empty());
    CHECK(fake::views.empty());

    // A destroyed pass rejects frames and owns no program.
    CHECK_STATUS(fixture.render(valid_frame(0xF003)), BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(!fixture.pass.uses_program(kTraceProgram));
    CHECK(!fixture.pass.uses_program(kTraceSceneProgram));
    CHECK(!fixture.pass.uses_program(kSpatialProgram));
    CHECK(!fixture.pass.uses_program(kDisplayProgram));
}

void test_failure_after_dispatch_keeps_pins() {
    Fixture fixture;

    // The core may have recorded the trace dispatch before failing, so the pass
    // must not complete its pins: only the host can prove the whole command
    // buffer is discarded.
    fake::fail_render_at(0, BEDRAY_ERROR_VULKAN);
    const BedrayPathTraceFrameDesc trace_failure = valid_frame(0x1101);
    CHECK_STATUS(fixture.render(trace_failure), BEDRAY_ERROR_VULKAN);
    CHECK(fixture.pass.has_recordings());
    CHECK(fixture.pass.destroy() == BEDRAY_ERROR_IN_USE);
    CHECK(fixture.release(trace_failure.command_buffer) == BEDRAY_OK);
    CHECK(!fixture.pass.has_recordings());

    // The display dispatch fails after the trace dispatch was recorded.
    fake::fail_render_at(1, BEDRAY_ERROR_OUT_OF_MEMORY);
    const BedrayPathTraceFrameDesc display_failure = valid_frame(0x1102);
    CHECK_STATUS(fixture.render(display_failure), BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(fixture.pass.has_recordings());
    CHECK(fixture.release(display_failure.command_buffer) == BEDRAY_OK);
    CHECK(!fixture.pass.has_recordings());

    // The spatial phase fails after the trace dispatch was recorded.
    fake::fail_render_at(-1, BEDRAY_OK);
    fake::fail_phase_call = true;
    BedrayPathTraceFrameDesc phase_failure = valid_frame(0x1103);
    phase_failure.flags = BEDRAY_PATH_TRACE_FRAME_SPATIAL;
    phase_failure.path.di_state[0] = BEDRAY_PATH_TRACE_DI_ENABLED;
    phase_failure.path.di_state[1] = 1;
    phase_failure.path.emitter_count = 1;
    phase_failure.scene.emitters.handle = 0x6001;
    phase_failure.scene.emitters.bytes = 96;
    phase_failure.scene.reservoirs.handle = 0x6002;
    phase_failure.scene.reservoirs.bytes = 64 * 64 * 128;
    CHECK_STATUS(fixture.render(phase_failure), BEDRAY_ERROR_VULKAN);
    CHECK(fixture.pass.has_recordings());
    CHECK(fixture.release(phase_failure.command_buffer) == BEDRAY_OK);
    CHECK(!fixture.pass.has_recordings());
    fake::fail_phase_call = false;

    // No failed frame may publish history: the retry still resets temporal
    // state instead of sampling a history image that was never written.
    const BedrayPathTraceFrameDesc retry = valid_frame(0x1104);
    CHECK_STATUS(fixture.render(retry), BEDRAY_OK);
    CHECK(fake::render_calls.size() == 6);
    if (fake::render_calls.size() == 6) {
        std::vector<unsigned char> constants;
        CHECK(read_buffer_bytes(
            resource_handle(fake::render_calls[4].resources, 16), constants));
        if (constants.size() >= 608) {
            CHECK(push_u32(constants, 604) == 4);
        }
    }
    CHECK(fixture.release(retry.command_buffer) == BEDRAY_OK);

    CHECK(fixture.pass.destroy() == BEDRAY_OK);
    CHECK(fake::buffers.empty());
    CHECK(fake::memories.empty());
    CHECK(fake::images.empty());
    CHECK(fake::views.empty());
}

void test_upload_failure_releases_pins() {
    Fixture fixture;

    // Fixture's configure already allocated the four dummy buffers. The render
    // then allocates the six pass images and the FrameConstants upload; letting
    // six allocations through makes the upload (the seventh) fail before a
    // single command was recorded, so the pass must complete its own pins.
    fake::fail_allocate_after = 6;
    CHECK_STATUS(fixture.render(valid_frame(0x1201)), BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(g_error.status == BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(!fixture.pass.has_recordings());
    CHECK(fixture.pass.destroy() == BEDRAY_OK);
    CHECK(fake::buffers.empty());
    CHECK(fake::memories.empty());
    CHECK(fake::images.empty());
    CHECK(fake::views.empty());
    CHECK(fake::faults == 0);
}

void test_device_lost_configure_cleanup() {
    reset_all();
    BedrayCore core{};
    fake::fail_allocate_after = 0;
    fake::fail_result = VK_ERROR_DEVICE_LOST;

    PathTracePass pass;
    CHECK_STATUS(pass.configure(&core, make_device_fns(), make_config(), &g_error),
        BEDRAY_ERROR_DEVICE_LOST);
    CHECK(fake::buffers.empty());
    CHECK(fake::memories.empty());
    CHECK(fake::faults == 0);
    CHECK(pass.destroy() == BEDRAY_OK);
}

void test_guide_aware_program_contract() {
    reset_all();
    std::vector<ProgramBinding> bindings(std::begin(kTraceBindings), std::end(kTraceBindings));
    for (std::uint32_t binding = 26; binding <= 32; ++binding)
        bindings.push_back({binding, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
    register_program(kTraceProgram, 112, bindings.data(), bindings.size());
    register_program(kTraceSceneProgram, 112, bindings.data(), bindings.size());
    BedrayCore core{};
    PathTracePass pass;
    // A 25-binding declaration does not excuse a missing/wrong guide type.
    g_programs[kTraceProgram].bindings.back().descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    CHECK_STATUS(pass.configure(&core, make_device_fns(), make_config(), &g_error), BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(fake::images.empty()); CHECK(fake::buffers.empty());
    g_programs[kTraceProgram].bindings.back().descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    // Four buffer dummies, then one image succeeds and the next allocation fails.
    fake::fail_allocate_after = 5;
    CHECK_STATUS(pass.configure(&core, make_device_fns(), make_config(), &g_error), BEDRAY_ERROR_OUT_OF_MEMORY);
    CHECK(fake::images.empty()); CHECK(fake::buffers.empty()); CHECK(fake::memories.empty());
    CHECK_STATUS(pass.configure(&core, make_device_fns(), make_config(), &g_error), BEDRAY_OK);
    CHECK(fake::images.size() == 3);
    auto frame = valid_frame(1);
    frame.path.flags = 1; // Stand-ins cannot satisfy an explicit G-buffer-write request.
    CHECK_STATUS(pass.render(frame, &g_error), BEDRAY_ERROR_UNSUPPORTED);
    CHECK(!pass.has_recordings()); CHECK(fake::render_calls.empty()); CHECK(fake::images.size() == 3);
    frame.path.flags = 0;
    CHECK_STATUS(pass.render(frame, &g_error), BEDRAY_OK);
    CHECK(fake::images.size() == 9);
    const auto& trace = fake::render_calls.front();
    CHECK(trace.resources.size() == 25); CHECK(trace.push_bytes == 112);
    constexpr VkFormat formats[] = {VK_FORMAT_R32_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM};
    for (std::size_t i = 0; i < std::size(formats); ++i) {
        const auto& resource = trace.resources[18 + i];
        CHECK(resource.binding == 26 + i); CHECK(resource.descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
        CHECK(resource.image_layout == VK_IMAGE_LAYOUT_GENERAL);
        const auto& image = fake::images.at(fake::view_images.at(resource.handle));
        CHECK(image.width == 1 && image.height == 1); CHECK(image.format == formats[i]);
    }
    CHECK_STATUS(pass.destroy(), BEDRAY_ERROR_IN_USE);
    pass.discard_recording(1);
    CHECK_STATUS(pass.release_recording(1), BEDRAY_OK);
    frame.command_buffer = 2;
    frame.flags |= BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE;
    CHECK_STATUS(pass.render(frame, &g_error), BEDRAY_OK);
    CHECK(fake::render_calls[fake::render_calls.size() - 2].program == kTraceSceneProgram);
    CHECK(fake::render_calls[fake::render_calls.size() - 2].resources.size() == 25);
    CHECK_STATUS(pass.release_recording(2), BEDRAY_OK);
    // An older 18-binding native-scene program remains independently supported.
    register_program(kTraceSceneProgram, 112, kTraceBindings, std::size(kTraceBindings));
    CHECK_STATUS(pass.configure(&core, make_device_fns(), make_config(), &g_error), BEDRAY_OK);
    frame.command_buffer = 3;
    CHECK_STATUS(pass.render(frame, &g_error), BEDRAY_OK);
    CHECK(fake::render_calls[fake::render_calls.size() - 2].resources.size() == 18);
    CHECK_STATUS(pass.release_recording(3), BEDRAY_OK);
    CHECK_STATUS(pass.destroy(), BEDRAY_OK);
    CHECK(fake::images.empty()); CHECK(fake::views.empty()); CHECK(fake::view_images.empty());
    CHECK(fake::buffers.empty()); CHECK(fake::memories.empty()); CHECK(fake::faults == 0);
}

} // namespace

int main() {
    test_configure_validation();
    test_render_validation();
    test_successful_frames_and_payloads();
    test_spatial_stage();
    test_native_reservoir_owner();
    test_extent_change_keeps_pinned_images();
    test_recording_lifetime();
    test_failure_after_dispatch_keeps_pins();
    test_upload_failure_releases_pins();
    test_device_lost_configure_cleanup();
    test_guide_aware_program_contract();

    if (g_failures != 0) {
        std::fprintf(stderr, "bedray_path_trace_pass_test FAILED: %d failure(s)\n", g_failures);
        return 1;
    }
    std::printf("bedray_path_trace_pass_test passed\n");
    return 0;
}
