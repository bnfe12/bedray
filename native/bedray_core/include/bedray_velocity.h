#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Additive ABI-v2 recorder for the live RG16F velocity contract. Programs,
 * sampler, image views and optional BDA ranges remain host-owned. The caller
 * retains every borrowed resource until exact command completion/discard. */
typedef struct BedrayVelocityDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t program; /* velocity_reproject_live: 8x8x1, no push, three bindings */
    uint64_t nearest_sampler;
    uint64_t buffer_budget_bytes;
} BedrayVelocityDesc;

typedef struct BedrayVelocityFrameDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint64_t depth_view; /* sampled depth, GENERAL */
    uint64_t output_view; /* RG16F storage image, GENERAL, exact width/height */
    uint32_t width;
    uint32_t height;
    const void* uniforms; /* copied synchronously; VelocityLiveUniforms, 368 B */
    uint32_t uniform_bytes;
    uint32_t reserved;
} BedrayVelocityFrameDesc;

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_velocity(
    BedrayCore*, const BedrayVelocityDesc*, BedrayError*);
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_velocity(
    BedrayCore*, const BedrayVelocityFrameDesc*, BedrayError*);
/* Refuses pending recordings; completion/discard uses the existing core API. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_velocity(BedrayCore*);

#ifdef __cplusplus
}
#include <cstddef>
static_assert(sizeof(BedrayVelocityDesc) == 32);
static_assert(sizeof(BedrayVelocityFrameDesc) == 56);
static_assert(offsetof(BedrayVelocityDesc, program) == 8);
static_assert(offsetof(BedrayVelocityDesc, nearest_sampler) == 16);
static_assert(offsetof(BedrayVelocityFrameDesc, command_buffer) == 8);
static_assert(offsetof(BedrayVelocityFrameDesc, width) == 32);
static_assert(offsetof(BedrayVelocityFrameDesc, uniforms) == 40);
static_assert(offsetof(BedrayVelocityFrameDesc, uniform_bytes) == 48);
#endif
