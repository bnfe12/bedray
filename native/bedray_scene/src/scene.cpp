#include "scene_internal.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <limits>
#include <map>
#include <memory>
#include <new>
#include <set>
#include <string>
#include <utility>
#include <vector>

static_assert(sizeof(BedraySceneError) == 1028, "scene ABI: BedraySceneError must stay 1028 bytes");
static_assert(sizeof(BedraySceneAbiInfo) == 48, "scene ABI: BedraySceneAbiInfo must stay 48 bytes");
static_assert(sizeof(BedraySceneDesc) == 88, "scene ABI: BedraySceneDesc must stay 88 bytes");
static_assert(sizeof(BedraySceneSectionInput) == 96, "scene ABI: section input must stay 96 bytes");
static_assert(sizeof(BedraySceneEntityInput) == 120, "scene ABI: entity input must stay 120 bytes");
static_assert(sizeof(BedraySceneUpdate) == 56, "scene ABI: update must stay 56 bytes");
static_assert(sizeof(BedraySceneTableDesc) == 40, "scene ABI: table desc must stay 40 bytes");
static_assert(sizeof(BedraySceneFrameDesc) == 24, "scene ABI: frame desc must stay 24 bytes");
static_assert(sizeof(BedraySceneFrameInfo) == 80, "scene ABI: frame info must stay 80 bytes");
static_assert(sizeof(BedraySceneResources) == 112, "scene ABI: resources must stay 112 bytes");
static_assert(sizeof(BedraySceneSectionRange) == 8, "scene ABI: section range must stay 8 bytes");
static_assert(sizeof(BedraySceneSectionRecord) == 64, "scene ABI: section record must stay 64 bytes");
static_assert(sizeof(BedraySceneStatus) == 4, "scene ABI: status enum must stay 4 bytes");
static_assert(sizeof(VkAccelerationStructureInstanceKHR) == 64,
    "scene ABI: a TLAS instance record must stay 64 bytes");

/* The shader reads both records as raw bytes, so field offsets are part of the
   ABI just like their sizes. */
static_assert(offsetof(BedraySceneSectionInput, section_id) == 0,
    "scene ABI: section_id must start the section input");
static_assert(offsetof(BedraySceneSectionInput, origin) == 56,
    "scene ABI: section origin must stay at offset 56");
static_assert(offsetof(BedraySceneSectionInput, geometry_count) == 68,
    "scene ABI: geometry_count must stay at offset 68");
static_assert(offsetof(BedraySceneSectionInput, quad_start) == 72,
    "scene ABI: quad_start must stay at offset 72");
static_assert(offsetof(BedraySceneSectionInput, pass_bits) == 84,
    "scene ABI: pass_bits must stay at offset 84");
static_assert(offsetof(BedraySceneSectionInput, flags) == 88,
    "scene ABI: section flags must stay at offset 88");
static_assert(offsetof(BedraySceneSectionInput, reserved) == 92,
    "scene ABI: section reserved must stay at offset 92");
static_assert(offsetof(BedraySceneSectionRecord, origin) == 0,
    "scene ABI: record origin must start the record");
static_assert(offsetof(BedraySceneSectionRecord, meta) == 16,
    "scene ABI: record meta must stay at offset 16");
static_assert(offsetof(BedraySceneSectionRecord, vertex_address) == 32,
    "scene ABI: record vertex address must stay at offset 32");
static_assert(offsetof(BedraySceneSectionRecord, ranges) == 40,
    "scene ABI: record ranges must stay at offset 40");
static_assert(offsetof(BedraySceneSectionRange, first_quad) == 0,
    "scene ABI: range first quad must start the range");
static_assert(offsetof(BedraySceneSectionRange, quad_count_pass) == 4,
    "scene ABI: range count/pass must stay at offset 4");

namespace bedray_scene {

void clear_error(BedraySceneError* error) {
    if (error == nullptr) {
        return;
    }
    error->status = BEDRAY_SCENE_OK;
    error->message[0] = '\0';
}

BedraySceneStatus fail(BedraySceneError* error, BedraySceneStatus status, const char* format, ...) {
    if (error != nullptr) {
        error->status = status;
        va_list args;
        va_start(args, format);
        std::vsnprintf(error->message, BEDRAY_SCENE_ERROR_MESSAGE_BYTES, format, args);
        va_end(args);
        error->message[BEDRAY_SCENE_ERROR_MESSAGE_BYTES - 1] = '\0';
    }
    return status;
}

BedraySceneStatus vk_status(VkResult result) {
    switch (result) {
        case VK_SUCCESS:
            return BEDRAY_SCENE_OK;
        case VK_ERROR_OUT_OF_HOST_MEMORY:
        case VK_ERROR_OUT_OF_DEVICE_MEMORY:
            return BEDRAY_SCENE_ERROR_OUT_OF_MEMORY;
        case VK_ERROR_DEVICE_LOST:
            return BEDRAY_SCENE_ERROR_DEVICE_LOST;
        default:
            return BEDRAY_SCENE_ERROR_VULKAN;
    }
}

void mark_failed(Scene& scene, BedraySceneStatus status, const char* message) {
    if (!scene.failed) {
        scene.failed = true;
        scene.failure_status = status;
        std::snprintf(scene.failure_message, sizeof(scene.failure_message), "%s",
            message != nullptr ? message : "scene failed");
    }
}

BedraySceneStatus failed_status(Scene& scene, BedraySceneError* error) {
    return fail(error, scene.failure_status,
        "scene is in a failed state: %s", scene.failure_message);
}

namespace {

struct GeometryJob {
    bool is_section = false;
    uint64_t id = 0;
    Section* section = nullptr;
    Entity* entity = nullptr;
    const PendingMesh* mesh = nullptr;
    GeometryResource* previous = nullptr;
    bool refit = false;
    std::unique_ptr<GeometryResource> created;
    uint64_t build_scratch = 0;
    uint64_t update_scratch = 0;
    uint64_t scratch_offset = 0;
    uint64_t vertex_upload_offset = 0;
    uint64_t index_upload_offset = 0;
};

struct PlannedCopy {
    uint64_t staging_offset = 0;
    Buffer* dst = nullptr;
    uint64_t dst_offset = 0;
    uint64_t size = 0;
};

struct PreparePlan {
    std::vector<GeometryJob> jobs;
    std::vector<PlannedCopy> copies;

    Buffer staging;
    bool staging_present = false;
    Buffer blas_scratch;
    bool blas_scratch_present = false;

    std::array<Buffer, BEDRAY_SCENE_TABLE_COUNT> new_tables{};
    std::array<bool, BEDRAY_SCENE_TABLE_COUNT> table_action{};
    std::array<uint64_t, BEDRAY_SCENE_TABLE_COUNT> table_upload_offset{};

    Buffer new_section_table;
    bool section_table_rebuild = false;
    bool section_table_present = false;
    uint64_t section_table_upload_offset = 0;

    std::unique_ptr<Acceleration> new_tlas;
    std::unique_ptr<Buffer> new_tlas_scratch;
    std::unique_ptr<Buffer> new_instance_buffer;

    std::vector<InstanceEntry> instances;
    std::vector<BedraySceneSectionRecord> section_records;
    /* section id -> slot in the append-ordered section table. The TLAS
       instanceCustomIndex of a section is exactly this slot, so the table and
       the instances must be generated from one map. */
    std::map<uint64_t, uint32_t> section_slots;

    uint64_t upload_bytes = 0;
    uint64_t blas_rebuilds = 0;
    uint64_t blas_refits = 0;
    uint64_t deferred_updates = 0;
    bool tlas_action = false;
    bool tlas_update = false;
    bool tlas_clear = false;
    bool instance_written = false;
    bool tables_uploaded = false;
    bool section_table_uploaded = false;
    uint64_t target_version = 0;
    uint64_t fingerprint = 0;
    uint32_t instance_count = 0;
    bool committed = false;
};

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
        if (can_collect(scene, it->after_frame)) {
            destroy_acceleration(scene, it->acceleration);
            it = scene.retired_accelerations.erase(it);
        } else {
            ++it;
        }
    }
}

BedraySceneStatus validate_mesh(
    const void* vertices,
    uint64_t vertex_count,
    uint32_t vertex_stride,
    const void* indices,
    uint64_t index_count,
    uint32_t index_type,
    uint64_t max_geometry_bytes,
    BedraySceneError* error,
    uint64_t& vertex_bytes,
    uint64_t& index_bytes) {
    if (vertices == nullptr || vertex_count == 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "mesh has no vertices");
    }
    if (vertex_count > std::numeric_limits<uint32_t>::max()) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "vertex_count %llu exceeds the Vulkan limit", static_cast<unsigned long long>(vertex_count));
    }
    if (vertex_stride < 12 || vertex_stride > 1024 || (vertex_stride % 4) != 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "vertex_stride %u must be a multiple of four between 12 and 1024", vertex_stride);
    }
    if (index_type != BEDRAY_SCENE_INDEX_NONE
        && index_type != BEDRAY_SCENE_INDEX_UINT16
        && index_type != BEDRAY_SCENE_INDEX_UINT32) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "unknown index type %u", index_type);
    }

    if (vertex_count > max_geometry_bytes / vertex_stride) {
        return fail(error, BEDRAY_SCENE_ERROR_LIMIT,
            "mesh exceeds max_geometry_bytes (%llu)", static_cast<unsigned long long>(max_geometry_bytes));
    }
    vertex_bytes = vertex_count * vertex_stride;

    if (index_type == BEDRAY_SCENE_INDEX_NONE) {
        if (index_count != 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "index_count must be zero for a non-indexed mesh");
        }
        if ((vertex_count % 3) != 0) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "non-indexed vertex_count %llu is not a multiple of three",
                static_cast<unsigned long long>(vertex_count));
        }
        index_bytes = 0;
        return BEDRAY_SCENE_OK;
    }

    if (indices == nullptr || index_count == 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "indexed mesh has no indices");
    }
    if ((index_count % 3) != 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "index_count %llu is not a multiple of three", static_cast<unsigned long long>(index_count));
    }
    const uint64_t per_index = index_type == BEDRAY_SCENE_INDEX_UINT16 ? 2 : 4;
    if (index_count > max_geometry_bytes / per_index) {
        return fail(error, BEDRAY_SCENE_ERROR_LIMIT,
            "mesh exceeds max_geometry_bytes (%llu)", static_cast<unsigned long long>(max_geometry_bytes));
    }
    index_bytes = index_count * per_index;
    if (index_bytes > max_geometry_bytes
        || vertex_bytes > max_geometry_bytes - index_bytes) {
        return fail(error, BEDRAY_SCENE_ERROR_LIMIT,
            "mesh exceeds max_geometry_bytes (%llu)", static_cast<unsigned long long>(max_geometry_bytes));
    }
    return BEDRAY_SCENE_OK;
}

BedraySceneStatus validate_section_contract(
    const BedraySceneSectionInput& input,
    BedraySceneError* error) {
    if (input.geometry_count < 1 || input.geometry_count > BEDRAY_SCENE_MAX_SECTION_GEOMETRIES) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "section %llu geometry_count %u must be between 1 and %u",
            static_cast<unsigned long long>(input.section_id), input.geometry_count,
            BEDRAY_SCENE_MAX_SECTION_GEOMETRIES);
    }
    if ((input.flags & ~static_cast<uint32_t>(BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX)) != 0) {
        return fail(error, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "section %llu has unknown flags 0x%08x",
            static_cast<unsigned long long>(input.section_id), input.flags);
    }
    if ((input.flags & BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX) != 0
        && input.vertex_stride != BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "section %llu requests GeometryVertex but has stride %u (need %u)",
            static_cast<unsigned long long>(input.section_id), input.vertex_stride,
            BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES);
    }
    /* The shader-facing stream is one quad per four vertices and two triangles
       per quad. A generic triangle section would decode as a different stream,
       so it is rejected here rather than silently mis-rendered. */
    if ((input.vertex_count % 4) != 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "section %llu vertex_count %llu is not a multiple of four",
            static_cast<unsigned long long>(input.section_id),
            static_cast<unsigned long long>(input.vertex_count));
    }
    const uint64_t quads = input.vertex_count / 4;
    if (quads > 0xFFFFu) {
        return fail(error, BEDRAY_SCENE_ERROR_LIMIT,
            "section %llu has %llu quads; the shader range count is 16-bit",
            static_cast<unsigned long long>(input.section_id),
            static_cast<unsigned long long>(quads));
    }
    if (input.index_type == BEDRAY_SCENE_INDEX_NONE || input.index_count != quads * 6) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "section %llu needs six indices per quad (index_type=%u, index_count=%llu, quads=%llu)",
            static_cast<unsigned long long>(input.section_id), input.index_type,
            static_cast<unsigned long long>(input.index_count),
            static_cast<unsigned long long>(quads));
    }
    if (input.quad_start[0] != 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "section %llu first quad range must start at zero",
            static_cast<unsigned long long>(input.section_id));
    }
    uint32_t previous = 0;
    uint32_t previous_pass = 0;
    for (uint32_t geometry = 0; geometry < input.geometry_count; ++geometry) {
        const uint32_t start = input.quad_start[geometry];
        if (start > quads || (geometry > 0 && start < previous)) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "section %llu quad_start[%u]=%u is outside the %llu-quad stream",
                static_cast<unsigned long long>(input.section_id), geometry, start,
                static_cast<unsigned long long>(quads));
        }
        const uint32_t end = geometry + 1u < input.geometry_count
            ? input.quad_start[geometry + 1u]
            : static_cast<uint32_t>(quads);
        if (start >= end) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "section %llu geometry %u has no quads (start=%u end=%u)",
                static_cast<unsigned long long>(input.section_id), geometry, start, end);
        }
        previous = start;
        const uint32_t pass = (input.pass_bits >> (2u * geometry)) & 0x3u;
        if (pass > 2u) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "section %llu geometry %u has unknown pass %u",
                static_cast<unsigned long long>(input.section_id), geometry, pass);
        }
        if (geometry > 0 && pass <= previous_pass) {
            return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
                "section %llu geometry %u pass %u is not after pass %u",
                static_cast<unsigned long long>(input.section_id), geometry, pass, previous_pass);
        }
        previous_pass = pass;
    }
    if ((input.pass_bits >> (2u * input.geometry_count)) != 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "section %llu pass_bits has bits beyond geometry_count",
            static_cast<unsigned long long>(input.section_id));
    }
    return BEDRAY_SCENE_OK;
}

void copy_mesh(
    PendingMesh& mesh,
    const void* vertices,
    uint64_t vertex_count,
    uint32_t vertex_stride,
    const void* indices,
    uint64_t index_count,
    uint32_t index_type,
    uint64_t vertex_bytes,
    uint64_t index_bytes) {
    mesh.vertices.resize(static_cast<size_t>(vertex_bytes));
    std::memcpy(mesh.vertices.data(), vertices, static_cast<size_t>(vertex_bytes));
    mesh.indices.resize(static_cast<size_t>(index_bytes));
    if (index_bytes > 0) {
        std::memcpy(mesh.indices.data(), indices, static_cast<size_t>(index_bytes));
    }
    mesh.vertex_count = vertex_count;
    mesh.vertex_stride = vertex_stride;
    mesh.index_count = index_count;
    mesh.index_type = index_type;
}

bool transform_valid(const float transform[12]) {
    for (int i = 0; i < 12; ++i) {
        if (!std::isfinite(transform[i])) {
            return false;
        }
    }
    return true;
}

std::array<float, 12> identity_transform() {
    return { 1.0f, 0.0f, 0.0f, 0.0f,
             0.0f, 1.0f, 0.0f, 0.0f,
             0.0f, 0.0f, 1.0f, 0.0f };
}

const std::array<float, 12>& effective_transform(const Entity& entity) {
    return entity.transform_pending ? entity.pending_transform : entity.transform;
}

const std::array<int32_t, 3>& effective_origin(const Section& section) {
    return section.has_pending ? section.pending.origin : section.origin;
}

/* Camera-relative section placement. The renderer traces rays from the eye at
   the origin, so a section is placed at translate(originBlocks - view); the
   subtraction happens in double and rounds once, matching the legacy
   InstanceMath.sectionTransform. */
std::array<float, 12> section_transform(
    const std::array<int32_t, 3>& origin, const std::array<double, 3>& view) {
    return { 1.0f, 0.0f, 0.0f, static_cast<float>(static_cast<double>(origin[0]) - view[0]),
             0.0f, 1.0f, 0.0f, static_cast<float>(static_cast<double>(origin[1]) - view[1]),
             0.0f, 0.0f, 1.0f, static_cast<float>(static_cast<double>(origin[2]) - view[2]) };
}

bool view_differs(const Scene& scene, const FrameSlot& slot) {
    if (scene.view_set != slot.view_valid) {
        return true;
    }
    if (!scene.view_set) {
        return false;
    }
    return scene.view[0] != slot.view[0]
        || scene.view[1] != slot.view[1]
        || scene.view[2] != slot.view[2];
}

uint32_t effective_mask(const Entity& entity) {
    return entity.transform_pending ? entity.pending_mask : entity.instance_mask;
}

uint32_t normalize_mask(uint32_t mask) {
    return mask == 0 ? 0xFFu : (mask & 0xFFu);
}

BlasView blas_view(const PendingMesh& mesh, const Buffer& buffer, uint64_t index_offset, bool quad_stream) {
    BlasView view{};
    view.vertex_address = buffer.address;
    view.index_address = index_offset > 0 ? buffer.address + index_offset : 0;
    view.vertex_count = mesh.vertex_count;
    view.index_count = mesh.index_count;
    view.vertex_stride = mesh.vertex_stride;
    view.index_type = mesh.index_type;
    view.quad_stream = quad_stream;
    view.geometry_count = mesh.geometry_count;
    view.quad_start = mesh.quad_start;
    view.pass_bits = mesh.pass_bits;
    return view;
}

// Stable 32-bit identity of a 64-bit section key for the shader record. The
// table slot is the lookup index; this value only has to survive membership
// changes for history/emitter identity, so it is a hash, not a truncation.
uint32_t section_identity(uint64_t id) {
    uint64_t value = id;
    value ^= value >> 33u;
    value *= 0xff51afd7ed558ccdull;
    value ^= value >> 33u;
    value *= 0xc4ceb9fe1a85ec53ull;
    value ^= value >> 33u;
    return static_cast<uint32_t>(value);
}

uint64_t instance_fingerprint(const std::vector<InstanceEntry>& instances) {
    uint64_t hash = 1469598103934665603ull;
    auto mix = [&hash](uint64_t value) {
        for (int byte = 0; byte < 8; ++byte) {
            hash ^= (value >> (byte * 8)) & 0xFFu;
            hash *= 1099511628211ull;
        }
    };
    mix(instances.size());
    for (const InstanceEntry& instance : instances) {
        mix(reinterpret_cast<uintptr_t>(instance.blas_handle));
        mix(instance.blas_address);
        mix(instance.custom_index);
        mix(instance.mask);
    }
    return hash;
}

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

        if (job.previous == nullptr || topology_changed || !can_refit || !same_shape) {
            job.refit = false;
            if (!build_job_resource(scene, job, error)) {
                return false;
            }
            plan.blas_rebuilds++;
            tlas_changed = true;
        } else {
            if (blas_referenced_by_outstanding(scene, job.previous->blas.handle)) {
                plan.deferred_updates++;
                continue;
            }
            job.refit = true;
            if (!build_job_resource(scene, job, error)) {
                return false;
            }
            plan.blas_refits++;
            /* A refit rewrites the BLAS in place, so every TLAS that references
               it must be rebuilt or updated before it is traced again. */
            tlas_changed = true;
        }
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

struct GeometryRef {
    uint64_t blas_address = 0;
    VkAccelerationStructureKHR blas_handle = VK_NULL_HANDLE;
};

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

    plan.fingerprint = instance_fingerprint(plan.instances);
    /* A view change rewrites every section transform, but the instance
       fingerprint (BLAS, custom index, mask) is unchanged, so the slot takes
       the same TLAS work path as a transform-only scene change and can update
       in place. */
    const bool need = !slot.tlas_valid
        || slot.built_version != plan.target_version
        || slot.instance_count != plan.instance_count
        || view_differs(scene, slot);
    if (!need) {
        return true;
    }

    plan.tlas_update = slot.tlas_valid
        && (scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0
        && slot.instance_count == plan.instance_count
        && slot.fingerprint == plan.fingerprint;

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
        const VkBufferUsageFlags usage =
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        if (!create_buffer(scene, scratch_need, usage, false, *plan.new_tlas_scratch, error)) {
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
    const VkBufferUsageFlags usage =
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    if (!create_buffer(scene, total, usage, false, plan.blas_scratch, error)) {
        return false;
    }
    plan.blas_scratch_present = true;
    return true;
}

Buffer& plan_instance_buffer(PreparePlan& plan, FrameSlot& slot) {
    return plan.new_instance_buffer ? *plan.new_instance_buffer : slot.instance_buffer;
}

BlasView blas_view_of(const GeometryJob& job);

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
        scene.fns.cmd_pipeline_barrier(command_buffer, src_stage,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
            memory_count, as_work ? &memory : nullptr,
            static_cast<uint32_t>(barriers.size()), barriers.data(), 0, nullptr);
    }
}

BlasView blas_view_of(const GeometryJob& job) {
    return blas_view(*job.mesh, job.created->gpu,
        static_cast<uint64_t>(job.created->index_address > 0
            ? job.created->index_address - job.created->gpu.address : 0),
        job.is_section);
}

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
    slot.blas_in_use.reserve(plan.instances.size());

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
    slot.fingerprint = plan.fingerprint;
    slot.instance_count = plan.instance_count;
    slot.view = scene.view;
    slot.view_valid = scene.view_set;
    slot.blas_in_use.clear();
    for (const InstanceEntry& instance : plan.instances) {
        slot.blas_in_use.push_back(instance.blas_handle);
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

} // namespace

namespace {

struct PreparedSection {
    uint64_t id = 0;
    bool queue = false;
    bool cancel_pending = false;
    uint64_t revision = 0;
    PendingMesh mesh;
};

struct PreparedEntity {
    uint64_t id = 0;
    bool queue = false;
    bool cancel_pending = false;
    bool transform_changed = false;
    uint64_t geometry_revision = 0;
    uint64_t topology_revision = 0;
    std::array<float, 12> transform{};
    uint32_t mask = 0xFF;
    PendingMesh mesh;
};

} // namespace

namespace {

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

} // namespace

} // namespace bedray_scene

extern "C" {

using namespace bedray_scene;

BEDRAY_SCENE_API uint32_t BEDRAY_SCENE_CALL bedray_scene_abi_version(void) try {
    return BEDRAY_SCENE_ABI_VERSION;
} catch (...) {
    return 0;
}

BEDRAY_SCENE_API void BEDRAY_SCENE_CALL bedray_scene_abi_info(BedraySceneAbiInfo* out_info) try {
    if (out_info == nullptr) {
        return;
    }
    out_info->abi_version = BEDRAY_SCENE_ABI_VERSION;
    out_info->sizeof_desc = static_cast<uint32_t>(sizeof(BedraySceneDesc));
    out_info->sizeof_section_input = static_cast<uint32_t>(sizeof(BedraySceneSectionInput));
    out_info->sizeof_entity_input = static_cast<uint32_t>(sizeof(BedraySceneEntityInput));
    out_info->sizeof_update = static_cast<uint32_t>(sizeof(BedraySceneUpdate));
    out_info->sizeof_table_desc = static_cast<uint32_t>(sizeof(BedraySceneTableDesc));
    out_info->sizeof_frame_desc = static_cast<uint32_t>(sizeof(BedraySceneFrameDesc));
    out_info->sizeof_frame_info = static_cast<uint32_t>(sizeof(BedraySceneFrameInfo));
    out_info->sizeof_resources = static_cast<uint32_t>(sizeof(BedraySceneResources));
    out_info->sizeof_section_record = static_cast<uint32_t>(sizeof(BedraySceneSectionRecord));
    out_info->sizeof_error = static_cast<uint32_t>(sizeof(BedraySceneError));
    out_info->reserved = 0;
} catch (...) {
    // Preserve the existing void discovery ABI; never unwind into FFM.
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_create(
    const BedraySceneDesc* desc,
    BedrayScene** out_scene,
    BedraySceneError* error) try {
    BedraySceneError local;
    BedraySceneError* err = error != nullptr ? error : &local;
    clear_error(err);

    if (out_scene == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "out_scene is null");
    }
    *out_scene = nullptr;
    if (desc == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "desc is null");
    }
    if (desc->abi_version != BEDRAY_SCENE_ABI_VERSION) {
        return fail(err, BEDRAY_SCENE_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u scene=%u", desc->abi_version, BEDRAY_SCENE_ABI_VERSION);
    }
    if (desc->flags != 0) {
        return fail(err, BEDRAY_SCENE_ERROR_UNSUPPORTED, "desc flags must be zero");
    }
    if (desc->reserved != 0) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "desc reserved field must be zero");
    }
    if (desc->instance == 0 || desc->physical_device == 0 || desc->device == 0) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "instance/physical_device/device must be non-zero");
    }
    if (desc->get_instance_proc_addr == 0 || desc->get_device_proc_addr == 0) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "vkGetInstanceProcAddr/vkGetDeviceProcAddr addresses must be non-zero");
    }
    if (desc->api_version < VK_API_VERSION_1_1) {
        return fail(err, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "Vulkan 1.1 or newer is required (host reported 0x%08x)", desc->api_version);
    }
    if (desc->frames_in_flight == 0 || desc->frames_in_flight > BEDRAY_SCENE_MAX_FRAMES_IN_FLIGHT) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "frames_in_flight must be between 1 and %u", BEDRAY_SCENE_MAX_FRAMES_IN_FLIGHT);
    }
    if (desc->max_sections == 0 || desc->max_entities == 0 || desc->max_instances == 0) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "max_sections/max_entities/max_instances must be non-zero");
    }
    if (desc->max_sections > desc->max_instances || desc->max_entities > desc->max_instances) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "max_sections and max_entities must not exceed max_instances");
    }
    if (desc->max_instances > 0xFFFFFFu) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "max_instances exceeds the 24-bit instanceCustomIndex limit");
    }
    /* Slots must stay clear of the entity tag bit: section slots are stored
       raw and entity slots are OR-ed with kEntityInstanceBit. */
    if (desc->max_sections > kInstanceSlotLimit || desc->max_entities > kInstanceSlotLimit) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "max_sections/max_entities exceed the 23-bit instance slot space (%u)",
            kInstanceSlotLimit);
    }
    if (desc->max_table_bytes == 0 || desc->max_geometry_bytes == 0) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "max_table_bytes and max_geometry_bytes must be non-zero");
    }
    if ((desc->capabilities & kRequiredCapabilities) != kRequiredCapabilities) {
        return fail(err, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "scene requires ACCELERATION_STRUCTURE and DEVICE_ADDRESS capabilities (host=0x%08x)",
            desc->capabilities);
    }

    std::unique_ptr<BedrayScene> owner(new (std::nothrow) BedrayScene());
    if (!owner) {
        return fail(err, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "cannot allocate BedrayScene");
    }
    Scene& impl = owner->impl;
    impl.desc = *desc;
    impl.instance = reinterpret_cast<VkInstance>(static_cast<uintptr_t>(desc->instance));
    impl.physical_device = reinterpret_cast<VkPhysicalDevice>(static_cast<uintptr_t>(desc->physical_device));
    impl.device = reinterpret_cast<VkDevice>(static_cast<uintptr_t>(desc->device));
    impl.capabilities = desc->capabilities;

    if (!load_vulkan(impl, err)) {
        return err->status;
    }
    impl.slots.resize(desc->frames_in_flight);
    impl.last_resources.abi_version = BEDRAY_SCENE_ABI_VERSION;

    *out_scene = owner.release();
    return BEDRAY_SCENE_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "cannot allocate scene state");
} catch (...) {
    return fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception creating scene");
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_update(
    BedrayScene* scene,
    const BedraySceneUpdate* update,
    BedraySceneError* error) try {
    BedraySceneError local;
    BedraySceneError* err = error != nullptr ? error : &local;
    clear_error(err);
    if (scene == nullptr || update == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "scene/update is null");
    }
    Scene& impl = scene->impl;
    if (impl.failed) {
        return failed_status(impl, err);
    }
    if (update->abi_version != BEDRAY_SCENE_ABI_VERSION) {
        return fail(err, BEDRAY_SCENE_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u scene=%u", update->abi_version, BEDRAY_SCENE_ABI_VERSION);
    }
    if (update->flags != 0) {
        return fail(err, BEDRAY_SCENE_ERROR_UNSUPPORTED, "update flags must be zero");
    }
    if (update->section_count > 0 && update->sections == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "sections is null");
    }
    if (update->entity_count > 0 && update->entities == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "entities is null");
    }

    bool mutating = false;
    try {
        std::set<uint64_t> removed_sections;
        std::vector<PreparedSection> prepared_sections;
        BedraySceneStatus status = prepare_sections(
            impl, *update, removed_sections, prepared_sections, err);
        if (status != BEDRAY_SCENE_OK) {
            return status;
        }

        std::set<uint64_t> removed_entities;
        std::vector<PreparedEntity> prepared_entities;
        status = prepare_entities(impl, *update, removed_entities, prepared_entities, err);
        if (status != BEDRAY_SCENE_OK) {
            return status;
        }

        const size_t new_sections = std::count_if(prepared_sections.begin(), prepared_sections.end(),
            [&impl](const PreparedSection& entry) {
                return impl.sections.find(entry.id) == impl.sections.end();
            });
        const size_t new_entities = std::count_if(prepared_entities.begin(), prepared_entities.end(),
            [&impl](const PreparedEntity& entry) {
                return impl.entities.find(entry.id) == impl.entities.end();
            });
        const size_t projected_sections =
            impl.sections.size() - removed_sections.size() + new_sections;
        const size_t projected_entities =
            impl.entities.size() - removed_entities.size() + new_entities;

        if (projected_sections > impl.desc.max_sections) {
            return fail(err, BEDRAY_SCENE_ERROR_LIMIT,
                "section capacity exceeded (%zu > %u)", projected_sections, impl.desc.max_sections);
        }
        if (projected_entities > impl.desc.max_entities) {
            return fail(err, BEDRAY_SCENE_ERROR_LIMIT,
                "entity capacity exceeded (%zu > %u)", projected_entities, impl.desc.max_entities);
        }
        if (projected_sections + projected_entities > impl.desc.max_instances) {
            return fail(err, BEDRAY_SCENE_ERROR_LIMIT,
                "instance capacity exceeded (%zu > %u)",
                projected_sections + projected_entities, impl.desc.max_instances);
        }

        impl.retired_buffers.reserve(impl.retired_buffers.size()
            + removed_sections.size() + removed_entities.size());
        impl.retired_accelerations.reserve(impl.retired_accelerations.size()
            + removed_sections.size() + removed_entities.size());

        mutating = true;
        apply_sections(impl, removed_sections, prepared_sections);
        apply_entities(impl, removed_entities, prepared_entities);
    } catch (const std::bad_alloc&) {
        if (mutating) {
            mark_failed(impl, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "out of memory while applying a scene update");
        }
        return fail(err, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "cannot apply scene update");
    } catch (...) {
        mark_failed(impl, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception while applying a scene update");
        return fail(err, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception applying scene update");
    }

    collect_garbage(impl);
    return BEDRAY_SCENE_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "cannot update scene");
} catch (...) {
    return fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception updating scene");
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_update_table(
    BedrayScene* scene,
    const BedraySceneTableDesc* desc,
    BedraySceneError* error) try {
    BedraySceneError local;
    BedraySceneError* err = error != nullptr ? error : &local;
    clear_error(err);
    if (scene == nullptr || desc == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "scene/desc is null");
    }
    Scene& impl = scene->impl;
    if (impl.failed) {
        return failed_status(impl, err);
    }
    if (desc->abi_version != BEDRAY_SCENE_ABI_VERSION) {
        return fail(err, BEDRAY_SCENE_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u scene=%u", desc->abi_version, BEDRAY_SCENE_ABI_VERSION);
    }
    if (desc->reserved != 0) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "table reserved field must be zero");
    }
    if (desc->table_kind >= BEDRAY_SCENE_TABLE_COUNT) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "unknown table kind %u", desc->table_kind);
    }
    if (desc->size > impl.desc.max_table_bytes) {
        return fail(err, BEDRAY_SCENE_ERROR_LIMIT,
            "table size %llu exceeds max_table_bytes (%u)",
            static_cast<unsigned long long>(desc->size), impl.desc.max_table_bytes);
    }
    if (desc->size > 0 && desc->data == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "table data is null");
    }

    Table& table = impl.tables[desc->table_kind];
    if (table.has_pending && table.pending_revision == desc->revision) {
        return BEDRAY_SCENE_OK;
    }
    if (!table.has_pending && table.revision == desc->revision) {
        return BEDRAY_SCENE_OK;
    }

    std::vector<uint8_t> pending;
    if (desc->size > 0) {
        pending.resize(static_cast<size_t>(desc->size));
        std::memcpy(pending.data(), desc->data, static_cast<size_t>(desc->size));
    }
    table.pending = std::move(pending);
    table.pending_revision = desc->revision;
    table.has_pending = true;
    return BEDRAY_SCENE_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "cannot store scene table");
} catch (...) {
    return fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception updating scene table");
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_set_view(
    BedrayScene* scene,
    double eye_x,
    double eye_y,
    double eye_z,
    BedraySceneError* error) try {
    BedraySceneError local;
    BedraySceneError* err = error != nullptr ? error : &local;
    clear_error(err);
    if (scene == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "scene is null");
    }
    if (!std::isfinite(eye_x) || !std::isfinite(eye_y) || !std::isfinite(eye_z)) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "view components must be finite (got %g, %g, %g)", eye_x, eye_y, eye_z);
    }
    Scene& impl = scene->impl;
    if (impl.failed) {
        return failed_status(impl, err);
    }
    impl.view = { eye_x, eye_y, eye_z };
    impl.view_set = true;
    return BEDRAY_SCENE_OK;
} catch (...) {
    return fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception setting scene view");
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_prepare_frame(
    BedrayScene* scene,
    const BedraySceneFrameDesc* desc,
    BedraySceneFrameInfo* out_info,
    BedraySceneError* error) try {
    BedraySceneError local;
    BedraySceneError* err = error != nullptr ? error : &local;
    clear_error(err);
    if (scene == nullptr || desc == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "scene/desc is null");
    }
    if (out_info == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "out_info is null");
    }
    std::memset(out_info, 0, sizeof(*out_info));
    out_info->abi_version = BEDRAY_SCENE_ABI_VERSION;

    Scene& impl = scene->impl;
    if (impl.failed) {
        return failed_status(impl, err);
    }
    if (desc->abi_version != BEDRAY_SCENE_ABI_VERSION) {
        return fail(err, BEDRAY_SCENE_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u scene=%u", desc->abi_version, BEDRAY_SCENE_ABI_VERSION);
    }
    if (desc->flags != 0) {
        return fail(err, BEDRAY_SCENE_ERROR_UNSUPPORTED, "frame flags must be zero");
    }
    if (desc->command_buffer == 0) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "command_buffer is null");
    }
    if (desc->frame_index == 0 || desc->frame_index <= impl.last_frame_index) {
        return fail(err, BEDRAY_SCENE_ERROR_STALE,
            "frame_index %llu is not greater than the last prepared frame %llu",
            static_cast<unsigned long long>(desc->frame_index),
            static_cast<unsigned long long>(impl.last_frame_index));
    }
    if (impl.outstanding >= impl.desc.frames_in_flight) {
        return fail(err, BEDRAY_SCENE_ERROR_IN_USE,
            "%u frames are already outstanding", impl.outstanding);
    }
    if (command_buffer_outstanding(impl, desc->command_buffer)) {
        return fail(err, BEDRAY_SCENE_ERROR_IN_USE,
            "command buffer 0x%llx already has an unreleased recording",
            static_cast<unsigned long long>(desc->command_buffer));
    }
    FrameSlot* slot = find_free_slot(impl);
    if (slot == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_IN_USE, "no free frame slot");
    }

    collect_garbage(impl);

    PreparePlan plan;
    bool tlas_changed = false;
    try {
        if (!plan_geometry_jobs(impl, plan, tlas_changed, err)) {
            cleanup_plan(impl, plan);
            return err->status;
        }
        if (impl.membership_changed) {
            tlas_changed = true;
        }
        plan_section_slots(impl, plan);
        plan_instances(impl, plan);
        plan_section_records(impl, plan);
        if (!plan_uploads(impl, plan, err)) {
            cleanup_plan(impl, plan);
            return err->status;
        }
        if (!plan_tlas(impl, plan, *slot, tlas_changed, err)) {
            cleanup_plan(impl, plan);
            return err->status;
        }
        if (!plan_blas_scratch(impl, plan, err)) {
            cleanup_plan(impl, plan);
            return err->status;
        }

        record_frame(impl, plan, *slot,
            reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(desc->command_buffer)));
        commit_plan(impl, plan, *slot, *desc);
    } catch (const std::bad_alloc&) {
        cleanup_plan(impl, plan);
        mark_failed(impl, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "out of memory while preparing a scene frame");
        return fail(err, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "cannot prepare scene frame");
    } catch (...) {
        cleanup_plan(impl, plan);
        mark_failed(impl, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception while preparing a scene frame");
        return fail(err, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception preparing scene frame");
    }

    out_info->flags = 0;
    if (plan.tlas_action) {
        out_info->flags |= plan.tlas_update ? BEDRAY_SCENE_FRAME_TLAS_UPDATED
                                            : BEDRAY_SCENE_FRAME_TLAS_REBUILT;
    } else if (plan.tlas_clear) {
        out_info->flags |= BEDRAY_SCENE_FRAME_TLAS_REBUILT;
    }
    if (plan.blas_rebuilds > 0) {
        out_info->flags |= BEDRAY_SCENE_FRAME_BLAS_REBUILT;
    }
    if (plan.blas_refits > 0) {
        out_info->flags |= BEDRAY_SCENE_FRAME_BLAS_REFIT;
    }
    if (plan.upload_bytes > 0) {
        out_info->flags |= BEDRAY_SCENE_FRAME_UPLOADED;
    }
    if (plan.tables_uploaded || plan.section_table_uploaded) {
        out_info->flags |= BEDRAY_SCENE_FRAME_TABLES_UPLOADED;
    }
    if (out_info->flags == 0) {
        out_info->flags |= BEDRAY_SCENE_FRAME_IDLE;
    }
    out_info->frame_index = desc->frame_index;
    out_info->tlas = reinterpret_cast<uint64_t>(slot->tlas.handle);
    out_info->instance_count = plan.instance_count;
    out_info->blas_builds = plan.blas_rebuilds;
    out_info->blas_refits = plan.blas_refits;
    out_info->deferred_updates = plan.deferred_updates;
    out_info->upload_bytes = plan.upload_bytes;
    out_info->section_count = impl.sections.size();
    return BEDRAY_SCENE_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_SCENE_ERROR_OUT_OF_MEMORY, "cannot prepare scene frame");
} catch (...) {
    return fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception preparing scene frame");
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_release_frame(
    BedrayScene* scene,
    uint64_t frame_index,
    BedraySceneError* error) try {
    BedraySceneError local;
    BedraySceneError* err = error != nullptr ? error : &local;
    clear_error(err);
    if (scene == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "scene is null");
    }
    Scene& impl = scene->impl;
    FrameSlot* slot = find_slot(impl, frame_index);
    if (slot == nullptr) {
        return fail(err, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT,
            "frame %llu is not outstanding", static_cast<unsigned long long>(frame_index));
    }
    for (Buffer& buffer : slot->transient) {
        destroy_buffer(impl, buffer);
    }
    slot->transient.clear();
    slot->free = true;
    slot->frame_index = 0;
    slot->command_buffer = 0;
    slot->blas_in_use.clear();
    if (impl.outstanding > 0) {
        impl.outstanding--;
    }
    collect_garbage(impl);
    return BEDRAY_SCENE_OK;
} catch (...) {
    return fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception releasing a scene frame");
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_query_resources(
    BedrayScene* scene,
    BedraySceneResources* out_resources) try {
    if (scene == nullptr || out_resources == nullptr) {
        return BEDRAY_SCENE_ERROR_INVALID_ARGUMENT;
    }
    const Scene& impl = scene->impl;
    if (!impl.has_last_resources) {
        std::memset(out_resources, 0, sizeof(*out_resources));
        out_resources->abi_version = BEDRAY_SCENE_ABI_VERSION;
        return BEDRAY_SCENE_OK;
    }
    *out_resources = impl.last_resources;
    return BEDRAY_SCENE_OK;
} catch (...) {
    return BEDRAY_SCENE_ERROR_INTERNAL;
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_resident_bytes(
    BedrayScene* scene, uint64_t* out_bytes) try {
    if (scene == nullptr || out_bytes == nullptr) return BEDRAY_SCENE_ERROR_INVALID_ARGUMENT;
    *out_bytes = scene->impl.resident_bytes;
    return BEDRAY_SCENE_OK;
} catch (...) {
    return BEDRAY_SCENE_ERROR_INTERNAL;
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_destroy(BedrayScene* scene) {
    if (scene == nullptr) {
        return BEDRAY_SCENE_ERROR_INVALID_ARGUMENT;
    }
    try {
        Scene& impl = scene->impl;
        if (impl.outstanding > 0) {
            return BEDRAY_SCENE_ERROR_IN_USE;
        }
        for (auto& [id, section] : impl.sections) {
            if (section.geometry) {
                destroy_geometry(impl, *section.geometry);
                section.geometry.reset();
            }
        }
        for (auto& [id, entity] : impl.entities) {
            if (entity.geometry) {
                destroy_geometry(impl, *entity.geometry);
                entity.geometry.reset();
            }
        }
        for (Table& table : impl.tables) {
            destroy_buffer(impl, table.gpu);
        }
        destroy_buffer(impl, impl.section_table);
        for (FrameSlot& slot : impl.slots) {
            for (Buffer& buffer : slot.transient) {
                destroy_buffer(impl, buffer);
            }
            slot.transient.clear();
            destroy_acceleration(impl, slot.tlas);
            destroy_buffer(impl, slot.instance_buffer);
            destroy_buffer(impl, slot.tlas_scratch);
        }
        for (RetiredBuffer& retired : impl.retired_buffers) {
            destroy_buffer(impl, retired.buffer);
        }
        impl.retired_buffers.clear();
        for (RetiredAcceleration& retired : impl.retired_accelerations) {
            destroy_acceleration(impl, retired.acceleration);
        }
        impl.retired_accelerations.clear();
        delete scene;
        return BEDRAY_SCENE_OK;
    } catch (...) {
        return BEDRAY_SCENE_ERROR_INTERNAL;
    }
}

} // extern "C"
