#pragma once

#include <cstdint>
#include <string_view>

namespace bedray {

// Stable identity of one temporal signal ("bedray.restir_di.reservoirs",
// "bedray.rc.cascade0", ...). The id is owned by the method that produces the
// signal; this module never enumerates methods, so a new one needs no change
// here. Ids must be stable across sessions and builds, which is why they are a
// hash of a fixed name rather than a registration order or an enum value.
struct SignalId {
    std::uint64_t value = 0;

    friend constexpr bool operator==(SignalId, SignalId) noexcept = default;
    [[nodiscard]] constexpr bool valid() const noexcept { return value != 0; }
};

// FNV-1a 64. Collisions are detected at registration (duplicate id), not
// silently merged. An empty name yields an invalid id.
constexpr SignalId signal_id(std::string_view name) noexcept {
    if (name.empty()) return SignalId{};
    std::uint64_t hash = 0xcbf29ce484222325ull;
    for (char c : name) {
        hash ^= static_cast<unsigned char>(c);
        hash *= 0x100000001b3ull;
    }
    return SignalId{hash == 0 ? 1u : hash};
}

} // namespace bedray
