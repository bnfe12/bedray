#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Additive ABI. Inputs are consumed synchronously. Program and GENERAL views
 * are borrowed; the host retains them until exact completion/discard proof.
 * layer_view: sampled RGBA8 premultiplied particle/weather layer.
 * output_view: RGBA8 storage image, same extent, distinct from layer_view. */
typedef struct BedrayParticlesFrameDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint64_t program; /* particles_composite: 8x8x1, push 16 B, bindings 0/2 */
    uint64_t layer_view;
    uint64_t output_view;
    uint32_t width;
    uint32_t height;
    float strength;
    uint32_t reserved; /* zero */
} BedrayParticlesFrameDesc;

/* Owns entry/exit barriers and descriptor/push packing. Uses the core's existing
 * immutable per-dispatch descriptors and completion/discard calls. No private
 * images, configuration owner, submission or waits are needed for this pass.
 * On failure the host must discard the entire command-buffer recording. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_particles(
    BedrayCore*, const BedrayParticlesFrameDesc*, BedrayError*);

#ifdef __cplusplus
}
#include <cstddef>
static_assert(sizeof(BedrayParticlesFrameDesc) == 56);
static_assert(offsetof(BedrayParticlesFrameDesc, command_buffer) == 8);
static_assert(offsetof(BedrayParticlesFrameDesc, program) == 16);
static_assert(offsetof(BedrayParticlesFrameDesc, layer_view) == 24);
static_assert(offsetof(BedrayParticlesFrameDesc, output_view) == 32);
static_assert(offsetof(BedrayParticlesFrameDesc, width) == 40);
static_assert(offsetof(BedrayParticlesFrameDesc, strength) == 48);
#endif
