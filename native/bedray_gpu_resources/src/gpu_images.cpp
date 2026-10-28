#include "bedray/gpu_images.hpp"

#include <bit>

namespace bedray::gpu {
namespace {
Status result_status(VkResult result) noexcept {
    if (result == VK_ERROR_DEVICE_LOST) return Status::device_lost;
    if (result == VK_ERROR_FORMAT_NOT_SUPPORTED) return Status::unsupported;
    if (result == VK_ERROR_OUT_OF_HOST_MEMORY || result == VK_ERROR_OUT_OF_DEVICE_MEMORY) return Status::out_of_memory;
    return Status::vulkan_error;
}

bool colour_format(VkFormat format) noexcept {
    return format == VK_FORMAT_R16G16B16A16_SFLOAT || format == VK_FORMAT_R32_SFLOAT ||
           format == VK_FORMAT_R32G32B32A32_SFLOAT || format == VK_FORMAT_R8G8B8A8_UNORM;
}
}

GpuImages::GpuImages(ImageDeviceContext context, VkDeviceSize budget) noexcept
    : context_(context), budget_(budget) {}

bool GpuImages::valid() const noexcept {
    const auto& f = context_.functions;
    return !lost_ && !closed_ && context_.physical_device != VK_NULL_HANDLE &&
        context_.device != VK_NULL_HANDLE && context_.generation != 0 &&
        context_.dedicated_allocation_enabled && context_.memory_properties.memoryTypeCount > 0 &&
        context_.memory_properties.memoryTypeCount <= VK_MAX_MEMORY_TYPES &&
        f.create_image && f.destroy_image && f.get_requirements && f.allocate_memory && f.free_memory &&
        f.bind_image_memory && f.create_view && f.destroy_view && f.image_format_properties;
}

GpuImages::Image* GpuImages::find(ImageId id) noexcept {
    for (auto& image : images_) if (image.id == id) return &image;
    return nullptr;
}
const GpuImages::Image* GpuImages::find(ImageId id) const noexcept {
    for (const auto& image : images_) if (image.id == id) return &image;
    return nullptr;
}
bool GpuImages::has_use(ImageId id) const noexcept {
    for (const auto& use : uses_) if (use.image == id) return true;
    return false;
}
void GpuImages::destroy(Image& image) noexcept {
    const auto& f = context_.functions;
    f.destroy_view(context_.device, image.public_view.view, nullptr);
    f.destroy_image(context_.device, image.public_view.image, nullptr);
    f.free_memory(context_.device, image.memory, nullptr);
    resident_bytes_ -= image.public_view.allocation_bytes;
    image = {};
}

Status GpuImages::create(const ImageDesc& desc, ImageId& id, ImageView& view_out, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    constexpr VkImageUsageFlags view_usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    constexpr VkImageUsageFlags allowed_usage = view_usage |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (desc.width == 0 || desc.height == 0 || !colour_format(desc.format) ||
        (desc.usage & view_usage) == 0 || (desc.usage & ~allowed_usage) != 0 || budget_ == 0) return Status::invalid_argument;
    if (next_image_ == 0) return Status::exhausted;
    const auto& f = context_.functions;
    VkImageFormatProperties properties{};
    error = f.image_format_properties(context_.physical_device, desc.format, VK_IMAGE_TYPE_2D,
        VK_IMAGE_TILING_OPTIMAL, desc.usage, 0, &properties);
    if (error != VK_SUCCESS) {
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        return result_status(error);
    }
    if (desc.width > properties.maxExtent.width || desc.height > properties.maxExtent.height ||
        properties.maxExtent.depth == 0 || properties.maxMipLevels == 0 || properties.maxArrayLayers == 0 ||
        (properties.sampleCounts & VK_SAMPLE_COUNT_1_BIT) == 0) return Status::unsupported;
    try { images_.reserve(images_.size() + 1); } catch (...) { return Status::out_of_memory; }

    VkImageCreateInfo create{};
    create.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    create.imageType = VK_IMAGE_TYPE_2D;
    create.format = desc.format;
    create.extent = {desc.width, desc.height, 1};
    create.mipLevels = 1; create.arrayLayers = 1; create.samples = VK_SAMPLE_COUNT_1_BIT;
    create.tiling = VK_IMAGE_TILING_OPTIMAL; create.usage = desc.usage;
    create.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    create.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage handle = VK_NULL_HANDLE;
    error = f.create_image(context_.device, &create, nullptr, &handle);
    if (error != VK_SUCCESS) {
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        return result_status(error);
    }
    if (handle == VK_NULL_HANDLE) return Status::vulkan_error;

    VkMemoryRequirements requirements{};
    f.get_requirements(context_.device, handle, &requirements);
    if (requirements.size == 0 || requirements.memoryTypeBits == 0 || requirements.size > properties.maxResourceSize ||
        requirements.size > budget_ || resident_bytes_ > budget_ - requirements.size) {
        f.destroy_image(context_.device, handle, nullptr);
        if (requirements.size == 0 || requirements.memoryTypeBits == 0) return Status::vulkan_error;
        return requirements.size > properties.maxResourceSize ? Status::unsupported : Status::budget_exceeded;
    }
    std::uint32_t memory_type = UINT32_MAX;
    int best = -1;
    for (std::uint32_t i = 0; i < context_.memory_properties.memoryTypeCount; ++i) {
        const auto flags = context_.memory_properties.memoryTypes[i].propertyFlags;
        if ((requirements.memoryTypeBits & (1u << i)) == 0 || (flags & desc.required) != desc.required) continue;
        const int score = std::popcount(static_cast<unsigned>(flags & desc.preferred));
        if (score > best) { memory_type = i; best = score; }
    }
    if (memory_type == UINT32_MAX) {
        f.destroy_image(context_.device, handle, nullptr);
        return Status::unsupported;
    }
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = handle;
    VkMemoryAllocateInfo allocate{};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; allocate.pNext = &dedicated;
    allocate.allocationSize = requirements.size; allocate.memoryTypeIndex = memory_type;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    error = f.allocate_memory(context_.device, &allocate, nullptr, &memory);
    if (error != VK_SUCCESS || memory == VK_NULL_HANDLE) {
        f.destroy_image(context_.device, handle, nullptr);
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        return error == VK_SUCCESS ? Status::vulkan_error : result_status(error);
    }
    error = f.bind_image_memory(context_.device, handle, memory, 0);
    if (error != VK_SUCCESS) {
        f.destroy_image(context_.device, handle, nullptr); f.free_memory(context_.device, memory, nullptr);
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        return result_status(error);
    }
    VkImageViewCreateInfo view_info{};
    view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view_info.image = handle; view_info.viewType = VK_IMAGE_VIEW_TYPE_2D; view_info.format = desc.format;
    view_info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView image_view = VK_NULL_HANDLE;
    error = f.create_view(context_.device, &view_info, nullptr, &image_view);
    if (error != VK_SUCCESS || image_view == VK_NULL_HANDLE) {
        f.destroy_image(context_.device, handle, nullptr); f.free_memory(context_.device, memory, nullptr);
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        return error == VK_SUCCESS ? Status::vulkan_error : result_status(error);
    }
    const ImageId new_id{context_.generation, next_image_++};
    const ImageView public_view{handle, image_view, desc.format, desc.width, desc.height, requirements.size};
    images_.push_back(Image{new_id, public_view, memory, false});
    resident_bytes_ += requirements.size;
    id = new_id; view_out = public_view;
    return Status::ok;
}

Status GpuImages::view(ImageId id, ImageView& out) const noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    const Image* image = find(id);
    if (!image) return Status::unknown_image;
    if (image->retired) return Status::retired;
    out = image->public_view;
    return Status::ok;
}
Status GpuImages::begin_use(ImageId id, UseId& token) noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    const Image* image = find(id);
    if (!image) return Status::unknown_image;
    if (image->retired) return Status::retired;
    if (next_use_ == 0) return Status::exhausted;
    const UseId next{context_.generation, next_use_};
    try { uses_.push_back(Use{next, id}); } catch (...) { return Status::out_of_memory; }
    ++next_use_; token = next;
    return Status::ok;
}
Status GpuImages::complete_use(UseId token) noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    for (auto it = uses_.begin(); it != uses_.end(); ++it) {
        if (it->id == token) { uses_.erase(it); return Status::ok; }
    }
    return Status::unknown_use;
}
Status GpuImages::retire(ImageId id) noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    Image* image = find(id);
    if (!image) return Status::unknown_image;
    if (image->retired) return Status::retired;
    image->retired = true;
    return Status::ok;
}
Status GpuImages::collect() noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    for (auto it = images_.begin(); it != images_.end();) {
        if (it->retired && !has_use(it->id)) { destroy(*it); it = images_.erase(it); }
        else ++it;
    }
    return Status::ok;
}
Status GpuImages::device_idle() noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    uses_.clear();
    return Status::ok;
}
Status GpuImages::release_after_device_loss() noexcept {
    if (!lost_ || closed_ || context_.device == VK_NULL_HANDLE) return Status::unavailable;
    for (auto& image : images_) destroy(image);
    images_.clear(); uses_.clear(); closed_ = true;
    return Status::ok;
}
Status GpuImages::close() noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    if (!images_.empty() || !uses_.empty()) return Status::in_use;
    closed_ = true;
    return Status::ok;
}

} // namespace bedray::gpu
