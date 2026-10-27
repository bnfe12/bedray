#include "bedray/reflection_manifest.hpp"

#include <algorithm>
#include <charconv>
#include <system_error>
#include <utility>

namespace bedray::graph {
namespace {

struct PendingBinding {
    std::string pass;
    ReflectedBinding binding;
    std::uint32_t line = 0;
};

struct PendingPush {
    std::string pass;
    PushConstantRange range;
    std::uint32_t line = 0;
};

bool fail(ManifestError& error, ManifestErrorCode code, std::uint32_t line = 0,
    std::string pass = {}, std::string semantic = {}) {
    error = ManifestError{};
    error.code = code;
    error.line = line;
    error.pass = std::move(pass);
    error.semantic = std::move(semantic);
    return false;
}

std::vector<std::string_view> tokenize(std::string_view line) {
    std::vector<std::string_view> tokens;
    std::size_t i = 0;
    while (i < line.size()) {
        while (i < line.size() && (line[i] == ' ' || line[i] == '\t')) ++i;
        const std::size_t start = i;
        while (i < line.size() && line[i] != ' ' && line[i] != '\t') ++i;
        if (i > start) {
            tokens.push_back(line.substr(start, i - start));
        }
    }
    return tokens;
}

bool starts_with_key(std::string_view token, std::string_view key) {
    return token.size() > key.size() && token.substr(0, key.size()) == key &&
           token[key.size()] == '=';
}

bool key_u32(std::string_view token, std::string_view key, std::uint32_t& out) {
    if (!starts_with_key(token, key)) {
        return false;
    }
    const std::string_view value = token.substr(key.size() + 1);
    if (value.empty()) {
        return false;
    }
    std::uint32_t parsed = 0;
    const auto result = std::from_chars(value.data(), value.data() + value.size(), parsed);
    if (result.ec != std::errc{} || result.ptr != value.data() + value.size()) {
        return false;
    }
    out = parsed;
    return true;
}

bool key_text(std::string_view token, std::string_view key, std::string& out) {
    if (!starts_with_key(token, key)) {
        return false;
    }
    const std::string_view value = token.substr(key.size() + 1);
    if (value.empty()) {
        return false;
    }
    out.assign(value.data(), value.size());
    return true;
}

bool plain_u32(std::string_view value, std::uint32_t& out) {
    if (value.empty()) {
        return false;
    }
    std::uint32_t parsed = 0;
    const auto result = std::from_chars(value.data(), value.data() + value.size(), parsed);
    if (result.ec != std::errc{} || result.ptr != value.data() + value.size()) {
        return false;
    }
    out = parsed;
    return true;
}

bool parse_kind(std::string_view value, BindingKind& out) {
    if (value == "uniform_buffer") out = BindingKind::uniform_buffer;
    else if (value == "storage_buffer") out = BindingKind::storage_buffer;
    else if (value == "sampled_image") out = BindingKind::sampled_image;
    else if (value == "storage_image") out = BindingKind::storage_image;
    else if (value == "combined_image_sampler") out = BindingKind::combined_image_sampler;
    else if (value == "acceleration_structure") out = BindingKind::acceleration_structure;
    else return false;
    return true;
}

ManifestPass* find_pass(ReflectionManifest& manifest, const std::string& name) {
    for (ManifestPass& pass : manifest.passes) {
        if (pass.name == name) {
            return &pass;
        }
    }
    return nullptr;
}

const ManifestPass* find_pass(const ReflectionManifest& manifest, const std::string& name) {
    for (const ManifestPass& pass : manifest.passes) {
        if (pass.name == name) {
            return &pass;
        }
    }
    return nullptr;
}

bool same_push_constants(std::vector<PushConstantRange> left,
    std::vector<PushConstantRange> right) {
    const auto by_offset = [](const PushConstantRange& a, const PushConstantRange& b) {
        return a.offset < b.offset;
    };
    std::sort(left.begin(), left.end(), by_offset);
    std::sort(right.begin(), right.end(), by_offset);
    if (left.size() != right.size()) {
        return false;
    }
    for (std::size_t i = 0; i < left.size(); ++i) {
        if (left[i].offset != right[i].offset || left[i].size != right[i].size) {
            return false;
        }
    }
    return true;
}

} // namespace

bool parse_reflection_manifest(std::string_view text, ReflectionManifest& out,
    ManifestError& error) {
    error = {};
    ReflectionManifest parsed;
    std::vector<PendingBinding> bindings;
    std::vector<PendingPush> pushes;
    bool saw_version = false;
    std::uint32_t line_number = 0;
    std::size_t position = 0;

    while (position < text.size()) {
        const std::size_t end = text.find('\n', position);
        std::string_view line = text.substr(position,
            end == std::string_view::npos ? std::string_view::npos : end - position);
        position = end == std::string_view::npos ? text.size() : end + 1;
        ++line_number;
        if (!line.empty() && line.back() == '\r') {
            line.remove_suffix(1);
        }
        while (!line.empty() && (line.front() == ' ' || line.front() == '\t')) {
            line.remove_prefix(1);
        }
        while (!line.empty() && (line.back() == ' ' || line.back() == '\t')) {
            line.remove_suffix(1);
        }
        if (line.empty() || line.front() == '#') {
            continue;
        }

        const std::vector<std::string_view> tokens = tokenize(line);
        if (!saw_version) {
            std::uint32_t version = 0;
            if (tokens.size() != 2 || tokens[0] != "bedray-reflection" ||
                !plain_u32(tokens[1], version)) {
                return fail(error, ManifestErrorCode::syntax, line_number);
            }
            if (version != 1) {
                return fail(error, ManifestErrorCode::unsupported_version, line_number);
            }
            parsed.version = version;
            saw_version = true;
            continue;
        }

        if (tokens[0] == "pass") {
            ManifestPass pass;
            if (tokens.size() != 3 || !key_text(tokens[2], "entry", pass.entry_point)) {
                return fail(error, ManifestErrorCode::syntax, line_number);
            }
            pass.name.assign(tokens[1].data(), tokens[1].size());
            if (pass.name.empty()) {
                return fail(error, ManifestErrorCode::syntax, line_number);
            }
            if (find_pass(parsed, pass.name) != nullptr) {
                return fail(error, ManifestErrorCode::duplicate_pass, line_number, pass.name);
            }
            parsed.passes.push_back(std::move(pass));
        } else if (tokens[0] == "binding") {
            if (tokens.size() != 8) {
                return fail(error, ManifestErrorCode::syntax, line_number);
            }
            PendingBinding pending;
            pending.line = line_number;
            pending.pass.assign(tokens[1].data(), tokens[1].size());
            pending.binding.semantic.assign(tokens[2].data(), tokens[2].size());
            if (!parse_kind(tokens[3], pending.binding.kind) ||
                !key_u32(tokens[4], "set", pending.binding.set) ||
                !key_u32(tokens[5], "binding", pending.binding.binding) ||
                !key_u32(tokens[6], "count", pending.binding.count) ||
                !key_text(tokens[7], "name", pending.binding.shader_name)) {
                return fail(error, ManifestErrorCode::syntax, line_number);
            }
            bindings.push_back(std::move(pending));
        } else if (tokens[0] == "push") {
            if (tokens.size() != 4) {
                return fail(error, ManifestErrorCode::syntax, line_number);
            }
            PendingPush pending;
            pending.line = line_number;
            pending.pass.assign(tokens[1].data(), tokens[1].size());
            if (!key_u32(tokens[2], "offset", pending.range.offset) ||
                !key_u32(tokens[3], "size", pending.range.size)) {
                return fail(error, ManifestErrorCode::syntax, line_number);
            }
            pushes.push_back(std::move(pending));
        } else {
            return fail(error, ManifestErrorCode::syntax, line_number);
        }
    }

    if (!saw_version) {
        return fail(error, ManifestErrorCode::syntax, line_number == 0 ? 1 : line_number);
    }
    for (PendingBinding& pending : bindings) {
        ManifestPass* pass = find_pass(parsed, pending.pass);
        if (pass == nullptr) {
            return fail(error, ManifestErrorCode::unknown_pass, pending.line, pending.pass);
        }
        pass->bindings.push_back(std::move(pending.binding));
    }
    for (PendingPush& pending : pushes) {
        ManifestPass* pass = find_pass(parsed, pending.pass);
        if (pass == nullptr) {
            return fail(error, ManifestErrorCode::unknown_pass, pending.line, pending.pass);
        }
        pass->push_constants.push_back(pending.range);
    }

    out = std::move(parsed);
    return true;
}

bool validate_reflection_manifest(const RenderGraph& graph,
    const ReflectionManifest& manifest, std::vector<ResolvedPassBindings>& out,
    ManifestError& error) {
    error = {};
    if (manifest.version != 1) {
        return fail(error, ManifestErrorCode::unsupported_version);
    }

    for (std::size_t i = 0; i < manifest.passes.size(); ++i) {
        if (manifest.passes[i].name.empty()) {
            return fail(error, ManifestErrorCode::syntax, 0, manifest.passes[i].name);
        }
        for (std::size_t j = 0; j < i; ++j) {
            if (manifest.passes[j].name == manifest.passes[i].name) {
                return fail(error, ManifestErrorCode::duplicate_pass, 0, manifest.passes[i].name);
            }
        }
        if (manifest.passes[i].entry_point.empty()) {
            return fail(error, ManifestErrorCode::invalid_entry_point, 0, manifest.passes[i].name);
        }
        bool known = false;
        for (std::size_t id = 0; id < graph.pass_count(); ++id) {
            const PassDesc* pass = graph.pass(static_cast<PassId>(id));
            known = known || (pass != nullptr && pass->name == manifest.passes[i].name);
        }
        if (!known) {
            return fail(error, ManifestErrorCode::unknown_pass, 0, manifest.passes[i].name);
        }
    }

    std::vector<ResolvedPassBindings> result;
    result.reserve(graph.pass_count());
    for (std::size_t id = 0; id < graph.pass_count(); ++id) {
        const PassId pass_id = static_cast<PassId>(id);
        const PassDesc* pass = graph.pass(pass_id);
        if (pass == nullptr) {
            return fail(error, ManifestErrorCode::missing_pass, 0);
        }
        const ManifestPass* reflected = find_pass(manifest, pass->name);
        if (reflected == nullptr) {
            return fail(error, ManifestErrorCode::missing_pass, 0, pass->name);
        }
        if (reflected->entry_point.empty()) {
            return fail(error, ManifestErrorCode::invalid_entry_point, 0, pass->name);
        }

        std::vector<ResolvedBinding> bindings;
        BindingError binding_error;
        if (!resolve_bindings(graph, pass_id, reflected->bindings, bindings, binding_error)) {
            fail(error, ManifestErrorCode::invalid_binding, 0, pass->name, binding_error.semantic);
            error.binding = binding_error.binding;
            return false;
        }
        if (bindings.size() != pass->uses.size()) {
            for (const ResourceUse& use : pass->uses) {
                bool bound = false;
                for (const ResolvedBinding& binding : bindings) {
                    bound = bound || binding.resource == use.resource;
                }
                if (!bound) {
                    const ResourceDesc* resource = graph.resource(use.resource);
                    return fail(error, ManifestErrorCode::missing_binding, 0, pass->name,
                        resource != nullptr ? resource->semantic : std::string{});
                }
            }
            return fail(error, ManifestErrorCode::invalid_binding, 0, pass->name);
        }
        if (!same_push_constants(reflected->push_constants, pass->push_constants)) {
            return fail(error, ManifestErrorCode::push_constant_mismatch, 0, pass->name);
        }

        ResolvedPassBindings resolved;
        resolved.pass = pass_id;
        resolved.name = pass->name;
        resolved.entry_point = reflected->entry_point;
        resolved.bindings = std::move(bindings);
        resolved.push_constants = reflected->push_constants;
        result.push_back(std::move(resolved));
    }
    out = std::move(result);
    return true;
}

} // namespace bedray::graph
