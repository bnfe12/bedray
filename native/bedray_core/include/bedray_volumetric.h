#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE (1u << 0)
#define BEDRAY_VOLUMETRIC_FRAME_RESET_HISTORY (1u << 1)

/* Programs stay runtime-owned. March uses the native GeometrySection table
 * at binding 2, the albedo and material atlases (combined image samplers) at
 * bindings 3 and 9 and the BEM2 emitter list at binding 18, and declares 8
 * bindings and 112 push bytes.
 * Apply declares 4 bindings and 32 push bytes. Both use an 8x8x1 workgroup. */
typedef struct BedrayVolumetricDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t reserved; /* zero */
    uint64_t program_march_scene;
    uint64_t program_apply; /* 4 bindings, 32 push bytes */
    uint64_t image_budget_bytes;
    uint64_t buffer_budget_bytes;
} BedrayVolumetricDesc;

/* Borrowed GENERAL output/depth images, TLAS and section table at the full
 * frame extent; host retains them through exact completion/discard proof.
 * The host's three POD payloads are copied synchronously. FrameConstants must
 * be 608 bytes and camera.size.xy must agree with width/height. March history
 * and both push frame indices are derived by native code, never trusted from
 * a host mirror. The march contract supports world instances only.
 * atlas_view/material_view/atlas_sampler (required) feed the hue and strength of block lights. The emitter
 * list is borrowed like the other inputs; zero emitters means "no list this
 * frame" and the sections table stands in as a harmless descriptor (the shader
 * validates the BEM2 header and finds nothing). emitters_bytes is a whole
 * number of 16-byte records and covers the table exactly. */
typedef struct BedrayVolumetricFrameDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint64_t output_view; /* RGBA8 */
    uint64_t depth_view; /* R32F, unit-ray distance */
    uint64_t tlas;
    uint64_t sections;
    uint64_t sections_offset;
    uint64_t sections_bytes; /* whole 64-byte section records */
    uint32_t width;
    uint32_t height;
    uint32_t flags; /* requires BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE */
    uint32_t frame_index;
    const void* frame_constants;
    uint32_t frame_constants_bytes;
    uint32_t march_push_bytes;
    const void* march_push;
    const void* apply_push;
    uint32_t apply_push_bytes;
    uint32_t reserved;
    uint64_t atlas_view;
    uint64_t material_view;
    uint64_t atlas_sampler;
    uint64_t emitters;
    uint64_t emitters_offset;
    uint64_t emitters_bytes;
} BedrayVolumetricFrameDesc;

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_volumetric(
    BedrayCore*, const BedrayVolumetricDesc*, BedrayError*);
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_volumetric(
    BedrayCore*, const BedrayVolumetricFrameDesc*, BedrayError*);
/* Refuses outstanding recordings. The existing exact completion/discard calls
 * release per-recording image/upload pins. No submission, wait or device owner. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_volumetric(BedrayCore*);

#ifdef __cplusplus
}
#include <cstddef>
static_assert(sizeof(BedrayVolumetricDesc) == 48);
static_assert(offsetof(BedrayVolumetricDesc, reserved) == 8);
static_assert(offsetof(BedrayVolumetricDesc, program_march_scene) == 16);
static_assert(offsetof(BedrayVolumetricDesc, program_apply) == 24);
static_assert(offsetof(BedrayVolumetricDesc, image_budget_bytes) == 32);
static_assert(offsetof(BedrayVolumetricDesc, buffer_budget_bytes) == 40);
static_assert(sizeof(BedrayVolumetricFrameDesc) == 168);
static_assert(offsetof(BedrayVolumetricFrameDesc, command_buffer) == 8);
static_assert(offsetof(BedrayVolumetricFrameDesc, output_view) == 16);
static_assert(offsetof(BedrayVolumetricFrameDesc, depth_view) == 24);
static_assert(offsetof(BedrayVolumetricFrameDesc, tlas) == 32);
static_assert(offsetof(BedrayVolumetricFrameDesc, sections) == 40);
static_assert(offsetof(BedrayVolumetricFrameDesc, sections_offset) == 48);
static_assert(offsetof(BedrayVolumetricFrameDesc, sections_bytes) == 56);
static_assert(offsetof(BedrayVolumetricFrameDesc, width) == 64);
static_assert(offsetof(BedrayVolumetricFrameDesc, height) == 68);
static_assert(offsetof(BedrayVolumetricFrameDesc, flags) == 72);
static_assert(offsetof(BedrayVolumetricFrameDesc, frame_index) == 76);
static_assert(offsetof(BedrayVolumetricFrameDesc, frame_constants) == 80);
static_assert(offsetof(BedrayVolumetricFrameDesc, frame_constants_bytes) == 88);
static_assert(offsetof(BedrayVolumetricFrameDesc, march_push_bytes) == 92);
static_assert(offsetof(BedrayVolumetricFrameDesc, march_push) == 96);
static_assert(offsetof(BedrayVolumetricFrameDesc, apply_push) == 104);
static_assert(offsetof(BedrayVolumetricFrameDesc, apply_push_bytes) == 112);
static_assert(offsetof(BedrayVolumetricFrameDesc, atlas_view) == 120);
static_assert(offsetof(BedrayVolumetricFrameDesc, material_view) == 128);
static_assert(offsetof(BedrayVolumetricFrameDesc, atlas_sampler) == 136);
static_assert(offsetof(BedrayVolumetricFrameDesc, emitters) == 144);
static_assert(offsetof(BedrayVolumetricFrameDesc, emitters_offset) == 152);
static_assert(offsetof(BedrayVolumetricFrameDesc, emitters_bytes) == 160);
#endif
