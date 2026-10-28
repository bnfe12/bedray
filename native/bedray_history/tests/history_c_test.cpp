/*
 * Contract test for the exported C ABI in bedray/history_c.h.
 *
 * The wrapper is compiled into bedray_core.dll; this test builds the same
 * translation unit over the static registry and exercises the C surface exactly
 * as a host would: ABI discovery, validation, the prepare/attach/submit/complete
 * lifecycle, retention, device loss, an unknown submit outcome and the export's
 * own guards.
 */

#include "bedray/history_c.h"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>

#define CHECK(condition)                                                                       \
    do {                                                                                       \
        if (!(condition)) {                                                                    \
            std::fprintf(stderr, "check failed: %s (%s:%d)\n", #condition, __FILE__, __LINE__); \
            std::abort();                                                                      \
        }                                                                                      \
    } while (0)

/* Wire layout the DLL publishes through bedray_history_abi_info (x64). */
static_assert(sizeof(BedrayHistoryImage) == 32, "image wire size");
static_assert(alignof(BedrayHistoryImage) == 8, "image wire alignment");
static_assert(sizeof(BedrayHistoryKey) == 56, "key wire size");
static_assert(alignof(BedrayHistoryKey) == 8, "key wire alignment");
static_assert(sizeof(BedrayHistoryUses) == 40, "uses wire size");
static_assert(alignof(BedrayHistoryUses) == 8, "uses wire alignment");
static_assert(sizeof(BedrayHistoryRecording) == 112, "recording wire size");
static_assert(offsetof(BedrayHistoryRecording, write_image) == 40, "recording write_image offset");
static_assert(offsetof(BedrayHistoryRecording, read_producer_serial) == 104,
              "recording read_producer_serial offset");
static_assert(sizeof(BedrayHistoryTicket) == 32, "ticket wire size");
static_assert(sizeof(BedrayHistoryRetired) == 312, "retired wire size");
static_assert(sizeof(BedrayHistoryAbiInfo) == 192, "abi info wire size");
static_assert(BEDRAY_HISTORY_MAX_QUEUES == 4, "queue capacity");
static_assert(BEDRAY_HISTORY_MAX_SLOTS == 4, "slot capacity");
static_assert(BEDRAY_HISTORY_DEFAULT_SENSITIVITY ==
                  (BEDRAY_HISTORY_RESET_TARGET | BEDRAY_HISTORY_RESET_SCENE |
                   BEDRAY_HISTORY_RESET_CAMERA | BEDRAY_HISTORY_RESET_DISCONTINUITY),
              "default sensitivity");
static_assert(BEDRAY_HISTORY_ABI_VERSION == 2, "abi version");

namespace {

BedrayHistoryImage image(std::uint64_t id, std::uint64_t bytes) {
    BedrayHistoryImage value{};
    value.image = id;
    value.memory = id * 1000;
    value.offset = 0;
    value.size = bytes;
    return value;
}

BedrayHistoryKey key(std::uint64_t device = 1, std::uint64_t signal_revision = 1) {
    BedrayHistoryKey value{};
    value.device_generation = device;
    value.target_generation = 1;
    value.pipeline_generation = 1;
    value.scene_generation = 1;
    value.camera_epoch = 1;
    value.signal_revision = signal_revision;
    value.width = 64;
    value.height = 64;
    return value;
}

/* The exported ABI takes an explicit byte count: never include the terminator. */
template <std::size_t N>
constexpr std::uint32_t name_len(const char (&)[N]) noexcept {
    return static_cast<std::uint32_t>(N - 1);
}

void memory(const BedrayHistory* history, std::uint64_t expected_configured,
            std::uint64_t expected_retained, std::uint64_t expected_budget) {
    std::uint64_t configured = 0;
    std::uint64_t retained = 0;
    std::uint64_t budget = 0;
    CHECK(bedray_history_memory(const_cast<BedrayHistory*>(history), &configured, &retained, &budget) ==
          BEDRAY_HISTORY_OK);
    CHECK(configured == expected_configured);
    CHECK(retained == expected_retained);
    CHECK(budget == expected_budget);
}

void attach_accepted(BedrayHistory* history, std::uint64_t signal, std::uint64_t token,
                     std::uint32_t queue, std::uint64_t serial, bool reset_applied) {
    BedrayHistoryTicket ticket{};
    CHECK(bedray_history_begin_submission(history, queue, serial, &ticket) == BEDRAY_HISTORY_OK);
    std::uint32_t detail = 0xFFFFFFFFu;
    CHECK(bedray_history_attach(history, &ticket, signal, token, reset_applied ? 1u : 0u, &detail) ==
          BEDRAY_HISTORY_OK);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_OK);
    CHECK(bedray_history_end_submission(history, &ticket, BEDRAY_HISTORY_ACCEPTED) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_complete(history, queue, serial) == BEDRAY_HISTORY_OK);
}

void test_abi() {
    CHECK(bedray_history_abi_version() == BEDRAY_HISTORY_ABI_VERSION);

    BedrayHistoryAbiInfo info{};
    CHECK(bedray_history_abi_info(&info, static_cast<std::uint32_t>(sizeof(info))) == BEDRAY_HISTORY_OK);
    CHECK(info.version == BEDRAY_HISTORY_ABI_VERSION);
    CHECK(info.sizeof_info == sizeof(BedrayHistoryAbiInfo));

    CHECK(info.sizeof_image == sizeof(BedrayHistoryImage));
    CHECK(info.align_image == alignof(BedrayHistoryImage));
    CHECK(info.image_offsets[0] == offsetof(BedrayHistoryImage, image));
    CHECK(info.image_offsets[1] == offsetof(BedrayHistoryImage, memory));
    CHECK(info.image_offsets[2] == offsetof(BedrayHistoryImage, offset));
    CHECK(info.image_offsets[3] == offsetof(BedrayHistoryImage, size));

    CHECK(info.sizeof_key == sizeof(BedrayHistoryKey));
    CHECK(info.align_key == alignof(BedrayHistoryKey));
    CHECK(info.key_offsets[0] == offsetof(BedrayHistoryKey, device_generation));
    CHECK(info.key_offsets[1] == offsetof(BedrayHistoryKey, target_generation));
    CHECK(info.key_offsets[2] == offsetof(BedrayHistoryKey, pipeline_generation));
    CHECK(info.key_offsets[3] == offsetof(BedrayHistoryKey, scene_generation));
    CHECK(info.key_offsets[4] == offsetof(BedrayHistoryKey, camera_epoch));
    CHECK(info.key_offsets[5] == offsetof(BedrayHistoryKey, signal_revision));
    CHECK(info.key_offsets[6] == offsetof(BedrayHistoryKey, width));
    CHECK(info.key_offsets[7] == offsetof(BedrayHistoryKey, height));

    CHECK(info.sizeof_uses == sizeof(BedrayHistoryUses));
    CHECK(info.align_uses == alignof(BedrayHistoryUses));
    CHECK(info.uses_offsets[0] == offsetof(BedrayHistoryUses, serial));
    CHECK(info.uses_offsets[1] == offsetof(BedrayHistoryUses, unknown));

    CHECK(info.sizeof_recording == sizeof(BedrayHistoryRecording));
    CHECK(info.align_recording == alignof(BedrayHistoryRecording));
    CHECK(info.recording_offsets[0] == offsetof(BedrayHistoryRecording, token));
    CHECK(info.recording_offsets[1] == offsetof(BedrayHistoryRecording, frame));
    CHECK(info.recording_offsets[2] == offsetof(BedrayHistoryRecording, queue));
    CHECK(info.recording_offsets[3] == offsetof(BedrayHistoryRecording, read_slot));
    CHECK(info.recording_offsets[4] == offsetof(BedrayHistoryRecording, read_image));
    CHECK(info.recording_offsets[5] == offsetof(BedrayHistoryRecording, write_slot));
    CHECK(info.recording_offsets[6] == offsetof(BedrayHistoryRecording, write_image));
    CHECK(info.recording_offsets[7] == offsetof(BedrayHistoryRecording, reset));
    CHECK(info.recording_offsets[8] == offsetof(BedrayHistoryRecording, write_after));
    CHECK(info.recording_offsets[9] == offsetof(BedrayHistoryRecording, read_producer_queue));
    CHECK(info.recording_offsets[10] == offsetof(BedrayHistoryRecording, read_producer_serial));

    CHECK(info.sizeof_ticket == sizeof(BedrayHistoryTicket));
    CHECK(info.align_ticket == alignof(BedrayHistoryTicket));
    CHECK(info.ticket_offsets[0] == offsetof(BedrayHistoryTicket, id));
    CHECK(info.ticket_offsets[1] == offsetof(BedrayHistoryTicket, device_generation));
    CHECK(info.ticket_offsets[2] == offsetof(BedrayHistoryTicket, queue));
    CHECK(info.ticket_offsets[3] == offsetof(BedrayHistoryTicket, serial));

    CHECK(info.sizeof_retired == sizeof(BedrayHistoryRetired));
    CHECK(info.align_retired == alignof(BedrayHistoryRetired));
    CHECK(info.retired_offsets[0] == offsetof(BedrayHistoryRetired, images));
    CHECK(info.retired_offsets[1] == offsetof(BedrayHistoryRetired, uses));
    CHECK(info.retired_offsets[2] == offsetof(BedrayHistoryRetired, count));
    CHECK(info.retired_offsets[3] == offsetof(BedrayHistoryRetired, device_generation));
    CHECK(info.retired_offsets[4] == offsetof(BedrayHistoryRetired, completion_known));

    BedrayHistoryAbiInfo small{};
    CHECK(bedray_history_abi_info(&small, static_cast<std::uint32_t>(sizeof(small) - 1)) ==
          BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_abi_info(nullptr, static_cast<std::uint32_t>(sizeof(small))) ==
          BEDRAY_HISTORY_INVALID_ARGUMENT);
}

void test_validation() {
    BedrayHistory* history = nullptr;
    CHECK(bedray_history_create(65536, nullptr) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_create(65536, &history) == BEDRAY_HISTORY_OK);
    CHECK(history != nullptr);
    memory(history, 0, 0, 65536);

    std::uint64_t signal = 0;
    CHECK(bedray_history_add(history, "bedray.test.history", name_len("bedray.test.history"),
                             BEDRAY_HISTORY_DEFAULT_SENSITIVITY, &signal) == BEDRAY_HISTORY_OK);
    CHECK(signal != 0);
    std::uint64_t duplicate = 0;
    CHECK(bedray_history_add(history, "bedray.test.history", name_len("bedray.test.history"),
                             BEDRAY_HISTORY_DEFAULT_SENSITIVITY, &duplicate) == BEDRAY_HISTORY_DUPLICATE_SIGNAL);
    const char embedded_nul[2] = {'a', '\0'};
    CHECK(bedray_history_add(history, embedded_nul, 2, 0, &duplicate) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_add(history, "", 0, 0, &duplicate) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_add(history, "bedray.test.pipeline", name_len("bedray.test.pipeline"),
                             BEDRAY_HISTORY_RESET_PIPELINE, &duplicate) == BEDRAY_HISTORY_INVALID_ARGUMENT);

    /* Before any device epoch, prepare/configure refuse but add is allowed. */
    BedrayHistoryKey probe = key();
    BedrayHistoryRecording recording{};
    std::uint32_t detail = 0;
    CHECK(bedray_history_prepare(history, signal, &probe, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_DEVICE_UNAVAILABLE);
    BedrayHistoryImage pair[2] = {image(1, 4096), image(2, 4096)};
    CHECK(bedray_history_configure(history, signal, pair, 2) == BEDRAY_HISTORY_DEVICE_UNAVAILABLE);

    CHECK(bedray_history_begin_device(history, 0) == BEDRAY_HISTORY_INVALID_SERIAL);
    CHECK(bedray_history_begin_device(history, 1) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_begin_device(history, 2) == BEDRAY_HISTORY_DEVICE_UNRESOLVED);
    CHECK(bedray_history_destroy(history) == BEDRAY_HISTORY_IN_USE);

    CHECK(bedray_history_configure(history, signal + 1, pair, 2) == BEDRAY_HISTORY_UNKNOWN_SIGNAL);
    CHECK(bedray_history_configure(history, signal, nullptr, 2) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_configure(history, signal, pair, 1) == BEDRAY_HISTORY_INVALID_IMAGES);
    CHECK(bedray_history_configure(history, signal, pair, 5) == BEDRAY_HISTORY_INVALID_ARGUMENT);

    /* Overlap inside one set makes the set itself invalid; cross-set overlap
     * against a still-known image is the aliasing check. */
    BedrayHistoryImage overlapping[2] = {image(1, 4096), image(1, 4096)};
    overlapping[1].offset = 2048;
    CHECK(bedray_history_configure(history, signal, overlapping, 2) == BEDRAY_HISTORY_INVALID_IMAGES);

    CHECK(bedray_history_configure(history, signal, pair, 2) == BEDRAY_HISTORY_OK);
    memory(history, 8192, 0, 65536);

    BedrayHistoryImage too_large[2] = {image(5, 40960), image(6, 40960)};
    CHECK(bedray_history_configure(history, signal, too_large, 2) == BEDRAY_HISTORY_OVER_BUDGET);
    memory(history, 8192, 0, 65536);

    BedrayHistoryKey zero_extent = key();
    zero_extent.width = 0;
    CHECK(bedray_history_prepare(history, signal, &zero_extent, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_INVALID_INPUT);
    probe = key();
    CHECK(bedray_history_prepare(history, signal, &probe, 1, 0, BEDRAY_HISTORY_SAME_QUEUE_BARRIER,
                                 &recording, &detail) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_prepare(history, signal, &probe, 0, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_STALE_FRAME);

    /* A second signal with zero sensitivity is legal and stays unconfigured. */
    std::uint64_t second = 0;
    CHECK(bedray_history_add(history, "bedray.test.second", name_len("bedray.test.second"), 0, &second) ==
          BEDRAY_HISTORY_OK);
    probe = key();
    CHECK(bedray_history_prepare(history, second, &probe, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_NOT_CONFIGURED);
    CHECK(bedray_history_remove(history, second) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_remove(history, second) == BEDRAY_HISTORY_UNKNOWN_SIGNAL);
    memory(history, 8192, 0, 65536);
}

void test_lifecycle() {
    BedrayHistory* history = nullptr;
    CHECK(bedray_history_create(65536, &history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_begin_device(history, 1) == BEDRAY_HISTORY_OK);
    std::uint64_t signal = 0;
    CHECK(bedray_history_add(history, "bedray.test.lifecycle", name_len("bedray.test.lifecycle"),
                             BEDRAY_HISTORY_DEFAULT_SENSITIVITY, &signal) == BEDRAY_HISTORY_OK);
    BedrayHistoryImage first_set[2] = {image(1, 4096), image(2, 4096)};
    CHECK(bedray_history_configure(history, signal, first_set, 2) == BEDRAY_HISTORY_OK);
    memory(history, 8192, 0, 65536);

    BedrayHistoryKey frame_key = key();
    BedrayHistoryRecording recording{};
    std::uint32_t detail = 0xFFFFFFFFu;
    CHECK(bedray_history_prepare(history, signal, &frame_key, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_OK);
    CHECK(recording.frame == 1);
    CHECK(recording.queue == 0);
    CHECK(recording.token != 0);
    CHECK(recording.read_slot == -1);
    CHECK(recording.read_image == 0);
    CHECK(recording.read_producer_serial == 0);
    CHECK(recording.write_slot == 0);
    CHECK(recording.write_image == 1);
    /* The first configure itself reports `reconfigured`, and `evaluate` returns
     * every pending reason while no history is committed: bit, not equality. */
    CHECK((recording.reset & BEDRAY_HISTORY_RESET_FIRST_FRAME) != 0);
    CHECK((recording.reset & BEDRAY_HISTORY_RESET_RECONFIGURED) != 0);
    CHECK(recording.write_after.unknown == 0);
    CHECK(recording.write_after.serial[0] == 0);

    const std::uint64_t token1 = recording.token;
    CHECK(bedray_history_prepare(history, signal, &frame_key, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_RECORDING_PENDING);
    CHECK(bedray_history_invalidate(history, signal, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_RECORDING_PENDING);
    CHECK(bedray_history_remove(history, signal) == BEDRAY_HISTORY_SIGNAL_BUSY);
    CHECK(bedray_history_configure(history, signal, first_set, 2) == BEDRAY_HISTORY_SIGNAL_BUSY);

    BedrayHistoryTicket ticket{};
    CHECK(bedray_history_begin_submission(history, 4, 10, &ticket) == BEDRAY_HISTORY_INVALID_SERIAL);
    CHECK(bedray_history_begin_submission(history, 0, 0, &ticket) == BEDRAY_HISTORY_INVALID_SERIAL);
    CHECK(bedray_history_begin_submission(history, 0, 10, &ticket) == BEDRAY_HISTORY_OK);
    CHECK(ticket.id != 0);
    CHECK(ticket.device_generation == 1);
    CHECK(ticket.queue == 0);
    CHECK(ticket.serial == 10);
    BedrayHistoryTicket second_ticket{};
    CHECK(bedray_history_begin_submission(history, 1, 10, &second_ticket) == BEDRAY_HISTORY_SUBMISSION_OPEN);
    CHECK(bedray_history_complete(history, 0, 10) == BEDRAY_HISTORY_INVALID_SERIAL);

    CHECK(bedray_history_attach(history, &ticket, signal + 1, token1, 1, &detail) ==
          BEDRAY_HISTORY_UNKNOWN_SIGNAL);
    CHECK(bedray_history_attach(history, &ticket, signal, token1 + 1, 1, &detail) ==
          BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_UNKNOWN_TOKEN);
    CHECK(bedray_history_attach(history, &ticket, signal, token1, 2, &detail) ==
          BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_attach(history, &ticket, signal, token1, 0, &detail) ==
          BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_RESET_NOT_APPLIED);
    CHECK(bedray_history_attach(history, &ticket, signal, token1, 1, &detail) == BEDRAY_HISTORY_OK);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_OK);
    CHECK(bedray_history_attach(history, &ticket, signal, token1, 1, &detail) ==
          BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_RECORDING_ATTACHED);
    CHECK(bedray_history_cancel(history, signal, token1, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_RECORDING_ATTACHED);

    BedrayHistoryTicket forged = ticket;
    forged.id += 1;
    CHECK(bedray_history_end_submission(history, &forged, BEDRAY_HISTORY_ACCEPTED) ==
          BEDRAY_HISTORY_NO_SUBMISSION);
    CHECK(bedray_history_end_submission(history, &ticket, BEDRAY_HISTORY_ACCEPTED) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_attach(history, &ticket, signal, token1, 1, &detail) ==
          BEDRAY_HISTORY_NO_SUBMISSION);
    CHECK(bedray_history_begin_submission(history, 0, 10, &second_ticket) == BEDRAY_HISTORY_INVALID_SERIAL);
    CHECK(bedray_history_complete(history, 0, 10) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_complete(history, 0, 9) == BEDRAY_HISTORY_INVALID_SERIAL);
    CHECK(bedray_history_complete(history, 0, 11) == BEDRAY_HISTORY_INVALID_SERIAL);

    /* Frame two reads the committed slot; a cancel must keep it readable. */
    CHECK(bedray_history_prepare(history, signal, &frame_key, 2, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_OK);
    CHECK(recording.reset == BEDRAY_HISTORY_RESET_NONE);
    CHECK(recording.read_slot == 0);
    CHECK(recording.read_image == 1);
    CHECK(recording.read_producer_queue == 0);
    CHECK(recording.read_producer_serial == 10);
    CHECK(recording.write_slot == 1);
    CHECK(recording.write_image == 2);
    const std::uint64_t token2 = recording.token;
    CHECK(bedray_history_cancel(history, signal, token2, &detail) == BEDRAY_HISTORY_OK);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_OK);

    CHECK(bedray_history_prepare(history, signal, &frame_key, 2, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    CHECK(recording.read_slot == 0);
    CHECK(recording.read_image == 1);
    CHECK(recording.write_slot == 1);
    CHECK(recording.read_producer_serial == 10);
    CHECK(bedray_history_prepare(history, signal, &frame_key, 3, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_RECORDING_PENDING);

    attach_accepted(history, signal, recording.token, 0, 20, false);

    CHECK(bedray_history_prepare(history, signal, &frame_key, 2, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_SEE_DETAIL);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_STALE_FRAME);

    CHECK(bedray_history_invalidate(history, signal, &detail) == BEDRAY_HISTORY_OK);
    CHECK(detail == BEDRAY_HISTORY_DETAIL_OK);
    CHECK(bedray_history_prepare(history, signal, &frame_key, 3, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    CHECK((recording.reset & BEDRAY_HISTORY_RESET_REQUESTED) != 0);
    CHECK(recording.read_slot == -1);
    CHECK(recording.write_slot == 0);
    CHECK(recording.write_image == 1);
    CHECK(bedray_history_cancel(history, signal, recording.token, &detail) == BEDRAY_HISTORY_OK);

    /* Reconfiguration retains the old set until its uses are complete. */
    BedrayHistoryImage second_set[2] = {image(3, 5000), image(4, 5000)};
    CHECK(bedray_history_configure(history, signal, second_set, 2) == BEDRAY_HISTORY_OK);
    memory(history, 10000, 8192, 65536);
    CHECK(bedray_history_configure(history, signal, first_set, 2) == BEDRAY_HISTORY_ALIASED_IMAGES);

    BedrayHistoryRetired retired{};
    std::uint32_t has = 0xFFFFFFFFu;
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 1);
    CHECK(retired.count == 2);
    CHECK(retired.device_generation == 1);
    CHECK(retired.completion_known == 1);
    CHECK(retired.images[0].image == 1);
    CHECK(retired.images[1].image == 2);
    CHECK(retired.uses[0].serial[0] == 20);
    CHECK(retired.uses[1].serial[0] == 20);
    CHECK(retired.uses[0].unknown == 0);
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 0);
    memory(history, 10000, 0, 65536);

    /* A reconfigured signal cannot read history, even across a cancel. */
    CHECK(bedray_history_prepare(history, signal, &frame_key, 4, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    /* Requested was already pending; the reconfigure bit must be present too. */
    CHECK((recording.reset & BEDRAY_HISTORY_RESET_RECONFIGURED) != 0);
    CHECK(recording.read_slot == -1);
    CHECK(recording.write_slot == 0);
    CHECK(recording.write_image == 3);
    attach_accepted(history, signal, recording.token, 0, 30, true);

    /* No recording is pending: the deferred variant equals invalidate(). */
    CHECK(bedray_history_invalidate_after_pending(history, signal) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_invalidate_after_pending(history, signal + 1) == BEDRAY_HISTORY_UNKNOWN_SIGNAL);

    CHECK(bedray_history_retire_device(history) == BEDRAY_HISTORY_NOT_IDLE);
    CHECK(bedray_history_device_idle(history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_retire_device(history) == BEDRAY_HISTORY_OK);
    memory(history, 0, 10000, 65536);
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 1);
    CHECK(retired.count == 2);
    CHECK(retired.images[0].image == 3);
    CHECK(retired.images[1].image == 4);
    CHECK(retired.uses[0].serial[0] == 30);
    CHECK(retired.completion_known == 1);
    memory(history, 0, 0, 65536);
    CHECK(bedray_history_destroy(history) == BEDRAY_HISTORY_OK);
}

void test_device_loss() {
    BedrayHistory* history = nullptr;
    CHECK(bedray_history_create(65536, &history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_begin_device(history, 7) == BEDRAY_HISTORY_OK);
    std::uint64_t signal = 0;
    CHECK(bedray_history_add(history, "bedray.test.lost", name_len("bedray.test.lost"),
                             BEDRAY_HISTORY_DEFAULT_SENSITIVITY, &signal) == BEDRAY_HISTORY_OK);
    BedrayHistoryImage images[2] = {image(10, 4096), image(11, 4096)};
    CHECK(bedray_history_configure(history, signal, images, 2) == BEDRAY_HISTORY_OK);
    memory(history, 8192, 0, 65536);

    BedrayHistoryKey frame_key = key(7);
    BedrayHistoryRecording recording{};
    std::uint32_t detail = 0;
    CHECK(bedray_history_prepare(history, signal, &frame_key, 1, 1, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    BedrayHistoryTicket ticket{};
    CHECK(bedray_history_begin_submission(history, 1, 100, &ticket) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_attach(history, &ticket, signal, recording.token, 1, &detail) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_device_lost(history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_begin_submission(history, 1, 101, &ticket) == BEDRAY_HISTORY_DEVICE_UNAVAILABLE);
    CHECK(bedray_history_begin_device(history, 8) == BEDRAY_HISTORY_DEVICE_UNRESOLVED);

    BedrayHistoryRetired retired{};
    std::uint32_t has = 0xFFFFFFFFu;
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 0);
    CHECK(bedray_history_pop_orphaned(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 1);
    CHECK(retired.count == 2);
    CHECK(retired.device_generation == 7);
    CHECK(retired.completion_known == 0);
    CHECK(retired.images[0].image == 10);
    CHECK(retired.images[1].image == 11);
    CHECK(bedray_history_pop_orphaned(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 0);
    memory(history, 0, 0, 65536);

    CHECK(bedray_history_begin_device(history, 8) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_device_idle(history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_retire_device(history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_destroy(history) == BEDRAY_HISTORY_OK);
}

void test_unknown_outcome() {
    BedrayHistory* history = nullptr;
    CHECK(bedray_history_create(65536, &history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_begin_device(history, 1) == BEDRAY_HISTORY_OK);
    std::uint64_t signal = 0;
    CHECK(bedray_history_add(history, "bedray.test.unknown", name_len("bedray.test.unknown"),
                             BEDRAY_HISTORY_DEFAULT_SENSITIVITY, &signal) == BEDRAY_HISTORY_OK);
    BedrayHistoryImage images[2] = {image(20, 4096), image(21, 4096)};
    CHECK(bedray_history_configure(history, signal, images, 2) == BEDRAY_HISTORY_OK);

    BedrayHistoryKey frame_key = key();
    BedrayHistoryRecording recording{};
    std::uint32_t detail = 0;
    CHECK(bedray_history_prepare(history, signal, &frame_key, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    BedrayHistoryTicket ticket{};
    CHECK(bedray_history_begin_submission(history, 0, 5, &ticket) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_attach(history, &ticket, signal, recording.token, 1, &detail) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_end_submission(history, &ticket, BEDRAY_HISTORY_UNKNOWN) == BEDRAY_HISTORY_OK);

    BedrayHistoryImage next[2] = {image(22, 4096), image(23, 4096)};
    CHECK(bedray_history_configure(history, signal, next, 2) == BEDRAY_HISTORY_OK);
    memory(history, 8192, 8192, 65536);

    BedrayHistoryRetired retired{};
    std::uint32_t has = 0xFFFFFFFFu;
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 0);
    CHECK(bedray_history_device_idle(history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 1);
    CHECK(retired.images[0].image == 20);
    CHECK(retired.images[1].image == 21);
    CHECK(retired.uses[0].unknown == 0);
    memory(history, 8192, 0, 65536);

    CHECK(bedray_history_retire_device(history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 1);
    CHECK(retired.images[0].image == 22);
    CHECK(retired.images[1].image == 23);
    CHECK(bedray_history_destroy(history) == BEDRAY_HISTORY_OK);
}

void test_rejected_releases_serial() {
    BedrayHistory* history = nullptr;
    CHECK(bedray_history_create(65536, &history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_begin_device(history, 3) == BEDRAY_HISTORY_OK);
    std::uint64_t signal = 0;
    CHECK(bedray_history_add(history, "bedray.test.rejected", name_len("bedray.test.rejected"),
                             BEDRAY_HISTORY_DEFAULT_SENSITIVITY, &signal) == BEDRAY_HISTORY_OK);
    BedrayHistoryImage images[2] = {image(30, 4096), image(31, 4096)};
    CHECK(bedray_history_configure(history, signal, images, 2) == BEDRAY_HISTORY_OK);

    BedrayHistoryKey frame_key = key(3);
    BedrayHistoryRecording recording{};
    std::uint32_t detail = 0;
    CHECK(bedray_history_prepare(history, signal, &frame_key, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    BedrayHistoryTicket ticket{};
    CHECK(bedray_history_begin_submission(history, 0, 50, &ticket) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_attach(history, &ticket, signal, recording.token, 1, &detail) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_end_submission(history, &ticket, BEDRAY_HISTORY_REJECTED) == BEDRAY_HISTORY_OK);

    /* A rejected submission never reserved its serial. */
    CHECK(bedray_history_begin_submission(history, 0, 50, &ticket) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_end_submission(history, &ticket, BEDRAY_HISTORY_REJECTED) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_prepare(history, signal, &frame_key, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_OK);
    CHECK(recording.read_slot == -1);
    CHECK((recording.reset & BEDRAY_HISTORY_RESET_FIRST_FRAME) != 0);
    CHECK(bedray_history_cancel(history, signal, recording.token, &detail) == BEDRAY_HISTORY_OK);

    CHECK(bedray_history_device_idle(history) == BEDRAY_HISTORY_OK);
    CHECK(bedray_history_retire_device(history) == BEDRAY_HISTORY_OK);
    BedrayHistoryRetired retired{};
    std::uint32_t has = 0;
    CHECK(bedray_history_pop_finished(history, &retired, &has) == BEDRAY_HISTORY_OK);
    CHECK(has == 1);
    CHECK(retired.count == 2);
    CHECK(bedray_history_destroy(history) == BEDRAY_HISTORY_OK);
}

void test_null_handles() {
    BedrayHistoryKey frame_key = key();
    BedrayHistoryImage images[2] = {image(1, 4096), image(2, 4096)};
    BedrayHistoryRecording recording{};
    BedrayHistoryTicket ticket{};
    BedrayHistoryRetired retired{};
    std::uint64_t value = 0;
    std::uint32_t detail = 0;
    std::uint32_t has = 0;
    CHECK(bedray_history_destroy(nullptr) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_memory(nullptr, &value, &value, &value) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_begin_device(nullptr, 1) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_device_lost(nullptr) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_device_idle(nullptr) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_retire_device(nullptr) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_add(nullptr, "n", 1, 0, &value) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_remove(nullptr, 1) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_configure(nullptr, 1, images, 2) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_invalidate(nullptr, 1, &detail) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_invalidate_after_pending(nullptr, 1) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_prepare(nullptr, 1, &frame_key, 1, 0, BEDRAY_HISTORY_COMPLETED_ONLY,
                                 &recording, &detail) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_cancel(nullptr, 1, 1, &detail) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_begin_submission(nullptr, 0, 1, &ticket) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_attach(nullptr, &ticket, 1, 1, 0, &detail) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_end_submission(nullptr, &ticket, BEDRAY_HISTORY_ACCEPTED) ==
          BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_complete(nullptr, 0, 1) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_pop_finished(nullptr, &retired, &has) == BEDRAY_HISTORY_INVALID_ARGUMENT);
    CHECK(bedray_history_pop_orphaned(nullptr, &retired, &has) == BEDRAY_HISTORY_INVALID_ARGUMENT);
}

} // namespace

int main() {
    test_abi();
    test_validation();
    test_lifecycle();
    test_device_loss();
    test_unknown_outcome();
    test_rejected_releases_serial();
    test_null_handles();
    return 0;
}
