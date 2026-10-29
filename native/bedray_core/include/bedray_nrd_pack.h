#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

enum { BEDRAY_NRD_PACK = 0, BEDRAY_NRD_COMPOSE = 1, BEDRAY_NRD_VIEW_COUNT = 14 };

/* Additive ABI: one phase around an external NRD/NGX dispatch. All views are
 * borrowed GENERAL images, in the existing 14-image G-buffer order. C++ owns
 * immutable per-dispatch descriptors, push packing and entry/exit barriers.
 * Host retains images/motion memory through exact completion/discard proof.
 * Motion is a 16-byte aligned vec4-per-pixel device address (zero = static).
 * Program is the matching AOT format variant; no runtime shader compiler. */
typedef struct BedrayNrdPackFrameDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint64_t program;
    uint64_t views[BEDRAY_NRD_VIEW_COUNT];
    uint32_t phase;
    uint32_t width;
    uint32_t height;
    uint32_t normal_encoding;    /* NRD: 2/4; RR: 0 */
    uint32_t roughness_encoding; /* 0..2 */
    uint32_t denoiser;           /* 0 REBLUR, 1 RELAX, 2 RR */
    uint32_t flags;              /* bit0 tone map, bit1 split */
    float hit_dist_a;
    float hit_dist_b;
    float hit_dist_c;
    float denoising_range;
    float exposure;
    uint64_t motion_address;
    uint64_t reserved;           /* zero */
} BedrayNrdPackFrameDesc;

/* On failure after recording starts, discard the complete command buffer. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_nrd_pack(
    BedrayCore*, const BedrayNrdPackFrameDesc*, BedrayError*);

#ifdef __cplusplus
}
#include <cstddef>
static_assert(sizeof(BedrayNrdPackFrameDesc) == 200);
static_assert(offsetof(BedrayNrdPackFrameDesc, views) == 24);
static_assert(offsetof(BedrayNrdPackFrameDesc, phase) == 136);
static_assert(offsetof(BedrayNrdPackFrameDesc, normal_encoding) == 148);
static_assert(offsetof(BedrayNrdPackFrameDesc, hit_dist_a) == 164);
static_assert(offsetof(BedrayNrdPackFrameDesc, motion_address) == 184);
static_assert(offsetof(BedrayNrdPackFrameDesc, reserved) == 192);
#endif
