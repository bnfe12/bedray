#ifndef BEDRAY_GPU_IMAGES_C_H
#define BEDRAY_GPU_IMAGES_C_H

#include "bedray/gpu_buffers_c.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BEDRAY_GPU_IMAGE_ABI_VERSION 1u
#define BEDRAY_GPU_IMAGE_FUNCTION_COUNT 9u
enum {
    BEDRAY_GPU_CREATE_IMAGE = 0, BEDRAY_GPU_DESTROY_IMAGE = 1,
    BEDRAY_GPU_IMAGE_REQUIREMENTS = 2, BEDRAY_GPU_IMAGE_ALLOCATE = 3,
    BEDRAY_GPU_IMAGE_FREE = 4, BEDRAY_GPU_IMAGE_BIND = 5,
    BEDRAY_GPU_CREATE_IMAGE_VIEW = 6, BEDRAY_GPU_DESTROY_IMAGE_VIEW = 7,
    BEDRAY_GPU_IMAGE_FORMAT_PROPERTIES = 8
};

typedef struct BedrayGpuImageOwner BedrayGpuImageOwner;
typedef struct BedrayGpuImageOwnerDesc {
    uint32_t abi_version, struct_size;
    uint64_t physical_device, device, generation, budget_bytes;
    uint32_t enabled_flags, memory_type_count;
    uint32_t memory_type_flags[BEDRAY_GPU_MAX_MEMORY_TYPES];
    uint64_t functions[BEDRAY_GPU_IMAGE_FUNCTION_COUNT];
} BedrayGpuImageOwnerDesc;

typedef struct BedrayGpuImageInfo {
    uint64_t owner_generation, serial, image, view, allocation_bytes;
    uint32_t format, width, height;
} BedrayGpuImageInfo;

typedef struct BedrayGpuImageAbiInfo {
    uint32_t version, sizeof_info;
    uint32_t sizeof_owner_desc, align_owner_desc, owner_offsets[10];
    uint32_t sizeof_image_info, align_image_info, image_offsets[8];
} BedrayGpuImageAbiInfo;

/* Host owns the existing device, its function addresses and GPU completion.
 * Images start UNDEFINED; this owner records no transitions. Descriptor
 * generation must be unique among all image owners for the process lifetime.
 * All operations are serialized on the render thread. Formats and Vulkan flags
 * retain their numeric values. Only one-mip/layer/sample optimal 2D colour
 * images in RGBA16F/RGBA32F/RGBA8_UNORM/R32F are supported initially.
 * Offset arrays follow the field declaration order. */
BEDRAY_GPU_API uint32_t BEDRAY_GPU_CALL bedray_gpu_image_abi_version(void);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_abi_info(BedrayGpuImageAbiInfo*, uint32_t out_size);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_owner_create(const BedrayGpuImageOwnerDesc*, BedrayGpuImageOwner**);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_owner_destroy(BedrayGpuImageOwner*);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_create(BedrayGpuImageOwner*, uint32_t width, uint32_t height,
    uint32_t format, uint32_t usage, uint32_t required_memory, uint32_t preferred_memory,
    BedrayGpuImageInfo* out, int32_t* vk_result);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_view(BedrayGpuImageOwner*, uint64_t generation, uint64_t serial,
    BedrayGpuImageInfo* out);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_use_begin(BedrayGpuImageOwner*, uint64_t generation,
    uint64_t serial, BedrayGpuUseToken* out);
/* Acknowledges proven discard or completion of ALL uses, with no resubmission. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_use_complete(BedrayGpuImageOwner*, uint64_t generation, uint64_t serial);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_retire(BedrayGpuImageOwner*, uint64_t generation, uint64_t serial);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_collect(BedrayGpuImageOwner*);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_resident_bytes(BedrayGpuImageOwner*, uint64_t* out);
/* Host proof: device idle AND all covered recordings cannot be resubmitted. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_device_idle(BedrayGpuImageOwner*);
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_device_lost(BedrayGpuImageOwner*);
/* Quiesced loss teardown, before VkDevice destruction; no normal completion is asserted. */
BEDRAY_GPU_API BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_release_after_device_loss(BedrayGpuImageOwner*);

#ifdef __cplusplus
}
#endif
#endif
