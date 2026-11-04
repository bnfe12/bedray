#pragma once
#include "fault_control.h"
#include <array>
#include <stdexcept>
namespace fault {
inline std::array<int, 10> remaining{}, partial{};
inline std::array<std::array<int, 3>, 10> calls{};
inline int last_feature = -1, last_viewport = -1;
inline bool hit(int operation, int kind = KindAny) {
    ++calls.at(operation).at(kind + 1);
    if (remaining.at(operation) <= 0) return false;
    --remaining.at(operation); return true;
}
inline void reset() { remaining = {}; partial = {}; calls = {}; last_feature = last_viewport = -1; }
}
