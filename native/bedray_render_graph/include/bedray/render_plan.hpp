#pragma once

#include <cstdint>
#include <vector>

namespace bedray::graph {

enum class TraversalMode : std::uint8_t {
    hardware,
    hybrid,
    bitmask_hdda,
};

enum class RenderFeature : std::uint64_t {
    none = 0,
    restir_di = 1ull << 0,
    restir_pt = 1ull << 1,
    radiance_cascades = 1ull << 2,
    denoise = 1ull << 3,
    upscale = 1ull << 4,
};

inline constexpr std::uint64_t all_render_features =
    static_cast<std::uint64_t>(RenderFeature::restir_di) |
    static_cast<std::uint64_t>(RenderFeature::restir_pt) |
    static_cast<std::uint64_t>(RenderFeature::radiance_cascades) |
    static_cast<std::uint64_t>(RenderFeature::denoise) |
    static_cast<std::uint64_t>(RenderFeature::upscale);

// Bits supplied by the host from features the logical device actually enabled.
// They are intentionally separate from the graph's generic capability mask.
enum class PlanCapability : std::uint64_t {
    hardware_ray_tracing = 1ull << 0,
    ray_query = 1ull << 1,
    hybrid_traversal = 1ull << 2,
    bitmask_hdda = 1ull << 3,
    restir_di = 1ull << 4,
    restir_pt = 1ull << 5,
    radiance_cascades = 1ull << 6,
    denoise = 1ull << 7,
    upscale = 1ull << 8,
};

// Traversal is a hard requirement: a plan that asks for an unavailable mode is
// rejected rather than silently retargeted. Algorithm features are optional:
// each unsupported feature is reported in PlanResolution::disabled_features and
// only its pass is omitted.
struct RenderPlan {
    TraversalMode traversal = TraversalMode::hardware;
    std::uint64_t features = 0;
};

enum class PlanErrorCode : std::uint8_t {
    none,
    invalid_traversal,
    invalid_feature,
    unsupported_traversal,
};

struct PlanError {
    PlanErrorCode code = PlanErrorCode::none;
    std::uint64_t missing_capabilities = 0;
    std::uint64_t invalid_features = 0;
};

struct DisabledFeature {
    RenderFeature feature;
    std::uint64_t missing_capabilities;
};

struct PlanResolution {
    TraversalMode traversal = TraversalMode::hardware;
    std::uint64_t enabled_features = 0;
    std::vector<DisabledFeature> disabled_features;
    // Union of the traversal capabilities and the enabled feature capabilities.
    std::uint64_t required_capabilities = 0;
};

enum class PlanPassKind : std::uint8_t {
    scene_trace,
    restir_di,
    restir_pt,
    radiance_cascades,
    denoise,
    upscale,
    display,
};

struct PlannedPass {
    PlanPassKind kind;
    const char* name;
    std::uint64_t required_capabilities;
    RenderFeature feature;
};

const char* traversal_name(TraversalMode mode) noexcept;
const char* feature_name(RenderFeature feature) noexcept;
const char* pass_name(PlanPassKind kind) noexcept;
std::uint64_t traversal_capabilities(TraversalMode mode) noexcept;
std::uint64_t feature_capabilities(RenderFeature feature) noexcept;
std::uint64_t feature_capabilities(std::uint64_t features) noexcept;

// `enabled_capabilities` is a PlanCapability mask. Outputs are unchanged on
// failure. Unsupported optional features are not failures.
[[nodiscard]] bool resolve_render_plan(const RenderPlan& plan,
    std::uint64_t enabled_capabilities, PlanResolution& out, PlanError& error);

// Resolves the plan, then emits the canonical pass order:
// scene.trace -> optional feature passes -> denoise -> upscale -> display.
// Unsuitable optional features are omitted and remain in `out.disabled_features`.
// The caller adds each planned pass to a RenderGraph with its semantic uses.
[[nodiscard]] bool plan_passes(const RenderPlan& plan,
    std::uint64_t enabled_capabilities, std::vector<PlannedPass>& out,
    PlanResolution& resolution, PlanError& error);

} // namespace bedray::graph
