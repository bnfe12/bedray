#include "bedray/gpu_images.hpp"
#include "bedray/gpu_images_c.h"

#include <cstddef>
#include <cstdlib>
#include <type_traits>

using namespace bedray::gpu;

namespace {
void require(bool value) { if (!value) std::abort(); }
template<class T> T handle(std::uintptr_t value) {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(value);
    else return static_cast<T>(value);
}
std::uintptr_t next_handle = 10;
int images = 0, memories = 0, views = 0;
bool fail_view = false;
VkResult format_result = VK_SUCCESS;

VKAPI_ATTR VkResult VKAPI_CALL format_properties(VkPhysicalDevice, VkFormat, VkImageType type,
    VkImageTiling tiling, VkImageUsageFlags, VkImageCreateFlags, VkImageFormatProperties* out) {
    require(type == VK_IMAGE_TYPE_2D && tiling == VK_IMAGE_TILING_OPTIMAL);
    *out = {{1024, 1024, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, 1u << 20};
    return format_result;
}
VKAPI_ATTR VkResult VKAPI_CALL create_image(VkDevice, const VkImageCreateInfo* info,
    const VkAllocationCallbacks*, VkImage* out) {
    require(info->initialLayout == VK_IMAGE_LAYOUT_UNDEFINED && info->sharingMode == VK_SHARING_MODE_EXCLUSIVE);
    require(info->mipLevels == 1 && info->arrayLayers == 1 && info->samples == VK_SAMPLE_COUNT_1_BIT);
    *out = handle<VkImage>(next_handle++); ++images;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_image(VkDevice, VkImage, const VkAllocationCallbacks*) { --images; }
VKAPI_ATTR void VKAPI_CALL requirements(VkDevice, VkImage, VkMemoryRequirements* out) { *out = {4096, 256, 1}; }
VKAPI_ATTR VkResult VKAPI_CALL allocate(VkDevice, const VkMemoryAllocateInfo* info,
    const VkAllocationCallbacks*, VkDeviceMemory* out) {
    auto dedicated = static_cast<const VkMemoryDedicatedAllocateInfo*>(info->pNext);
    require(dedicated && dedicated->image != VK_NULL_HANDLE && dedicated->buffer == VK_NULL_HANDLE);
    require(info->allocationSize == 4096 && info->memoryTypeIndex == 0);
    *out = handle<VkDeviceMemory>(next_handle++); ++memories;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL free_memory(VkDevice, VkDeviceMemory, const VkAllocationCallbacks*) { --memories; }
VKAPI_ATTR VkResult VKAPI_CALL bind(VkDevice, VkImage, VkDeviceMemory, VkDeviceSize offset) {
    require(offset == 0); return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL create_view(VkDevice, const VkImageViewCreateInfo* info,
    const VkAllocationCallbacks*, VkImageView* out) {
    require(info->viewType == VK_IMAGE_VIEW_TYPE_2D && info->subresourceRange.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
    if (fail_view) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *out = handle<VkImageView>(next_handle++); ++views;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_view(VkDevice, VkImageView, const VkAllocationCallbacks*) { --views; }

ImageDeviceContext context(std::uint64_t generation) {
    ImageDeviceContext result{};
    result.physical_device = handle<VkPhysicalDevice>(1); result.device = handle<VkDevice>(2);
    result.functions = {create_image, destroy_image, requirements, allocate, free_memory, bind,
                        create_view, destroy_view, format_properties};
    result.memory_properties.memoryTypeCount = 1;
    result.memory_properties.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    result.dedicated_allocation_enabled = true; result.generation = generation;
    return result;
}
ImageDesc description() { return {32, 32, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT}; }
void balanced() { require(images == 0 && memories == 0 && views == 0); }

void use_retirement() {
    GpuImages owner(context(1), 8192);
    ImageId id{}, second{}, third{}; ImageView view{}; VkResult error{};
    require(owner.create(description(), id, view, error) == Status::ok);
    require(view.width == 32 && view.format == VK_FORMAT_R16G16B16A16_SFLOAT && owner.resident_bytes() == 4096);
    require(owner.create(description(), second, view, error) == Status::ok);
    require(owner.create(description(), third, view, error) == Status::budget_exceeded);
    require(images == 2 && views == 2 && memories == 2);
    UseId a{}, b{};
    require(owner.begin_use(id, a) == Status::ok && owner.begin_use(id, b) == Status::ok);
    require(owner.retire(id) == Status::ok && owner.collect() == Status::ok && images == 2);
    require(owner.begin_use(id, a) == Status::retired && owner.close() == Status::in_use);
    require(owner.complete_use(a) == Status::ok && owner.collect() == Status::ok && images == 2);
    require(owner.complete_use(a) == Status::unknown_use);
    require(owner.complete_use(b) == Status::ok && owner.collect() == Status::ok && images == 1);
    require(owner.view({2, second.serial}, view) == Status::unknown_image);
    require(owner.retire(second) == Status::ok && owner.collect() == Status::ok);
    require(owner.resident_bytes() == 0 && owner.close() == Status::ok);
    balanced();
}

void rollback() {
    GpuImages owner(context(3), 8192);
    ImageId id{99, 99}; ImageView view{}; VkResult error{};
    fail_view = true;
    require(owner.create(description(), id, view, error) == Status::out_of_memory);
    require(id.owner_generation == 99 && owner.resident_bytes() == 0);
    balanced(); fail_view = false;
    format_result = VK_ERROR_FORMAT_NOT_SUPPORTED;
    require(owner.create(description(), id, view, error) == Status::unsupported);
    balanced(); format_result = VK_SUCCESS;
    auto large = description(); large.width = 1025;
    require(owner.create(large, id, view, error) == Status::unsupported);
    auto depth = description(); depth.format = VK_FORMAT_D32_SFLOAT;
    require(owner.create(depth, id, view, error) == Status::invalid_argument);
    auto transfer = description(); transfer.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    require(owner.create(transfer, id, view, error) == Status::invalid_argument);
    transfer.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    require(owner.create(transfer, id, view, error) == Status::invalid_argument);
    balanced(); require(owner.close() == Status::ok);
}

void lost_device() {
    GpuImages owner(context(4), 8192);
    ImageId id{}; ImageView view{}; VkResult error{}; UseId use{};
    require(owner.create(description(), id, view, error) == Status::ok && owner.begin_use(id, use) == Status::ok);
    owner.device_lost();
    require(owner.collect() == Status::device_lost && owner.complete_use(use) == Status::device_lost);
    require(images == 1 && owner.release_after_device_loss() == Status::ok);
    balanced(); require(owner.release_after_device_loss() == Status::unavailable);
}

void c_abi() {
    BedrayGpuImageAbiInfo layout{};
    layout.version = 42;
    require(bedray_gpu_image_abi_info(&layout, sizeof(layout) - 1) == BEDRAY_GPU_INVALID_ARGUMENT && layout.version == 42);
    require(bedray_gpu_image_abi_info(&layout, sizeof(layout)) == BEDRAY_GPU_OK);
    require(layout.sizeof_image_info == sizeof(BedrayGpuImageInfo) && layout.align_image_info == alignof(BedrayGpuImageInfo));
    require(layout.image_offsets[7] == offsetof(BedrayGpuImageInfo, height));
    BedrayGpuImageOwnerDesc desc{};
    desc.abi_version = BEDRAY_GPU_IMAGE_ABI_VERSION; desc.struct_size = sizeof(desc);
    desc.physical_device = 1; desc.device = 2; desc.generation = 90; desc.budget_bytes = 8192;
    desc.enabled_flags = BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED; desc.memory_type_count = 1;
    desc.memory_type_flags[0] = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    desc.functions[0] = reinterpret_cast<std::uintptr_t>(create_image);
    desc.functions[1] = reinterpret_cast<std::uintptr_t>(destroy_image);
    desc.functions[2] = reinterpret_cast<std::uintptr_t>(requirements);
    desc.functions[3] = reinterpret_cast<std::uintptr_t>(allocate);
    desc.functions[4] = reinterpret_cast<std::uintptr_t>(free_memory);
    desc.functions[5] = reinterpret_cast<std::uintptr_t>(bind);
    desc.functions[6] = reinterpret_cast<std::uintptr_t>(create_view);
    desc.functions[7] = reinterpret_cast<std::uintptr_t>(destroy_view);
    desc.functions[8] = reinterpret_cast<std::uintptr_t>(format_properties);
    BedrayGpuImageOwner* owner = nullptr;
    require(bedray_gpu_image_owner_create(&desc, &owner) == BEDRAY_GPU_OK);
    BedrayGpuImageInfo image{}; int32_t vk{};
    require(bedray_gpu_image_create(owner, 32, 32, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &image, &vk) == BEDRAY_GPU_OK);
    BedrayGpuUseToken use{};
    require(bedray_gpu_image_use_begin(owner, image.owner_generation, image.serial, &use) == BEDRAY_GPU_OK);
    require(bedray_gpu_image_retire(owner, image.owner_generation, image.serial) == BEDRAY_GPU_OK);
    require(bedray_gpu_image_collect(owner) == BEDRAY_GPU_OK && images == 1);
    require(bedray_gpu_image_owner_destroy(owner) == BEDRAY_GPU_IN_USE);
    require(bedray_gpu_image_use_complete(owner, use.owner_generation, use.serial) == BEDRAY_GPU_OK);
    require(bedray_gpu_image_collect(owner) == BEDRAY_GPU_OK && images == 0);
    require(bedray_gpu_image_owner_destroy(owner) == BEDRAY_GPU_OK);
    balanced();
}
}

int main() { use_retirement(); rollback(); lost_device(); c_abi(); }
