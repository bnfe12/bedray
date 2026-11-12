#include "scene_internal.h"

#include <cstring>
#include <set>
#include <utility>
#include <vector>

namespace bedray_scene {

BedraySceneStatus prepare_sections(
    Scene& scene,
    const BedraySceneUpdate& update,
    std::set<uint64_t>& removed,
    std::vector<PreparedSection>& prepared,
    BedraySceneError* error) {
    if (update.removed_section_count > 0) {
        if (update.removed_sections == nullptr) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "removed_sections is null");
        }
        for (uint32_t i = 0; i < update.removed_section_count; ++i) {
            const uint64_t id = update.removed_sections[i];
            if (!removed.insert(id).second) {
                return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "duplicate removed section %llu",
                    static_cast<unsigned long long>(id));
            }
            if (scene.sections.find(id) == scene.sections.end()) {
                return fail(error, BEDRAY_SCENE_ERROR_UNKNOWN_ID,
                    "cannot remove unknown section %llu", static_cast<unsigned long long>(id));
            }
        }
    }

    prepared.reserve(update.section_count);
    std::set<uint64_t> seen;
    for (uint32_t i = 0; i < update.section_count; ++i) {
        const BedraySceneSectionInput& input = update.sections[i];
        if (input.section_id == 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "section_id must be non-zero");
        }
        if (input.reserved != 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "section %llu has a non-zero reserved field",
                static_cast<unsigned long long>(input.section_id));
        }
        if (!seen.insert(input.section_id).second) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "duplicate section %llu",
                static_cast<unsigned long long>(input.section_id));
        }
        if (removed.count(input.section_id) != 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "section %llu is both updated and removed",
                static_cast<unsigned long long>(input.section_id));
        }

        auto it = scene.sections.find(input.section_id);
        const bool pending_same = it != scene.sections.end()
            && it->second.has_pending
            && it->second.pending.geometry_revision == input.geometry_revision;
        const bool need_mesh = it == scene.sections.end()
            || (input.geometry_revision != it->second.revision && !pending_same);

        PreparedSection entry;
        entry.id = input.section_id;
        entry.revision = input.geometry_revision;
        entry.queue = need_mesh;
        entry.cancel_pending = !need_mesh && !pending_same
            && it != scene.sections.end() && it->second.has_pending;
        if (need_mesh) {
            uint64_t vertex_bytes = 0;
            uint64_t index_bytes = 0;
            const BedraySceneStatus status = validate_mesh(
                input.vertices, input.vertex_count, input.vertex_stride,
                input.indices, input.index_count, input.index_type,
                scene.desc.max_geometry_bytes, error, vertex_bytes, index_bytes);
            if (status != BEDRAY_SCENE_OK) {
                return status;
            }
            const BedraySceneStatus contract = validate_section_contract(input, error);
            if (contract != BEDRAY_SCENE_OK) {
                return contract;
            }
            copy_mesh(entry.mesh, input.vertices, input.vertex_count, input.vertex_stride,
                input.indices, input.index_count, input.index_type, vertex_bytes, index_bytes);
            entry.mesh.geometry_revision = input.geometry_revision;
            entry.mesh.origin = { input.origin[0], input.origin[1], input.origin[2] };
            entry.mesh.geometry_count = input.geometry_count;
            entry.mesh.quad_start = {
                input.quad_start[0], input.quad_start[1], input.quad_start[2] };
            entry.mesh.pass_bits = input.pass_bits;
            entry.mesh.flags = input.flags;
        }
        prepared.push_back(std::move(entry));
    }
    return BEDRAY_SCENE_OK;
}

void apply_sections(
    Scene& scene,
    const std::set<uint64_t>& removed,
    std::vector<PreparedSection>& prepared) {
    const uint64_t retire_after = scene.last_frame_index;
    for (uint64_t id : removed) {
        auto it = scene.sections.find(id);
        if (it == scene.sections.end()) {
            continue;
        }
        if (it->second.geometry) {
            scene.retired_buffers.push_back({ it->second.geometry->gpu, retire_after });
            scene.retired_accelerations.push_back({ it->second.geometry->blas, retire_after });
        }
        scene.sections.erase(it);
        scene.membership_changed = true;
        scene.section_table_dirty = true;
    }

    for (PreparedSection& entry : prepared) {
        auto it = scene.sections.find(entry.id);
        if (it == scene.sections.end()) {
            Section section;
            section.id = entry.id;
            section.pending = std::move(entry.mesh);
            section.has_pending = true;
            scene.sections.emplace(entry.id, std::move(section));
            scene.membership_changed = true;
            scene.section_table_dirty = true;
        } else if (entry.queue) {
            it->second.pending = std::move(entry.mesh);
            it->second.has_pending = true;
            scene.section_table_dirty = true;
        } else if (entry.cancel_pending && it->second.has_pending) {
            it->second.has_pending = false;
            it->second.pending = PendingMesh{};
        }
    }
}

BedraySceneStatus prepare_entities(
    Scene& scene,
    const BedraySceneUpdate& update,
    std::set<uint64_t>& removed,
    std::vector<PreparedEntity>& prepared,
    BedraySceneError* error) {
    if (update.removed_entity_count > 0) {
        if (update.removed_entities == nullptr) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "removed_entities is null");
        }
        for (uint32_t i = 0; i < update.removed_entity_count; ++i) {
            const uint64_t id = update.removed_entities[i];
            if (!removed.insert(id).second) {
                return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "duplicate removed entity %llu",
                    static_cast<unsigned long long>(id));
            }
            if (scene.entities.find(id) == scene.entities.end()) {
                return fail(error, BEDRAY_SCENE_ERROR_UNKNOWN_ID,
                    "cannot remove unknown entity %llu", static_cast<unsigned long long>(id));
            }
        }
    }

    prepared.reserve(update.entity_count);
    std::set<uint64_t> seen;
    for (uint32_t i = 0; i < update.entity_count; ++i) {
        const BedraySceneEntityInput& input = update.entities[i];
        if (input.entity_id == 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "entity_id must be non-zero");
        }
        if (input.reserved != 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "entity %llu has a non-zero reserved field",
                static_cast<unsigned long long>(input.entity_id));
        }
        if (!seen.insert(input.entity_id).second) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "duplicate entity %llu",
                static_cast<unsigned long long>(input.entity_id));
        }
        if (removed.count(input.entity_id) != 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "entity %llu is both updated and removed",
                static_cast<unsigned long long>(input.entity_id));
        }
        if (!transform_valid(input.transform)) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "entity %llu has a non-finite transform",
                static_cast<unsigned long long>(input.entity_id));
        }

        auto it = scene.entities.find(input.entity_id);
        const bool pending_same = it != scene.entities.end()
            && it->second.has_pending
            && it->second.pending.geometry_revision == input.geometry_revision
            && it->second.pending.topology_revision == input.topology_revision;
        const bool need_mesh = it == scene.entities.end()
            || ((input.geometry_revision != it->second.geometry_revision
                 || input.topology_revision != it->second.topology_revision)
                && !pending_same);

        PreparedEntity entry;
        entry.id = input.entity_id;
        entry.geometry_revision = input.geometry_revision;
        entry.topology_revision = input.topology_revision;
        entry.mask = normalize_mask(input.instance_mask);
        std::memcpy(entry.transform.data(), input.transform, sizeof(input.transform));
        entry.queue = need_mesh;
        entry.cancel_pending = !need_mesh && !pending_same
            && it != scene.entities.end() && it->second.has_pending;
        if (need_mesh) {
            uint64_t vertex_bytes = 0;
            uint64_t index_bytes = 0;
            const BedraySceneStatus status = validate_mesh(
                input.vertices, input.vertex_count, input.vertex_stride,
                input.indices, input.index_count, input.index_type,
                scene.desc.max_geometry_bytes, error, vertex_bytes, index_bytes);
            if (status != BEDRAY_SCENE_OK) {
                return status;
            }
            copy_mesh(entry.mesh, input.vertices, input.vertex_count, input.vertex_stride,
                input.indices, input.index_count, input.index_type, vertex_bytes, index_bytes);
            entry.mesh.geometry_revision = input.geometry_revision;
            entry.mesh.topology_revision = input.topology_revision;
        }

        if (it != scene.entities.end()) {
            const std::array<float, 12>& current = effective_transform(it->second);
            entry.transform_changed = std::memcmp(current.data(), entry.transform.data(),
                sizeof(entry.transform)) != 0 || effective_mask(it->second) != entry.mask;
        }
        prepared.push_back(std::move(entry));
    }
    return BEDRAY_SCENE_OK;
}

void apply_entities(
    Scene& scene,
    const std::set<uint64_t>& removed,
    std::vector<PreparedEntity>& prepared) {
    const uint64_t retire_after = scene.last_frame_index;
    for (uint64_t id : removed) {
        auto it = scene.entities.find(id);
        if (it == scene.entities.end()) {
            continue;
        }
        if (it->second.geometry) {
            scene.retired_buffers.push_back({ it->second.geometry->gpu, retire_after });
            scene.retired_accelerations.push_back({ it->second.geometry->blas, retire_after });
        }
        scene.entities.erase(it);
        scene.membership_changed = true;
    }

    for (PreparedEntity& entry : prepared) {
        auto it = scene.entities.find(entry.id);
        if (it == scene.entities.end()) {
            Entity entity;
            entity.id = entry.id;
            entity.transform = entry.transform;
            entity.instance_mask = entry.mask;
            entity.pending = std::move(entry.mesh);
            entity.has_pending = true;
            scene.entities.emplace(entry.id, std::move(entity));
            scene.membership_changed = true;
        } else {
            if (entry.queue) {
                it->second.pending = std::move(entry.mesh);
                it->second.has_pending = true;
            } else if (entry.cancel_pending && it->second.has_pending) {
                it->second.has_pending = false;
                it->second.pending = PendingMesh{};
            }
            if (entry.transform_changed) {
                it->second.transform_pending = true;
                it->second.pending_transform = entry.transform;
                it->second.pending_mask = entry.mask;
            }
        }
    }
}

} // namespace bedray_scene
