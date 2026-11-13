#include <array>
#include <cstdio>
#include "restir_di.generated.cpp"

int main() {
    constexpr std::array labels{
        "zero-weight fresh batch retains proposal count",
        "history is normalized against the fresh zero-weight draws",
        "enumerated same-receiver estimator has its original mean",
        "visibility-rejected history is excluded from the merge",
        "diffuse hits contribute emission when there are no emitter proposals",
        "diffuse emission outside the local light radius survives pane and cutout advances",
        "a smaller emitter list range bounds the local proposal support",
        "thin panes keep authored tint and angle-dependent Fresnel",
        "optical slabs retain thickness-based absorption",
    };
    std::array<uint32_t, labels.size()> verdicts{};
    GlobalParams_0 globals{};
    globals.sink_0 = {verdicts.data(), verdicts.size()};
    ComputeThreadVaryingInput thread{};
    main_0_Thread(&thread, nullptr, &globals);
    for (size_t i = 0; i < verdicts.size(); ++i) {
        if (verdicts[i] != 1) {
            std::fprintf(stderr, "production ReSTIR DI contract failed: %s\n", labels[i]);
            return 1;
        }
    }
    std::printf("%zu production Slang ReSTIR DI/lighting CPU cases passed (no GPU)\n", verdicts.size());
}
