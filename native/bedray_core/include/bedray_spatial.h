#pragma once
#include "bedray_core.h"
#ifdef __cplusplus
extern "C" {
#endif

enum { BEDRAY_SPATIAL_FSR1 = 0, BEDRAY_SPATIAL_NIS = 1, BEDRAY_SPATIAL_SHARPEN = 1 };
/* Additive ABI-v2 coarse spatial recorder. All programs, samplers and GENERAL
 * views are borrowed through exact completion/discard. No submission or image
 * ownership. Constants are the unchanged live EASU/RCAS/NIS layouts. On failure
 * after recording begins, the host discards the complete command buffer. */
typedef struct BedraySpatialFrameDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint64_t programs[3]; /* EASU, RCAS, NIS; unused entries may be zero */
    uint64_t samplers[2]; /* linear clamp, nearest clamp */
    uint64_t views[3]; /* input, optional EASU intermediate, output RGBA8 */
    uint32_t input_width;
    uint32_t input_height;
    uint32_t output_width;
    uint32_t output_height;
    uint32_t backend;
    uint32_t flags; /* bit0 EASU+RCAS, only for FSR1 */
    const void* primary_push; /* EASU 80 B or NIS 112 B */
    uint32_t primary_bytes;
    uint32_t secondary_bytes;
    const void* secondary_push; /* RCAS 32 B when sharpened; otherwise null */
    uint64_t reserved;
} BedraySpatialFrameDesc;

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_spatial(
    BedrayCore*, const BedraySpatialFrameDesc*, BedrayError*);
#ifdef __cplusplus
}
#include <cstddef>
static_assert(sizeof(BedraySpatialFrameDesc) == 136);
static_assert(offsetof(BedraySpatialFrameDesc, programs) == 16);
static_assert(offsetof(BedraySpatialFrameDesc, samplers) == 40);
static_assert(offsetof(BedraySpatialFrameDesc, views) == 56);
static_assert(offsetof(BedraySpatialFrameDesc, input_width) == 80);
static_assert(offsetof(BedraySpatialFrameDesc, backend) == 96);
static_assert(offsetof(BedraySpatialFrameDesc, primary_push) == 104);
static_assert(offsetof(BedraySpatialFrameDesc, secondary_push) == 120);
#endif
