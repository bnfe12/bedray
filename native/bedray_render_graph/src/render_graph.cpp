#include "bedray/render_graph.hpp"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace bedray::graph {
namespace {

bool writes(Access access) noexcept {
    return access == Access::write || access == Access::read_write;
}

bool reads(Access access) noexcept {
    return access == Access::read || access == Access::read_write;
}

bool valid_usage(const ResourceUse& use) noexcept {
    const bool access_valid = use.access == Access::read || use.access == Access::write || use.access == Access::read_write;
    const bool stage_valid = use.stage == Stage::transfer || use.stage == Stage::compute ||
                             use.stage == Stage::acceleration_build || use.stage == Stage::ray_tracing;
    return access_valid && stage_valid;
}

bool fail(Error& error, ErrorCode code, ResourceId resource = 0,
          PassId pass = 0, PassId other = 0) noexcept {
    error = Error{code, resource, pass, other};
    return false;
}

} // namespace

ResourceId RenderGraph::add_resource(ResourceDesc resource) {
    if (resources_.size() >= external_pass || revision_ == std::numeric_limits<std::uint64_t>::max()) {
        throw std::overflow_error("render graph resource ID or revision exhausted");
    }
    resources_.push_back(std::move(resource));
    ++revision_;
    return static_cast<ResourceId>(resources_.size() - 1);
}

PassId RenderGraph::add_pass(PassDesc pass) {
    if (passes_.size() >= external_pass || revision_ == std::numeric_limits<std::uint64_t>::max()) {
        throw std::overflow_error("render graph pass ID or revision exhausted");
    }
    passes_.push_back(std::move(pass));
    ++revision_;
    return static_cast<PassId>(passes_.size() - 1);
}

const ResourceDesc* RenderGraph::resource(ResourceId id) const noexcept {
    return id < resources_.size() ? &resources_[id] : nullptr;
}

const PassDesc* RenderGraph::pass(PassId id) const noexcept {
    return id < passes_.size() ? &passes_[id] : nullptr;
}

bool RenderGraph::compile(std::uint64_t enabled_capabilities, CompiledGraph& out, Error& error) const {
    error = {};
    const std::size_t count = passes_.size();
    if (count >= external_pass || resources_.size() >= external_pass) {
        return fail(error, ErrorCode::invalid_resource);
    }

    for (std::size_t i = 0; i < resources_.size(); ++i) {
        const auto& r = resources_[i];
        if (r.semantic.empty()) return fail(error, ErrorCode::empty_name, static_cast<ResourceId>(i));
        if (r.kind != ResourceKind::buffer && r.kind != ResourceKind::image &&
            r.kind != ResourceKind::acceleration_structure) {
            return fail(error, ErrorCode::invalid_resource, static_cast<ResourceId>(i));
        }
        for (std::size_t j = 0; j < i; ++j) {
            if (r.semantic == resources_[j].semantic) return fail(error, ErrorCode::duplicate_name, static_cast<ResourceId>(i));
            if (r.allocation_id != 0 && r.allocation_id == resources_[j].allocation_id) {
                return fail(error, ErrorCode::unsupported_alias, static_cast<ResourceId>(i));
            }
        }
    }

    // Reachability is deliberately precomputed: graph compilation is not a
    // per-frame operation, and explicit ordering is more important than a
    // hidden insertion-order dependency in the hot recording path.
    std::vector<std::vector<bool>> precedes(count, std::vector<bool>(count, false));
    std::uint32_t queue_family = any_queue;
    PassId queue_family_pass = 0;
    for (std::size_t i = 0; i < count; ++i) {
        const auto& pass = passes_[i];
        if (pass.name.empty()) return fail(error, ErrorCode::empty_name, 0, static_cast<PassId>(i));
        const std::uint64_t missing = pass.required_capabilities & ~enabled_capabilities;
        if (missing != 0) {
            fail(error, ErrorCode::missing_capability, 0, static_cast<PassId>(i));
            error.missing_capabilities = missing;
            return false;
        }
        for (std::size_t j = 0; j < i; ++j) {
            if (pass.name == passes_[j].name) return fail(error, ErrorCode::duplicate_name, 0, static_cast<PassId>(i));
        }
        if (pass.queue_family != any_queue) {
            if (queue_family != any_queue && queue_family != pass.queue_family) {
                return fail(error, ErrorCode::unsupported_multi_queue, 0,
                            static_cast<PassId>(i), queue_family_pass);
            }
            queue_family = pass.queue_family;
            queue_family_pass = static_cast<PassId>(i);
        }
        if (pass.dispatch.x == 0 || pass.dispatch.y == 0 || pass.dispatch.z == 0 ||
            pass.dispatch.x > 65535 || pass.dispatch.y > 65535 || pass.dispatch.z > 65535) {
            return fail(error, ErrorCode::invalid_dispatch, 0, static_cast<PassId>(i));
        }
        for (std::size_t r = 0; r < pass.push_constants.size(); ++r) {
            const PushConstantRange& range = pass.push_constants[r];
            if (range.size == 0 || (range.offset & 3u) != 0 || (range.size & 3u) != 0 ||
                range.offset > max_push_constant_bytes ||
                range.size > max_push_constant_bytes - range.offset) {
                return fail(error, ErrorCode::invalid_push_constant, 0, static_cast<PassId>(i));
            }
            for (std::size_t s = 0; s < r; ++s) {
                const PushConstantRange& other = pass.push_constants[s];
                if (range.offset < other.offset + other.size && other.offset < range.offset + range.size) {
                    return fail(error, ErrorCode::invalid_push_constant, 0, static_cast<PassId>(i));
                }
            }
        }
        for (PassId dependency : pass.after) {
            if (dependency >= count) return fail(error, ErrorCode::invalid_dependency, 0, static_cast<PassId>(i), dependency);
            if (dependency == i) return fail(error, ErrorCode::self_dependency, 0, static_cast<PassId>(i));
            precedes[dependency][i] = true;
        }
        for (std::size_t j = 0; j < pass.uses.size(); ++j) {
            const auto& use = pass.uses[j];
            if (use.resource >= resources_.size()) return fail(error, ErrorCode::invalid_resource, use.resource, static_cast<PassId>(i));
            if (!valid_usage(use)) return fail(error, ErrorCode::invalid_usage, use.resource, static_cast<PassId>(i));
            const bool is_image = resources_[use.resource].kind == ResourceKind::image;
            if ((is_image && use.image_layout == 0) || (!is_image && use.image_layout != 0)) {
                return fail(error, ErrorCode::invalid_usage, use.resource, static_cast<PassId>(i));
            }
            if (resources_[use.resource].kind == ResourceKind::acceleration_structure &&
                (use.stage == Stage::transfer || (writes(use.access) && use.stage != Stage::acceleration_build))) {
                return fail(error, ErrorCode::invalid_usage, use.resource, static_cast<PassId>(i));
            }
            for (std::size_t k = 0; k < j; ++k) {
                if (use.resource == pass.uses[k].resource) return fail(error, ErrorCode::duplicate_use, use.resource, static_cast<PassId>(i));
            }
        }
    }
    for (std::size_t k = 0; k < count; ++k) {
        for (std::size_t i = 0; i < count; ++i) {
            if (!precedes[i][k]) continue;
            for (std::size_t j = 0; j < count; ++j) {
                precedes[i][j] = precedes[i][j] || precedes[k][j];
            }
        }
    }
    for (std::size_t i = 0; i < count; ++i) {
        if (precedes[i][i]) return fail(error, ErrorCode::cycle, 0, static_cast<PassId>(i));
    }

    CompiledGraph result;
    result.owner = this;
    result.revision = revision_;
    result.enabled_capabilities = enabled_capabilities;
    result.queue_family = queue_family;
    result.order.reserve(count);
    std::vector<bool> emitted(count, false);
    for (std::size_t n = 0; n < count; ++n) {
        bool found = false;
        for (std::size_t i = 0; i < count; ++i) {
            if (emitted[i]) continue;
            bool ready = true;
            for (std::size_t j = 0; j < count; ++j) {
                if (precedes[j][i] && !emitted[j]) { ready = false; break; }
            }
            if (ready) {
                result.order.push_back(static_cast<PassId>(i));
                emitted[i] = true;
                found = true;
                break;
            }
        }
        if (!found) return fail(error, ErrorCode::cycle);
    }

    for (std::size_t i = 0; i < count; ++i) {
        for (const ResourceUse& use : passes_[i].uses) {
            bool initialized = resources_[use.resource].initialized_on_entry;
            bool has_preceding_use = false;
            bool has_following_use = false;
            for (std::size_t j = 0; j < count; ++j) {
                if (i == j) continue;
                for (const ResourceUse& other : passes_[j].uses) {
                    if (other.resource != use.resource) continue;
                    has_preceding_use = has_preceding_use || precedes[j][i];
                    has_following_use = has_following_use || precedes[i][j];
                    if (precedes[j][i] && writes(other.access)) initialized = true;
                    const bool layout_change = resources_[use.resource].kind == ResourceKind::image &&
                                               use.image_layout != other.image_layout;
                    if (!(writes(use.access) || writes(other.access) || layout_change)) continue;
                    if (!precedes[i][j] && !precedes[j][i]) {
                        return fail(error, ErrorCode::unordered_hazard, use.resource,
                                    static_cast<PassId>(i), static_cast<PassId>(j));
                    }
                    if (precedes[j][i]) {
                        result.hazards.push_back(Hazard{use.resource, static_cast<PassId>(j),
                            static_cast<PassId>(i), other, use});
                    }
                }
            }
            if (reads(use.access) && !initialized) {
                return fail(error, ErrorCode::uninitialized_read, use.resource, static_cast<PassId>(i));
            }
            if (!has_preceding_use) result.entries.push_back(BoundaryUse{use.resource, static_cast<PassId>(i), use});
            if (!has_following_use) result.exits.push_back(BoundaryUse{use.resource, static_cast<PassId>(i), use});
        }
    }
    out = std::move(result);
    return true;
}

} // namespace bedray::graph
