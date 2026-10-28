/*
 * Bedray native GPU scene — C ABI exported by bedray_core and a standalone test DLL.
 *
 * The host owns the VkDevice, the queue and every command buffer. This library
 * owns the Bedray scene representation, section/entity geometry buffers, BLAS
 * and TLAS objects, scratch memory and the per-frame TLAS instance snapshots.
 * It records only Bedray commands into a command buffer supplied by the host;
 * it never creates a device or queue and never submits, presents or waits.
 *
 * ABI rules:
 *   - every structure is versioned and has a fixed layout;
 *   - no C++ types or exceptions cross this boundary;
 *   - Vulkan handles are passed as uint64_t;
 *   - input pointers are read only during the call; the library copies all
 *     data it needs before returning;
 *   - calls must be externally synchronized on the render thread.
 *
 * Lifetime contract:
 *   - a frame prepared with bedray_scene_prepare_frame keeps its transient
 *     upload buffers, scratch memory and retired resources alive until the host
 *     calls bedray_scene_release_frame after proving GPU completion (or after
 *     discarding an unsubmitted recording);
 *   - at most frames_in_flight frames may be outstanding;
 *   - bedray_scene_destroy fails with BEDRAY_SCENE_ERROR_IN_USE while frames
 *     are outstanding.
 */

#ifndef BEDRAY_SCENE_H
#define BEDRAY_SCENE_H

#include <stdint.h>

#if defined(_WIN32)
#  if defined(BEDRAY_SCENE_BUILD)
#    define BEDRAY_SCENE_API __declspec(dllexport)
#  else
#    define BEDRAY_SCENE_API
#  endif
#  define BEDRAY_SCENE_CALL __cdecl
#else
#  define BEDRAY_SCENE_API __attribute__((visibility("default")))
#  define BEDRAY_SCENE_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define BEDRAY_SCENE_ABI_VERSION 2u
#define BEDRAY_SCENE_ERROR_MESSAGE_BYTES 1024u
#define BEDRAY_SCENE_MAX_FRAMES_IN_FLIGHT 8u
#define BEDRAY_SCENE_TABLE_COUNT 2u
#define BEDRAY_SCENE_MAX_SECTION_GEOMETRIES 3u
#define BEDRAY_SCENE_SECTION_RECORD_BYTES 64u
#define BEDRAY_SCENE_INSTANCE_RECORD_BYTES 64u

/* BedraySceneSectionInput.flags. A zero flags word is valid. */
enum {
    /* vertices points at GeometryVertex records (64 B stride, see
       BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES). The path tracer requires this
       flag; the scene itself only validates the stride. */
    BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX = 1u << 0
};

/* Shader-facing GeometryVertex record: position, uv/light, tint, meta.
   The scene never decodes it; it only copies the stream and builds the BLAS
   with position at offset 0 and this stride. */
#define BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES 64u

typedef struct BedrayScene BedrayScene;

typedef enum BedraySceneStatus {
    BEDRAY_SCENE_OK = 0,
    BEDRAY_SCENE_ERROR_INVALID_ARGUMENT = 1,
    BEDRAY_SCENE_ERROR_UNSUPPORTED = 2,
    BEDRAY_SCENE_ERROR_VULKAN = 3,
    BEDRAY_SCENE_ERROR_OUT_OF_MEMORY = 4,
    BEDRAY_SCENE_ERROR_DEVICE_LOST = 5,
    BEDRAY_SCENE_ERROR_ABI_MISMATCH = 6,
    BEDRAY_SCENE_ERROR_INTERNAL = 7,
    BEDRAY_SCENE_ERROR_IN_USE = 8,
    BEDRAY_SCENE_ERROR_STALE = 9,
    BEDRAY_SCENE_ERROR_UNKNOWN_ID = 10,
    BEDRAY_SCENE_ERROR_LIMIT = 11
} BedraySceneStatus;

/*
 * Capability bits are the features the host actually enabled on its logical
 * device. They are never inferred from a GPU name or a physical-device query.
 * ACCELERATION_STRUCTURE and DEVICE_ADDRESS are required; AS_UPDATE enables
 * in-place BLAS/TLAS refits. Without AS_UPDATE geometry changes rebuild.
 */
typedef enum BedraySceneCapability {
    BEDRAY_SCENE_CAP_ACCELERATION_STRUCTURE = 1u << 0,
    BEDRAY_SCENE_CAP_DEVICE_ADDRESS = 1u << 1,
    BEDRAY_SCENE_CAP_AS_UPDATE = 1u << 2
} BedraySceneCapability;

typedef enum BedraySceneTableKind {
    BEDRAY_SCENE_TABLE_MATERIAL = 0,
    BEDRAY_SCENE_TABLE_LIGHT = 1
} BedraySceneTableKind;

typedef enum BedraySceneIndexType {
    BEDRAY_SCENE_INDEX_NONE = 0,
    BEDRAY_SCENE_INDEX_UINT16 = 1,
    BEDRAY_SCENE_INDEX_UINT32 = 2
} BedraySceneIndexType;

/* Optional bedray_scene_prepare_frame output flags. */
enum {
    BEDRAY_SCENE_FRAME_TLAS_REBUILT = 1u << 0,
    BEDRAY_SCENE_FRAME_TLAS_UPDATED = 1u << 1,
    BEDRAY_SCENE_FRAME_BLAS_REBUILT = 1u << 2,
    BEDRAY_SCENE_FRAME_BLAS_REFIT = 1u << 3,
    BEDRAY_SCENE_FRAME_UPLOADED = 1u << 4,
    BEDRAY_SCENE_FRAME_TABLES_UPLOADED = 1u << 5,
    BEDRAY_SCENE_FRAME_IDLE = 1u << 6
};

typedef struct BedraySceneError {
    BedraySceneStatus status;
    char message[BEDRAY_SCENE_ERROR_MESSAGE_BYTES];
} BedraySceneError;

typedef struct BedraySceneAbiInfo {
    uint32_t abi_version;
    uint32_t sizeof_desc;
    uint32_t sizeof_section_input;
    uint32_t sizeof_entity_input;
    uint32_t sizeof_update;
    uint32_t sizeof_table_desc;
    uint32_t sizeof_frame_desc;
    uint32_t sizeof_frame_info;
    uint32_t sizeof_resources;
    uint32_t sizeof_section_record;
    uint32_t sizeof_error;
    uint32_t reserved;
} BedraySceneAbiInfo;

typedef struct BedraySceneDesc {
    uint32_t abi_version;
    uint32_t flags;
    /* Host-owned Vulkan objects (uint64_t reinterpret_cast of the handles). */
    uint64_t instance;
    uint64_t physical_device;
    uint64_t device;
    /* Addresses of PFN_vkGetInstanceProcAddr and PFN_vkGetDeviceProcAddr. */
    uint64_t get_instance_proc_addr;
    uint64_t get_device_proc_addr;
    uint32_t api_version;
    /* Enabled BedraySceneCapability mask; ACCELERATION_STRUCTURE and
       DEVICE_ADDRESS are required. */
    uint32_t capabilities;
    /* 1..BEDRAY_SCENE_MAX_FRAMES_IN_FLIGHT. */
    uint32_t frames_in_flight;
    uint32_t max_sections;
    uint32_t max_entities;
    /* Upper bound for sections + entities (TLAS instance capacity). */
    uint32_t max_instances;
    uint32_t max_table_bytes;
    uint32_t reserved;
    /* Maximum vertex+index bytes of one section or entity mesh. */
    uint64_t max_geometry_bytes;
} BedraySceneDesc;

/*
 * A section is a static terrain chunk. Its BLAS is rebuilt only when
 * geometry_revision changes. All pointers are copied during the call.
 *
 * The vertex stream is opaque to the scene. When flags has
 * BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX, it is an array of 64-byte
 * GeometryVertex records (position at offset 0), which is also the stream the
 * Slang SceneTraversal reads through the section record's device address.
 *
 * origin is the section origin in blocks (16-block sections). geometry_count is
 * 1..3; quad_start[g] is the first quad of geometry g in the vertex stream and
 * the ranges are contiguous in pass order. pass_bits stores 2 bits per geometry
 * (0 solid, 1 cutout, 2 translucent), the same encoding as the legacy table.
 */
typedef struct BedraySceneSectionInput {
    uint64_t section_id;
    uint64_t geometry_revision;
    const void* vertices;
    uint64_t vertex_count;
    uint32_t vertex_stride;
    uint32_t index_type;
    const void* indices;
    uint64_t index_count;
    int32_t origin[3];
    uint32_t geometry_count;
    uint32_t quad_start[BEDRAY_SCENE_MAX_SECTION_GEOMETRIES];
    uint32_t pass_bits;
    uint32_t flags;
    uint32_t reserved;
} BedraySceneSectionInput;

/*
 * An entity is a dynamic object. Transform-only and mask-only changes never
 * touch the BLAS; geometry_revision changes may refit and topology_revision
 * changes rebuild. transform is a row-major 3x4 matrix (VkTransformMatrixKHR).
 * instance_mask 0 selects the default 0xFF.
 *
 * TLAS instances of entities carry bit 0x800000 in instanceCustomIndex; the
 * remaining bits are the entity slot. Section instances store their section
 * table slot raw, so the bit is the only thing a hit needs to tell a mob quad
 * from a section quad. The scene rejects more than 0x800000 sections/entities.
 */
typedef struct BedraySceneEntityInput {
    uint64_t entity_id;
    uint64_t geometry_revision;
    uint64_t topology_revision;
    const void* vertices;
    uint64_t vertex_count;
    const void* indices;
    uint64_t index_count;
    uint32_t vertex_stride;
    uint32_t index_type;
    uint32_t instance_mask;
    uint32_t reserved;
    float transform[12];
} BedraySceneEntityInput;

typedef struct BedraySceneUpdate {
    uint32_t abi_version;
    uint32_t flags;
    const BedraySceneSectionInput* sections;
    uint32_t section_count;
    uint32_t removed_section_count;
    const uint64_t* removed_sections;
    const BedraySceneEntityInput* entities;
    uint32_t entity_count;
    uint32_t removed_entity_count;
    const uint64_t* removed_entities;
} BedraySceneUpdate;

typedef struct BedraySceneTableDesc {
    uint32_t abi_version;
    uint32_t table_kind;
    /* Revision identifies content; repeated equal revisions are coalesced. */
    uint64_t revision;
    const void* data;
    /* size 0 clears the table. */
    uint64_t size;
    uint64_t reserved;
} BedraySceneTableDesc;

typedef struct BedraySceneFrameDesc {
    uint32_t abi_version;
    uint32_t flags;
    /* Strictly increasing, non-zero host frame serial. */
    uint64_t frame_index;
    /* Host VkCommandBuffer (uint64_t reinterpret_cast). */
    uint64_t command_buffer;
} BedraySceneFrameDesc;

typedef struct BedraySceneFrameInfo {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t frame_index;
    uint64_t tlas;
    uint64_t instance_count;
    uint64_t blas_builds;
    uint64_t blas_refits;
    uint64_t deferred_updates;
    uint64_t upload_bytes;
    uint64_t section_count;
    uint64_t reserved;
} BedraySceneFrameInfo;

/*
 * Resources of the most recently prepared frame. uint64 handles are Vulkan
 * objects owned by the scene; the host may bind them but must not destroy them.
 */
typedef struct BedraySceneResources {
    uint32_t abi_version;
    uint32_t section_count;
    uint32_t instance_count;
    uint32_t reserved;
    uint64_t tlas;
    uint64_t instance_buffer;
    uint64_t instance_buffer_address;
    uint64_t section_table_buffer;
    uint64_t section_table_address;
    uint64_t section_table_bytes;
    uint64_t material_table_buffer;
    uint64_t material_table_address;
    uint64_t material_table_bytes;
    uint64_t light_table_buffer;
    uint64_t light_table_address;
    uint64_t light_table_bytes;
} BedraySceneResources;

/*
 * Shader-facing section record (64 bytes). Sections are sorted by section_id and
 * the TLAS instanceCustomIndex of a world instance is this table slot.
 *
 *   origin.xyz  section origin in blocks; origin.w stable section id
 *   meta.x      vertex count in the section's GeometryVertex stream
 *   meta.y      geometry count (1..3)
 *   meta.z      BEDRAY_SCENE_SECTION_* flags
 *   meta.w      reserved, zero
 *   vertexAddress  device address of the GeometryVertex stream (BDA)
 *   ranges[g].firstQuad  first quad of geometry g
 *   ranges[g].quadCountPass  bits 0..15 quad count, bits 16..17 pass,
 *                            bits 18..31 reserved flags
 *
 * GeometryVertex is 64 bytes: float4 position (xyz section-local), float4
 * uvLight (xy uv, zw block/sky light 0..1), float4 tint (rgb), uint4 meta
 * (x material id, y vertex flags, zw reserved).
 */
typedef struct BedraySceneSectionRange {
    uint32_t first_quad;
    uint32_t quad_count_pass;
} BedraySceneSectionRange;

typedef struct BedraySceneSectionRecord {
    int32_t origin[4];
    uint32_t meta[4];
    uint64_t vertex_address;
    BedraySceneSectionRange ranges[BEDRAY_SCENE_MAX_SECTION_GEOMETRIES];
} BedraySceneSectionRecord;

BEDRAY_SCENE_API uint32_t BEDRAY_SCENE_CALL bedray_scene_abi_version(void);
BEDRAY_SCENE_API void BEDRAY_SCENE_CALL bedray_scene_abi_info(BedraySceneAbiInfo* out_info);

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_create(
    const BedraySceneDesc* desc,
    BedrayScene** out_scene,
    BedraySceneError* error);

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_update(
    BedrayScene* scene,
    const BedraySceneUpdate* update,
    BedraySceneError* error);

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_update_table(
    BedrayScene* scene,
    const BedraySceneTableDesc* desc,
    BedraySceneError* error);

/*
 * Sets the camera eye position in blocks, in the same world space as section
 * origins. The renderer traces camera-relative rays, so each section instance
 * is placed at translate(origin - view) in every prepared frame. The view
 * defaults to unset, which keeps the historical identity section placement;
 * entities keep their own transforms either way. All three components must be
 * finite. A view change rewrites the instance buffer and takes the TLAS work
 * path on the next prepare (an in-place update when AS_UPDATE is enabled),
 * without any BLAS work.
 */
BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_set_view(
    BedrayScene* scene,
    double eye_x,
    double eye_y,
    double eye_z,
    BedraySceneError* error);

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_prepare_frame(
    BedrayScene* scene,
    const BedraySceneFrameDesc* desc,
    BedraySceneFrameInfo* out_info,
    BedraySceneError* error);

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_release_frame(
    BedrayScene* scene,
    uint64_t frame_index,
    BedraySceneError* error);

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_query_resources(
    BedrayScene* scene,
    BedraySceneResources* out_resources);

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_destroy(
    BedrayScene* scene);

#ifdef __cplusplus
}
#endif

#endif /* BEDRAY_SCENE_H */
