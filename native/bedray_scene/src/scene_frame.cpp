#include "scene_internal.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

namespace bedray_scene {

FrameSlot* find_free_slot(Scene& scene) {
    for (FrameSlot& slot : scene.slots) {
        if (slot.free) {
            return &slot;
        }
    }
    return nullptr;
}

FrameSlot* find_slot(Scene& scene, uint64_t frame_index) {
    for (FrameSlot& slot : scene.slots) {
        if (!slot.free && slot.frame_index == frame_index) {
            return &slot;
        }
    }
    return nullptr;
}

bool command_buffer_outstanding(const Scene& scene, uint64_t command_buffer) {
    for (const FrameSlot& slot : scene.slots) {
        if (!slot.free && slot.command_buffer == command_buffer) {
            return true;
        }
    }
    return false;
}

namespace {

bool can_collect(const Scene& scene, uint64_t after_frame) {
    if (after_frame == 0) {
        return true;
    }
    for (const FrameSlot& slot : scene.slots) {
        if (!slot.free && slot.frame_index <= after_frame) {
            return false;
        }
    }
    return true;
}

} // namespace

void collect_garbage(Scene& scene) {
    for (auto it = scene.retired_buffers.begin(); it != scene.retired_buffers.end();) {
        if (can_collect(scene, it->after_frame)) {
            destroy_buffer(scene, it->buffer);
            it = scene.retired_buffers.erase(it);
        } else {
            ++it;
        }
    }
    for (auto it = scene.retired_accelerations.begin(); it != scene.retired_accelerations.end();) {
        if (can_collect(scene, it->after_frame)
                && !blas_referenced_by_outstanding(scene, it->acceleration.handle)
                && !blas_referenced_by_cached_tlas(scene, it->acceleration.handle)) {
            destroy_acceleration(scene, it->acceleration);
            it = scene.retired_accelerations.erase(it);
        } else {
            ++it;
        }
    }
}

void plan_instances(Scene& scene, PreparePlan& plan) {
    plan.instances.clear();
    plan.instances.reserve(scene.sections.size() + scene.entities.size());

    for (auto& [id, section] : scene.sections) {
        const GeometryRef geometry = job_blas(plan, true, id, section.geometry.get());
        if (geometry.blas_handle == VK_NULL_HANDLE) {
            continue;
        }
        const auto slot = plan.section_slots.find(id);
        if (slot == plan.section_slots.end()) {
            continue;
        }
        InstanceEntry instance{};
        instance.transform = scene.view_set
            ? section_transform(effective_origin(section), scene.view)
            : identity_transform();
        instance.blas_address = geometry.blas_address;
        instance.blas_handle = geometry.blas_handle;
        instance.custom_index = slot->second;
        instance.mask = 0xFF;
        plan.instances.push_back(instance);
    }

    uint32_t entity_index = 0;
    for (auto& [id, entity] : scene.entities) {
        const GeometryRef geometry = job_blas(plan, false, id, entity.geometry.get());
        if (geometry.blas_handle == VK_NULL_HANDLE) {
            continue;
        }
        InstanceEntry instance{};
        instance.transform = effective_transform(entity);
        instance.blas_address = geometry.blas_address;
        instance.blas_handle = geometry.blas_handle;
        instance.custom_index = kEntityInstanceBit | entity_index;
        instance.mask = effective_mask(entity);
        entity_index++;
        plan.instances.push_back(instance);
    }
}

void plan_section_records(Scene& scene, PreparePlan& plan) {
    plan.section_records.clear();
    const bool section_job = std::any_of(plan.jobs.begin(), plan.jobs.end(),
        [](const GeometryJob& job) { return job.is_section; });
    plan.section_table_rebuild = scene.section_table_dirty || section_job;
    if (!plan.section_table_rebuild) {
        return;
    }
    plan.section_records.reserve(plan.section_slots.size());
    /* Iterating the slot map keeps the record order (and therefore every
       record's index) equal to the TLAS custom index of its section. */
    for (const auto& [id, slot] : plan.section_slots) {
        (void)slot;
        auto it = scene.sections.find(id);
        if (it == scene.sections.end()) {
            continue;
        }
        Section& section = it->second;
        GeometryResource* geometry = job_geometry(plan, true, id, section.geometry.get());
        if (geometry == nullptr) {
            continue;
        }
        BedraySceneSectionRecord record{};
        record.origin[0] = section.has_pending ? section.pending.origin[0] : section.origin[0];
        record.origin[1] = section.has_pending ? section.pending.origin[1] : section.origin[1];
        record.origin[2] = section.has_pending ? section.pending.origin[2] : section.origin[2];
        record.origin[3] = static_cast<int32_t>(section_identity(section.id));
        const uint64_t vertex_count = section.has_pending
            ? section.pending.vertex_count : section.vertex_count;
        const uint32_t geometry_count = section.has_pending
            ? section.pending.geometry_count : section.geometry_count;
        record.meta[0] = static_cast<uint32_t>(
            std::min<uint64_t>(vertex_count, std::numeric_limits<uint32_t>::max()));
        record.meta[1] = geometry_count;
        record.meta[2] = section.has_pending ? section.pending.flags : section.flags;
        record.meta[3] = 0;
        record.vertex_address = geometry->vertex_address;
        const uint64_t quads = vertex_count / 4;
        for (uint32_t g = 0; g < geometry_count && g < BEDRAY_SCENE_MAX_SECTION_GEOMETRIES; ++g) {
            const uint32_t start = section.has_pending
                ? section.pending.quad_start[g] : section.quad_start[g];
            const uint32_t next = (g + 1 < geometry_count)
                ? (section.has_pending ? section.pending.quad_start[g + 1] : section.quad_start[g + 1])
                : static_cast<uint32_t>(std::min<uint64_t>(quads, 0xFFFFu));
            const uint32_t count = next > start ? next - start : 0;
            const uint32_t pass = (section.has_pending ? section.pending.pass_bits : section.pass_bits)
                >> (2u * g) & 0x3u;
            record.ranges[g].first_quad = start;
            record.ranges[g].quad_count_pass = (count & 0xFFFFu) | ((pass & 0x3u) << 16u);
        }
        plan.section_records.push_back(record);
    }
}

bool plan_uploads(Scene& scene, PreparePlan& plan, BedraySceneError* error) {
    uint64_t cursor = 0;
    auto reserve_bytes = [&cursor](uint64_t bytes) {
        cursor = align_up(cursor, kBufferAlignment);
        const uint64_t offset = cursor;
        cursor += bytes;
        return offset;
    };

    for (GeometryJob& job : plan.jobs) {
        const PendingMesh& mesh = *job.mesh;
        job.vertex_upload_offset = reserve_bytes(mesh.vertices.size());
        if (!mesh.indices.empty()) {
            job.index_upload_offset = reserve_bytes(mesh.indices.size());
        }
    }

    for (uint32_t kind = 0; kind < BEDRAY_SCENE_TABLE_COUNT; ++kind) {
        Table& table = scene.tables[kind];
        if (!table.has_pending) {
            continue;
        }
        plan.table_action[kind] = true;
        plan.tables_uploaded = true;
        if (table.pending.empty()) {
            continue;
        }
        if (table.pending.size() > scene.desc.max_table_bytes) {
            fail(error, BEDRAY_SCENE_ERROR_LIMIT, "table %u exceeds max_table_bytes", kind);
            return false;
        }
        const VkBufferUsageFlags usage =
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (!create_buffer(scene, static_cast<uint64_t>(table.pending.size()), usage, false,
                plan.new_tables[kind], error)) {
            return false;
        }
        plan.table_upload_offset[kind] = reserve_bytes(table.pending.size());
    }

    if (plan.section_table_rebuild) {
        // An entity-only scene still needs a valid section descriptor. Publish a
        // real zeroed record while keeping the logical section count at zero.
        const uint64_t bytes = std::max<size_t>(1, plan.section_records.size()) * BEDRAY_SCENE_SECTION_RECORD_BYTES;
        const VkBufferUsageFlags usage =
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (!create_buffer(scene, bytes, usage, false, plan.new_section_table, error)) {
            return false;
        }
        plan.section_table_present = true;
        plan.section_table_uploaded = true;
        plan.section_table_upload_offset = reserve_bytes(bytes);
    }

    plan.upload_bytes = cursor;
    if (cursor == 0) {
        return true;
    }

    if (!create_buffer(scene, cursor, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, plan.staging, error)) {
        return false;
    }
    plan.staging_present = true;
    if (plan.staging.mapped == nullptr) {
        fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "staging buffer is not mapped");
        return false;
    }

    auto* base = static_cast<uint8_t*>(plan.staging.mapped);
    plan.copies.reserve(plan.jobs.size() * 2 + BEDRAY_SCENE_TABLE_COUNT + 1);
    for (GeometryJob& job : plan.jobs) {
        const PendingMesh& mesh = *job.mesh;
        std::memcpy(base + job.vertex_upload_offset, mesh.vertices.data(), mesh.vertices.size());
        plan.copies.push_back({ job.vertex_upload_offset, &job.created->gpu, 0, mesh.vertices.size() });
        if (!mesh.indices.empty()) {
            std::memcpy(base + job.index_upload_offset, mesh.indices.data(), mesh.indices.size());
            const uint64_t index_offset = static_cast<uint64_t>(job.created->index_address - job.created->gpu.address);
            plan.copies.push_back({ job.index_upload_offset, &job.created->gpu, index_offset, mesh.indices.size() });
        }
    }
    for (uint32_t kind = 0; kind < BEDRAY_SCENE_TABLE_COUNT; ++kind) {
        Table& table = scene.tables[kind];
        if (!plan.table_action[kind] || table.pending.empty()) {
            continue;
        }
        std::memcpy(base + plan.table_upload_offset[kind], table.pending.data(), table.pending.size());
        plan.copies.push_back({ plan.table_upload_offset[kind], &plan.new_tables[kind], 0, table.pending.size() });
    }
    if (plan.section_table_present) {
        const uint64_t bytes = plan.new_section_table.size;
        std::memset(base + plan.section_table_upload_offset, 0, static_cast<size_t>(bytes));
        if (!plan.section_records.empty())
            std::memcpy(base + plan.section_table_upload_offset, plan.section_records.data(),
                plan.section_records.size() * BEDRAY_SCENE_SECTION_RECORD_BYTES);
        plan.copies.push_back({ plan.section_table_upload_offset, &plan.new_section_table, 0, bytes });
    }

    return flush_buffer(scene, plan.staging, 0, plan.staging.size, error);
}

bool plan_tlas(Scene& scene, PreparePlan& plan, FrameSlot& slot, bool tlas_changed, BedraySceneError* error) {
    plan.target_version = scene.version + (tlas_changed ? 1u : 0u);
    plan.instance_count = static_cast<uint32_t>(plan.instances.size());

    if (plan.instances.empty()) {
        plan.tlas_clear = slot.tlas_valid;
        return true;
    }

    /* Every published instance has a nonzero BLAS reference. For a released slot,
       UPDATE may replace instance definitions (including BLAS/mask/custom index)
       while the instance count and active state remain unchanged. */
    const bool need = !slot.tlas_valid
        || slot.built_version != plan.target_version
        || slot.instance_count != plan.instance_count
        || view_differs(scene, slot);
    if (!need) {
        return true;
    }

    plan.tlas_update = slot.tlas_valid
        && (scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0
        && slot.instance_count == plan.instance_count;

    uint64_t storage_size = 0;
    uint64_t build_scratch = 0;
    uint64_t update_scratch = 0;
    if (!query_tlas_sizes(scene, plan.instance_count, storage_size, build_scratch, update_scratch, error)) {
        return false;
    }

    if (!plan.tlas_update) {
        plan.new_tlas = std::make_unique<Acceleration>();
        if (!create_acceleration_object(scene, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                storage_size, *plan.new_tlas, error)) {
            return false;
        }
    }

    const uint64_t scratch_need = std::max(
        align_up(plan.tlas_update ? update_scratch : build_scratch, scene.scratch_alignment),
        scene.scratch_alignment);
    if (slot.tlas_scratch.handle == VK_NULL_HANDLE || slot.tlas_scratch_capacity < scratch_need) {
        plan.new_tlas_scratch = std::make_unique<Buffer>();
        if (!create_scratch_buffer(scene, scratch_need, *plan.new_tlas_scratch, error)) {
            return false;
        }
    }

    if (slot.instance_buffer.handle == VK_NULL_HANDLE) {
        plan.new_instance_buffer = std::make_unique<Buffer>();
        const uint64_t bytes = align_up(
            static_cast<uint64_t>(scene.desc.max_instances) * BEDRAY_SCENE_INSTANCE_RECORD_BYTES,
            kBufferAlignment);
        const VkBufferUsageFlags usage =
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        if (!create_buffer(scene, bytes, usage, true, *plan.new_instance_buffer, error)) {
            return false;
        }
    }

    Buffer& instance_buffer = plan.new_instance_buffer ? *plan.new_instance_buffer : slot.instance_buffer;
    if (instance_buffer.mapped == nullptr) {
        fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "instance buffer is not mapped");
        return false;
    }
    std::memset(instance_buffer.mapped, 0,
        plan.instances.size() * BEDRAY_SCENE_INSTANCE_RECORD_BYTES);
    auto* base = static_cast<uint8_t*>(instance_buffer.mapped);
    for (size_t i = 0; i < plan.instances.size(); ++i) {
        const InstanceEntry& instance = plan.instances[i];
        VkAccelerationStructureInstanceKHR record{};
        std::memcpy(&record.transform, instance.transform.data(), sizeof(record.transform));
        record.instanceCustomIndex = instance.custom_index & kInstanceCustomIndexMask;
        record.mask = instance.mask & 0xFFu;
        record.instanceShaderBindingTableRecordOffset = 0;
        record.flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;
        record.accelerationStructureReference = instance.blas_address;
        std::memcpy(base + i * BEDRAY_SCENE_INSTANCE_RECORD_BYTES, &record, sizeof(record));
    }
    if (!flush_buffer(scene, instance_buffer, 0, instance_buffer.size, error)) {
        return false;
    }

    plan.tlas_action = true;
    plan.instance_written = true;
    return true;
}

bool plan_blas_scratch(Scene& scene, PreparePlan& plan, BedraySceneError* error) {
    uint64_t total = 0;
    for (GeometryJob& job : plan.jobs) {
        uint64_t size = align_up(job.refit ? job.update_scratch : job.build_scratch,
            scene.scratch_alignment);
        if (size == 0) {
            size = scene.scratch_alignment;
        }
        job.scratch_offset = total;
        total += size;
    }
    if (total == 0) {
        return true;
    }
    if (!create_scratch_buffer(scene, total, plan.blas_scratch, error)) {
        return false;
    }
    plan.blas_scratch_present = true;
    return true;
}

namespace {

Buffer& plan_instance_buffer(PreparePlan& plan, FrameSlot& slot) {
    return plan.new_instance_buffer ? *plan.new_instance_buffer : slot.instance_buffer;
}

BlasView blas_view_of(const GeometryJob& job);

} // namespace

void record_frame(Scene& scene, PreparePlan& plan, FrameSlot& slot, VkCommandBuffer command_buffer) {
    std::vector<VkBufferMemoryBarrier> barriers;
    barriers.reserve(plan.jobs.size() * 2 + BEDRAY_SCENE_TABLE_COUNT + 3);

    auto buffer_barrier = [&barriers](const Buffer& buffer, VkAccessFlags src, VkAccessFlags dst) {
        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = src;
        barrier.dstAccessMask = dst;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = buffer.handle;
        barrier.offset = 0;
        barrier.size = VK_WHOLE_SIZE;
        barriers.push_back(barrier);
    };

    if (plan.staging_present) {
        buffer_barrier(plan.staging, VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        record_buffer_barriers(scene, command_buffer,
            VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            barriers.data(), static_cast<uint32_t>(barriers.size()));
        barriers.clear();
    }

    for (const PlannedCopy& copy : plan.copies) {
        record_copy(scene, command_buffer, plan.staging, copy.staging_offset,
            *copy.dst, copy.dst_offset, copy.size);
    }

    const bool as_work = !plan.jobs.empty() || plan.tlas_action;
    if (!plan.jobs.empty() || plan.instance_written) {
        VkPipelineStageFlags src_stage = 0;
        if (!plan.jobs.empty()) {
            src_stage |= VK_PIPELINE_STAGE_TRANSFER_BIT;
            for (const GeometryJob& job : plan.jobs) {
                buffer_barrier(job.created->gpu,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            }
        }
        if (plan.instance_written) {
            src_stage |= VK_PIPELINE_STAGE_HOST_BIT;
            buffer_barrier(plan_instance_buffer(plan, slot),
                VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        }
        record_buffer_barriers(scene, command_buffer,
            src_stage, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            barriers.data(), static_cast<uint32_t>(barriers.size()));
        barriers.clear();
    }

    for (const GeometryJob& job : plan.jobs) {
        const uint64_t scratch_address = plan.blas_scratch.address + job.scratch_offset;
        if (job.refit) {
            record_blas_build(scene, command_buffer, blas_view_of(job), job.previous->blas.handle,
                job.previous->blas.handle, true, scratch_address);
        } else {
            record_blas_build(scene, command_buffer, blas_view_of(job), job.created->blas.handle,
                VK_NULL_HANDLE, false, scratch_address);
        }
    }

    if (plan.tlas_action && (!plan.jobs.empty() || plan.tlas_update)) {
        record_memory_barrier(scene, command_buffer,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
            VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }

    if (plan.tlas_action) {
        const VkAccelerationStructureKHR target = plan.new_tlas
            ? plan.new_tlas->handle : slot.tlas.handle;
        const VkAccelerationStructureKHR source = plan.tlas_update ? target : VK_NULL_HANDLE;
        const uint64_t scratch_address = plan.new_tlas_scratch
            ? plan.new_tlas_scratch->address : slot.tlas_scratch.address;
        Buffer& instance_buffer = plan_instance_buffer(plan, slot);
        record_tlas_build(scene, command_buffer, instance_buffer.address, plan.instance_count,
            target, source, plan.tlas_update, scratch_address);
    }

    if (as_work || plan.tables_uploaded || plan.section_table_uploaded) {
        VkPipelineStageFlags src_stage = 0;
        if (as_work) {
            src_stage |= VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
        }
        VkAccessFlags src_access = 0;
        if (as_work) {
            src_access |= VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
        }
        if (!plan.jobs.empty() || plan.tables_uploaded || plan.section_table_uploaded) {
            src_stage |= VK_PIPELINE_STAGE_TRANSFER_BIT;
        }
        if (plan.instance_written) {
            src_stage |= VK_PIPELINE_STAGE_HOST_BIT;
        }

        for (const GeometryJob& job : plan.jobs) {
            buffer_barrier(job.created->gpu, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        for (uint32_t kind = 0; kind < BEDRAY_SCENE_TABLE_COUNT; ++kind) {
            if (plan.table_action[kind] && plan.new_tables[kind].handle != VK_NULL_HANDLE) {
                buffer_barrier(plan.new_tables[kind], VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
        }
        if (plan.section_table_present) {
            buffer_barrier(plan.new_section_table, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        if (plan.instance_written) {
            buffer_barrier(plan_instance_buffer(plan, slot), VK_ACCESS_HOST_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        }

        VkMemoryBarrier memory{};
        memory.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memory.srcAccessMask = src_access;
        /* Shader traces read the TLAS through an acceleration-structure access,
           which VK_ACCESS_SHADER_READ_BIT alone does not cover. */
        memory.dstAccessMask = as_work
            ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR)
            : 0;
        const uint32_t memory_count = as_work ? 1u : 0u;
        /* The host may consume this scene from compute ray queries or a ray-generation pipeline.
           ALL_COMMANDS covers either without requiring the scene ABI to assume rayTracingPipeline was enabled. */
        scene.fns.cmd_pipeline_barrier(command_buffer, src_stage,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
            memory_count, as_work ? &memory : nullptr,
            static_cast<uint32_t>(barriers.size()), barriers.data(), 0, nullptr);
    }
}

namespace {

BlasView blas_view_of(const GeometryJob& job) {
    return blas_view(*job.mesh, job.created->gpu,
        static_cast<uint64_t>(job.created->index_address > 0
            ? job.created->index_address - job.created->gpu.address : 0),
        job.is_section);
}

} // namespace

} // namespace bedray_scene
