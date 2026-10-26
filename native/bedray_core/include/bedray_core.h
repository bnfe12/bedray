/*
 * Bedray native renderer core — stable C ABI.
 *
 * The Java host owns the Minecraft/Sodium lifecycle, the VkInstance/VkDevice/queue
 * and the command buffer. This library never creates a device, a swapchain or a
 * queue, never submits work and never waits for the device. It records compute
 * dispatches into the command buffer supplied by the host.
 *
 * ABI rules:
 *   - every structure is versioned and has a fixed layout;
 *   - no C++ types, STL containers or exceptions cross this boundary;
 *   - all Vulkan handles are passed as uint64_t;
 *   - the host keeps every resource alive until the recorded command buffer has
 *     completed on the GPU.
 */

#ifndef BEDRAY_CORE_H
#define BEDRAY_CORE_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  if defined(BEDRAY_CORE_BUILD)
#    define BEDRAY_API __declspec(dllexport)
#  else
#    define BEDRAY_API __declspec(dllimport)
#  endif
#  define BEDRAY_CALL __cdecl
#else
#  define BEDRAY_API __attribute__((visibility("default")))
#  define BEDRAY_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define BEDRAY_CORE_ABI_VERSION 2u
#define BEDRAY_ERROR_MESSAGE_BYTES 1024u

typedef struct BedrayCore BedrayCore;

typedef enum BedrayStatus {
    BEDRAY_OK = 0,
    BEDRAY_ERROR_INVALID_ARGUMENT = 1,
    BEDRAY_ERROR_UNSUPPORTED = 2,
    BEDRAY_ERROR_VULKAN = 3,
    BEDRAY_ERROR_OUT_OF_MEMORY = 4,
    BEDRAY_ERROR_DEVICE_LOST = 5,
    BEDRAY_ERROR_ABI_MISMATCH = 6,
    BEDRAY_ERROR_INTERNAL = 7,
    BEDRAY_ERROR_IN_USE = 8
} BedrayStatus;

/* Capability bits are negotiated by the Java host and verified by the core. */
typedef enum BedrayCapability {
    BEDRAY_CAP_RAY_QUERY = 1u << 0,
    BEDRAY_CAP_ACCELERATION_STRUCTURE = 1u << 1,
    BEDRAY_CAP_BUFFER_DEVICE_ADDRESS = 1u << 2,
    BEDRAY_CAP_DESCRIPTOR_INDEXING = 1u << 3,
    BEDRAY_CAP_SYNCHRONIZATION2 = 1u << 4,
    BEDRAY_CAP_TIMELINE_SEMAPHORE = 1u << 5,
    BEDRAY_CAP_DYNAMIC_RENDERING = 1u << 6,
    BEDRAY_CAP_OPACITY_MICROMAP = 1u << 7,
    BEDRAY_CAP_DESCRIPTOR_BUFFER = 1u << 8,
    /* Needed by SPIR-V that does 64-bit integer work, including physical-storage-buffer
       addressing (the legacy section table stores device addresses). */
    BEDRAY_CAP_SHADER_INT64 = 1u << 9
} BedrayCapability;

typedef struct BedrayError {
    BedrayStatus status;
    char message[BEDRAY_ERROR_MESSAGE_BYTES];
} BedrayError;

typedef struct BedrayAbiInfo {
    uint32_t abi_version;
    uint32_t sizeof_core_desc;
    uint32_t sizeof_capabilities;
    uint32_t sizeof_program_desc;
    uint32_t sizeof_frame_desc;
    uint32_t sizeof_resource;
    uint32_t sizeof_error;
    uint32_t reserved;
} BedrayAbiInfo;

typedef struct BedrayCoreDesc {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t instance;
    uint64_t physical_device;
    uint64_t device;
    uint64_t queue;
    uint32_t queue_family;
    uint32_t api_version;
    /* Addresses of PFN_vkGetInstanceProcAddr and PFN_vkGetDeviceProcAddr. */
    uint64_t get_instance_proc_addr;
    uint64_t get_device_proc_addr;
    /* Enabled capability bits, computed by the host from its RenderPlan. */
    uint32_t capabilities;
    uint32_t reserved;
} BedrayCoreDesc;

typedef struct BedrayCapabilities {
    uint32_t abi_version;
    uint32_t flags;
    uint32_t max_push_constants;
    uint32_t max_compute_work_group_count_x;
    uint32_t max_compute_work_group_count_y;
    uint32_t max_compute_work_group_count_z;
    uint32_t max_compute_work_group_invocations;
    uint32_t reserved;
    uint64_t max_storage_buffer_range;
    uint64_t max_uniform_buffer_range;
    uint64_t device_memory_bytes;
} BedrayCapabilities;

typedef struct BedrayDescriptorBinding {
    uint32_t binding;
    /* VkDescriptorType value. */
    uint32_t descriptor_type;
    uint32_t descriptor_count;
    /* VkShaderStageFlags value. */
    uint32_t stage_flags;
} BedrayDescriptorBinding;

typedef struct BedrayProgramDesc {
    uint32_t abi_version;
    uint32_t flags;
    /* UTF-8, null-terminated. */
    const char* name;
    /* UTF-8, null-terminated. */
    const char* entry_point;
    const uint32_t* spirv;
    uint64_t spirv_word_count;
    const BedrayDescriptorBinding* bindings;
    uint32_t binding_count;
    uint32_t push_constant_bytes;
} BedrayProgramDesc;

typedef struct BedrayResource {
    uint32_t binding;
    /* VkDescriptorType value. Must match the registered program. */
    uint32_t descriptor_type;
    /* VkImageLayout value for image descriptors. */
    uint32_t image_layout;
    uint32_t reserved;
    /* VkBuffer, VkImageView or VkAccelerationStructureKHR. */
    uint64_t handle;
    /* VkSampler for combined image samplers, otherwise 0. */
    uint64_t sampler;
    uint64_t offset;
    uint64_t range;
} BedrayResource;

typedef struct BedrayFrameDesc {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t command_buffer;
    uint64_t program;
    uint32_t group_count_x;
    uint32_t group_count_y;
    uint32_t group_count_z;
    uint32_t resource_count;
    const BedrayResource* resources;
    const void* push_constants;
    uint32_t push_constant_bytes;
    uint32_t reserved;
} BedrayFrameDesc;

BEDRAY_API uint32_t BEDRAY_CALL bedray_core_abi_version(void);
BEDRAY_API void BEDRAY_CALL bedray_core_abi_info(BedrayAbiInfo* out_info);

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_create(
    const BedrayCoreDesc* desc,
    BedrayCore** out_core,
    BedrayError* error);

/* Refuses destruction while recordings remain registered. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy(BedrayCore* core);

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_query_capabilities(
    BedrayCore* core,
    BedrayCapabilities* out_capabilities);

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_register_program(
    BedrayCore* core,
    const BedrayProgramDesc* desc,
    uint64_t* out_program,
    BedrayError* error);

BEDRAY_API void BEDRAY_CALL bedray_core_destroy_program(
    BedrayCore* core,
    uint64_t program);

/* Release every use recorded into this command buffer ONLY after all its GPU
 * submissions completed and it will not be resubmitted, or after an unsubmitted
 * recording was discarded/reset. Call before re-recording the same handle.
 * The caller must externally synchronize these operations on the render thread.
 * destroy_program defers destruction while recordings still reference it.
 * Before core_destroy, the host must ensure all recordings are no longer in use.
 */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_release_recording(
    BedrayCore* core, uint64_t command_buffer);

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(
    BedrayCore* core,
    const BedrayFrameDesc* desc,
    BedrayError* error);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* BEDRAY_CORE_H */
