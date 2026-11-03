#ifndef BEDRAY_HISTORY_C_H
#define BEDRAY_HISTORY_C_H

#include <stdint.h>

#if defined(_WIN32)
#  if defined(BEDRAY_CORE_BUILD)
#    define BEDRAY_HISTORY_API __declspec(dllexport)
#  else
#    define BEDRAY_HISTORY_API __declspec(dllimport)
#  endif
#else
#  define BEDRAY_HISTORY_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Independent ABI version; the caller serializes every operation on the render
 * thread. All image identifiers and memory remain owned by the Vulkan host. */
#define BEDRAY_HISTORY_ABI_VERSION 2u
#define BEDRAY_HISTORY_MAX_QUEUES 4u
#define BEDRAY_HISTORY_MAX_SLOTS 4u

typedef struct BedrayHistory BedrayHistory;
typedef uint32_t BedrayHistoryStatus;
enum {
    BEDRAY_HISTORY_OK = 0,
    BEDRAY_HISTORY_INVALID_SIGNAL = 1,
    BEDRAY_HISTORY_DUPLICATE_SIGNAL = 2,
    BEDRAY_HISTORY_HASH_COLLISION = 3,
    BEDRAY_HISTORY_UNKNOWN_SIGNAL = 4,
    BEDRAY_HISTORY_INVALID_IMAGES = 5,
    BEDRAY_HISTORY_ALIASED_IMAGES = 6,
    BEDRAY_HISTORY_OVER_BUDGET = 7,
    BEDRAY_HISTORY_SIGNAL_BUSY = 8,
    BEDRAY_HISTORY_OUT_OF_MEMORY = 9,
    BEDRAY_HISTORY_DEVICE_UNAVAILABLE = 10,
    BEDRAY_HISTORY_DEVICE_UNRESOLVED = 11,
    BEDRAY_HISTORY_SUBMISSION_OPEN = 12,
    BEDRAY_HISTORY_NO_SUBMISSION = 13,
    BEDRAY_HISTORY_INVALID_SERIAL = 14,
    BEDRAY_HISTORY_SEE_DETAIL = 15,
    BEDRAY_HISTORY_NOT_IDLE = 16,
    BEDRAY_HISTORY_TICKETS_EXHAUSTED = 17,
    BEDRAY_HISTORY_INVALID_ARGUMENT = 0x100,
    BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY = 0x101,
    BEDRAY_HISTORY_IN_USE = 0x102,
    /* Unexpected C++ exception in the wrapper; no successful output is published. */
    BEDRAY_HISTORY_INTERNAL_ERROR = 0x103
};
/* Explicit default: zero intentionally disables the four optional resets. */
#define BEDRAY_HISTORY_DEFAULT_SENSITIVITY (BEDRAY_HISTORY_RESET_TARGET | BEDRAY_HISTORY_RESET_SCENE | \
    BEDRAY_HISTORY_RESET_CAMERA | BEDRAY_HISTORY_RESET_DISCONTINUITY)
enum {
    BEDRAY_HISTORY_DETAIL_OK = 0,
    BEDRAY_HISTORY_DETAIL_NOT_CONFIGURED = 1,
    BEDRAY_HISTORY_DETAIL_RECORDING_PENDING = 2,
    BEDRAY_HISTORY_DETAIL_RECORDING_ATTACHED = 3,
    BEDRAY_HISTORY_DETAIL_STALE_FRAME = 4,
    BEDRAY_HISTORY_DETAIL_INVALID_INPUT = 5,
    BEDRAY_HISTORY_DETAIL_RESET_NOT_APPLIED = 6,
    BEDRAY_HISTORY_DETAIL_WRITE_UNAVAILABLE = 7,
    BEDRAY_HISTORY_DETAIL_DEVICE_UNAVAILABLE = 8,
    BEDRAY_HISTORY_DETAIL_UNKNOWN_TOKEN = 9,
    BEDRAY_HISTORY_DETAIL_TOKEN_EXHAUSTED = 10
};
enum { BEDRAY_HISTORY_COMPLETED_ONLY = 0, BEDRAY_HISTORY_SAME_QUEUE_BARRIER = 1,
       BEDRAY_HISTORY_CROSS_QUEUE_SEMAPHORE = 2 };
enum { BEDRAY_HISTORY_ACCEPTED = 0, BEDRAY_HISTORY_REJECTED = 1, BEDRAY_HISTORY_UNKNOWN = 2 };
enum {
    BEDRAY_HISTORY_RESET_NONE = 0, BEDRAY_HISTORY_RESET_FIRST_FRAME = 1u << 0,
    BEDRAY_HISTORY_RESET_DEVICE = 1u << 1,
    BEDRAY_HISTORY_RESET_TARGET = 1u << 2, BEDRAY_HISTORY_RESET_PIPELINE = 1u << 3,
    BEDRAY_HISTORY_RESET_SCENE = 1u << 4, BEDRAY_HISTORY_RESET_CAMERA = 1u << 5,
    BEDRAY_HISTORY_RESET_EXTENT = 1u << 6, BEDRAY_HISTORY_RESET_INVALID_INPUT = 1u << 7,
    BEDRAY_HISTORY_RESET_DISCONTINUITY = 1u << 8, BEDRAY_HISTORY_RESET_SIGNAL = 1u << 9,
    BEDRAY_HISTORY_RESET_REQUESTED = 1u << 10, BEDRAY_HISTORY_RESET_DEVICE_LOST = 1u << 11,
    BEDRAY_HISTORY_RESET_RECONFIGURED = 1u << 12
};

typedef struct BedrayHistoryImage {
    uint64_t image, memory, offset, size;
} BedrayHistoryImage;

typedef struct BedrayHistoryKey {
    uint64_t device_generation, target_generation, pipeline_generation;
    uint64_t scene_generation, camera_epoch, signal_revision;
    uint32_t width, height;
} BedrayHistoryKey;

typedef struct BedrayHistoryUses {
    uint64_t serial[BEDRAY_HISTORY_MAX_QUEUES];
    uint32_t unknown;
} BedrayHistoryUses;

typedef struct BedrayHistoryRecording {
    uint64_t token, frame;
    uint32_t queue;
    int32_t read_slot;
    uint64_t read_image;
    uint32_t write_slot;
    uint64_t write_image;
    uint32_t reset;
    BedrayHistoryUses write_after;
    uint32_t read_producer_queue;
    uint64_t read_producer_serial;
} BedrayHistoryRecording;

typedef struct BedrayHistoryTicket {
    uint64_t id, device_generation;
    uint32_t queue;
    uint64_t serial;
} BedrayHistoryTicket;

typedef struct BedrayHistoryRetired {
    BedrayHistoryImage images[BEDRAY_HISTORY_MAX_SLOTS];
    BedrayHistoryUses uses[BEDRAY_HISTORY_MAX_SLOTS];
    uint32_t count;
    uint64_t device_generation;
    uint32_t completion_known;
} BedrayHistoryRetired;

typedef struct BedrayHistoryAbiInfo {
    uint32_t version, sizeof_info;
    uint32_t sizeof_image, align_image, image_offsets[4];
    uint32_t sizeof_key, align_key, key_offsets[8];
    uint32_t sizeof_uses, align_uses, uses_offsets[2];
    uint32_t sizeof_recording, align_recording, recording_offsets[11];
    uint32_t sizeof_ticket, align_ticket, ticket_offsets[4];
    uint32_t sizeof_retired, align_retired, retired_offsets[5];
} BedrayHistoryAbiInfo;

/* Offset arrays follow the field declaration order in the corresponding
 * structure. Array fields report the offset of element zero; stride is the
 * reported sizeof_image, sizeof_uses or sizeof(uint64_t), respectively. */

/* The registry status and per-signal detail values are defined in the C ABI
 * contract. Outputs are valid only on success; pop uses has_record=0 when no
 * record is ready. An unknown submit outcome is conservatively retained. */
BEDRAY_HISTORY_API uint32_t bedray_history_abi_version(void);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_abi_info(BedrayHistoryAbiInfo* out, uint32_t out_size);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_create(uint64_t budget, BedrayHistory** out);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_destroy(BedrayHistory* history);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_memory(BedrayHistory*, uint64_t* configured,
                                                           uint64_t* retained, uint64_t* budget);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_begin_device(BedrayHistory*, uint64_t generation);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_device_lost(BedrayHistory*);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_device_idle(BedrayHistory*);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_retire_device(BedrayHistory*);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_add(BedrayHistory*, const char* name, uint32_t name_bytes,
                                       uint32_t sensitivity, uint64_t* signal);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_remove(BedrayHistory*, uint64_t signal);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_configure(BedrayHistory*, uint64_t signal,
                                             const BedrayHistoryImage* images, uint32_t count);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_invalidate(BedrayHistory*, uint64_t signal, uint32_t* detail);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_invalidate_after_pending(BedrayHistory*, uint64_t signal);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_prepare(BedrayHistory*, uint64_t signal, const BedrayHistoryKey*,
    uint64_t frame, uint32_t queue, uint32_t ordering, BedrayHistoryRecording*, uint32_t* detail);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_cancel(BedrayHistory*, uint64_t signal, uint64_t token, uint32_t* detail);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_begin_submission(BedrayHistory*, uint32_t queue, uint64_t serial,
                                                     BedrayHistoryTicket*);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_attach(BedrayHistory*, const BedrayHistoryTicket*, uint64_t signal,
                                           uint64_t token, uint32_t reset_applied, uint32_t* detail);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_end_submission(BedrayHistory*, const BedrayHistoryTicket*, uint32_t outcome);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_complete(BedrayHistory*, uint32_t queue, uint64_t serial);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_pop_finished(BedrayHistory*, BedrayHistoryRetired*, uint32_t* has_record);
BEDRAY_HISTORY_API BedrayHistoryStatus bedray_history_pop_orphaned(BedrayHistory*, BedrayHistoryRetired*, uint32_t* has_record);

#ifdef __cplusplus
}
#endif
#endif
