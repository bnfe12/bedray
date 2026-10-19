/*
 * vkb_dlss.h - flat C API of the Bedray DLSS wrapper (vkb_dlss.dll).
 *
 * The DLL wraps the NVIDIA NGX SDK (Vulkan backend). It is meant to be called from Java
 * through the FFM API (java.lang.foreign), therefore:
 *   - only plain C types; Vulkan handles are passed as uint64_t (dispatchable handles are
 *     pointer values, non-dispatchable handles are 64-bit values on x64);
 *   - structs are POD with explicit padding and a leading struct_size field;
 *   - all functions use the default x64 calling convention.
 *
 * Threading: calls are serialised by an internal mutex, but NGX expects all calls that
 * record into a command buffer to come from the thread that owns that command buffer.
 *
 * Return value convention: 0 == VKB_DLSS_OK, everything else is an error; details via
 * vkb_dlss_get_last_error_string() / vkb_dlss_get_last_ngx_result() (thread-local).
 */
#ifndef VKB_DLSS_H
#define VKB_DLSS_H

#include <stdint.h>
#include <wchar.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(VKB_DLSS_BUILD)
#    define VKB_DLSS_API __declspec(dllexport)
#  else
#    define VKB_DLSS_API __declspec(dllimport)
#  endif
#else
#  define VKB_DLSS_API
#endif

/* ---- API version: (major << 16) | (minor << 8) | patch ------------------------------------ */
#define VKB_DLSS_API_VERSION_MAJOR 0
#define VKB_DLSS_API_VERSION_MINOR 1
#define VKB_DLSS_API_VERSION_PATCH 0
#define VKB_DLSS_API_VERSION \
    ((VKB_DLSS_API_VERSION_MAJOR << 16) | (VKB_DLSS_API_VERSION_MINOR << 8) | VKB_DLSS_API_VERSION_PATCH)

/* ---- result codes -------------------------------------------------------------------------- */
typedef enum VkbDlssResult {
    VKB_DLSS_OK                   = 0,
    VKB_DLSS_ERR_INVALID_ARG      = 1,  /* null pointer, bad struct_size, bad enum */
    VKB_DLSS_ERR_NOT_INITIALIZED  = 2,  /* vkb_dlss_init was not called / failed */
    VKB_DLSS_ERR_ALREADY_INIT     = 3,
    VKB_DLSS_ERR_NGX              = 4,  /* NGX call failed, code in vkb_dlss_get_last_ngx_result() */
    VKB_DLSS_ERR_UNSUPPORTED      = 5,  /* NGX initialised but DLSS SR is not available on this system */
    VKB_DLSS_ERR_BUFFER_TOO_SMALL = 6,
    VKB_DLSS_NOT_IMPLEMENTED      = 100 /* STUB: Ray Reconstruction / Frame Generation, see below */
} VkbDlssResult;

/* ---- quality modes (own numbering, mapped to NVSDK_NGX_PerfQuality_Value inside) --------- */
typedef enum VkbDlssQualityMode {
    VKB_DLSS_MODE_ULTRA_PERFORMANCE = 0,
    VKB_DLSS_MODE_MAX_PERFORMANCE   = 1,
    VKB_DLSS_MODE_BALANCED          = 2,
    VKB_DLSS_MODE_MAX_QUALITY       = 3,
    VKB_DLSS_MODE_ULTRA_QUALITY     = 4,
    VKB_DLSS_MODE_DLAA              = 5,
    VKB_DLSS_MODE_COUNT             = 6
} VkbDlssQualityMode;

/* ---- feature creation flags ---------------------------------------------------------------- */
#define VKB_DLSS_FLAG_MV_LOW_RES      (1u << 0) /* motion vectors are at render resolution */
#define VKB_DLSS_FLAG_IS_HDR          (1u << 1) /* input color is HDR */
#define VKB_DLSS_FLAG_DEPTH_INVERTED  (1u << 2) /* reversed-Z */
#define VKB_DLSS_FLAG_AUTO_EXPOSURE   (1u << 3) /* DLSS computes exposure itself (no exposure texture) */
#define VKB_DLSS_FLAG_MV_JITTERED     (1u << 4) /* motion vectors include jitter */
#define VKB_DLSS_FLAG_SHARPEN         (1u << 5) /* deprecated by NVIDIA (ignored by recent DLSS) */
#define VKB_DLSS_FLAG_ALPHA_UPSCALING (1u << 6)

/* ---- generic helpers ------------------------------------------------------------------------ */
VKB_DLSS_API uint32_t    vkb_dlss_get_api_version(void);              /* VKB_DLSS_API_VERSION */
VKB_DLSS_API uint32_t    vkb_dlss_get_ngx_api_version(void);          /* NVSDK_NGX_Version_API the DLL was built with */
VKB_DLSS_API const char* vkb_dlss_get_last_error_string(void);        /* thread-local, never NULL, UTF-8 */
VKB_DLSS_API uint32_t    vkb_dlss_get_last_ngx_result(void);          /* thread-local, NVSDK_NGX_Result of the last NGX failure (0 if none) */
VKB_DLSS_API const char* vkb_dlss_result_to_string(int32_t vkbResult);

/* Optional sink for NGX log lines. level: 0 off, 1 on, 2 verbose. Pass NULL to remove. */
typedef void (*VkbDlssLogCallback)(const char* message, int32_t level);
VKB_DLSS_API void vkb_dlss_set_log_callback(VkbDlssLogCallback cb);

/* ---- required Vulkan extensions ------------------------------------------------------------- */
/*
 * Must be called BEFORE vkCreateInstance / vkCreateDevice so the host can enable what NGX needs
 * (NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements / ...DeviceExtensionRequirements).
 * featurePath: folder containing nvngx_dlss.dll (wide string, may be NULL = application dir).
 * dataPath:    writable folder for NGX logs (may be NULL = same as featurePath).
 * On success *outCount / *outNames describe an array of UTF-8 names owned by the wrapper,
 * valid until the next call of the same function.
 */
VKB_DLSS_API int32_t vkb_dlss_get_required_instance_extensions(
    const wchar_t* featurePath, const wchar_t* dataPath,
    uint32_t* outCount, const char* const** outNames);

/* instance/physicalDevice: valid Vulkan handles; the instance must be created with the extensions above. */
VKB_DLSS_API int32_t vkb_dlss_get_required_device_extensions(
    uint64_t instance, uint64_t physicalDevice,
    const wchar_t* featurePath, const wchar_t* dataPath,
    uint32_t* outCount, const char* const** outNames);

/* ---- init / capabilities -------------------------------------------------------------------- */
typedef struct VkbDlssCaps {
    uint32_t struct_size;                  /* in: sizeof(VkbDlssCaps) */
    uint32_t super_sampling_available;     /* 1 if DLSS SR can be created */
    uint32_t needs_updated_driver;         /* 1 if the driver is too old */
    uint32_t min_driver_major;             /* NVIDIA-reported minimum driver (0 if unknown) */
    uint32_t min_driver_minor;
    uint32_t feature_init_result;          /* SuperSampling.FeatureInitResult (NGX_Result, 0 if unknown) */
    uint32_t ngx_result_init;              /* NGX_Result of NVSDK_NGX_VULKAN_Init_with_ProjectID */
    uint32_t reserved;
} VkbDlssCaps;

/*
 * Initialises NGX for the given Vulkan device and fills `outCaps`.
 * projectId: UTF-8 GUID-like string identifying the app, NULL = built-in default.
 * engineVersion: UTF-8 string, NULL = "26.3".
 * featurePath: folder with nvngx_dlss.dll. logPath: writable folder for NGX logs.
 * If NGX init succeeds, returns VKB_DLSS_OK and outCaps is filled with the initial capabilities.
 * If the GPU driver is too old, returns VKB_DLSS_ERR_UNSUPPORTED.
 * If the DLSS model is still loading in background (super_sampling_available == 0), the library stays
 * initialised and returns VKB_DLSS_OK so the caller can poll vkb_dlss_query_caps until ready.
 */
VKB_DLSS_API int32_t vkb_dlss_init(
    uint64_t instance, uint64_t physicalDevice, uint64_t device,
    void* vkGetInstanceProcAddr, void* vkGetDeviceProcAddr,
    const wchar_t* featurePath, const wchar_t* logPath,
    const char* projectId, const char* engineVersion,
    VkbDlssCaps* outCaps);

/*
 * Re-queries capability parameters for the already-initialised NGX session.
 * Used to poll SuperSampling.Available when the NGX background service loads the model asynchronously.
 */
VKB_DLSS_API int32_t vkb_dlss_query_caps(VkbDlssCaps* outCaps);

VKB_DLSS_API void vkb_dlss_shutdown(void);  /* releases all features + NGX; GPU must be idle */

/* ---- optimal settings ----------------------------------------------------------------------- */
typedef struct VkbDlssOptimalSettings {
    uint32_t struct_size;            /* in: sizeof(VkbDlssOptimalSettings) */
    uint32_t render_width, render_height;
    uint32_t min_width, min_height;
    uint32_t max_width, max_height;
    float    sharpness;
    uint32_t reserved;
} VkbDlssOptimalSettings;

VKB_DLSS_API int32_t vkb_dlss_query_optimal_settings(
    int32_t qualityMode, uint32_t outWidth, uint32_t outHeight, VkbDlssOptimalSettings* out);

/* ---- DLSS Super Resolution feature ---------------------------------------------------------- */
typedef struct VkbDlssCreateInfo {
    uint32_t struct_size;            /* in: sizeof(VkbDlssCreateInfo) */
    uint32_t render_width, render_height;   /* max render resolution the feature is created for */
    uint32_t output_width, output_height;
    int32_t  quality_mode;           /* VkbDlssQualityMode */
    uint32_t flags;                  /* VKB_DLSS_FLAG_* */
    uint32_t enable_output_subrects; /* 0/1 */
} VkbDlssCreateInfo;

typedef struct VkbDlssImage {
    uint64_t image;                  /* VkImage */
    uint64_t view;                   /* VkImageView; 0 == resource not provided (only legal for exposure) */
    uint32_t format;                 /* VkFormat */
    uint32_t width, height;          /* of the sub-resource in pixels */
    uint32_t aspect_mask;            /* VkImageAspectFlags of the view */
    uint32_t base_mip_level, level_count;
    uint32_t base_array_layer, layer_count;
    uint32_t read_write;             /* 1 if the image has VK_IMAGE_USAGE_STORAGE_BIT (required for output) */
    uint32_t reserved;
} VkbDlssImage;

typedef struct VkbDlssEvalInfo {
    uint32_t struct_size;            /* in: sizeof(VkbDlssEvalInfo) */
    uint32_t reset;                  /* 1 = discard history (scene change, resize) */
    VkbDlssImage color;              /* render-resolution input color */
    VkbDlssImage output;             /* output-resolution result (needs STORAGE usage, read_write=1) */
    VkbDlssImage depth;              /* render-resolution depth */
    VkbDlssImage motion_vectors;     /* motion vectors */
    VkbDlssImage exposure;           /* optional, view == 0 if unused */
    float    jitter_x, jitter_y;     /* sub-pixel jitter in render pixels */
    float    mv_scale_x, mv_scale_y; /* 0 -> 1.0 */
    float    sharpness;
    float    pre_exposure;           /* 0 -> 1.0 */
    float    exposure_scale;         /* 0 -> 1.0 */
    uint32_t render_subrect_width, render_subrect_height; /* actual rendered area inside the input textures */
    uint32_t reserved[5];                 /* sizeof(VkbDlssEvalInfo) == 344 */
} VkbDlssEvalInfo;

/*
 * Both functions RECORD into `commandBuffer` (VkCommandBuffer, recording state). NGX binds its own
 * pipelines/descriptor sets, so the caller must re-bind its state afterwards. Images must already be
 * in the layouts NGX expects (color/depth/MV: SHADER_READ_ONLY_OPTIMAL or GENERAL, output: GENERAL).
 * `outFeature` receives an opaque handle (NOT an NGX pointer).
 */
VKB_DLSS_API int32_t vkb_dlss_create_feature(uint64_t commandBuffer, const VkbDlssCreateInfo* info, uint64_t* outFeature);
VKB_DLSS_API int32_t vkb_dlss_evaluate(uint64_t commandBuffer, uint64_t feature, const VkbDlssEvalInfo* info);
VKB_DLSS_API int32_t vkb_dlss_release_feature(uint64_t feature);  /* GPU must no longer use the feature */

/* ---- DLSS Ray Reconstruction (NGX DLSS-D / DLSS 3.5+) -------------------------------------- */
typedef struct VkbDlssRrCreateInfo {
    uint32_t struct_size;            /* in: sizeof(VkbDlssRrCreateInfo) */
    uint32_t render_width, render_height;   /* max render resolution the feature is created for */
    uint32_t output_width, output_height;   /* target output resolution */
    int32_t  quality_mode;           /* VkbDlssQualityMode */
    uint32_t flags;                  /* VKB_DLSS_FLAG_* */
    uint32_t enable_output_subrects; /* 0/1 */
    uint32_t roughness_mode;         /* 0 = Unpacked (separate roughness texture), 1 = Packed (roughness in normals.w) */
    uint32_t depth_type;             /* 0 = Linear, 1 = HW Depth */
    uint32_t reserved[2];            /* alignment and future expansion (sizeof == 48) */
} VkbDlssRrCreateInfo;

typedef struct VkbDlssRrEvalInfo {
    uint32_t struct_size;            /* in: sizeof(VkbDlssRrEvalInfo) */
    uint32_t reset;                  /* 1 = discard history (scene change, resize) */
    VkbDlssImage color;              /* render-resolution noisy input color */
    VkbDlssImage output;             /* output-resolution result (needs STORAGE usage, read_write=1) */
    VkbDlssImage depth;              /* render-resolution depth (HW or Linear per create info) */
    VkbDlssImage motion_vectors;     /* motion vectors */
    VkbDlssImage exposure;           /* optional, view == 0 if unused */
    VkbDlssImage diffuse_albedo;     /* diffuse albedo (demodulated surface color) */
    VkbDlssImage specular_albedo;    /* specular albedo / reflectance */
    VkbDlssImage normals;            /* surface normals (world or view space, or +roughness in .w) */
    VkbDlssImage roughness;          /* optional if packed in normals.w, view == 0 if unused */
    VkbDlssImage diffuse_hit_distance;     /* optional diffuse ray hit distance, view == 0 if unused */
    VkbDlssImage specular_hit_distance;    /* optional specular ray hit distance, view == 0 if unused */
    VkbDlssImage ray_tracing_hit_distance; /* optional combined ray hit distance, view == 0 if unused */
    VkbDlssImage transparency_mask;        /* optional transparency mask, view == 0 if unused */
    VkbDlssImage disocclusion_mask;        /* optional disocclusion mask, view == 0 if unused */
    float    jitter_x, jitter_y;     /* sub-pixel jitter in render pixels */
    float    mv_scale_x, mv_scale_y; /* 0 -> 1.0 (default 1.0) */
    float    sharpness;              /* reserved for API consistency (0.0) */
    float    pre_exposure;           /* 0 -> 1.0 (default 1.0) */
    float    exposure_scale;         /* 0 -> 1.0 (default 1.0) */
    float    frame_time_delta_ms;    /* frame delta time in milliseconds */
    uint32_t render_subrect_width, render_subrect_height; /* actual rendered area */
    uint32_t has_matrices;           /* 1 if matrices below are provided, 0 otherwise */
    float    world_to_view_matrix[16]; /* optional 4x4 matrix */
    float    view_to_clip_matrix[16];  /* optional 4x4 matrix */
    uint32_t reserved;               /* padding to 8-byte boundary: sizeof == 968 */
} VkbDlssRrEvalInfo;

VKB_DLSS_API int32_t vkb_dlss_rr_is_supported(uint32_t* outSupported);
VKB_DLSS_API int32_t vkb_dlss_rr_create_feature(uint64_t commandBuffer, const VkbDlssRrCreateInfo* info, uint64_t* outFeature);
VKB_DLSS_API int32_t vkb_dlss_rr_evaluate(uint64_t commandBuffer, uint64_t feature, const VkbDlssRrEvalInfo* info);
VKB_DLSS_API int32_t vkb_dlss_rr_release_feature(uint64_t feature);

/* ---- STUBS: not implemented yet ------------------------------------------------------------- */
/* STUB: DLSS Frame Generation needs Streamline (swapchain/present ownership, Reflex), separate step. */
VKB_DLSS_API int32_t vkb_dlss_fg_is_supported(uint32_t* outSupported);

#ifdef __cplusplus
}
#endif
#endif /* VKB_DLSS_H */
