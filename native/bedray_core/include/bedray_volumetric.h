#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE (1u << 0)
#define BEDRAY_VOLUMETRIC_FRAME_RESET_HISTORY (1u << 1)

/* Separate additive layouts; existing ABI-v2 structures remain unchanged.
 * Programs stay runtime-owned. March variants share 5 bindings/96 push bytes,
 * but binding 2 has different section record semantics (legacy/native scene).
 * Each program must have the reflected 8x8x1 workgroup contract. */
typedef struct BedrayVolumetricDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t program_march_legacy; /* zero when only native scene is supported */
    uint64_t program_march_scene; /* zero when only legacy tables are supported */
    uint64_t program_apply; /* 4 bindings, 24 push bytes */
    uint64_t image_budget_bytes;
    uint64_t buffer_budget_bytes;
} BedrayVolumetricDesc;

/* Borrowed GENERAL output/depth images, TLAS and section table at the full
 * frame extent; host retains them through exact completion/discard proof.
 * The host's three POD payloads are copied synchronously. FrameConstants must
 * be 608 bytes and camera.size.xy must agree with width/height. March history
 * and both push frame indices are derived by native code, never trusted from
 * a host mirror. The five-binding march contract supports world instances only. */
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
    uint32_t flags;
    uint32_t frame_index;
    const void* frame_constants;
    uint32_t frame_constants_bytes;
    uint32_t march_push_bytes;
    const void* march_push;
    const void* apply_push;
    uint32_t apply_push_bytes;
    uint32_t reserved;
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
static_assert(offsetof(BedrayVolumetricDesc, program_march_legacy) == 8);
static_assert(offsetof(BedrayVolumetricDesc, program_march_scene) == 16);
static_assert(offsetof(BedrayVolumetricDesc, program_apply) == 24);
static_assert(offsetof(BedrayVolumetricDesc, image_budget_bytes) == 32);
static_assert(offsetof(BedrayVolumetricDesc, buffer_budget_bytes) == 40);
static_assert(sizeof(BedrayVolumetricFrameDesc) == 120);
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
#endif
