#pragma once
#include "bedray_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Additive stateless decode ABI; existing ABI-v2 layouts remain unchanged.
 * All addresses are borrowed on the host device. The host guarantees backing
 * ranges of raw=80, arena=112 and optional flags=4 bytes per quad and retains
 * them through exact recording completion/discard. Ranges must not overlap.
 * The registered sodium_dequantize program is 64x1x1, push32, no descriptors. */
typedef struct BedraySodiumDecodeDesc {
    uint32_t abi_version;
    uint32_t struct_size;
    uint64_t command_buffer;
    uint64_t program;
    uint64_t raw_address;
    uint64_t arena_address;
    uint64_t flags_address; /* zero means no per-quad flags */
    uint32_t quad_count;
    uint32_t reserved; /* zero */
} BedraySodiumDecodeDesc;

/* Owns push/group packing and entry/exit memory barriers. The existing core
 * recording retains its pipeline until release/discard. On failure discard the
 * whole command buffer; no submission, wait or resource allocation is introduced. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_decode_sodium(
    BedrayCore*, const BedraySodiumDecodeDesc*, BedrayError*);

#ifdef __cplusplus
}
#include <cstddef>
static_assert(sizeof(BedraySodiumDecodeDesc) == 56);
static_assert(offsetof(BedraySodiumDecodeDesc, command_buffer) == 8);
static_assert(offsetof(BedraySodiumDecodeDesc, program) == 16);
static_assert(offsetof(BedraySodiumDecodeDesc, raw_address) == 24);
static_assert(offsetof(BedraySodiumDecodeDesc, arena_address) == 32);
static_assert(offsetof(BedraySodiumDecodeDesc, flags_address) == 40);
static_assert(offsetof(BedraySodiumDecodeDesc, quad_count) == 48);
static_assert(offsetof(BedraySodiumDecodeDesc, reserved) == 52);
#endif
