#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Additive producer-only ABI. Images/views stay GENERAL. The host retains all
 * inputs through exact command completion/discard; output ownership also needs
 * the independent asynchronous vendor-consumer lifetime. Releasing a core
 * recording is not evidence that Streamline/FFX finished using these outputs.
 * Native owns immutable descriptors, push data, barriers and flipped blit. */
typedef struct BedrayFgPrepareFrameDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint64_t program;
    uint64_t sampler;
    uint64_t source_color_image;
    uint64_t hudless_image;
    uint64_t depth_view;
    uint64_t input_motion_view;
    uint64_t output_depth_view;
    uint64_t output_motion_view;
    uint32_t window_width;
    uint32_t window_height;
    uint32_t render_width;
    uint32_t render_height;
    uint64_t reserved;
} BedrayFgPrepareFrameDesc;

/* On recording failure, discard the whole command buffer. Never submits/waits. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_fg_prepare(
    BedrayCore*, const BedrayFgPrepareFrameDesc*, BedrayError*);

#ifdef __cplusplus
}
#include <cstddef>
static_assert(sizeof(BedrayFgPrepareFrameDesc) == 104);
static_assert(offsetof(BedrayFgPrepareFrameDesc, command_buffer) == 8);
static_assert(offsetof(BedrayFgPrepareFrameDesc, sampler) == 24);
static_assert(offsetof(BedrayFgPrepareFrameDesc, source_color_image) == 32);
static_assert(offsetof(BedrayFgPrepareFrameDesc, depth_view) == 48);
static_assert(offsetof(BedrayFgPrepareFrameDesc, window_width) == 80);
static_assert(offsetof(BedrayFgPrepareFrameDesc, reserved) == 96);
#endif
