#include "bedray/gpu_images_c.h"
#include "bedray/gpu_images.hpp"

#include <algorithm>
#include <cstddef>
#include <new>
#include <memory>
#include <type_traits>

struct BedrayGpuImageOwner {
    explicit BedrayGpuImageOwner(bedray::gpu::ImageDeviceContext context, uint64_t budget) noexcept : images(context, budget) {}
    bedray::gpu::GpuImages images;
    bool released = false;
};

namespace {
using namespace bedray::gpu;
static_assert(sizeof(std::uintptr_t) == sizeof(uint64_t), "Image ABI requires a 64-bit process");
static_assert(static_cast<uint32_t>(Status::unknown_image) == BEDRAY_GPU_UNKNOWN_IMAGE);
template<class T> T address(uint64_t value) noexcept { return reinterpret_cast<T>(static_cast<std::uintptr_t>(value)); }
template<class T> uint64_t handle(T value) noexcept {
    if constexpr (std::is_pointer_v<T>) return static_cast<uint64_t>(reinterpret_cast<std::uintptr_t>(value));
    else return static_cast<uint64_t>(value);
}
uint32_t status(Status value) noexcept { return static_cast<uint32_t>(value); }
BedrayGpuImageInfo image_info(ImageId id, const ImageView& view) noexcept {
    return {id.owner_generation, id.serial, handle(view.image), handle(view.view), view.allocation_bytes,
            static_cast<uint32_t>(view.format), view.width, view.height};
}
}

extern "C" {
uint32_t BEDRAY_GPU_CALL bedray_gpu_image_abi_version(void) try { return BEDRAY_GPU_IMAGE_ABI_VERSION; } catch (...) { return 0; }
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_abi_info(BedrayGpuImageAbiInfo* out, uint32_t size) try {
    if (!out || size < sizeof(BedrayGpuImageAbiInfo)) return BEDRAY_GPU_INVALID_ARGUMENT;
    BedrayGpuImageAbiInfo info{};
    info.version = BEDRAY_GPU_IMAGE_ABI_VERSION; info.sizeof_info = sizeof(info);
    info.sizeof_owner_desc = sizeof(BedrayGpuImageOwnerDesc); info.align_owner_desc = alignof(BedrayGpuImageOwnerDesc);
    info.sizeof_image_info = sizeof(BedrayGpuImageInfo); info.align_image_info = alignof(BedrayGpuImageInfo);
#define IMAGE_OFFSET(T, F) static_cast<uint32_t>(offsetof(T, F))
    const uint32_t owner[] = {IMAGE_OFFSET(BedrayGpuImageOwnerDesc, abi_version), IMAGE_OFFSET(BedrayGpuImageOwnerDesc, struct_size),
        IMAGE_OFFSET(BedrayGpuImageOwnerDesc, physical_device), IMAGE_OFFSET(BedrayGpuImageOwnerDesc, device),
        IMAGE_OFFSET(BedrayGpuImageOwnerDesc, generation), IMAGE_OFFSET(BedrayGpuImageOwnerDesc, budget_bytes),
        IMAGE_OFFSET(BedrayGpuImageOwnerDesc, enabled_flags), IMAGE_OFFSET(BedrayGpuImageOwnerDesc, memory_type_count),
        IMAGE_OFFSET(BedrayGpuImageOwnerDesc, memory_type_flags), IMAGE_OFFSET(BedrayGpuImageOwnerDesc, functions)};
    const uint32_t image[] = {IMAGE_OFFSET(BedrayGpuImageInfo, owner_generation), IMAGE_OFFSET(BedrayGpuImageInfo, serial),
        IMAGE_OFFSET(BedrayGpuImageInfo, image), IMAGE_OFFSET(BedrayGpuImageInfo, view), IMAGE_OFFSET(BedrayGpuImageInfo, allocation_bytes),
        IMAGE_OFFSET(BedrayGpuImageInfo, format), IMAGE_OFFSET(BedrayGpuImageInfo, width), IMAGE_OFFSET(BedrayGpuImageInfo, height)};
#undef IMAGE_OFFSET
    std::copy_n(owner, 10, info.owner_offsets); std::copy_n(image, 8, info.image_offsets);
    *out = info; return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_owner_create(const BedrayGpuImageOwnerDesc* desc, BedrayGpuImageOwner** out) try {
    if (!desc || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    *out = nullptr;
    if (desc->abi_version != BEDRAY_GPU_IMAGE_ABI_VERSION || desc->struct_size < sizeof(BedrayGpuImageOwnerDesc))
        return BEDRAY_GPU_ABI_MISMATCH;
    if (desc->physical_device == 0 || desc->device == 0 || desc->generation == 0 || desc->budget_bytes == 0 ||
        desc->memory_type_count == 0 || desc->memory_type_count > BEDRAY_GPU_MAX_MEMORY_TYPES ||
        desc->enabled_flags != BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED) return BEDRAY_GPU_INVALID_ARGUMENT;
    for (uint32_t i = 0; i < BEDRAY_GPU_IMAGE_FUNCTION_COUNT; ++i)
        if (desc->functions[i] == 0) return BEDRAY_GPU_INVALID_ARGUMENT;
    ImageDeviceContext context{};
    context.physical_device = address<VkPhysicalDevice>(desc->physical_device); context.device = address<VkDevice>(desc->device);
    context.generation = desc->generation; context.dedicated_allocation_enabled = true;
    context.memory_properties.memoryTypeCount = desc->memory_type_count;
    for (uint32_t i = 0; i < desc->memory_type_count; ++i)
        context.memory_properties.memoryTypes[i].propertyFlags = desc->memory_type_flags[i];
    auto& f = context.functions;
    f.create_image = address<PFN_vkCreateImage>(desc->functions[BEDRAY_GPU_CREATE_IMAGE]);
    f.destroy_image = address<PFN_vkDestroyImage>(desc->functions[BEDRAY_GPU_DESTROY_IMAGE]);
    f.get_requirements = address<PFN_vkGetImageMemoryRequirements>(desc->functions[BEDRAY_GPU_IMAGE_REQUIREMENTS]);
    f.allocate_memory = address<PFN_vkAllocateMemory>(desc->functions[BEDRAY_GPU_IMAGE_ALLOCATE]);
    f.free_memory = address<PFN_vkFreeMemory>(desc->functions[BEDRAY_GPU_IMAGE_FREE]);
    f.bind_image_memory = address<PFN_vkBindImageMemory>(desc->functions[BEDRAY_GPU_IMAGE_BIND]);
    f.create_view = address<PFN_vkCreateImageView>(desc->functions[BEDRAY_GPU_CREATE_IMAGE_VIEW]);
    f.destroy_view = address<PFN_vkDestroyImageView>(desc->functions[BEDRAY_GPU_DESTROY_IMAGE_VIEW]);
    f.image_format_properties = address<PFN_vkGetPhysicalDeviceImageFormatProperties>(desc->functions[BEDRAY_GPU_IMAGE_FORMAT_PROPERTIES]);
    auto result = std::make_unique<BedrayGpuImageOwner>(context, desc->budget_bytes);
    if (!result->images.valid()) return BEDRAY_GPU_INVALID_ARGUMENT;
    *out = result.release(); return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_owner_destroy(BedrayGpuImageOwner* owner) try {
    if (!owner) return BEDRAY_GPU_INVALID_ARGUMENT;
    if (!owner->released) {
        const Status closed = owner->images.close();
        if (closed != Status::ok) return status(closed);
    }
    delete owner; return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_create(BedrayGpuImageOwner* owner, uint32_t width, uint32_t height,
    uint32_t format, uint32_t usage, uint32_t required, uint32_t preferred, BedrayGpuImageInfo* out, int32_t* vk_result) try {
    if (!owner || !out || !vk_result) return BEDRAY_GPU_INVALID_ARGUMENT;
    ImageId id{}; ImageView view{}; VkResult vk = VK_SUCCESS;
    const Status result = owner->images.create({width, height, static_cast<VkFormat>(format), usage, required, preferred}, id, view, vk);
    *vk_result = static_cast<int32_t>(vk);
    if (result == Status::ok) *out = image_info(id, view);
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_view(BedrayGpuImageOwner* owner, uint64_t generation, uint64_t serial,
    BedrayGpuImageInfo* out) try {
    if (!owner || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    ImageView view{}; const ImageId id{generation, serial};
    const Status result = owner->images.view(id, view);
    if (result == Status::ok) *out = image_info(id, view);
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_use_begin(BedrayGpuImageOwner* owner, uint64_t generation, uint64_t serial,
    BedrayGpuUseToken* out) try {
    if (!owner || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    UseId token{}; const Status result = owner->images.begin_use({generation, serial}, token);
    if (result == Status::ok) *out = {token.owner_generation, token.serial};
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_use_complete(BedrayGpuImageOwner* owner, uint64_t generation, uint64_t serial) try {
    return owner ? status(owner->images.complete_use({generation, serial})) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_retire(BedrayGpuImageOwner* owner, uint64_t generation, uint64_t serial) try {
    return owner ? status(owner->images.retire({generation, serial})) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_collect(BedrayGpuImageOwner* owner) try {
    return owner ? status(owner->images.collect()) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_resident_bytes(BedrayGpuImageOwner* owner, uint64_t* out) try {
    if (!owner || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    *out = owner->images.resident_bytes(); return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_device_idle(BedrayGpuImageOwner* owner) try {
    return owner ? status(owner->images.device_idle()) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_device_lost(BedrayGpuImageOwner* owner) try {
    if (!owner) return BEDRAY_GPU_INVALID_ARGUMENT;
    owner->images.device_lost(); return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_image_release_after_device_loss(BedrayGpuImageOwner* owner) try {
    if (!owner) return BEDRAY_GPU_INVALID_ARGUMENT;
    const Status result = owner->images.release_after_device_loss();
    if (result == Status::ok) owner->released = true;
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
}
