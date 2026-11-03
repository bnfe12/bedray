#include <array>
#include <cstdio>
#include "triangle_intersection.generated.cpp"

int main() {
    std::array<uint32_t, 12> verdicts{};
    GlobalParams_0 globals{};
    globals.sink_0 = {verdicts.data(), verdicts.size()};
    ComputeThreadVaryingInput thread{};
    main_0_Thread(&thread, nullptr, &globals);
    for (size_t i = 0; i < verdicts.size(); ++i) {
        if (verdicts[i] != 1) {
            std::fprintf(stderr, "production triangle contract case %zu failed: %u\n", i, verdicts[i]);
            return 1;
        }
    }
    std::puts("12 production Slang triangle exclusion/miss/scheduling CPU cases passed (no GPU)");
}
