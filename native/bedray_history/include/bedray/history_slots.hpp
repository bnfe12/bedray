#pragma once

#include "bedray/history_state.hpp"
#include "bedray/history_timeline.hpp"

#include <array>
#include <cstdint>

namespace bedray {

inline constexpr std::uint32_t kMaxHistorySlots = 4;

// One history image as the Vulkan owner declares it. `image` is the identity of
// the underlying VkImage (never a view); `memory`/`offset`/`size` describe its
// bound memory range so DECLARED aliasing can be rejected. Undeclared aliasing
// (sparse binding, external memory, an allocator that reuses ranges without
// telling us) cannot be detected here and is the Vulkan owner's responsibility.
struct HistoryImage {
    std::uint64_t image = 0;
    std::uint64_t memory = 0;
    std::uint64_t offset = 0;
    std::uint64_t size = 0;
};

struct HistoryImageSet {
    std::array<HistoryImage, kMaxHistorySlots> images{};
    std::uint32_t count = 0; // 2..kMaxHistorySlots
};

// Images that left a signal (reconfiguration, removal, device loss) together
// with the submissions that may still reference them. The registry retains
// these records; the host destroys an image only after the registry hands the
// record back as finished (or, for the lost device, as orphaned).
struct RetiredHistoryImages {
    std::array<HistoryImage, kMaxHistorySlots> images{};
    std::array<QueueUses, kMaxHistorySlots> uses{};
    std::uint32_t count = 0;
    std::uint64_t device_generation = 0;
    bool completion_known = true; // false: device lost, no completion can be proven any more
};

// What the render graph is able to promise for a write-after-use hazard.
// The current source-only render graph can only honour `completed_only`.
enum class HistoryOrdering : std::uint32_t {
    completed_only = 0,         // every previous use is proven complete
    same_queue_barrier = 1,     // graph inserts an execution + memory dependency on the same queue
    cross_queue_semaphore = 2,  // graph additionally waits on the other queues' serials
};

enum class HistoryStatus : std::uint32_t {
    ok = 0,
    not_configured,
    recording_pending,   // one recording at a time per signal
    recording_attached,  // already bound to a submission: cancel is no longer possible
    stale_frame,
    invalid_input,
    reset_not_applied,   // the pass did not honour a reported reset: must not be submitted
    write_unavailable,   // no slot can be written under the requested ordering
    device_unavailable,  // no device, or the device was lost
    unknown_token,
    token_exhausted,     // 2^64 recordings: fail closed rather than reuse a token
};

enum class SubmitOutcome : std::uint32_t {
    accepted = 0,  // the queue took the work: uses are real, history advances
    rejected = 1,  // the queue provably did not take the work
    unknown = 2,   // the host cannot tell (e.g. submit failed with device loss)
};

struct HistoryRecording {
    std::uint64_t token = 0;
    std::uint64_t frame = 0;
    std::uint32_t queue = 0;
    std::int32_t read_slot = -1;      // -1: reset, the pass must not read history
    std::uint64_t read_image = 0;
    std::uint32_t write_slot = 0;
    std::uint64_t write_image = 0;
    HistoryReset reset = HistoryReset::none;
    // Unfinished earlier uses of the write image; the graph must order after
    // each nonzero serial before writing. Always empty under completed_only.
    QueueUses write_after{};
    // Submission that produced the read image; reading requires a dependency on it.
    std::uint32_t read_producer_queue = 0;
    std::uint64_t read_producer_serial = 0;
};

// Image-slot ring for ONE signal. It selects images and records which
// submissions referenced them; queue serials and completion live in the
// registry's QueueTimeline. It never allocates or frees anything.
//
// Lifecycle of one recording: prepare -> (cancel | attach) -> finish(outcome).
// Everything that can be refused is refused before the host submits; finish()
// cannot fail, so an accepted submission can never be "forgotten".
class HistorySlots final {
public:
    explicit HistorySlots(HistoryResetPolicy policy = {}) noexcept : state_(policy) {}

    // Preconditions (checked by the registry): not pending, `images` validated.
    void configure(const HistoryImageSet& images, std::uint64_t device_generation,
                   RetiredHistoryImages& old) noexcept;
    void release(std::uint64_t device_generation, RetiredHistoryImages& old) noexcept;

    // `token` is issued by the registry and never reused across signals,
    // removals or devices; the slots only remember it.
    [[nodiscard]] HistoryStatus prepare(const HistoryKey& key, std::uint64_t frame, std::uint32_t queue,
                                        HistoryOrdering ordering, const QueueTimeline& timeline,
                                        std::uint64_t token, HistoryRecording& out) noexcept;
    // Binds the pending recording to the submission about to be made on `queue`.
    [[nodiscard]] HistoryStatus attach(std::uint64_t token, std::uint32_t queue, bool reset_applied) noexcept;
    // Only for a recording that was never attached.
    [[nodiscard]] HistoryStatus cancel(std::uint64_t token) noexcept;
    // Precondition: attached(). Never fails.
    void finish(SubmitOutcome outcome, std::uint64_t serial) noexcept;

    // Invalidation that must affect the next recording: refused while any
    // recording is pending, so no recording that read old history can be
    // submitted after it.
    [[nodiscard]] HistoryStatus invalidate() noexcept;
    // Invalidation for frames after the pending one: the pending recording may
    // still read the old history and be submitted, but its output is never
    // committed. Without a pending recording it equals invalidate().
    void invalidate_after_pending() noexcept;
    // Drops any unattached recording and hands every image back with
    // completion_known = false. The slots become unconfigured.
    void device_lost(std::uint64_t device_generation, RetiredHistoryImages& old) noexcept;
    // Host proof that the whole device is idle: unknown uses are finished.
    void clear_unknown_uses() noexcept;

    [[nodiscard]] bool pending() const noexcept { return pending_; }
    [[nodiscard]] bool attached() const noexcept { return attached_; }
    [[nodiscard]] bool configured() const noexcept { return images_.count != 0; }
    [[nodiscard]] const HistoryImageSet& images() const noexcept { return images_; }
    [[nodiscard]] std::uint64_t configured_bytes() const noexcept;

private:
    void hand_back(std::uint64_t device_generation, bool completion_known, RetiredHistoryImages& old) const noexcept;
    void clear() noexcept;
    // Turns a deferred invalidate_after_pending() into a real reset; used on
    // every path where the pending recording ends without committing.
    void apply_deferred_reset() noexcept;

    HistoryState state_;
    HistoryImageSet images_{};
    std::array<QueueUses, kMaxHistorySlots> uses_{};
    HistoryKey pending_key_{};
    HistoryRecording pending_record_{};
    bool pending_reset_applied_ = false;
    bool discard_pending_result_ = false;
    std::int32_t latest_slot_ = -1;          // slot holding committed history
    std::uint32_t latest_queue_ = 0;
    std::uint64_t latest_serial_ = 0;
    bool pending_ = false;
    bool attached_ = false;
};

[[nodiscard]] bool history_image_set_valid(const HistoryImageSet& set) noexcept;
[[nodiscard]] bool history_images_overlap(const HistoryImage& a, const HistoryImage& b) noexcept;
[[nodiscard]] std::uint64_t history_image_set_bytes(const HistoryImageSet& set) noexcept;

} // namespace bedray
