#pragma once

#include <cstdint>
#include <cstddef>
#include <functional>
#include <vector>

namespace bedray {

// The host assigns increasing serials to submitted frames on one queue and
// reports completion only after its own fence/timeline proves it. This class
// never submits, polls, waits, or assumes that recording implies submission.
class FrameLifetime final {
public:
    using Serial = std::uint64_t;
    using Releaser = std::function<void()>;

    FrameLifetime() = default;
    FrameLifetime(const FrameLifetime&) = delete;
    FrameLifetime& operator=(const FrameLifetime&) = delete;
    // Explicit drain is required: silently running Vulkan destruction here
    // could destroy resources still referenced by the game's queue.
    ~FrameLifetime() = default;

    // Schedule destruction after the last submission referencing the resource.
    // A serial already observed complete is released immediately. Returns false
    // for a missing callback. Calls must occur on the renderer thread.
    bool retire(Serial last_use, Releaser release);

    // Must be called only with a completion serial proven by the host. Returns
    // false for a regressing serial and leaves the queue unchanged. All ready
    // callbacks run even if one throws; the first exception is then rethrown.
    // A C ABI caller must contain that exception at its boundary.
    bool complete(Serial completed);

    [[nodiscard]] Serial completed_serial() const noexcept { return completed_; }
    [[nodiscard]] std::size_t pending_count() const noexcept { return pending_.size(); }

private:
    struct Pending {
        Serial last_use;
        Releaser release;
    };
    Serial completed_ = 0;
    std::vector<Pending> pending_;
};

} // namespace bedray
