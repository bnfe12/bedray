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

/*
 * Additive path-trace pass ABI.
 *
 * These declarations do not change any layout above and are optional for a
 * host that only uses bedray_core_render_frame/render_phases. The pass records
 * path_trace.slang -> restir_spatial.slang (optional) -> path_display.slang into
 * the host command buffer. It never submits or waits.
 *
 * Ownership:
 *   - the core owns radiance, motion, the two accumulation images, the two
 *     history-depth images, FrameConstants uploads and one-element dummies;
 *   - with NATIVE_DI the core also owns the DI ring, reset and parity;
 *   - the host owns the TLAS, section table, atlases, emitters, external reservoirs,
 *     entity rings, output view and depth view, and keeps them alive and in the
 *     declared layouts until bedray_core_release_recording for the command
 *     buffer;
 *   - the host must call bedray_core_release_recording after proven GPU
 *     completion or proven discard, exactly as for every other core recording.
 */

#define BEDRAY_PATH_TRACE_FRAME_RESET_HISTORY (1u << 0)
#define BEDRAY_PATH_TRACE_FRAME_ACCUMULATE    (1u << 1)
#define BEDRAY_PATH_TRACE_FRAME_CAMERA_STATIC (1u << 2)
#define BEDRAY_PATH_TRACE_FRAME_FLIP_Y        (1u << 3)
#define BEDRAY_PATH_TRACE_FRAME_SPATIAL       (1u << 4)
/* scene.sections is the 64-byte GeometrySection table of bedray_scene and the
 * pass traces with program_trace_scene (path_trace_scene.slang). The flag is
 * host-only: it never reaches the shader push block. */
#define BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE  (1u << 5)
/* Optional host-only extension. With NATIVE_DI, scene.reservoirs and input
 * di_state.y must be zero. C++ owns the ring, clear, history bit and parity.
 * Query the feature mask before use; RESET_DI invalidates native DI contents. */
#define BEDRAY_PATH_TRACE_FRAME_NATIVE_DI      (1u << 6)
#define BEDRAY_PATH_TRACE_FRAME_RESET_DI       (1u << 7)
#define BEDRAY_PATH_TRACE_FEATURE_NATIVE_DI    (1u << 0)
BEDRAY_API uint32_t BEDRAY_CALL bedray_core_path_trace_feature_mask(void);

#define BEDRAY_PATH_DISPLAY_ACCUMULATE     (1u << 0)
#define BEDRAY_PATH_DISPLAY_TONEMAP        (1u << 1)
#define BEDRAY_PATH_DISPLAY_REPROJECT      (1u << 2)
#define BEDRAY_PATH_DISPLAY_CAMERA_STATIC  (1u << 3)
#define BEDRAY_PATH_DISPLAY_FLIP_Y         (1u << 4)

#define BEDRAY_PATH_TRACE_DI_ENABLED (1u << 0)
#define BEDRAY_PATH_TRACE_DI_HISTORY (1u << 1)
#define BEDRAY_PATH_TRACE_DI_STATIC  (1u << 2)
#define BEDRAY_PATH_TRACE_DI_FLIP_Y  (1u << 3)

/* One VkBuffer range. flags/reserved must be zero. */
typedef struct BedrayPathTraceBuffer {
    uint64_t handle;
    uint64_t offset;
    uint64_t bytes;
    uint32_t flags;
    uint32_t reserved;
} BedrayPathTraceBuffer;

/* One VkBuffer range without the reserved words (entity rings). */
typedef struct BedrayPathTraceRange {
    uint64_t handle;
    uint64_t offset;
    uint64_t bytes;
} BedrayPathTraceRange;

/* Borrowed scene resources for one frame. A zero handle selects the pass's
 * one-element dummy where the shader has a corresponding inactive binding. */
typedef struct BedrayPathTraceScene {
    uint64_t tlas;                          /* VkAccelerationStructureKHR */
    BedrayPathTraceBuffer sections;         /* LegacySection table, 64 B slots */
    uint64_t albedo_view;                   /* VkImageView, GENERAL */
    uint64_t material_view;                 /* VkImageView, GENERAL */
    uint64_t normal_view;                   /* VkImageView, GENERAL */
    uint64_t atlas_sampler;                 /* VkSampler */
    BedrayPathTraceBuffer block_materials;  /* BlockMaterial table, 64 B slots */
    BedrayPathTraceBuffer emitters;         /* BlockEmitterRecord table */
    BedrayPathTraceBuffer reservoirs;       /* two DIReservoir parities */
    BedrayPathTraceRange entity_positions;
    BedrayPathTraceRange entity_indices;
    BedrayPathTraceRange entity_attrs;
    BedrayPathTraceRange entity_rects;
    BedrayPathTraceRange entity_texels;
    BedrayPathTraceRange entity_previous;
} BedrayPathTraceScene;

/* Mirror of contracts/frame.slang CameraState (416 B). Matrices are column-major
 * as Slang float4x4 and JOML Matrix4f both store them. */
typedef struct BedrayPathTraceCamera {
    float view[16];
    float projection[16];
    float view_projection[16];
    float inv_view_projection[16];
    float prev_view_projection[16];
    float origin[4];          /* xyz camera origin, w frame time */
    float forward[4];         /* xyz forward, w tan(half vertical fov) */
    float right[4];           /* xyz right, w aspect ratio */
    float up[4];              /* xyz up, w reserved */
    float jitter[4];          /* xy pixel jitter, zw 1 / render size */
    uint32_t width;
    uint32_t height;
    uint32_t display_width;
    uint32_t display_height;
} BedrayPathTraceCamera;

/* Sun/moon plus the display-only exposure inputs (80 B). */
typedef struct BedrayPathTraceLighting {
    float sun_direction[4];       /* xyz towards sun, w angular radius */
    float sun_color[4];           /* rgb radiance, w intensity */
    float moon_direction[4];      /* xyz towards moon, w angular radius */
    float moon_color[4];          /* rgb radiance, w intensity */
    float exposure;               /* linear multiplier, applied once in display */
    float max_radiance;           /* display firefly clamp; 0 uses path.max_radiance */
    float reprojection_tolerance; /* relative depth tolerance; 0 uses 0.02 */
    uint32_t reserved;
} BedrayPathTraceLighting;

/* Mirror of contracts/frame.slang AtmosphereState (80 B). */
typedef struct BedrayPathTraceAtmosphere {
    float zenith_color[4];
    float horizon_color[4];
    float fog_color[4];
    float fog[4];
    float cloud[4];
} BedrayPathTraceAtmosphere;

/* Mirror of passes/path_trace.slang PathTracePush (112 B). With NATIVE_DI,
 * the core derives the HISTORY bit and parity; otherwise forwarded verbatim. */
typedef struct BedrayPathTracePath {
    uint32_t max_depth;
    uint32_t max_surface_steps;
    uint32_t sample_index;
    uint32_t material_count;
    uint32_t flags;
    float ray_bias;
    float alpha_cutoff;
    float max_distance;
    float max_radiance;
    float sun_probability;
    uint32_t emitter_count;
    float emitter_probability;
    float emitter_camera_relative[4]; /* xyz camera minus emitter-list origin, w candidates */
    float di_camera_delta[4];         /* xyz current minus previous camera, w reserved */
    uint32_t di_state[4];             /* x: BEDRAY_PATH_TRACE_DI_*, y: external parity or zero for NATIVE_DI */
    uint32_t entity_state[4];         /* x atlas texels per side, yz self-avatar range,
                                       * w auto-normal strength as float bits, 0 = off */
} BedrayPathTracePath;

typedef struct BedrayPathTraceFrameDesc {
    uint32_t abi_version;
    uint32_t flags;                 /* BEDRAY_PATH_TRACE_FRAME_* */
    uint64_t command_buffer;
    uint64_t output_view;           /* RGBA8, GENERAL, host-owned */
    uint64_t depth_view;            /* R32F, GENERAL, host-owned */
    BedrayPathTraceScene scene;
    BedrayPathTraceCamera camera;
    BedrayPathTraceLighting lighting;
    BedrayPathTraceAtmosphere atmosphere;
    BedrayPathTracePath path;
    uint32_t frame_index;
    uint32_t samples_per_frame;
    uint32_t accum_frames;          /* samples already in accum; 0 discards history */
    uint32_t display_flags;         /* extra BEDRAY_PATH_DISPLAY_* bits */
    float frame_time;
    uint32_t reserved[5];
} BedrayPathTraceFrameDesc;

typedef struct BedrayPathTraceDesc {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t program_trace;         /* handle from bedray_core_register_program */
    uint64_t program_spatial;       /* 0 disables the spatial stage */
    uint64_t program_display;
    uint64_t image_budget_bytes;    /* 0 selects 512 MiB */
    uint64_t buffer_budget_bytes;   /* 0 selects 16 MiB */
    uint32_t width;                 /* initial extent hint; frame desc wins */
    uint32_t height;
    uint32_t reserved0;
    uint32_t reserved1;
    /* path_trace_scene.slang; 0 keeps the legacy table for every frame. The
     * field occupies the former reserved[0], so the struct size is unchanged. */
    uint64_t program_trace_scene;
    uint64_t reserved;
} BedrayPathTraceDesc;

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_path_trace(
    BedrayCore* core,
    const BedrayPathTraceDesc* desc,
    BedrayError* error);

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace(
    BedrayCore* core,
    const BedrayPathTraceFrameDesc* desc,
    BedrayError* error);

/* Refuses while a path-trace recording remains. Programs stay host-owned and
 * must not be destroyed while the pass can still record with them. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_path_trace(
    BedrayCore* core);

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

/* Borrowed nearest-filtered, clamp-to-edge, mip-0 normalized sampler.
 * Owned by the core; valid until successful core destruction. The host must
 * release every recording that uses it before destroying the core and must
 * not destroy this sampler itself. External uses outside this core are not
 * tracked: they must also be completed/discarded and never resubmitted before
 * core destruction. Render-thread serialization is required. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_atlas_sampler(
    BedrayCore* core, uint64_t* out_sampler, BedrayError* error);
/* Same ownership, normalized coordinates and mip-0 range, with linear filtering. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_linear_sampler(
    BedrayCore* core, uint64_t* out_sampler, BedrayError* error);

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
 * recording was discarded/reset and no recorded uploads/history will be reused.
 * For a discarded path-trace frame use discard_recording below. Call before re-recording the same handle.
 * The caller must externally synchronize these operations on the render thread.
 * destroy_program defers destruction while recordings still reference it.
 * Before core_destroy, the host must ensure all recordings are no longer in use.
 */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_release_recording(
    BedrayCore* core, uint64_t command_buffer);

/* Proven discard, or unknown execution after completion/exclusion of resubmission:
 * invalidate pass history/layout state before releasing this recording.
 * Never use for an in-flight or resubmittable buffer. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_discard_recording(
    BedrayCore* core, uint64_t command_buffer);

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(
    BedrayCore* core,
    const BedrayFrameDesc* desc,
    BedrayError* error);

/* Records 1..8 compute phases with an incrementing uint32 push field.
 * phase_offset must address an aligned word inside the push block; input
 * memory is unchanged. Compute read/write barriers separate phases and follow
 * the final phase. On any failure discard the entire command recording before
 * release; earlier phases may already have been recorded. Never submits/waits. */
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_phases(
    BedrayCore* core, const BedrayFrameDesc* desc, uint32_t phase_offset,
    uint32_t phase_count, BedrayError* error);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* BEDRAY_CORE_H */
