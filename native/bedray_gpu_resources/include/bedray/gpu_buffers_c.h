#ifndef BEDRAY_GPU_BUFFERS_C_H
#define BEDRAY_GPU_BUFFERS_C_H

#include <stdint.h>

/* For standalone static consumers no import macro is needed. When this source
 * is deliberately compiled into a DLL, define BEDRAY_GPU_RESOURCES_EXPORTS on
 * that target. Linking a static archive alone does not guarantee exports. */
#if defined(_WIN32) && defined(BEDRAY_GPU_RESOURCES_EXPORTS)
#  define BEDRAY_GPU_API __declspec(dllexport)
#elif !defined(_WIN32)
#  define BEDRAY_GPU_API __attribute__((visibility("default")))
#else
#  define BEDRAY_GPU_API
#endif
#if defined(_WIN32)
#  define BEDRAY_GPU_CALL __cdecl
#else
#  define BEDRAY_GPU_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define BEDRAY_GPU_ABI_VERSION 1u
#define BEDRAY_GPU_MAX_MEMORY_TYPES 32u
#define BEDRAY_GPU_FUNCTION_COUNT 11u

/* Addresses are the actual enabled-device dispatch functions, not names or
 * offsets. Address slot 10 may be zero if buffer device address is disabled. */
enum {
    BEDRAY_GPU_CREATE_BUFFER = 0, BEDRAY_GPU_DESTROY_BUFFER = 1,
    BEDRAY_GPU_GET_REQUIREMENTS = 2, BEDRAY_GPU_ALLOCATE_MEMORY = 3,
    BEDRAY_GPU_FREE_MEMORY = 4, BEDRAY_GPU_BIND_MEMORY = 5,
    BEDRAY_GPU_MAP_MEMORY = 6, BEDRAY_GPU_UNMAP_MEMORY = 7,
    BEDRAY_GPU_FLUSH = 8, BEDRAY_GPU_INVALIDATE = 9,
    BEDRAY_GPU_GET_BUFFER_ADDRESS = 10
};
enum {
    BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED = 1u << 0,
    BEDRAY_GPU_DEVICE_ADDRESS_ENABLED = 1u << 1
};
typedef uint32_t BedrayGpuStatus;
enum {
    BEDRAY_GPU_OK = 0, BEDRAY_GPU_INVALID_ARGUMENT = 1,
    BEDRAY_GPU_UNAVAILABLE = 2, BEDRAY_GPU_UNKNOWN_BUFFER = 3,
    BEDRAY_GPU_UNKNOWN_USE = 4, BEDRAY_GPU_IN_USE = 5,
    BEDRAY_GPU_RETIRED = 6, BEDRAY_GPU_BUDGET_EXCEEDED = 7,
    BEDRAY_GPU_UNSUPPORTED = 8, BEDRAY_GPU_EXHAUSTED = 9,
    BEDRAY_GPU_OUT_OF_MEMORY = 10, BEDRAY_GPU_DEVICE_LOST = 11,
    BEDRAY_GPU_VULKAN_ERROR = 12, BEDRAY_GPU_UNKNOWN_IMAGE = 13, BEDRAY_GPU_ABI_MISMATCH = 0x100,
    /* Wrapper failure; not a VkResult. Existing status values/layouts are unchanged. */
    BEDRAY_GPU_INTERNAL_ERROR = 0x101
};

typedef struct BedrayGpuOwner BedrayGpuOwner;

/* All function and handle values are native 64-bit process addresses. Memory
 * flags and Vulkan usage/feature bits have their Vk numeric values. The host
 * must supply actual device properties; falsifying them can cause invalid
 * Vulkan operations. Generation must never be reused by any owner in-process. */
typedef struct BedrayGpuOwnerDesc {
    uint32_t abi_version, struct_size;
    uint64_t device, generation, budget_bytes, non_coherent_atom_size;
    uint32_t enabled_flags, memory_type_count;
    uint32_t memory_type_flags[BEDRAY_GPU_MAX_MEMORY_TYPES];
    uint64_t functions[BEDRAY_GPU_FUNCTION_COUNT];
} BedrayGpuOwnerDesc;

typedef struct BedrayGpuBufferInfo {
    uint64_t owner_generation, serial, buffer, size, device_address;
    uint32_t memory_flags;
} BedrayGpuBufferInfo;

typedef struct BedrayGpuUseToken {
    uint64_t owner_generation, serial;
} BedrayGpuUseToken;

typedef struct BedrayGpuAbiInfo {
    uint32_t abi_version, sizeof_info;
    uint32_t sizeof_owner_desc, align_owner_desc, owner_offsets[10];
    uint32_t sizeof_buffer_info, align_buffer_info, buffer_offsets[6];
    uint32_t sizeof_use_token, align_use_token, use_offsets[2];
} BedrayGpuAbiInfo;
/* Offset arrays follow declaration order, including grouped declarations.
 * Array offsets identify the first element; memory flags stride=4, functions
 * stride=8. Consumers must check sizes, alignment and every used offset before
 * creating an owner; natural layout is not a cross-platform promise. */

BEDRAY_GPU_API uint32_t BEDRAY_GPU_CALL bedray_gpu_abi_version(void);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_abi_info(BedrayGpuAbiInfo* out, uint32_t out_size);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_owner_create(const BedrayGpuOwnerDesc* desc, BedrayGpuOwner** out);
/* Refuses while any resource or use remains, or while a lost-device owner has
 * not undergone its explicit teardown. Does not destroy the device or wait. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_owner_destroy(BedrayGpuOwner* owner);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_create(BedrayGpuOwner*, uint64_t size, uint32_t usage,
    uint32_t required_memory, uint32_t preferred_memory, uint32_t mapped, BedrayGpuBufferInfo* out, int32_t* vk_result);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_view(BedrayGpuOwner*, uint64_t generation, uint64_t serial,
    BedrayGpuBufferInfo* out);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_write(BedrayGpuOwner*, uint64_t generation, uint64_t serial,
    uint64_t offset, const void* data, uint64_t bytes, int32_t* vk_result);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_read(BedrayGpuOwner*, uint64_t generation, uint64_t serial,
    uint64_t offset, void* data, uint64_t bytes, int32_t* vk_result);
/* Copies the bytes into a native upload-pool slot and reserves its use before
 * returning. Complete the returned token under the same recording proof as
 * use_begin. The pool retains its slots until uploads_retire + collect. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_upload(BedrayGpuOwner*, uint32_t usage,
    const void* data, uint64_t bytes, BedrayGpuBufferInfo* out, BedrayGpuUseToken* use, int32_t* vk_result);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_uploads_retire(BedrayGpuOwner*);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_use_begin(BedrayGpuOwner*, uint64_t generation, uint64_t serial,
    BedrayGpuUseToken* out);
/* Only call after proving discard before submit, or completion of all submits
 * with no possibility of resubmit. Unknown outcome must remain pinned. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_use_complete(BedrayGpuOwner*, uint64_t generation, uint64_t serial);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_retire(BedrayGpuOwner*, uint64_t generation, uint64_t serial);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_collect(BedrayGpuOwner*);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_resident_bytes(BedrayGpuOwner*, uint64_t* out);
/* The host supplies proof of idle and no future submissions for all uses. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_device_idle(BedrayGpuOwner*);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_device_lost(BedrayGpuOwner*);
/* Only after quiescing all device callers, before host destroys the device. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_release_after_device_loss(BedrayGpuOwner*);

#ifdef __cplusplus
}
#endif
#endif
