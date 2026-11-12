#include "scene_internal.h"

#include <array>
#include <cmath>
#include <cstring>
#include <limits>

namespace bedray_scene {

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
    view.allow_update = !quad_stream; // Static sections always rebuild; only entity BLAS needs update storage.
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

bool finite_positions(const PendingMesh& mesh) {
    for (uint64_t vertex = 0; vertex < mesh.vertex_count; ++vertex) {
        float position[3];
        std::memcpy(position, mesh.vertices.data() + vertex * mesh.vertex_stride, sizeof(position));
        if (!std::isfinite(position[0]) || !std::isfinite(position[1]) || !std::isfinite(position[2])) return false;
    }
    return true;
}

} // namespace bedray_scene
