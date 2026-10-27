#include "bedray/reflection_manifest.hpp"
#include "bedray/render_graph.hpp"
#include "bedray/render_plan.hpp"

#include <cstdint>
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

using namespace bedray::graph;

namespace {

int failures = 0;

void check(bool condition, const char* what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

constexpr std::uint32_t layout_general = 1;

struct Pipeline {
    ResourceId tlas = 0;
    ResourceId radiance = 0;
    ResourceId denoised = 0;
    ResourceId output = 0;
    PassId trace = 0;
    PassId denoise = 0;
    PassId display = 0;
};

Pipeline make_pipeline(RenderGraph& graph) {
    Pipeline p;
    p.tlas = graph.add_resource({"scene.tlas", ResourceKind::acceleration_structure, 0, true});
    p.radiance = graph.add_resource({"history.radiance", ResourceKind::image, 0, false});
    p.denoised = graph.add_resource({"denoise.color", ResourceKind::image, 0, false});
    p.output = graph.add_resource({"output.color", ResourceKind::image, 0, true});

    PassDesc trace;
    trace.name = "scene.trace";
    trace.uses = {
        {p.tlas, Access::read, Stage::compute, 0},
        {p.radiance, Access::write, Stage::compute, layout_general},
    };
    trace.dispatch = {120, 68, 1};
    trace.push_constants = {{0, 16}};
    p.trace = graph.add_pass(std::move(trace));

    PassDesc denoise;
    denoise.name = "denoise";
    denoise.uses = {
        {p.radiance, Access::read, Stage::compute, layout_general},
        {p.denoised, Access::write, Stage::compute, layout_general},
    };
    denoise.after = {p.trace};
    denoise.dispatch = {60, 34, 1};
    denoise.push_constants = {{0, 16}};
    p.denoise = graph.add_pass(std::move(denoise));

    PassDesc display;
    display.name = "display";
    display.uses = {
        {p.denoised, Access::read, Stage::compute, layout_general},
        {p.output, Access::write, Stage::compute, layout_general},
    };
    display.after = {p.denoise};
    display.dispatch = {120, 68, 1};
    display.push_constants = {{0, 16}};
    p.display = graph.add_pass(std::move(display));
    return p;
}

void multi_pass_compiles_in_order_with_hazards() {
    RenderGraph graph;
    const Pipeline p = make_pipeline(graph);
    CompiledGraph plan;
    Error error;
    check(graph.compile(0x3, plan, error), "multi-pass graph compiles");
    check(error.code == ErrorCode::none, "compile has no error");
    check(plan.order.size() == 3, "three passes planned");
    if (plan.order.size() == 3) {
        check(plan.order[0] == p.trace && plan.order[1] == p.denoise && plan.order[2] == p.display,
            "topological order follows explicit dependencies");
    }
    check(plan.hazards.size() == 2, "two write-ordered hazards");
    bool radiance_hazard = false;
    bool denoise_hazard = false;
    for (const Hazard& hazard : plan.hazards) {
        if (hazard.resource == p.radiance && hazard.source == p.trace &&
            hazard.destination == p.denoise) {
            radiance_hazard = true;
        }
        if (hazard.resource == p.denoised && hazard.source == p.denoise &&
            hazard.destination == p.display) {
            denoise_hazard = true;
        }
    }
    check(radiance_hazard, "radiance producer/consumer hazard");
    check(denoise_hazard, "denoise producer/consumer hazard");
    check(plan.queue_family == any_queue, "unspecified passes use any_queue");
    check(graph.is_current(plan, 0x3), "plan is current for the graph revision");
    check(!graph.is_current(plan, 0x2), "plan is stale for a different capability mask");

    const PassId extra = graph.add_pass(PassDesc{"extra", {}, {}, 0, any_queue, {1, 1, 1}, {}});
    check(extra == 3, "pass IDs are contiguous");
    check(!graph.is_current(plan, 0x3), "graph mutation invalidates the plan");
    check(graph.pass_count() == 4 && graph.resource_count() == 4, "graph counts are exposed");
}

void rejects_invalid_graphs() {
    {
        RenderGraph cycle;
        cycle.add_resource({"r", ResourceKind::buffer, 0, true});
        cycle.add_pass(PassDesc{"x", {}, {1}, 0, any_queue, {1, 1, 1}, {}});
        cycle.add_pass(PassDesc{"y", {}, {0}, 0, any_queue, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!cycle.compile(0, plan, error) && error.code == ErrorCode::cycle,
            "mutually dependent passes are rejected as a cycle");
    }
    {
        RenderGraph self;
        self.add_resource({"r", ResourceKind::buffer, 0, true});
        self.add_pass(PassDesc{"self", {}, {0}, 0, any_queue, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!self.compile(0, plan, error) && error.code == ErrorCode::self_dependency,
            "self-dependency is rejected");
    }
    {
        RenderGraph forward;
        forward.add_resource({"r", ResourceKind::buffer, 0, true});
        forward.add_pass(PassDesc{"first", {}, {5}, 0, any_queue, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!forward.compile(0, plan, error) && error.code == ErrorCode::invalid_dependency,
            "dependency on a missing pass is rejected");
    }
    {
        RenderGraph graph;
        const ResourceId resource = graph.add_resource({"r", ResourceKind::image, 0, true});
        graph.add_pass(PassDesc{"write.a", {{resource, Access::write, Stage::compute, layout_general}},
            {}, 0, any_queue, {1, 1, 1}, {}});
        graph.add_pass(PassDesc{"write.b", {{resource, Access::write, Stage::compute, layout_general}},
            {}, 0, any_queue, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!graph.compile(0, plan, error) && error.code == ErrorCode::unordered_hazard,
            "unordered write/write is rejected");
    }
    {
        RenderGraph graph;
        const ResourceId resource = graph.add_resource({"r", ResourceKind::buffer, 0, false});
        graph.add_pass(PassDesc{"read", {{resource, Access::read, Stage::compute, 0}},
            {}, 0, any_queue, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!graph.compile(0, plan, error) && error.code == ErrorCode::uninitialized_read,
            "read without an initializer is rejected");
    }
    {
        RenderGraph graph;
        graph.add_pass(PassDesc{"needs.cap", {}, {}, 0x2, any_queue, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!graph.compile(0x1, plan, error) && error.code == ErrorCode::missing_capability &&
            error.missing_capabilities == 0x2, "missing pass capability is reported");
    }
    {
        RenderGraph graph;
        const ResourceId resource = graph.add_resource({"r", ResourceKind::image, 0, true});
        graph.add_pass(PassDesc{"image", {{resource, Access::read, Stage::compute, 0}},
            {}, 0, any_queue, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!graph.compile(0, plan, error) && error.code == ErrorCode::invalid_usage,
            "image use without a layout is rejected");
    }
    {
        RenderGraph graph;
        graph.add_pass(PassDesc{"a", {}, {}, 0, 0, {1, 1, 1}, {}});
        graph.add_pass(PassDesc{"b", {}, {}, 0, 1, {1, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!graph.compile(0, plan, error) && error.code == ErrorCode::unsupported_multi_queue,
            "mixed explicit queue families are rejected");
    }
    {
        RenderGraph graph;
        graph.add_pass(PassDesc{"zero", {}, {}, 0, any_queue, {0, 1, 1}, {}});
        CompiledGraph plan;
        Error error;
        check(!graph.compile(0, plan, error) && error.code == ErrorCode::invalid_dispatch,
            "zero dispatch dimension is rejected");
    }
    {
        RenderGraph graph;
        graph.add_pass(PassDesc{"push", {}, {}, 0, any_queue, {1, 1, 1}, {{0, 16}, {8, 8}}});
        CompiledGraph plan;
        Error error;
        check(!graph.compile(0, plan, error) && error.code == ErrorCode::invalid_push_constant,
            "overlapping push-constant ranges are rejected");
        RenderGraph misaligned;
        misaligned.add_pass(PassDesc{"push", {}, {}, 0, any_queue, {1, 1, 1}, {{2, 4}}});
        check(!misaligned.compile(0, plan, error) && error.code == ErrorCode::invalid_push_constant,
            "misaligned push-constant range is rejected");
    }
}

const char* valid_manifest_text() {
    return
        "bedray-reflection 1\n"
        "pass scene.trace entry=traceMain\n"
        "pass denoise entry=denoiseMain\n"
        "pass display entry=displayMain\n"
        "binding scene.trace scene.tlas acceleration_structure set=0 binding=0 count=1 name=sceneTlas\n"
        "binding scene.trace history.radiance storage_image set=0 binding=1 count=1 name=radianceImage\n"
        "push scene.trace offset=0 size=16\n"
        "binding denoise history.radiance storage_image set=0 binding=0 count=1 name=denoiseIn\n"
        "binding denoise denoise.color storage_image set=0 binding=1 count=1 name=denoiseOut\n"
        "push denoise offset=0 size=16\n"
        "binding display denoise.color storage_image set=0 binding=0 count=1 name=displayIn\n"
        "binding display output.color storage_image set=0 binding=1 count=1 name=displayOut\n"
        "push display offset=0 size=16\n";
}

void reflection_manifest_parses_and_validates() {
    RenderGraph graph;
    make_pipeline(graph);
    ReflectionManifest manifest;
    ManifestError error;
    check(parse_reflection_manifest(valid_manifest_text(), manifest, error),
        "valid reflection manifest parses");
    check(error.code == ManifestErrorCode::none, "parse error is clear");
    check(manifest.version == 1 && manifest.passes.size() == 3, "three manifest passes");
    std::vector<ResolvedPassBindings> resolved;
    check(validate_reflection_manifest(graph, manifest, resolved, error),
        "valid reflection manifest validates");
    check(resolved.size() == 3, "one resolved entry per graph pass");
    if (resolved.size() == 3) {
        check(resolved[0].name == "scene.trace" && resolved[0].bindings.size() == 2 &&
            resolved[0].push_constants.size() == 1, "trace bindings resolved");
        check(resolved[0].bindings.size() == 2 && resolved[0].bindings[0].resource == 0 &&
            resolved[0].bindings[1].resource == 1,
            "semantic binding resolves to resource IDs");
        check(resolved[0].entry_point == "traceMain", "entry point is preserved");
    }
}

void reflection_manifest_rejects_invalid_input() {
    RenderGraph graph;
    make_pipeline(graph);
    ReflectionManifest manifest;
    ManifestError error;
    const std::string valid = valid_manifest_text();
    check(!parse_reflection_manifest("bedray-reflection 2\n", manifest, error) &&
        error.code == ManifestErrorCode::unsupported_version, "manifest version is checked");
    check(!parse_reflection_manifest("not a manifest\n", manifest, error) &&
        error.code == ManifestErrorCode::syntax, "malformed first line is rejected");
    check(!parse_reflection_manifest("bedray-reflection 1\nbogus x\n", manifest, error) &&
        error.code == ManifestErrorCode::syntax, "unknown directive is rejected");

    std::string unknown_pass = valid;
    const std::string from = "binding scene.trace";
    unknown_pass.replace(unknown_pass.find(from), from.size(), "binding unknown.pass");
    check(!parse_reflection_manifest(unknown_pass, manifest, error) &&
        error.code == ManifestErrorCode::unknown_pass, "binding to unknown pass is rejected");

    std::string missing_pass = valid;
    const std::string display_lines[] = {
        "pass display entry=displayMain\n",
        "binding display denoise.color storage_image set=0 binding=0 count=1 name=displayIn\n",
        "binding display output.color storage_image set=0 binding=1 count=1 name=displayOut\n",
        "push display offset=0 size=16\n",
    };
    for (const std::string& line : display_lines) {
        const std::size_t position = missing_pass.find(line);
        check(position != std::string::npos, "display manifest line is present");
        if (position != std::string::npos) {
            missing_pass.erase(position, line.size());
        }
    }
    check(parse_reflection_manifest(missing_pass, manifest, error), "manifest without a pass parses");
    std::vector<ResolvedPassBindings> resolved;
    check(!validate_reflection_manifest(graph, manifest, resolved, error) &&
        error.code == ManifestErrorCode::missing_pass, "missing manifest pass is rejected");

    std::string missing_binding = valid;
    const std::string radiance_binding =
        "binding scene.trace history.radiance storage_image set=0 binding=1 count=1 name=radianceImage\n";
    missing_binding.erase(missing_binding.find(radiance_binding), radiance_binding.size());
    check(parse_reflection_manifest(missing_binding, manifest, error), "manifest without a binding parses");
    check(!validate_reflection_manifest(graph, manifest, resolved, error) &&
        error.code == ManifestErrorCode::missing_binding, "unbound pass resource is rejected");

    std::string wrong_kind = valid;
    const std::string radiance_kind = "history.radiance storage_image";
    wrong_kind.replace(wrong_kind.find(radiance_kind), radiance_kind.size(),
        "history.radiance acceleration_structure");
    check(parse_reflection_manifest(wrong_kind, manifest, error), "wrong-kind manifest parses");
    check(!validate_reflection_manifest(graph, manifest, resolved, error) &&
        error.code == ManifestErrorCode::invalid_binding, "type-incompatible binding is rejected");

    std::string duplicate_semantic = valid;
    const std::string second_radiance_kind =
        "binding denoise history.radiance storage_image set=0 binding=0 count=1 name=denoiseIn\n"
        "binding denoise denoise.color storage_image set=0 binding=1 count=1 name=denoiseOut\n";
    duplicate_semantic.replace(duplicate_semantic.find(second_radiance_kind),
        second_radiance_kind.size(),
        "binding denoise history.radiance storage_image set=0 binding=0 count=1 name=denoiseIn\n"
        "binding denoise history.radiance storage_image set=0 binding=1 count=1 name=denoiseOut\n");
    check(parse_reflection_manifest(duplicate_semantic, manifest, error), "duplicate-semantic manifest parses");
    check(!validate_reflection_manifest(graph, manifest, resolved, error) &&
        error.code == ManifestErrorCode::invalid_binding, "duplicate semantic is rejected");

    std::string wrong_push = valid;
    const std::string trace_push = "push scene.trace offset=0 size=16\n";
    wrong_push.replace(wrong_push.find(trace_push), trace_push.size(),
        "push scene.trace offset=0 size=32\n");
    check(parse_reflection_manifest(wrong_push, manifest, error), "wrong push manifest parses");
    check(!validate_reflection_manifest(graph, manifest, resolved, error) &&
        error.code == ManifestErrorCode::push_constant_mismatch, "push-constant mismatch is rejected");

    std::string unsupported_set = valid;
    const std::string trace_binding =
        "binding scene.trace scene.tlas acceleration_structure set=0 binding=0 count=1 name=sceneTlas";
    unsupported_set.replace(unsupported_set.find(trace_binding), trace_binding.size(),
        "binding scene.trace scene.tlas acceleration_structure set=1 binding=0 count=1 name=sceneTlas");
    check(parse_reflection_manifest(unsupported_set, manifest, error), "set=1 manifest parses");
    check(!validate_reflection_manifest(graph, manifest, resolved, error) &&
        error.code == ManifestErrorCode::invalid_binding, "nonzero descriptor set is rejected");

    std::string missing_entry = valid;
    const std::string trace_pass = "pass scene.trace entry=traceMain\n";
    missing_entry.replace(missing_entry.find(trace_pass), trace_pass.size(),
        "pass scene.trace\n");
    check(!parse_reflection_manifest(missing_entry, manifest, error) &&
        error.code == ManifestErrorCode::syntax, "pass without entry point is rejected");
}

void render_plan_selects_optional_capabilities() {
    PlanError error;
    PlanResolution resolution;
    std::vector<PlannedPass> passes;
    const std::uint64_t hardware = static_cast<std::uint64_t>(PlanCapability::hardware_ray_tracing);
    const std::uint64_t query = static_cast<std::uint64_t>(PlanCapability::ray_query);
    const std::uint64_t denoise = static_cast<std::uint64_t>(PlanCapability::denoise);

    RenderPlan plan;
    plan.traversal = TraversalMode::hardware;
    plan.features = static_cast<std::uint64_t>(RenderFeature::restir_pt) |
                    static_cast<std::uint64_t>(RenderFeature::denoise);
    check(plan_passes(plan, hardware | query | denoise, passes, resolution, error),
        "plan with one unsupported optional feature resolves");
    check(passes.size() == 3, "only supported optional passes are emitted");
    if (passes.size() == 3) {
        check(passes[0].kind == PlanPassKind::scene_trace, "trace pass is first");
        check(passes[1].kind == PlanPassKind::denoise, "denoise pass is selected");
        check(passes[2].kind == PlanPassKind::display, "display pass is last");
    }
    check(resolution.enabled_features == static_cast<std::uint64_t>(RenderFeature::denoise),
        "only the supported feature is enabled");
    check(resolution.disabled_features.size() == 1 &&
        resolution.disabled_features[0].feature == RenderFeature::restir_pt &&
        resolution.disabled_features[0].missing_capabilities ==
            static_cast<std::uint64_t>(PlanCapability::restir_pt),
        "unsupported ReSTIR PT is reported, not fatal");

    RenderPlan all;
    all.traversal = TraversalMode::bitmask_hdda;
    all.features = all_render_features;
    const std::uint64_t all_caps = static_cast<std::uint64_t>(PlanCapability::bitmask_hdda) |
        query | static_cast<std::uint64_t>(PlanCapability::restir_di) |
        static_cast<std::uint64_t>(PlanCapability::restir_pt) |
        static_cast<std::uint64_t>(PlanCapability::radiance_cascades) |
        denoise | static_cast<std::uint64_t>(PlanCapability::upscale);
    check(plan_passes(all, all_caps, passes, resolution, error), "fully supported plan resolves");
    check(passes.size() == 7, "all optional passes emitted");
    if (passes.size() == 7) {
        check(passes[1].kind == PlanPassKind::restir_di &&
            passes[2].kind == PlanPassKind::restir_pt &&
            passes[3].kind == PlanPassKind::radiance_cascades &&
            passes[4].kind == PlanPassKind::denoise &&
            passes[5].kind == PlanPassKind::upscale &&
            passes[6].kind == PlanPassKind::display, "canonical plan order is stable");
    }

    RenderPlan hybrid;
    hybrid.traversal = TraversalMode::hybrid;
    check(!plan_passes(hybrid, query, passes, resolution, error) &&
        error.code == PlanErrorCode::unsupported_traversal &&
        error.missing_capabilities == static_cast<std::uint64_t>(PlanCapability::hybrid_traversal),
        "unsupported traversal is a hard host-visible error");

    RenderPlan invalid;
    invalid.features = 1ull << 40;
    check(!resolve_render_plan(invalid, ~0ull, resolution, error) &&
        error.code == PlanErrorCode::invalid_feature && error.invalid_features == (1ull << 40),
        "unknown feature bits are rejected");

    invalid = RenderPlan{};
    invalid.traversal = static_cast<TraversalMode>(99);
    check(!resolve_render_plan(invalid, ~0ull, resolution, error) &&
        error.code == PlanErrorCode::invalid_traversal, "unknown traversal mode is rejected");
}

} // namespace

int main() {
    multi_pass_compiles_in_order_with_hazards();
    rejects_invalid_graphs();
    reflection_manifest_parses_and_validates();
    reflection_manifest_rejects_invalid_input();
    render_plan_selects_optional_capabilities();
    if (failures != 0) {
        std::fprintf(stderr, "%d render-graph contract check(s) failed\n", failures);
        return 1;
    }
    std::printf("bedray_render_graph contract test passed\n");
    return 0;
}
