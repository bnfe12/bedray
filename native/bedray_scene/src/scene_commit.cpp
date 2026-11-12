#include "scene_internal.h"

#include <utility>

namespace bedray_scene {

void commit_plan(
    Scene& scene,
    PreparePlan& plan,
    FrameSlot& slot,
    const BedraySceneFrameDesc& desc) {
    const uint64_t retire_after = scene.last_frame_index;

    scene.retired_buffers.reserve(scene.retired_buffers.size()
        + plan.jobs.size() * 2 + BEDRAY_SCENE_TABLE_COUNT + 2);
    scene.retired_accelerations.reserve(scene.retired_accelerations.size() + plan.jobs.size());
    slot.transient.clear();
    slot.transient.reserve(2);
    slot.blas_in_use.reserve(plan.instances.size() + (plan.tlas_update ? slot.cached_blas_refs.size() : 0));
    slot.cached_blas_refs.reserve(plan.instances.size());

    for (GeometryJob& job : plan.jobs) {
        if (job.is_section) {
            Section& section = *job.section;
            if (job.refit) {
                scene.retired_buffers.push_back({ section.geometry->gpu, retire_after });
                section.geometry->gpu = job.created->gpu;
                section.geometry->vertex_address = job.created->vertex_address;
                section.geometry->index_address = job.created->index_address;
                job.created->gpu = Buffer{};
            } else {
                if (section.geometry) {
                    scene.retired_buffers.push_back({ section.geometry->gpu, retire_after });
                    scene.retired_accelerations.push_back({ section.geometry->blas, retire_after });
                }
                section.geometry = std::move(job.created);
            }
            section.revision = job.mesh->geometry_revision;
            section.vertex_count = job.mesh->vertex_count;
            section.index_count = job.mesh->index_count;
            section.vertex_stride = job.mesh->vertex_stride;
            section.index_type = job.mesh->index_type;
            section.origin = job.mesh->origin;
            section.geometry_count = job.mesh->geometry_count;
            section.quad_start = job.mesh->quad_start;
            section.pass_bits = job.mesh->pass_bits;
            section.flags = job.mesh->flags;
            section.has_pending = false;
            section.pending = PendingMesh{};
        } else {
            Entity& entity = *job.entity;
            if (job.refit) {
                scene.retired_buffers.push_back({ entity.geometry->gpu, retire_after });
                entity.geometry->gpu = job.created->gpu;
                entity.geometry->vertex_address = job.created->vertex_address;
                entity.geometry->index_address = job.created->index_address;
                job.created->gpu = Buffer{};
            } else {
                if (entity.geometry) {
                    scene.retired_buffers.push_back({ entity.geometry->gpu, retire_after });
                    scene.retired_accelerations.push_back({ entity.geometry->blas, retire_after });
                }
                entity.geometry = std::move(job.created);
            }
            entity.geometry_revision = job.mesh->geometry_revision;
            entity.topology_revision = job.mesh->topology_revision;
            entity.vertex_count = job.mesh->vertex_count;
            entity.index_count = job.mesh->index_count;
            entity.vertex_stride = job.mesh->vertex_stride;
            entity.index_type = job.mesh->index_type;
            entity.finite_positions = job.finite_positions;
            entity.has_pending = false;
            entity.pending = PendingMesh{};
        }
    }

    for (auto& [id, entity] : scene.entities) {
        if (entity.transform_pending) {
            entity.transform = entity.pending_transform;
            entity.instance_mask = entity.pending_mask;
            entity.transform_pending = false;
        }
        if (entity.has_pending && entity.geometry != nullptr) {
            const PendingMesh& mesh = entity.pending;
            if (mesh.geometry_revision == entity.geometry_revision
                && mesh.topology_revision == entity.topology_revision) {
                entity.has_pending = false;
                entity.pending = PendingMesh{};
            }
        }
    }

    for (uint32_t kind = 0; kind < BEDRAY_SCENE_TABLE_COUNT; ++kind) {
        Table& table = scene.tables[kind];
        if (!plan.table_action[kind]) {
            continue;
        }
        if (table.gpu.handle != VK_NULL_HANDLE) {
            scene.retired_buffers.push_back({ table.gpu, retire_after });
        }
        table.gpu = plan.new_tables[kind];
        plan.new_tables[kind] = Buffer{};
        table.revision = table.pending_revision;
        table.has_pending = false;
        table.pending.clear();
    }

    if (plan.section_table_rebuild) {
        if (scene.section_table.handle != VK_NULL_HANDLE) {
            scene.retired_buffers.push_back({ scene.section_table, retire_after });
        }
        scene.section_table = plan.new_section_table;
        plan.new_section_table = Buffer{};
        scene.section_table_bytes = scene.section_table.size;
        scene.section_table_dirty = false;
    }

    if (plan.tlas_clear && slot.tlas_valid) {
        destroy_acceleration(scene, slot.tlas);
    }
    if (plan.new_tlas) {
        if (slot.tlas_valid) {
            destroy_acceleration(scene, slot.tlas);
        }
        slot.tlas = *plan.new_tlas;
        plan.new_tlas.reset();
    }
    if (plan.new_tlas_scratch) {
        destroy_buffer(scene, slot.tlas_scratch);
        slot.tlas_scratch = *plan.new_tlas_scratch;
        slot.tlas_scratch_capacity = slot.tlas_scratch.size;
        plan.new_tlas_scratch.reset();
    }
    if (plan.new_instance_buffer) {
        destroy_buffer(scene, slot.instance_buffer);
        slot.instance_buffer = *plan.new_instance_buffer;
        plan.new_instance_buffer.reset();
    }

    slot.tlas_valid = !plan.instances.empty();
    slot.built_version = plan.target_version;
    slot.instance_count = plan.instance_count;
    slot.view = scene.view;
    slot.view_valid = scene.view_set;
    slot.blas_in_use.clear();
    if (plan.tlas_update) {
        // The update reads the old source bindings until this recording completes.
        for (VkAccelerationStructureKHR handle : slot.cached_blas_refs) slot.blas_in_use.push_back(handle);
    }
    slot.cached_blas_refs.clear();
    for (const InstanceEntry& instance : plan.instances) {
        slot.blas_in_use.push_back(instance.blas_handle);
        if ((scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0)
            slot.cached_blas_refs.push_back(instance.blas_handle);
    }

    if (plan.staging_present) {
        slot.transient.push_back(plan.staging);
        plan.staging = Buffer{};
        plan.staging_present = false;
    }
    if (plan.blas_scratch_present) {
        slot.transient.push_back(plan.blas_scratch);
        plan.blas_scratch = Buffer{};
        plan.blas_scratch_present = false;
    }

    slot.free = false;
    slot.frame_index = desc.frame_index;
    slot.command_buffer = desc.command_buffer;
    scene.outstanding++;

    scene.version = plan.target_version;
    scene.last_frame_index = desc.frame_index;
    scene.membership_changed = false;
    plan.committed = true;

    BedraySceneResources resources{};
    resources.abi_version = BEDRAY_SCENE_ABI_VERSION;
    resources.section_count = static_cast<uint32_t>(scene.sections.size());
    resources.instance_count = plan.instance_count;
    resources.tlas = slot.tlas_valid ? reinterpret_cast<uint64_t>(slot.tlas.handle) : 0;
    resources.instance_buffer = reinterpret_cast<uint64_t>(slot.instance_buffer.handle);
    resources.instance_buffer_address = slot.instance_buffer.address;
    resources.section_table_buffer = reinterpret_cast<uint64_t>(scene.section_table.handle);
    resources.section_table_address = scene.section_table.address;
    resources.section_table_bytes = scene.section_table_bytes;
    resources.material_table_buffer = reinterpret_cast<uint64_t>(scene.tables[BEDRAY_SCENE_TABLE_MATERIAL].gpu.handle);
    resources.material_table_address = scene.tables[BEDRAY_SCENE_TABLE_MATERIAL].gpu.address;
    resources.material_table_bytes = scene.tables[BEDRAY_SCENE_TABLE_MATERIAL].gpu.size;
    resources.light_table_buffer = reinterpret_cast<uint64_t>(scene.tables[BEDRAY_SCENE_TABLE_LIGHT].gpu.handle);
    resources.light_table_address = scene.tables[BEDRAY_SCENE_TABLE_LIGHT].gpu.address;
    resources.light_table_bytes = scene.tables[BEDRAY_SCENE_TABLE_LIGHT].gpu.size;
    scene.last_resources = resources;
    scene.has_last_resources = true;
}

} // namespace bedray_scene
