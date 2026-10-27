#include "bedray/render_plan.hpp"

#include <utility>

namespace bedray::graph {
namespace {

bool fail(PlanError& error, PlanErrorCode code) noexcept {
    error = PlanError{code, 0, 0};
    return false;
}

} // namespace

const char* traversal_name(TraversalMode mode) noexcept {
    switch (mode) {
        case TraversalMode::hardware: return "hardware";
        case TraversalMode::hybrid: return "hybrid";
        case TraversalMode::bitmask_hdda: return "bitmask_hdda";
    }
    return "unknown";
}

const char* feature_name(RenderFeature feature) noexcept {
    switch (feature) {
        case RenderFeature::none: return "none";
        case RenderFeature::restir_di: return "restir_di";
        case RenderFeature::restir_pt: return "restir_pt";
        case RenderFeature::radiance_cascades: return "radiance_cascades";
        case RenderFeature::denoise: return "denoise";
        case RenderFeature::upscale: return "upscale";
    }
    return "unknown";
}

const char* pass_name(PlanPassKind kind) noexcept {
    switch (kind) {
        case PlanPassKind::scene_trace: return "scene.trace";
        case PlanPassKind::restir_di: return "restir.di";
        case PlanPassKind::restir_pt: return "restir.pt";
        case PlanPassKind::radiance_cascades: return "radiance_cascades.trace";
        case PlanPassKind::denoise: return "denoise";
        case PlanPassKind::upscale: return "upscale";
        case PlanPassKind::display: return "display";
    }
    return "unknown";
}

std::uint64_t traversal_capabilities(TraversalMode mode) noexcept {
    switch (mode) {
        case TraversalMode::hardware:
            return static_cast<std::uint64_t>(PlanCapability::hardware_ray_tracing) |
                   static_cast<std::uint64_t>(PlanCapability::ray_query);
        case TraversalMode::hybrid:
            return static_cast<std::uint64_t>(PlanCapability::hybrid_traversal) |
                   static_cast<std::uint64_t>(PlanCapability::ray_query);
        case TraversalMode::bitmask_hdda:
            return static_cast<std::uint64_t>(PlanCapability::bitmask_hdda) |
                   static_cast<std::uint64_t>(PlanCapability::ray_query);
    }
    return 0;
}

std::uint64_t feature_capabilities(RenderFeature feature) noexcept {
    switch (feature) {
        case RenderFeature::restir_di:
            return static_cast<std::uint64_t>(PlanCapability::restir_di);
        case RenderFeature::restir_pt:
            return static_cast<std::uint64_t>(PlanCapability::restir_pt);
        case RenderFeature::radiance_cascades:
            return static_cast<std::uint64_t>(PlanCapability::radiance_cascades);
        case RenderFeature::denoise:
            return static_cast<std::uint64_t>(PlanCapability::denoise);
        case RenderFeature::upscale:
            return static_cast<std::uint64_t>(PlanCapability::upscale);
        case RenderFeature::none:
            return 0;
    }
    return 0;
}

std::uint64_t feature_capabilities(std::uint64_t features) noexcept {
    std::uint64_t capabilities = 0;
    for (std::uint64_t bit = 1; bit <= static_cast<std::uint64_t>(RenderFeature::upscale); bit <<= 1) {
        if ((features & bit) != 0) {
            capabilities |= feature_capabilities(static_cast<RenderFeature>(bit));
        }
    }
    return capabilities;
}

bool resolve_render_plan(const RenderPlan& plan, std::uint64_t enabled_capabilities,
    PlanResolution& out, PlanError& error) {
    error = {};
    const auto traversal = static_cast<std::uint8_t>(plan.traversal);
    if (traversal > static_cast<std::uint8_t>(TraversalMode::bitmask_hdda)) {
        return fail(error, PlanErrorCode::invalid_traversal);
    }
    const std::uint64_t invalid_features = plan.features & ~all_render_features;
    if (invalid_features != 0) {
        fail(error, PlanErrorCode::invalid_feature);
        error.invalid_features = invalid_features;
        return false;
    }

    const std::uint64_t required = traversal_capabilities(plan.traversal);
    const std::uint64_t missing = required & ~enabled_capabilities;
    if (missing != 0) {
        fail(error, PlanErrorCode::unsupported_traversal);
        error.missing_capabilities = missing;
        return false;
    }

    PlanResolution result;
    result.traversal = plan.traversal;
    result.required_capabilities = required;
    for (std::uint64_t bit = 1; bit <= static_cast<std::uint64_t>(RenderFeature::upscale); bit <<= 1) {
        if ((plan.features & bit) == 0) {
            continue;
        }
        const RenderFeature feature = static_cast<RenderFeature>(bit);
        const std::uint64_t feature_required = feature_capabilities(feature);
        const std::uint64_t feature_missing = feature_required & ~enabled_capabilities;
        if (feature_missing != 0) {
            result.disabled_features.push_back(DisabledFeature{feature, feature_missing});
        } else {
            result.enabled_features |= bit;
            result.required_capabilities |= feature_required;
        }
    }
    out = std::move(result);
    return true;
}

bool plan_passes(const RenderPlan& plan, std::uint64_t enabled_capabilities,
    std::vector<PlannedPass>& out, PlanResolution& resolution, PlanError& error) {
    PlanResolution resolved;
    if (!resolve_render_plan(plan, enabled_capabilities, resolved, error)) {
        return false;
    }
    std::vector<PlannedPass> passes;
    passes.reserve(7);
    passes.push_back(PlannedPass{PlanPassKind::scene_trace, pass_name(PlanPassKind::scene_trace),
        traversal_capabilities(plan.traversal), RenderFeature::none});
    const auto add_feature = [&passes, &resolved](RenderFeature feature, PlanPassKind kind) {
        if ((resolved.enabled_features & static_cast<std::uint64_t>(feature)) != 0) {
            passes.push_back(PlannedPass{kind, pass_name(kind), feature_capabilities(feature), feature});
        }
    };
    add_feature(RenderFeature::restir_di, PlanPassKind::restir_di);
    add_feature(RenderFeature::restir_pt, PlanPassKind::restir_pt);
    add_feature(RenderFeature::radiance_cascades, PlanPassKind::radiance_cascades);
    add_feature(RenderFeature::denoise, PlanPassKind::denoise);
    add_feature(RenderFeature::upscale, PlanPassKind::upscale);
    passes.push_back(PlannedPass{PlanPassKind::display, pass_name(PlanPassKind::display),
        0, RenderFeature::none});
    out = std::move(passes);
    resolution = std::move(resolved);
    return true;
}

} // namespace bedray::graph
