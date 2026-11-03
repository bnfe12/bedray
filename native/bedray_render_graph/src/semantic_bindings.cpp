#include "bedray/semantic_bindings.hpp"

#include <utility>

namespace bedray::graph {
namespace {

bool kind_matches(BindingKind binding, ResourceKind resource) noexcept {
    switch (binding) {
        case BindingKind::uniform_buffer:
        case BindingKind::storage_buffer: return resource == ResourceKind::buffer;
        case BindingKind::sampled_image:
        case BindingKind::storage_image:
        case BindingKind::combined_image_sampler: return resource == ResourceKind::image;
        case BindingKind::acceleration_structure: return resource == ResourceKind::acceleration_structure;
    }
    return false;
}

bool access_matches(BindingKind binding, Access access) noexcept {
    switch (binding) {
        case BindingKind::uniform_buffer:
        case BindingKind::sampled_image:
        case BindingKind::combined_image_sampler:
        case BindingKind::acceleration_structure: return access == Access::read;
        case BindingKind::storage_buffer:
        case BindingKind::storage_image: return access == Access::read || access == Access::write || access == Access::read_write;
    }
    return false;
}

bool fail(BindingError& error, BindingErrorCode code, const ReflectedBinding& binding) {
    error = BindingError{code, binding.binding, binding.semantic};
    return false;
}

} // namespace

bool resolve_bindings(const RenderGraph& graph, PassId pass_id,
                      const std::vector<ReflectedBinding>& reflected,
                      std::vector<ResolvedBinding>& out, BindingError& error) {
    error = {};
    const PassDesc* pass = graph.pass(pass_id);
    if (pass == nullptr) {
        error.code = BindingErrorCode::invalid_pass;
        return false;
    }
    std::vector<ResolvedBinding> result;
    result.reserve(reflected.size());
    for (std::size_t i = 0; i < reflected.size(); ++i) {
        const ReflectedBinding& binding = reflected[i];
        if (binding.shader_name.empty() || binding.semantic.empty()) return fail(error, BindingErrorCode::invalid_name, binding);
        if (binding.set != 0) return fail(error, BindingErrorCode::unsupported_set, binding);
        if (binding.count != 1) return fail(error, BindingErrorCode::unsupported_array, binding);
        for (std::size_t j = 0; j < i; ++j) {
            if (reflected[j].set == binding.set && reflected[j].binding == binding.binding)
                return fail(error, BindingErrorCode::duplicate_binding, binding);
            if (reflected[j].shader_name == binding.shader_name)
                return fail(error, BindingErrorCode::duplicate_shader_name, binding);
            if (reflected[j].semantic == binding.semantic)
                return fail(error, BindingErrorCode::duplicate_semantic, binding);
        }

        const ResourceUse* use = nullptr;
        const ResourceDesc* resource = nullptr;
        for (const ResourceUse& candidate : pass->uses) {
            const ResourceDesc* desc = graph.resource(candidate.resource);
            if (desc != nullptr && desc->semantic == binding.semantic) {
                use = &candidate;
                resource = desc;
                break;
            }
        }
        if (use == nullptr) return fail(error, BindingErrorCode::undeclared_resource, binding);
        if (!kind_matches(binding.kind, resource->kind)) return fail(error, BindingErrorCode::kind_mismatch, binding);
        if (!access_matches(binding.kind, use->access)) return fail(error, BindingErrorCode::incompatible_access, binding);
        if (use->stage != Stage::compute) return fail(error, BindingErrorCode::incompatible_stage, binding);
        result.push_back(ResolvedBinding{binding.set, binding.binding, binding.kind, use->resource});
    }
    out = std::move(result);
    return true;
}

} // namespace bedray::graph
