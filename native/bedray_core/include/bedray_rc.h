#pragma once
#include "bedray_core.h"
#ifdef __cplusplus
extern "C" {
#endif
/* Additive opt-in finite-window diffuse RC. Configure the ordinary native path
 * first with rc_path_trace_scene as its scene program, then configure this owner.
 * Native scene only. No sky boundary/history is fabricated. Frame ABI unchanged. */
typedef struct BedrayRCDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t program_build; /* rc_field_build_scene, 64x1x1, push80, 16 bindings */
    uint32_t base_axis;     /* power of two, 8..32; five haloed levels */
    uint32_t reserved;
    uint64_t buffer_budget_bytes; /* 0 selects 192 MiB across outstanding recordings */
} BedrayRCDesc;
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_radiance_cascades(
    BedrayCore*, const BedrayRCDesc*, BedrayError*);
#ifdef __cplusplus
}
static_assert(sizeof(BedrayRCDesc) == 32);
#endif
