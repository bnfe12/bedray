#include "scene_internal.h"
#include "bedray/memory_binding.hpp"

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <set>
#include <utility>
#include <vector>

bedray::gpu::Status bedray::gpu::attach_memory(BedrayScene* scene, std::shared_ptr<MemoryAllocator> memory) noexcept {
    if (!scene || !memory || !memory->valid() || memory->device() != scene->impl.device) return Status::invalid_argument;
    if (scene->impl.resident_bytes || scene->impl.failed) return Status::in_use;
    scene->impl.memory = std::move(memory);
    return Status::ok;
}

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
    if (status == BEDRAY_SCENE_ERROR_DEVICE_LOST && scene.memory) scene.memory->device_lost();
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

} // namespace bedray_scene

extern "C" {

using namespace bedray_scene;

BEDRAY_SCENE_API uint32_t BEDRAY_SCENE_CALL bedray_scene_abi_version(void) try {
    return BEDRAY_SCENE_ABI_VERSION;
} catch (...) {
    return 0;
}

BEDRAY_SCENE_API uint32_t BEDRAY_SCENE_CALL bedray_scene_features(void) {
    return BEDRAY_SCENE_FEATURE_SAFE_AS_UPDATE;
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
    if (plan.blas_copy_on_write) out_info->flags |= BEDRAY_SCENE_FRAME_BLAS_COPY_ON_WRITE;
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

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_discard_frame(
    BedrayScene* scene, uint64_t frame_index, BedraySceneError* error) try {
    if (scene == nullptr || find_slot(scene->impl, frame_index) == nullptr)
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "discarded frame is not outstanding");
    mark_failed(scene->impl, BEDRAY_SCENE_ERROR_INTERNAL,
        "prepared GPU writes were discarded; recreate the scene from host captures");
    return bedray_scene_release_frame(scene, frame_index, error);
} catch (...) {
    return fail(error, BEDRAY_SCENE_ERROR_INTERNAL, "unexpected exception discarding scene frame");
}

BEDRAY_SCENE_API BedraySceneStatus BEDRAY_SCENE_CALL bedray_scene_query_resources(
    BedrayScene* scene,
    BedraySceneResources* out_resources) try {
    if (scene == nullptr || out_resources == nullptr) {
        return BEDRAY_SCENE_ERROR_INVALID_ARGUMENT;
    }
    const Scene& impl = scene->impl;
    if (impl.failed) {
        std::memset(out_resources, 0, sizeof(*out_resources));
        out_resources->abi_version = BEDRAY_SCENE_ABI_VERSION;
        return impl.failure_status;
    }
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
