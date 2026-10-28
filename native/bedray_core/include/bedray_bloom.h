#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Additive coarse post-pass ABI; no existing ABI-v2 layout changes. All handles
 * and image views are borrowed on the host device. Push bytes are copied during
 * the call and retain the existing BloomPush shader layout. */
typedef struct BedrayBloomDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t programs[4]; /* prefilter/down, down, up, apply; 8x8x1, push 128 B */
    uint64_t linear_sampler;
    uint64_t nearest_sampler;
    uint64_t image_budget_bytes;
} BedrayBloomDesc;

typedef struct BedrayBloomFrameDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint32_t width;
    uint32_t height;
    uint64_t output_view; /* RGBA8, GENERAL; host keeps both views until proof */
    uint64_t depth_view; /* ray distance, GENERAL */
    uint32_t bloom_enabled; /* 0: apply glare only; push strength must be zero */
    uint32_t push_bytes;
    const void* push_constants;
} BedrayBloomFrameDesc;

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_bloom(
    BedrayCore*, const BedrayBloomDesc*, BedrayError*);
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_bloom(
    BedrayCore*, const BedrayBloomFrameDesc*, BedrayError*);
/* Refuses pending recordings. Use the existing core completion/discard calls. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_bloom(BedrayCore*);

#ifdef __cplusplus
}
static_assert(sizeof(BedrayBloomDesc) == 64);
static_assert(sizeof(BedrayBloomFrameDesc) == 56);
#endif
