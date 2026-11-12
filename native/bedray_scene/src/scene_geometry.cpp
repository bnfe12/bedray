#include "scene_internal.h"

#include <algorithm>
#include <memory>
#include <utility>

namespace bedray_scene {

void destroy_geometry(Scene& scene, GeometryResource& geometry) {
    destroy_acceleration(scene, geometry.blas);
    destroy_buffer(scene, geometry.gpu);
}

bool blas_referenced_by_outstanding(const Scene& scene, VkAccelerationStructureKHR handle) {
    for (const FrameSlot& slot : scene.slots) {
        if (slot.free) {
            continue;
        }
        for (VkAccelerationStructureKHR candidate : slot.blas_in_use) {
            if (candidate == handle) {
                return true;
            }
        }
    }
    return false;
}

bool blas_referenced_by_cached_tlas(const Scene& scene, VkAccelerationStructureKHR handle) {
    if ((scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) == 0) return false;
    for (const FrameSlot& slot : scene.slots) {
        if (slot.tlas_valid && std::find(slot.cached_blas_refs.begin(), slot.cached_blas_refs.end(), handle)
                != slot.cached_blas_refs.end()) return true;
    }
    return false;
}

namespace {

bool build_job_resource(Scene& scene, GeometryJob& job, BedraySceneError* error) {
    const PendingMesh& mesh = *job.mesh;
    auto created = std::make_unique<GeometryResource>();
    uint64_t index_offset = 0;
    if (!create_geometry_buffer(scene, mesh, created->gpu, index_offset, error)) {
        return false;
    }
    created->vertex_address = created->gpu.address;
    created->index_address = index_offset > 0 ? created->gpu.address + index_offset : 0;

    const BlasView view = blas_view(mesh, created->gpu, index_offset, job.is_section);
    uint64_t storage_size = 0;
    if (!query_blas_sizes(scene, view, storage_size, job.build_scratch, job.update_scratch, error)) {
        destroy_buffer(scene, created->gpu);
        return false;
    }
    if (!job.refit) {
        if (!create_acceleration_object(scene, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                storage_size, created->blas, error)) {
            destroy_buffer(scene, created->gpu);
            return false;
        }
    }
    job.created = std::move(created);
    return true;
}

} // namespace

void cleanup_plan(Scene& scene, PreparePlan& plan) {
    if (plan.committed) {
        return;
    }
    for (GeometryJob& job : plan.jobs) {
        if (job.created) {
            destroy_geometry(scene, *job.created);
            job.created.reset();
        }
    }
    for (PlannedCopy& copy : plan.copies) {
        copy = PlannedCopy{};
    }
    plan.copies.clear();

    destroy_buffer(scene, plan.staging);
    plan.staging_present = false;
    destroy_buffer(scene, plan.blas_scratch);
    plan.blas_scratch_present = false;

    for (Buffer& buffer : plan.new_tables) {
        destroy_buffer(scene, buffer);
    }
    destroy_buffer(scene, plan.new_section_table);
    plan.section_table_present = false;

    if (plan.new_tlas) {
        destroy_acceleration(scene, *plan.new_tlas);
        plan.new_tlas.reset();
    }
    if (plan.new_tlas_scratch) {
        destroy_buffer(scene, *plan.new_tlas_scratch);
        plan.new_tlas_scratch.reset();
    }
    if (plan.new_instance_buffer) {
        destroy_buffer(scene, *plan.new_instance_buffer);
        plan.new_instance_buffer.reset();
    }
}

bool plan_geometry_jobs(Scene& scene, PreparePlan& plan, bool& tlas_changed, BedraySceneError* error) {
    plan.jobs.reserve(scene.sections.size() + scene.entities.size());

    for (auto& [id, section] : scene.sections) {
        if (!section.has_pending) {
            continue;
        }
        GeometryJob job;
        job.is_section = true;
        job.id = id;
        job.section = &section;
        job.mesh = &section.pending;
        job.previous = section.geometry.get();
        job.refit = false;
        if (!build_job_resource(scene, job, error)) {
            return false;
        }
        plan.blas_rebuilds++;
        tlas_changed = true;
        plan.jobs.push_back(std::move(job));
    }

    const bool can_refit = (scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0;
    for (auto& [id, entity] : scene.entities) {
        if (!entity.has_pending) {
            continue;
        }
        const PendingMesh& mesh = entity.pending;
        const bool topology_changed = mesh.topology_revision != entity.topology_revision;
        const bool geometry_changed = mesh.geometry_revision != entity.geometry_revision;
        if (!geometry_changed && !topology_changed && entity.geometry != nullptr) {
            continue;
        }

        GeometryJob job;
        job.is_section = false;
        job.id = id;
        job.entity = &entity;
        job.mesh = &mesh;
        job.previous = entity.geometry.get();

        const bool same_shape = entity.vertex_count == mesh.vertex_count
            && entity.index_count == mesh.index_count
            && entity.vertex_stride == mesh.vertex_stride
            && entity.index_type == mesh.index_type;

        job.finite_positions = finite_positions(mesh);
        // UPDATE cannot change active/inactive primitives. Non-finite inputs keep the full-build path.
        const bool refit_eligible = job.previous != nullptr && !topology_changed && can_refit && same_shape
            && entity.finite_positions && job.finite_positions;
        const bool pinned_refit = refit_eligible && blas_referenced_by_outstanding(scene, job.previous->blas.handle);
        job.refit = refit_eligible && !pinned_refit;
        if (!build_job_resource(scene, job, error)) return false;
        if (job.refit) {
            plan.blas_refits++;
        } else {
            plan.blas_rebuilds++;
            plan.blas_copy_on_write |= pinned_refit;
        }
        // Never shade current host attributes with an older, deferred BLAS. A pinned refit becomes a COW build.
        tlas_changed = true;
        plan.jobs.push_back(std::move(job));
    }

    if (!tlas_changed) {
        for (const auto& entry : scene.entities) {
            if (entry.second.transform_pending) {
                tlas_changed = true;
                break;
            }
        }
    }
    return true;
}

GeometryResource* job_geometry(const PreparePlan& plan, bool is_section, uint64_t id, GeometryResource* committed) {
    for (const GeometryJob& job : plan.jobs) {
        if (job.is_section == is_section && job.id == id && job.created) {
            return job.created.get();
        }
    }
    return committed;
}

/* A refit job keeps the previous acceleration structure and only replaces the
   vertex/index buffer, so instances must reference the previous BLAS. */
GeometryRef job_blas(const PreparePlan& plan, bool is_section, uint64_t id, const GeometryResource* committed) {
    for (const GeometryJob& job : plan.jobs) {
        if (job.is_section == is_section && job.id == id && job.created) {
            if (job.refit) {
                return { job.previous->blas.address, job.previous->blas.handle };
            }
            return { job.created->blas.address, job.created->blas.handle };
        }
    }
    if (committed != nullptr) {
        return { committed->blas.address, committed->blas.handle };
    }
    return {};
}

void plan_section_slots(Scene& scene, PreparePlan& plan) {
    plan.section_slots.clear();
    uint32_t slot = 0;
    for (auto& [id, section] : scene.sections) {
        if (job_geometry(plan, true, id, section.geometry.get()) == nullptr) {
            continue;
        }
        plan.section_slots.emplace(id, slot++);
    }
}

} // namespace bedray_scene
