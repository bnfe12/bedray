#ifndef BEDRAY_CORE_MEMORY_H
#define BEDRAY_CORE_MEMORY_H
#include "bedray_core.h"
#ifdef __cplusplus
extern "C" {
#endif

/* Additive interface; ABI-v2 layouts are unchanged. One configured core/service
 * owns the native allocation budget of one host device. No foreign VMA handle. */
typedef struct BedrayMemoryStats {
    uint64_t limit_bytes, reserved_bytes, used_bytes, peak_reserved_bytes;
    uint64_t allocation_count, block_count, heap_budget_bytes, heap_usage_bytes, flags;
} BedrayMemoryStats;
enum { BEDRAY_MEMORY_HEAP_BUDGET = 1u };
enum { BEDRAY_MEMORY_BUFFERS = 0u, BEDRAY_MEMORY_IMAGES = 1u, BEDRAY_MEMORY_SCENE = 2u };

BEDRAY_API uint32_t BEDRAY_CALL bedray_core_memory_version(void);
/* Once only, before registering programs or attaching/allocating resources.
 * HEAP_BUDGET means the host actually enabled VK_EXT_memory_budget. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_memory_configure(
    BedrayCore*, uint64_t limit_bytes, uint64_t block_bytes, uint32_t flags, BedrayError*);
/* An empty owner from this DLL and this VkDevice; kind must match its creation API.
 * Copies shared ownership; does not retain a borrowed C ABI pointer to the core. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_memory_attach(
    BedrayCore*, uint32_t kind, void* owner, BedrayError*);
/* Used/reserved include every retained generation until its exact release proof.
 * Heap budget/usage are process-wide estimates, zero unless flags&HEAP_BUDGET. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_memory_stats(
    BedrayCore*, BedrayMemoryStats* out, uint32_t out_size, BedrayError*);
#ifdef __cplusplus
}
#endif
#endif
