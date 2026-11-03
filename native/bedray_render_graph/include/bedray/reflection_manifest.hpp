#pragma once

#include "bedray/render_graph.hpp"
#include "bedray/semantic_bindings.hpp"

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace bedray::graph {

// Text emitted by the build-time Slang reflection adapter. Version 1 grammar:
//
//   bedray-reflection 1
//   pass <pass-name> entry=<entry-point>
//   binding <pass-name> <semantic> <kind> set=<u32> binding=<u32> count=<u32> name=<shader-name>
//   push <pass-name> offset=<u32> size=<u32>
//
// One directive per line; '#' starts a comment; blank lines are ignored.
// kind is one of: uniform_buffer, storage_buffer, sampled_image, storage_image,
// combined_image_sampler, acceleration_structure.
struct ManifestPass {
    std::string name;
    std::string entry_point;
    std::vector<ReflectedBinding> bindings;
    std::vector<PushConstantRange> push_constants;
};

struct ReflectionManifest {
    std::uint32_t version = 1;
    std::vector<ManifestPass> passes;
};

enum class ManifestErrorCode : std::uint8_t {
    none,
    syntax,
    unsupported_version,
    duplicate_pass,
    unknown_pass,
    missing_pass,
    invalid_entry_point,
    invalid_binding,
    missing_binding,
    push_constant_mismatch,
};

struct ManifestError {
    ManifestErrorCode code = ManifestErrorCode::none;
    std::uint32_t line = 0;
    std::uint32_t binding = 0;
    std::string pass;
    std::string semantic;
};

struct ResolvedPassBindings {
    PassId pass = 0;
    std::string name;
    std::string entry_point;
    std::vector<ResolvedBinding> bindings;
    std::vector<PushConstantRange> push_constants;
};

// Parsing is independent of Vulkan object creation. `out` is unchanged on error.
[[nodiscard]] bool parse_reflection_manifest(std::string_view text,
    ReflectionManifest& out, ManifestError& error);

// Requires exactly one manifest pass for every graph pass and vice versa.
// Every graph resource use must be bound, binding kinds/access/stages must be
// compatible, and declared push-constant ranges must match exactly. `out` is
// ordered by graph pass ID and unchanged on error.
[[nodiscard]] bool validate_reflection_manifest(const RenderGraph& graph,
    const ReflectionManifest& manifest, std::vector<ResolvedPassBindings>& out,
    ManifestError& error);

} // namespace bedray::graph
