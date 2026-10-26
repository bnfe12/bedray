#pragma once

#include <array>
#include <cstdint>

namespace bedray {

inline constexpr std::uint32_t kMaxHistoryQueues = 4;

// Last submission serial per host queue that referenced an image (read or
// write). Serial 0 means "never referenced on that queue". `unknown` marks a
// use whose submission outcome the host could not determine: such an image is
// never treated as finished until the host proves the whole device idle.
struct QueueUses {
    std::array<std::uint64_t, kMaxHistoryQueues> serial{};
    bool unknown = false;
};

// Authoritative per-device queue state, owned by HistoryRegistry and shared by
// every signal. Serials are host-assigned (fence / timeline values) and strictly
// increase per queue within one device epoch. Completion is only what the host
// reports from its own synchronization.
struct QueueTimeline {
    std::uint64_t device_generation = 0;  // 0: no device yet
    std::array<std::uint64_t, kMaxHistoryQueues> submitted{};  // highest serial reserved and not rolled back
    std::array<std::uint64_t, kMaxHistoryQueues> completed{};
    bool lost = false;
    bool retired = false; // orderly teardown after a proven idle device

    [[nodiscard]] bool active() const noexcept { return device_generation != 0 && !lost && !retired; }

    [[nodiscard]] bool finished(const QueueUses& uses) const noexcept {
        if (uses.unknown) return false;
        for (std::uint32_t q = 0; q < kMaxHistoryQueues; ++q) {
            if (uses.serial[q] > completed[q]) return false;
        }
        return true;
    }
};

} // namespace bedray
