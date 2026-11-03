#include "bedray/history_c.h"
#include "bedray/history_registry.hpp"

#include <new>
#include <cstddef>
#include <algorithm>
#include <string_view>

struct BedrayHistory {
    explicit BedrayHistory(uint64_t budget) noexcept : registry(budget) {}
    bedray::HistoryRegistry registry;
};

namespace {
using namespace bedray;
#define CHECK_ENUM(type, value, abi) static_assert(static_cast<uint32_t>(type::value) == abi)
CHECK_ENUM(RegistryStatus, ok, BEDRAY_HISTORY_OK);
CHECK_ENUM(RegistryStatus, invalid_signal, BEDRAY_HISTORY_INVALID_SIGNAL);
CHECK_ENUM(RegistryStatus, duplicate_signal, BEDRAY_HISTORY_DUPLICATE_SIGNAL);
CHECK_ENUM(RegistryStatus, hash_collision, BEDRAY_HISTORY_HASH_COLLISION);
CHECK_ENUM(RegistryStatus, unknown_signal, BEDRAY_HISTORY_UNKNOWN_SIGNAL);
CHECK_ENUM(RegistryStatus, invalid_images, BEDRAY_HISTORY_INVALID_IMAGES);
CHECK_ENUM(RegistryStatus, aliased_images, BEDRAY_HISTORY_ALIASED_IMAGES);
CHECK_ENUM(RegistryStatus, over_budget, BEDRAY_HISTORY_OVER_BUDGET);
CHECK_ENUM(RegistryStatus, signal_busy, BEDRAY_HISTORY_SIGNAL_BUSY);
CHECK_ENUM(RegistryStatus, out_of_memory, BEDRAY_HISTORY_OUT_OF_MEMORY);
CHECK_ENUM(RegistryStatus, device_unavailable, BEDRAY_HISTORY_DEVICE_UNAVAILABLE);
CHECK_ENUM(RegistryStatus, device_unresolved, BEDRAY_HISTORY_DEVICE_UNRESOLVED);
CHECK_ENUM(RegistryStatus, submission_open, BEDRAY_HISTORY_SUBMISSION_OPEN);
CHECK_ENUM(RegistryStatus, no_submission, BEDRAY_HISTORY_NO_SUBMISSION);
CHECK_ENUM(RegistryStatus, invalid_serial, BEDRAY_HISTORY_INVALID_SERIAL);
CHECK_ENUM(RegistryStatus, see_detail, BEDRAY_HISTORY_SEE_DETAIL);
CHECK_ENUM(RegistryStatus, not_idle, BEDRAY_HISTORY_NOT_IDLE);
CHECK_ENUM(RegistryStatus, tickets_exhausted, BEDRAY_HISTORY_TICKETS_EXHAUSTED);
CHECK_ENUM(HistoryStatus, ok, BEDRAY_HISTORY_DETAIL_OK);
CHECK_ENUM(HistoryStatus, not_configured, BEDRAY_HISTORY_DETAIL_NOT_CONFIGURED);
CHECK_ENUM(HistoryStatus, recording_pending, BEDRAY_HISTORY_DETAIL_RECORDING_PENDING);
CHECK_ENUM(HistoryStatus, recording_attached, BEDRAY_HISTORY_DETAIL_RECORDING_ATTACHED);
CHECK_ENUM(HistoryStatus, stale_frame, BEDRAY_HISTORY_DETAIL_STALE_FRAME);
CHECK_ENUM(HistoryStatus, invalid_input, BEDRAY_HISTORY_DETAIL_INVALID_INPUT);
CHECK_ENUM(HistoryStatus, reset_not_applied, BEDRAY_HISTORY_DETAIL_RESET_NOT_APPLIED);
CHECK_ENUM(HistoryStatus, write_unavailable, BEDRAY_HISTORY_DETAIL_WRITE_UNAVAILABLE);
CHECK_ENUM(HistoryStatus, device_unavailable, BEDRAY_HISTORY_DETAIL_DEVICE_UNAVAILABLE);
CHECK_ENUM(HistoryStatus, unknown_token, BEDRAY_HISTORY_DETAIL_UNKNOWN_TOKEN);
CHECK_ENUM(HistoryStatus, token_exhausted, BEDRAY_HISTORY_DETAIL_TOKEN_EXHAUSTED);
CHECK_ENUM(HistoryOrdering, completed_only, BEDRAY_HISTORY_COMPLETED_ONLY);
CHECK_ENUM(HistoryOrdering, same_queue_barrier, BEDRAY_HISTORY_SAME_QUEUE_BARRIER);
CHECK_ENUM(HistoryOrdering, cross_queue_semaphore, BEDRAY_HISTORY_CROSS_QUEUE_SEMAPHORE);
CHECK_ENUM(SubmitOutcome, accepted, BEDRAY_HISTORY_ACCEPTED);
CHECK_ENUM(SubmitOutcome, rejected, BEDRAY_HISTORY_REJECTED);
CHECK_ENUM(SubmitOutcome, unknown, BEDRAY_HISTORY_UNKNOWN);
CHECK_ENUM(HistoryReset, none, BEDRAY_HISTORY_RESET_NONE);
CHECK_ENUM(HistoryReset, first_frame, BEDRAY_HISTORY_RESET_FIRST_FRAME);
CHECK_ENUM(HistoryReset, device, BEDRAY_HISTORY_RESET_DEVICE);
CHECK_ENUM(HistoryReset, target, BEDRAY_HISTORY_RESET_TARGET);
CHECK_ENUM(HistoryReset, pipeline, BEDRAY_HISTORY_RESET_PIPELINE);
CHECK_ENUM(HistoryReset, scene, BEDRAY_HISTORY_RESET_SCENE);
CHECK_ENUM(HistoryReset, camera, BEDRAY_HISTORY_RESET_CAMERA);
CHECK_ENUM(HistoryReset, extent, BEDRAY_HISTORY_RESET_EXTENT);
CHECK_ENUM(HistoryReset, invalid_input, BEDRAY_HISTORY_RESET_INVALID_INPUT);
CHECK_ENUM(HistoryReset, discontinuity, BEDRAY_HISTORY_RESET_DISCONTINUITY);
CHECK_ENUM(HistoryReset, signal, BEDRAY_HISTORY_RESET_SIGNAL);
CHECK_ENUM(HistoryReset, requested, BEDRAY_HISTORY_RESET_REQUESTED);
CHECK_ENUM(HistoryReset, device_lost, BEDRAY_HISTORY_RESET_DEVICE_LOST);
CHECK_ENUM(HistoryReset, reconfigured, BEDRAY_HISTORY_RESET_RECONFIGURED);
#undef CHECK_ENUM
static_assert(static_cast<uint32_t>(HistoryResetPolicy{}.sensitive_to) == BEDRAY_HISTORY_DEFAULT_SENSITIVITY);
static_assert(kMaxHistorySlots == BEDRAY_HISTORY_MAX_SLOTS && kMaxHistoryQueues == BEDRAY_HISTORY_MAX_QUEUES);
uint32_t status(RegistryStatus s) noexcept { return static_cast<uint32_t>(s); }
HistoryImage image(const BedrayHistoryImage& v) noexcept { return {v.image, v.memory, v.offset, v.size}; }
BedrayHistoryImage image_out(const HistoryImage& v) noexcept { return {v.image, v.memory, v.offset, v.size}; }
SubmissionTicket ticket(const BedrayHistoryTicket& v) noexcept { return {v.id, v.device_generation, v.queue, v.serial}; }
BedrayHistoryTicket ticket_out(const SubmissionTicket& v) noexcept { return {v.id, v.device_generation, v.queue, v.serial}; }
BedrayHistoryUses uses_out(const QueueUses& v) noexcept {
    BedrayHistoryUses r{};
    for (uint32_t i = 0; i < BEDRAY_HISTORY_MAX_QUEUES; ++i) r.serial[i] = v.serial[i];
    r.unknown = v.unknown ? 1u : 0u;
    return r;
}
BedrayHistoryRetired retired_out(const RetiredHistoryImages& v) noexcept {
    BedrayHistoryRetired r{};
    for (uint32_t i = 0; i < v.count; ++i) { r.images[i] = image_out(v.images[i]); r.uses[i] = uses_out(v.uses[i]); }
    r.count = v.count; r.device_generation = v.device_generation; r.completion_known = v.completion_known ? 1u : 0u;
    return r;
}
}

extern "C" {
uint32_t bedray_history_abi_version(void) try { return BEDRAY_HISTORY_ABI_VERSION; } catch (...) { return 0; }
BedrayHistoryStatus bedray_history_abi_info(BedrayHistoryAbiInfo* out, uint32_t out_size) try {
    if (!out || out_size < sizeof(BedrayHistoryAbiInfo)) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    BedrayHistoryAbiInfo info{};
    info.version = BEDRAY_HISTORY_ABI_VERSION; info.sizeof_info = sizeof(info);
    info.sizeof_image = sizeof(BedrayHistoryImage); info.align_image = alignof(BedrayHistoryImage);
    info.sizeof_key = sizeof(BedrayHistoryKey); info.align_key = alignof(BedrayHistoryKey);
    info.sizeof_uses = sizeof(BedrayHistoryUses); info.align_uses = alignof(BedrayHistoryUses);
    info.sizeof_recording = sizeof(BedrayHistoryRecording); info.align_recording = alignof(BedrayHistoryRecording);
    info.sizeof_ticket = sizeof(BedrayHistoryTicket); info.align_ticket = alignof(BedrayHistoryTicket);
    info.sizeof_retired = sizeof(BedrayHistoryRetired); info.align_retired = alignof(BedrayHistoryRetired);
#define BEDRAY_OFFSET(T, F) static_cast<uint32_t>(offsetof(T, F))
    const uint32_t image_offsets[] = {BEDRAY_OFFSET(BedrayHistoryImage, image), BEDRAY_OFFSET(BedrayHistoryImage, memory),
        BEDRAY_OFFSET(BedrayHistoryImage, offset), BEDRAY_OFFSET(BedrayHistoryImage, size)};
    const uint32_t key_offsets[] = {BEDRAY_OFFSET(BedrayHistoryKey, device_generation), BEDRAY_OFFSET(BedrayHistoryKey, target_generation),
        BEDRAY_OFFSET(BedrayHistoryKey, pipeline_generation), BEDRAY_OFFSET(BedrayHistoryKey, scene_generation),
        BEDRAY_OFFSET(BedrayHistoryKey, camera_epoch), BEDRAY_OFFSET(BedrayHistoryKey, signal_revision),
        BEDRAY_OFFSET(BedrayHistoryKey, width), BEDRAY_OFFSET(BedrayHistoryKey, height)};
    const uint32_t uses_offsets[] = {BEDRAY_OFFSET(BedrayHistoryUses, serial), BEDRAY_OFFSET(BedrayHistoryUses, unknown)};
    const uint32_t recording_offsets[] = {BEDRAY_OFFSET(BedrayHistoryRecording, token), BEDRAY_OFFSET(BedrayHistoryRecording, frame),
        BEDRAY_OFFSET(BedrayHistoryRecording, queue), BEDRAY_OFFSET(BedrayHistoryRecording, read_slot),
        BEDRAY_OFFSET(BedrayHistoryRecording, read_image), BEDRAY_OFFSET(BedrayHistoryRecording, write_slot),
        BEDRAY_OFFSET(BedrayHistoryRecording, write_image), BEDRAY_OFFSET(BedrayHistoryRecording, reset),
        BEDRAY_OFFSET(BedrayHistoryRecording, write_after), BEDRAY_OFFSET(BedrayHistoryRecording, read_producer_queue),
        BEDRAY_OFFSET(BedrayHistoryRecording, read_producer_serial)};
    const uint32_t ticket_offsets[] = {BEDRAY_OFFSET(BedrayHistoryTicket, id), BEDRAY_OFFSET(BedrayHistoryTicket, device_generation),
        BEDRAY_OFFSET(BedrayHistoryTicket, queue), BEDRAY_OFFSET(BedrayHistoryTicket, serial)};
    const uint32_t retired_offsets[] = {BEDRAY_OFFSET(BedrayHistoryRetired, images), BEDRAY_OFFSET(BedrayHistoryRetired, uses),
        BEDRAY_OFFSET(BedrayHistoryRetired, count), BEDRAY_OFFSET(BedrayHistoryRetired, device_generation),
        BEDRAY_OFFSET(BedrayHistoryRetired, completion_known)};
#undef BEDRAY_OFFSET
    std::copy_n(image_offsets, 4, info.image_offsets); std::copy_n(key_offsets, 8, info.key_offsets);
    std::copy_n(uses_offsets, 2, info.uses_offsets); std::copy_n(recording_offsets, 11, info.recording_offsets);
    std::copy_n(ticket_offsets, 4, info.ticket_offsets); std::copy_n(retired_offsets, 5, info.retired_offsets);
    *out = info;
    return BEDRAY_HISTORY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_create(uint64_t budget, BedrayHistory** out) try {
    if (!out) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    *out = nullptr;
    *out = new BedrayHistory(budget);
    return BEDRAY_HISTORY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_destroy(BedrayHistory* h) try {
    if (!h) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    if (h->registry.timeline().active() || h->registry.retained_count() != 0 || h->registry.configured_bytes() != 0)
        return BEDRAY_HISTORY_IN_USE;
    delete h; return BEDRAY_HISTORY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_memory(BedrayHistory* h, uint64_t* configured, uint64_t* retained, uint64_t* budget) try {
    if (!h || !configured || !retained || !budget) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    *configured = h->registry.configured_bytes();
    *retained = h->registry.retained_bytes();
    *budget = h->registry.budget_bytes();
    return BEDRAY_HISTORY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_begin_device(BedrayHistory* h, uint64_t generation) try {
    return h ? status(h->registry.begin_device(generation)) : BEDRAY_HISTORY_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_device_lost(BedrayHistory* h) try {
    return h ? status(h->registry.device_lost()) : BEDRAY_HISTORY_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_device_idle(BedrayHistory* h) try {
    return h ? status(h->registry.device_idle()) : BEDRAY_HISTORY_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_retire_device(BedrayHistory* h) try {
    return h ? status(h->registry.retire_device()) : BEDRAY_HISTORY_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_add(BedrayHistory* h, const char* name, uint32_t bytes,
                                       uint32_t sensitivity, uint64_t* out) try {
    constexpr uint32_t allowed = BEDRAY_HISTORY_RESET_TARGET | BEDRAY_HISTORY_RESET_SCENE |
        BEDRAY_HISTORY_RESET_CAMERA | BEDRAY_HISTORY_RESET_DISCONTINUITY;
    if (!h || !name || !out || bytes == 0 || bytes > 256 || (sensitivity & ~allowed) != 0)
        return BEDRAY_HISTORY_INVALID_ARGUMENT;
    for (uint32_t i = 0; i < bytes; ++i) if (name[i] == '\0') return BEDRAY_HISTORY_INVALID_ARGUMENT;
    SignalId id;
    const auto result = h->registry.add(std::string_view(name, bytes), HistoryResetPolicy{static_cast<HistoryReset>(sensitivity)}, id);
    if (result == RegistryStatus::ok) *out = id.value;
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_remove(BedrayHistory* h, uint64_t id) try {
    return h ? status(h->registry.remove(SignalId{id})) : BEDRAY_HISTORY_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_configure(BedrayHistory* h, uint64_t id,
                                             const BedrayHistoryImage* images, uint32_t count) try {
    if (!h || !images || count > BEDRAY_HISTORY_MAX_SLOTS) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    HistoryImageSet set{}; set.count = count;
    for (uint32_t i = 0; i < count; ++i) set.images[i] = image(images[i]);
    return status(h->registry.configure(SignalId{id}, set));
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_invalidate(BedrayHistory* h, uint64_t id, uint32_t* detail) try {
    if (!h || !detail) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    HistoryStatus d = HistoryStatus::ok; auto s = h->registry.invalidate(SignalId{id}, d);
    *detail = static_cast<uint32_t>(d); return status(s);
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_invalidate_after_pending(BedrayHistory* h, uint64_t id) try {
    return h ? status(h->registry.invalidate_after_pending(SignalId{id})) : BEDRAY_HISTORY_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_prepare(BedrayHistory* h, uint64_t id, const BedrayHistoryKey* k,
    uint64_t frame, uint32_t queue, uint32_t ordering, BedrayHistoryRecording* out, uint32_t* detail) try {
    // The current host integration cannot provide the other ordering modes.
    if (!h || !k || !out || !detail || ordering != BEDRAY_HISTORY_COMPLETED_ONLY)
        return BEDRAY_HISTORY_INVALID_ARGUMENT;
    HistoryKey key{k->device_generation, k->target_generation, k->pipeline_generation, k->scene_generation,
                   k->camera_epoch, k->signal_revision, k->width, k->height};
    HistoryRecording r{}; HistoryStatus d = HistoryStatus::ok;
    const auto s = h->registry.prepare(SignalId{id}, key, frame, queue, static_cast<HistoryOrdering>(ordering), r, d);
    *detail = static_cast<uint32_t>(d);
    if (s == RegistryStatus::ok) {
        BedrayHistoryRecording v{};
        v.token = r.token; v.frame = r.frame; v.queue = r.queue; v.read_slot = r.read_slot;
        v.read_image = r.read_image; v.write_slot = r.write_slot; v.write_image = r.write_image;
        v.reset = static_cast<uint32_t>(r.reset); v.write_after = uses_out(r.write_after);
        v.read_producer_queue = r.read_producer_queue; v.read_producer_serial = r.read_producer_serial;
        *out = v;
    }
    return status(s);
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_cancel(BedrayHistory* h, uint64_t id, uint64_t token, uint32_t* detail) try {
    if (!h || !detail) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    HistoryStatus d = HistoryStatus::ok; auto s = h->registry.cancel(SignalId{id}, token, d);
    *detail = static_cast<uint32_t>(d); return status(s);
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_begin_submission(BedrayHistory* h, uint32_t queue, uint64_t serial,
                                                     BedrayHistoryTicket* out) try {
    if (!h || !out) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    SubmissionTicket t{}; auto s = h->registry.begin_submission(queue, serial, t);
    if (s == RegistryStatus::ok) *out = ticket_out(t);
    return status(s);
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_attach(BedrayHistory* h, const BedrayHistoryTicket* t, uint64_t id,
                                           uint64_t token, uint32_t reset, uint32_t* detail) try {
    if (!h || !t || !detail || reset > 1) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    HistoryStatus d = HistoryStatus::ok; auto s = h->registry.attach(ticket(*t), SignalId{id}, token, reset != 0, d);
    *detail = static_cast<uint32_t>(d); return status(s);
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_end_submission(BedrayHistory* h, const BedrayHistoryTicket* t, uint32_t outcome) try {
    if (!h || !t) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    return status(h->registry.end_submission(ticket(*t), static_cast<SubmitOutcome>(outcome)));
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_complete(BedrayHistory* h, uint32_t queue, uint64_t serial) try {
    return h ? status(h->registry.complete(queue, serial)) : BEDRAY_HISTORY_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_pop_finished(BedrayHistory* h, BedrayHistoryRetired* out, uint32_t* has) try {
    if (!h || !out || !has) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    RetiredHistoryImages r{}; *has = h->registry.pop_finished(r) ? 1u : 0u;
    if (*has) *out = retired_out(r);
    return BEDRAY_HISTORY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
BedrayHistoryStatus bedray_history_pop_orphaned(BedrayHistory* h, BedrayHistoryRetired* out, uint32_t* has) try {
    if (!h || !out || !has) return BEDRAY_HISTORY_INVALID_ARGUMENT;
    RetiredHistoryImages r{}; *has = h->registry.pop_orphaned(r) ? 1u : 0u;
    if (*has) *out = retired_out(r);
    return BEDRAY_HISTORY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_HISTORY_INTERNAL_ERROR;
}
}
