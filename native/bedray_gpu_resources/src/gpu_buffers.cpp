#include "bedray/gpu_buffers.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <limits>

namespace bedray::gpu {
namespace {
Status result_status(VkResult result) noexcept {
    if (result == VK_ERROR_DEVICE_LOST) return Status::device_lost;
    if (result == VK_ERROR_OUT_OF_HOST_MEMORY || result == VK_ERROR_OUT_OF_DEVICE_MEMORY) return Status::out_of_memory;
    return Status::vulkan_error;
}
}

GpuBuffers::GpuBuffers(DeviceContext context, VkDeviceSize budget_bytes) noexcept
    : context_(context), budget_(budget_bytes) {}

bool GpuBuffers::valid() const noexcept {
    const auto& f = context_.functions;
    return !lost_ && !closed_ && context_.device != VK_NULL_HANDLE && context_.generation != 0 &&
        context_.dedicated_allocation_enabled && context_.non_coherent_atom_size != 0 &&
        context_.memory_properties.memoryTypeCount > 0 && context_.memory_properties.memoryTypeCount <= VK_MAX_MEMORY_TYPES &&
        f.create_buffer && f.destroy_buffer && f.get_requirements && f.allocate_memory && f.free_memory &&
        f.bind_buffer_memory && f.map_memory && f.unmap_memory && f.flush && f.invalidate;
}

GpuBuffers::Buffer* GpuBuffers::find(BufferId id) noexcept {
    for (auto& buffer : buffers_) if (buffer.id == id) return &buffer;
    return nullptr;
}
const GpuBuffers::Buffer* GpuBuffers::find(BufferId id) const noexcept {
    for (const auto& buffer : buffers_) if (buffer.id == id) return &buffer;
    return nullptr;
}
bool GpuBuffers::has_use(BufferId id) const noexcept {
    for (const auto& use : uses_) if (use.buffer == id) return true;
    return false;
}

void GpuBuffers::destroy(Buffer& buffer) noexcept {
    const auto& f = context_.functions;
    if (buffer.mapped) f.unmap_memory(context_.device, buffer.memory);
    if (buffer.buffer != VK_NULL_HANDLE) f.destroy_buffer(context_.device, buffer.buffer, nullptr);
    if (buffer.memory != VK_NULL_HANDLE) f.free_memory(context_.device, buffer.memory, nullptr);
    resident_bytes_ -= buffer.allocation_size;
    buffer = {};
}

Status GpuBuffers::create(const BufferDesc& desc, BufferId& id, BufferView& view_out, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    if (desc.size == 0 || desc.usage == 0 || budget_ == 0) return Status::invalid_argument;
    if ((desc.usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0 &&
        (!context_.buffer_device_address_enabled || !context_.functions.get_buffer_device_address))
        return Status::unsupported;
    if (next_buffer_ == 0) return Status::exhausted;
    try { buffers_.reserve(buffers_.size() + 1); } catch (...) { return Status::out_of_memory; }

    VkBufferCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    create_info.size = desc.size;
    create_info.usage = desc.usage;
    create_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkBuffer handle = VK_NULL_HANDLE;
    error = context_.functions.create_buffer(context_.device, &create_info, nullptr, &handle);
    if (error != VK_SUCCESS) { if (error == VK_ERROR_DEVICE_LOST) lost_ = true; return result_status(error); }

    VkMemoryRequirements requirements{};
    context_.functions.get_requirements(context_.device, handle, &requirements);
    if (requirements.size < desc.size || requirements.size > budget_ || resident_bytes_ > budget_ - requirements.size) {
        context_.functions.destroy_buffer(context_.device, handle, nullptr);
        return requirements.size < desc.size ? Status::vulkan_error : Status::budget_exceeded;
    }
    const VkMemoryPropertyFlags required = desc.required | (desc.mapped ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT : 0);
    std::uint32_t index = UINT32_MAX;
    int score = -1;
    for (std::uint32_t i = 0; i < context_.memory_properties.memoryTypeCount; ++i) {
        const auto flags = context_.memory_properties.memoryTypes[i].propertyFlags;
        if ((requirements.memoryTypeBits & (1u << i)) == 0 || (flags & required) != required) continue;
        const int candidate = std::popcount(static_cast<unsigned>(flags & desc.preferred));
        if (candidate > score) { index = i; score = candidate; }
    }
    if (index == UINT32_MAX) {
        context_.functions.destroy_buffer(context_.device, handle, nullptr);
        return Status::unsupported;
    }
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.buffer = handle;
    VkMemoryAllocateFlagsInfo allocation_flags{};
    allocation_flags.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO;
    allocation_flags.flags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
    if ((desc.usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) dedicated.pNext = &allocation_flags;
    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &dedicated;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = index;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    error = context_.functions.allocate_memory(context_.device, &allocation, nullptr, &memory);
    if (error != VK_SUCCESS) {
        context_.functions.destroy_buffer(context_.device, handle, nullptr);
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        return result_status(error);
    }
    error = context_.functions.bind_buffer_memory(context_.device, handle, memory, 0);
    if (error != VK_SUCCESS) {
        context_.functions.destroy_buffer(context_.device, handle, nullptr);
        context_.functions.free_memory(context_.device, memory, nullptr);
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        return result_status(error);
    }
    VkDeviceAddress device_address = 0;
    if ((desc.usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
        VkBufferDeviceAddressInfo address_info{};
        address_info.sType = VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO;
        address_info.buffer = handle;
        device_address = context_.functions.get_buffer_device_address(context_.device, &address_info);
        if (device_address == 0) {
            context_.functions.destroy_buffer(context_.device, handle, nullptr);
            context_.functions.free_memory(context_.device, memory, nullptr);
            return Status::vulkan_error;
        }
    }
    void* mapped = nullptr;
    if (desc.mapped) {
        error = context_.functions.map_memory(context_.device, memory, 0, requirements.size, 0, &mapped);
        if (error != VK_SUCCESS || mapped == nullptr) {
            if (error == VK_SUCCESS) context_.functions.unmap_memory(context_.device, memory);
            context_.functions.destroy_buffer(context_.device, handle, nullptr);
            context_.functions.free_memory(context_.device, memory, nullptr);
            if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
            return error == VK_SUCCESS ? Status::vulkan_error : result_status(error);
        }
    }
    const BufferId new_id{context_.generation, next_buffer_++};
    const auto flags = context_.memory_properties.memoryTypes[index].propertyFlags;
    buffers_.push_back(Buffer{new_id, handle, memory, desc.size, requirements.size, flags, device_address, mapped, false, desc.usage, false});
    resident_bytes_ += requirements.size;
    id = new_id;
    view_out = BufferView{handle, desc.size, flags, device_address};
    return Status::ok;
}

Status GpuBuffers::view(BufferId id, BufferView& out) const noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    const Buffer* buffer = find(id);
    if (!buffer) return Status::unknown_buffer;
    if (buffer->retired) return Status::retired;
    out = BufferView{buffer->buffer, buffer->size, buffer->flags, buffer->device_address};
    return Status::ok;
}

Status GpuBuffers::mapped_transfer(BufferId id, VkDeviceSize offset, void* data,
                                   VkDeviceSize bytes, bool is_write, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    Buffer* buffer = find(id);
    if (!buffer) return Status::unknown_buffer;
    if (buffer->retired) return Status::retired;
    if (!buffer->mapped) return Status::unsupported;
    if (bytes == 0 || !data || offset > buffer->size || bytes > buffer->size - offset ||
        bytes > static_cast<VkDeviceSize>(std::numeric_limits<std::size_t>::max()) ||
        offset > static_cast<VkDeviceSize>(std::numeric_limits<std::size_t>::max()) - bytes)
        return Status::invalid_argument;
    if (has_use(id)) return Status::in_use;
    const bool coherent = (buffer->flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    VkMappedMemoryRange range{};
    if (!coherent) {
        const VkDeviceSize atom = context_.non_coherent_atom_size;
        const VkDeviceSize start = offset - offset % atom;
        const VkDeviceSize end = offset + bytes;
        const VkDeviceSize tail = end % atom;
        const VkDeviceSize aligned_end = tail == 0 ? end :
            (atom - tail > buffer->allocation_size - end ? buffer->allocation_size : end + atom - tail);
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = buffer->memory;
        range.offset = start;
        range.size = aligned_end - start;
        if (!is_write) {
            error = context_.functions.invalidate(context_.device, 1, &range);
            if (error != VK_SUCCESS) { if (error == VK_ERROR_DEVICE_LOST) lost_ = true; return result_status(error); }
        }
    }
    auto* mapped = static_cast<unsigned char*>(buffer->mapped) + static_cast<std::size_t>(offset);
    if (is_write) std::memcpy(mapped, data, static_cast<std::size_t>(bytes));
    else std::memcpy(data, mapped, static_cast<std::size_t>(bytes));
    if (!coherent && is_write) {
        error = context_.functions.flush(context_.device, 1, &range);
        if (error != VK_SUCCESS) { if (error == VK_ERROR_DEVICE_LOST) lost_ = true; return result_status(error); }
    }
    return Status::ok;
}
Status GpuBuffers::write(BufferId id, VkDeviceSize offset, const void* data, VkDeviceSize bytes, VkResult& error) noexcept {
    return mapped_transfer(id, offset, const_cast<void*>(data), bytes, true, error);
}
Status GpuBuffers::read(BufferId id, VkDeviceSize offset, void* data, VkDeviceSize bytes, VkResult& error) noexcept {
    return mapped_transfer(id, offset, data, bytes, false, error);
}
Status GpuBuffers::upload(VkBufferUsageFlags usage, const void* data, VkDeviceSize bytes,
                         BufferId& id, BufferView& out, UseId& use, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    constexpr auto allowed = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    if (!data || bytes == 0 || usage == 0 || (usage & ~allowed) != 0)
        return Status::invalid_argument;
    Buffer* selected = nullptr;
    for (auto& buffer : buffers_) {
        if (buffer.upload_slot && !buffer.retired && buffer.usage == usage &&
            buffer.size >= bytes && !has_use(buffer.id) && (!selected || buffer.size < selected->size))
            selected = &buffer;
    }
    BufferId selected_id{};
    BufferView selected_view{};
    if (selected) {
        selected_id = selected->id;
        selected_view = {selected->buffer, selected->size, selected->flags, selected->device_address};
    } else {
        const BufferDesc desc{bytes, usage, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true};
        const Status created = create(desc, selected_id, selected_view, error);
        if (created != Status::ok) return created;
        find(selected_id)->upload_slot = true;
    }
    const Status copied = write(selected_id, 0, data, bytes, error);
    if (copied != Status::ok) return copied;
    UseId token{};
    const Status pinned = begin_use(selected_id, token);
    if (pinned != Status::ok) return pinned;
    id = selected_id; out = selected_view; use = token;
    return Status::ok;
}
Status GpuBuffers::retire_uploads() noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    for (auto& buffer : buffers_) if (buffer.upload_slot) buffer.retired = true;
    return Status::ok;
}
Status GpuBuffers::begin_use(BufferId id, UseId& token) noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    const Buffer* buffer = find(id);
    if (!buffer) return Status::unknown_buffer;
    if (buffer->retired) return Status::retired;
    if (next_use_ == 0) return Status::exhausted;
    const UseId next{context_.generation, next_use_};
    try { uses_.push_back(Use{next, id}); } catch (...) { return Status::out_of_memory; }
    token = next;
    ++next_use_;
    return Status::ok;
}
Status GpuBuffers::complete_use(UseId token) noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    for (auto it = uses_.begin(); it != uses_.end(); ++it) {
        if (it->id == token) { uses_.erase(it); return Status::ok; }
    }
    return Status::unknown_use;
}
Status GpuBuffers::retire(BufferId id) noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    Buffer* buffer = find(id);
    if (!buffer) return Status::unknown_buffer;
    if (buffer->retired) return Status::retired;
    buffer->retired = true;
    return Status::ok;
}
Status GpuBuffers::collect() noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    for (auto it = buffers_.begin(); it != buffers_.end();) {
        if (it->retired && !has_use(it->id)) { destroy(*it); it = buffers_.erase(it); }
        else ++it;
    }
    return Status::ok;
}
Status GpuBuffers::device_idle() noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    uses_.clear();
    return Status::ok;
}
Status GpuBuffers::release_after_device_loss() noexcept {
    if (!lost_ || closed_ || context_.device == VK_NULL_HANDLE) return Status::unavailable;
    for (auto& buffer : buffers_) destroy(buffer);
    buffers_.clear(); uses_.clear(); closed_ = true;
    return Status::ok;
}
Status GpuBuffers::close() noexcept {
    if (lost_) return Status::device_lost;
    if (!valid()) return Status::unavailable;
    if (!buffers_.empty() || !uses_.empty()) return Status::in_use;
    closed_ = true;
    return Status::ok;
}

} // namespace bedray::gpu
