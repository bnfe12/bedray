/*
 * vkb_nrd.h - flat C API of the Bedray NRD wrapper (vkb_nrd.dll).
 *
 * The DLL drives NVIDIA Real-Time Denoisers (NRD) on a Vulkan device that the CALLER owns (the game's
 * VkDevice or a probe device). NRD itself is NOT linked into vkb_nrd.dll: NRD.dll is loaded at runtime from
 * the path given to vkb_nrd_init() (the user builds it with build_nrd.bat or supplies it).
 * Adapted from nvpro-samples/vk_denoise_nrd (Apache-2.0), see third_party/vk_denoise_nrd/NOTICE.
 *
 * FFM-friendly: plain C types, Vulkan handles as uint64_t, POD structs with a leading struct_size.
 * Threading: calls are serialised by an internal mutex; vkb_nrd_dispatch records into the VkCommandBuffer
 * and must be called from the thread that owns that command buffer.
 * Return convention: 0 == VKB_NRD_OK, everything else is an error, details via vkb_nrd_get_last_error_string().
 *
 * Contract for the caller:
 *  - the VkDevice must have shaderStorageImageReadWithoutFormat, shaderStorageImageWriteWithoutFormat and
 *    shaderStorageImageExtendedFormats ENABLED (see vkb_nrd_get_required_features);
 *  - every image passed in VkbNrdResource must be a 2D image in VK_IMAGE_LAYOUT_GENERAL with SAMPLED (IN_*) or
 *    STORAGE (OUT_*) usage; vkb_nrd_dispatch does not transition user images;
 *  - after vkb_nrd_dispatch the caller must re-bind its own pipeline / descriptors / push constants;
 *  - the caller must make the writes of the previous pass visible to compute-shader reads (IN_*) before the
 *    call and make the OUT_* writes visible to its consumer afterwards.
 */
#ifndef VKB_NRD_H
#define VKB_NRD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(VKB_NRD_BUILD)
#    define VKB_NRD_API __declspec(dllexport)
#  else
#    define VKB_NRD_API __declspec(dllimport)
#  endif
#else
#  define VKB_NRD_API
#endif

#define VKB_NRD_API_VERSION_MAJOR 0
#define VKB_NRD_API_VERSION_MINOR 1
#define VKB_NRD_API_VERSION_PATCH 0
#define VKB_NRD_API_VERSION \
    ((VKB_NRD_API_VERSION_MAJOR << 16) | (VKB_NRD_API_VERSION_MINOR << 8) | VKB_NRD_API_VERSION_PATCH)

typedef enum VkbNrdResult {
    VKB_NRD_OK                  = 0,
    VKB_NRD_ERR_INVALID_ARG     = 1,  /* null pointer, bad struct_size, bad enum, missing required resource */
    VKB_NRD_ERR_NOT_INITIALIZED = 2,
    VKB_NRD_ERR_ALREADY_INIT    = 3,
    VKB_NRD_ERR_LOAD            = 4,  /* NRD.dll missing / wrong version / wrong ABI */
    VKB_NRD_ERR_NRD             = 5,  /* an NRD call returned a failure code (see last error string) */
    VKB_NRD_ERR_VULKAN          = 6,  /* a Vulkan call failed or the device lacks a required capability */
    VKB_NRD_ERR_UNSUPPORTED     = 7
} VkbNrdResult;

/* nrd::ResourceType numbering of NRD 4.17 and 4.18 (checked by static_assert in vkb_nrd.cpp against NRD.h).
 * Index of VkbNrdResource slots. */
enum {
    VKB_NRD_RES_IN_MV = 0,
    VKB_NRD_RES_IN_NORMAL_ROUGHNESS = 1,
    VKB_NRD_RES_IN_VIEWZ = 2,
    VKB_NRD_RES_IN_DIFF_CONFIDENCE = 3,
    VKB_NRD_RES_IN_SPEC_CONFIDENCE = 4,
    VKB_NRD_RES_IN_DISOCCLUSION_THRESHOLD_MIX = 5,
    VKB_NRD_RES_IN_DIFF_RADIANCE_HITDIST = 6,
    VKB_NRD_RES_IN_SPEC_RADIANCE_HITDIST = 7,
    VKB_NRD_RES_IN_DIFF_HITDIST = 8,
    VKB_NRD_RES_IN_SPEC_HITDIST = 9,
    VKB_NRD_RES_IN_DIFF_DIRECTION_HITDIST = 10,
    VKB_NRD_RES_IN_DIFF_SH0 = 11,
    VKB_NRD_RES_IN_DIFF_SH1 = 12,
    VKB_NRD_RES_IN_SPEC_SH0 = 13,
    VKB_NRD_RES_IN_SPEC_SH1 = 14,
    VKB_NRD_RES_IN_PENUMBRA = 15,
    VKB_NRD_RES_IN_TRANSLUCENCY = 16,
    VKB_NRD_RES_IN_SIGNAL = 17,
    VKB_NRD_RES_OUT_DIFF_RADIANCE_HITDIST = 18,
    VKB_NRD_RES_OUT_SPEC_RADIANCE_HITDIST = 19,
    VKB_NRD_RES_OUT_DIFF_SH0 = 20,
    VKB_NRD_RES_OUT_DIFF_SH1 = 21,
    VKB_NRD_RES_OUT_SPEC_SH0 = 22,
    VKB_NRD_RES_OUT_SPEC_SH1 = 23,
    VKB_NRD_RES_OUT_DIFF_HITDIST = 24,
    VKB_NRD_RES_OUT_SPEC_HITDIST = 25,
    VKB_NRD_RES_OUT_DIFF_DIRECTION_HITDIST = 26,
    VKB_NRD_RES_OUT_SHADOW_TRANSLUCENCY = 27,
    VKB_NRD_RES_OUT_SIGNAL = 28,
    VKB_NRD_RES_OUT_VALIDATION = 29,
    VKB_NRD_RES_COUNT = 32 /* >= nrd::ResourceType::MAX_NUM; slots 30/31 (pools) are internal and must stay empty */
};

/* denoiser selection (bit mask for vkb_nrd_create_denoiser and vkb_nrd_dispatch) */
#define VKB_NRD_MODE_REBLUR_DIFFUSE_SPECULAR (1u << 0)
#define VKB_NRD_MODE_RELAX_DIFFUSE_SPECULAR  (1u << 1)
#define VKB_NRD_MODE_SIGMA_SHADOW            (1u << 2)
#define VKB_NRD_MODE_ALL_MASK                (0x7u)

/* device features the caller must enable on the VkDevice */
#define VKB_NRD_FEATURE_STORAGE_IMAGE_READ_WITHOUT_FORMAT  (1u << 0)
#define VKB_NRD_FEATURE_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT (1u << 1)
#define VKB_NRD_FEATURE_STORAGE_IMAGE_EXTENDED_FORMATS     (1u << 2)

/* VkbNrdFrameSettings.flags */
#define VKB_NRD_FRAME_MV_WORLD_SPACE       (1u << 0) /* IN_MV is 3D world-space motion, else 2D screen-space */
#define VKB_NRD_FRAME_ENABLE_VALIDATION    (1u << 1) /* needs OUT_VALIDATION */
#define VKB_NRD_FRAME_HISTORY_CONFIDENCE   (1u << 2) /* IN_DIFF/SPEC_CONFIDENCE provided */

/* VkbNrdFrameSettings.accumulation_mode (matches nrd::AccumulationMode) */
#define VKB_NRD_ACCUM_CONTINUE          0u
#define VKB_NRD_ACCUM_RESTART           1u
#define VKB_NRD_ACCUM_CLEAR_AND_RESTART 2u

/* ---- structs (sizes are static_assert-ed in vkb_nrd.cpp; update the FFM layout when they change) ---- */

typedef struct VkbNrdLibraryInfo {
    uint32_t struct_size;
    uint32_t version_major, version_minor, version_build;
    uint32_t normal_encoding;       /* nrd::NormalEncoding: 0 RGBA8_UNORM 1 RGBA8_SNORM 2 R10G10B10A2_UNORM 3 RGBA16_UNORM 4 RGBA16_SNORM */
    uint32_t roughness_encoding;    /* nrd::RoughnessEncoding: 0 SQ_LINEAR 1 LINEAR 2 SQRT_LINEAR */
    uint32_t normal_roughness_vk_format; /* VkFormat IN_NORMAL_ROUGHNESS must use */
    uint32_t spirv_offset_sampler, spirv_offset_texture, spirv_offset_constant_buffer, spirv_offset_storage;
} VkbNrdLibraryInfo;

typedef struct VkbNrdResource {
    uint64_t image;       /* VkImage (reserved for diagnostics) */
    uint64_t view;        /* VkImageView, 2D, whole image */
    uint32_t vk_format;   /* VkFormat of the view */
    uint32_t reserved;
} VkbNrdResource;

typedef struct VkbNrdCreateInfo {
    uint32_t struct_size;
    uint32_t width, height;         /* resource size of the pools = max render rectangle */
    uint32_t mode_mask;             /* VKB_NRD_MODE_* */
    uint32_t frames_in_flight;      /* descriptor pool ring size, 1..8 */
    uint32_t flags;                 /* reserved, 0 */
} VkbNrdCreateInfo;

typedef struct VkbNrdFrameSettings {
    uint32_t struct_size;
    uint32_t frame_index;           /* +1 per frame */
    uint32_t accumulation_mode;     /* VKB_NRD_ACCUM_* */
    uint32_t flags;                 /* VKB_NRD_FRAME_* */
    float view_to_clip[16];         /* column-major, vector is a column, NOT jittered */
    float view_to_clip_prev[16];
    float world_to_view[16];
    float world_to_view_prev[16];   /* must include the camera delta if the world moves with the camera */
    float mv_scale[3];              /* mv = IN_MV * scale + bias */
    float mv_bias[3];
    float jitter[2];                /* [-0.5, 0.5] pixels */
    float jitter_prev[2];
    uint32_t rect_width, rect_height;           /* active render rectangle (<= pool size) */
    uint32_t rect_width_prev, rect_height_prev;
    float view_z_scale;             /* 1.0 */
    float time_delta_ms;            /* 0 = tracked by NRD */
    float denoising_range;          /* viewZ >= range is ignored (sky) */
    float disocclusion_threshold;   /* 0.01 default */
} VkbNrdFrameSettings;

typedef struct VkbNrdReblurSettings {
    uint32_t struct_size;
    uint32_t max_accumulated_frame_num;      /* 30 default, <= 63 */
    uint32_t max_fast_accumulated_frame_num; /* 6 */
    uint32_t max_stabilized_frame_num;       /* 63 */
    float hit_dist_a, hit_dist_b, hit_dist_c;/* 3.0, 0.1, 20.0: MUST match the hit-distance normalisation of the packing shader */
    float min_blur_radius, max_blur_radius;  /* 1, 30 */
    float diffuse_prepass_blur_radius, specular_prepass_blur_radius; /* 30, 50 */
    float plane_distance_sensitivity;        /* 0.02 */
    uint32_t enable_anti_firefly;            /* 1 */
} VkbNrdReblurSettings;

typedef struct VkbNrdSigmaSettings {
    uint32_t struct_size;
    float light_direction[3];       /* direction TO the light, world space */
    float plane_distance_sensitivity; /* 0.02 */
    uint32_t max_stabilized_frame_num; /* 5; 0 disables stabilization (then IN_MV is not read by SIGMA) */
} VkbNrdSigmaSettings;

typedef struct VkbNrdDispatchStats {
    uint32_t struct_size;
    uint32_t dispatch_count;        /* vkCmdDispatch recorded */
    uint32_t clear_dispatch_count;  /* of them: NRD "Clear" passes */
    uint32_t descriptor_sets;       /* sets allocated from the frame pool */
    uint32_t constant_bytes;        /* bytes uploaded to the constant buffer */
    uint32_t pipeline_count;        /* pipelines in the instance */
    uint64_t pool_memory_bytes;     /* device memory of NRD's internal permanent+transient textures */
    float cpu_ms;                   /* CPU time spent in vkb_nrd_dispatch */
    uint32_t reserved;
} VkbNrdDispatchStats;

/* ---- API ------------------------------------------------------------------------------------- */
VKB_NRD_API uint32_t    vkb_nrd_get_api_version(void);
VKB_NRD_API const char* vkb_nrd_get_last_error_string(void);   /* thread-local, never NULL, UTF-8 */
VKB_NRD_API uint32_t    vkb_nrd_get_required_features(void);   /* VKB_NRD_FEATURE_* mask */

/* Loads NRD.dll (UTF-8 path), checks version/ABI, stores the Vulkan handles and loads the Vulkan entry points
 * through gipa / gdpa (PFN_vkGetInstanceProcAddr / PFN_vkGetDeviceProcAddr). Fills *out_info. */
VKB_NRD_API int32_t vkb_nrd_init(uint64_t instance, uint64_t physical_device, uint64_t device,
                                 void* gipa, void* gdpa, const char* nrd_dll_path, VkbNrdLibraryInfo* out_info);

/* Creates an NRD instance for the selected denoisers, the internal texture pools (pool images are
 * transitioned/cleared lazily inside the first vkb_nrd_dispatch command buffer), the pipelines, samplers
 * and the constant buffer. */
VKB_NRD_API int32_t vkb_nrd_create_denoiser(const VkbNrdCreateInfo* info, uint64_t* out_handle);
/* Recreates the pools for a new size. The caller must make sure the device is idle (no work in flight).
 * History is reset (RESTART forced on the next dispatch). */
VKB_NRD_API int32_t vkb_nrd_resize(uint64_t handle, uint32_t width, uint32_t height);
VKB_NRD_API int32_t vkb_nrd_set_frame_settings(uint64_t handle, const VkbNrdFrameSettings* s);
VKB_NRD_API int32_t vkb_nrd_set_reblur_settings(uint64_t handle, const VkbNrdReblurSettings* s);
VKB_NRD_API int32_t vkb_nrd_set_sigma_settings(uint64_t handle, const VkbNrdSigmaSettings* s);
/* STUB: RELAX uses the NRD defaults, no tuning API yet. */

/* Records all dispatches of the denoisers in mode_mask into cmd. resources[VKB_NRD_RES_COUNT] is indexed by
 * VKB_NRD_RES_*; an input/output that the selected denoisers need but that is empty -> VKB_NRD_ERR_INVALID_ARG.
 * Each call advances the descriptor-pool ring by one slot. */
VKB_NRD_API int32_t vkb_nrd_dispatch(uint64_t handle, uint64_t command_buffer, uint32_t mode_mask,
                                     const VkbNrdResource* resources, VkbNrdDispatchStats* out_stats);

VKB_NRD_API void    vkb_nrd_destroy(uint64_t handle);   /* caller: device idle */
VKB_NRD_API void    vkb_nrd_shutdown(void);             /* unloads NRD.dll; destroys remaining denoisers */

#ifdef __cplusplus
}
#endif
#endif /* VKB_NRD_H */
