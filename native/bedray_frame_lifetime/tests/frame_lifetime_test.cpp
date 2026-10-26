#include "bedray/frame_lifetime.hpp"

#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <vector>

static void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

int main() {
    bedray::FrameLifetime lifetime;
    std::vector<int> released;
    require(lifetime.retire(3, [&] { released.push_back(3); }), "retire 3");
    require(lifetime.retire(1, [&] { released.push_back(1); }), "retire 1");
    require(lifetime.retire(2, [&] { released.push_back(2); }), "retire 2");
    require(lifetime.complete(1), "complete 1");
    require(released == std::vector<int>{1} && lifetime.pending_count() == 2,
        "only completed frame is released");
    require(!lifetime.complete(0) && lifetime.pending_count() == 2,
        "regressing completion rejected");
    require(lifetime.retire(1, [&] { released.push_back(10); }), "already completed");
    require(released == std::vector<int>({1, 10}), "already completed releases immediately");
    require(lifetime.complete(3), "complete remaining");
    require(released == std::vector<int>({1, 10, 3, 2}) && lifetime.pending_count() == 0,
        "out-of-order retirements are all released once");
    require(lifetime.complete(3) && released.size() == 4, "completion is idempotent");
    require(!lifetime.retire(4, {}), "empty callback rejected");
    require(lifetime.retire(4, [] { throw std::runtime_error("release failure"); }), "failing release");
    require(lifetime.retire(4, [&] { released.push_back(4); }), "release after failure");
    bool threw = false;
    try { lifetime.complete(4); } catch (const std::runtime_error&) { threw = true; }
    require(threw && released.back() == 4 && lifetime.pending_count() == 0,
        "a failing callback does not prevent other releases");
    std::cout << "frame lifetime contract: PASS\n";
}
