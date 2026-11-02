#include "bedray/memory_allocator.hpp"
#include "bedray/gpu_buffers.hpp"
#include <algorithm>
#include <limits>

namespace bedray::gpu {
namespace {
bool power_two(VkDeviceSize n) noexcept { return n && (n & (n - 1)) == 0; }
bool align(VkDeviceSize n, VkDeviceSize a, VkDeviceSize& out) noexcept {
    if (!power_two(a) || n > UINT64_MAX - (a - 1)) return false;
    out = (n + a - 1) & ~(a - 1);
    return true;
}
Status result(VkResult r) noexcept {
    if (r == VK_ERROR_DEVICE_LOST) return Status::device_lost;
    if (r == VK_ERROR_OUT_OF_DEVICE_MEMORY || r == VK_ERROR_OUT_OF_HOST_MEMORY) return Status::out_of_memory;
    return Status::vulkan_error;
}
}

bool MemoryAllocator::valid() const noexcept {
    const auto& f = context_.functions;
    return !lost_ && context_.device && context_.physical && context_.limit && power_two(context_.block_size)
        && power_two(context_.atom_size) && context_.max_blocks && context_.properties.memoryTypeCount > 0
        && context_.properties.memoryTypeCount <= VK_MAX_MEMORY_TYPES
        && context_.properties.memoryHeapCount > 0 && context_.properties.memoryHeapCount <= VK_MAX_MEMORY_HEAPS
        && f.allocate && f.free && f.map && f.unmap && f.flush && f.invalidate
        && f.buffer_requirements && f.image_requirements && (!context_.heap_budget_enabled || f.heap_properties);
}

Status MemoryAllocator::buffer(VkBuffer buffer, std::uint32_t type, bool address, bool mapped,
                              MemoryAllocation& out, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (lost_) return Status::device_lost;
    if (!valid() || !buffer) return Status::invalid_argument;
    VkMemoryDedicatedRequirements dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS};
    VkMemoryRequirements2 requirements{VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2, &dedicated};
    VkBufferMemoryRequirementsInfo2 info{VK_STRUCTURE_TYPE_BUFFER_MEMORY_REQUIREMENTS_INFO_2, nullptr, buffer};
    context_.functions.buffer_requirements(context_.device, &info, &requirements);
    return allocate(requirements.memoryRequirements, dedicated.requiresDedicatedAllocation || dedicated.prefersDedicatedAllocation,
                    buffer, VK_NULL_HANDLE, type, address, mapped, out, error);
}

Status MemoryAllocator::image(VkImage image, std::uint32_t type, MemoryAllocation& out, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (lost_) return Status::device_lost;
    if (!valid() || !image) return Status::invalid_argument;
    VkMemoryDedicatedRequirements dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS};
    VkMemoryRequirements2 requirements{VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2, &dedicated};
    VkImageMemoryRequirementsInfo2 info{VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2, nullptr, image};
    context_.functions.image_requirements(context_.device, &info, &requirements);
    return allocate(requirements.memoryRequirements, dedicated.requiresDedicatedAllocation || dedicated.prefersDedicatedAllocation,
                    VK_NULL_HANDLE, image, type, false, false, out, error);
}

bool MemoryAllocator::room(std::uint32_t type, VkDeviceSize bytes) const noexcept {
    if (bytes > context_.limit || reserved_ > context_.limit - bytes || blocks_.size() >= context_.max_blocks) return false;
    const auto heap = context_.properties.memoryTypes[type].heapIndex;
    if (heap >= context_.properties.memoryHeapCount) return false;
    VkDeviceSize own_heap = 0;
    for (const auto& block : blocks_)
        if (context_.properties.memoryTypes[block.type].heapIndex == heap) own_heap += block.size;
    const auto capacity = context_.properties.memoryHeaps[heap].size;
    if (bytes > capacity || own_heap > capacity - bytes) return false;
    if (!context_.heap_budget_enabled) return true;
    VkPhysicalDeviceMemoryBudgetPropertiesEXT budget{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT};
    VkPhysicalDeviceMemoryProperties2 properties{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2, &budget};
    context_.functions.heap_properties(context_.physical, &properties);
    // Usage is process-wide (including the host allocator). Estimates are advisory, not a reservation.
    return bytes <= budget.heapBudget[heap] && budget.heapUsage[heap] <= budget.heapBudget[heap] - bytes;
}

void MemoryAllocator::publish(Block& block, VkDeviceSize offset, VkDeviceSize occupied,
                              VkDeviceSize requested, MemoryAllocation& out) {
    const auto at = std::lower_bound(block.spans.begin(), block.spans.end(), offset,
        [](const Span& span, VkDeviceSize offset) { return span.offset < offset; });
    const auto serial = next_allocation_++;
    block.spans.insert(at, Span{serial, offset, occupied, requested}); // capacity reserved before driver calls
    used_ += requested;
    ++count_;
    out = {block.id, serial, block.memory, offset, requested, occupied,
        block.base ? static_cast<unsigned char*>(block.base) + offset : nullptr};
}

Status MemoryAllocator::allocate(const VkMemoryRequirements& req, bool dedicated, VkBuffer buffer, VkImage image,
                                std::uint32_t type, bool address, bool mapped, MemoryAllocation& out, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (!req.size || !power_two(req.alignment) || type >= context_.properties.memoryTypeCount
        || !(req.memoryTypeBits & (1u << type)) || !next_block_ || !next_allocation_) return Status::invalid_argument;
    const auto memory_flags = context_.properties.memoryTypes[type].propertyFlags;
    if (mapped && !(memory_flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) return Status::unsupported;
    // Separate optimal images and buffers: no bufferImageGranularity conflict, even across free holes.
    // Mapped slices isolate noncoherent atoms; a flush must never touch a neighbour that is in use.
    VkDeviceSize alignment = req.alignment, occupied = req.size;
    if (mapped) alignment = std::max(alignment, context_.atom_size);
    if (!align(req.size, mapped ? context_.atom_size : VkDeviceSize(1), occupied)) return Status::invalid_argument;
    dedicated = dedicated || req.size > context_.block_size / 2;
    try {
        if (!dedicated) for (auto& block : blocks_) {
            if (block.dedicated || block.type != type || block.address != address || block.mapped != mapped
                || block.image != (image != VK_NULL_HANDLE)) continue;
            VkDeviceSize cursor = 0;
            for (const auto& span : block.spans) {
                VkDeviceSize aligned = 0;
                if (!align(cursor, alignment, aligned)) break;
                if (aligned <= span.offset && occupied <= span.offset - aligned) { cursor = aligned; break; }
                cursor = span.offset + span.size;
            }
            VkDeviceSize offset = 0;
            if (!align(cursor, alignment, offset) || offset > block.size || occupied > block.size - offset) continue;
            block.spans.reserve(block.spans.size() + 1);
            publish(block, offset, occupied, req.size, out);
            return Status::ok;
        }
        VkDeviceSize bytes = dedicated ? req.size : std::max(context_.block_size, occupied);
        // Near the budget limit use a smaller pool block; never manufacture budget by evicting pinned resources.
        if (!room(type, bytes)) {
            if (dedicated || !room(type, occupied)) return Status::budget_exceeded;
            bytes = occupied;
        }
        blocks_.reserve(blocks_.size() + 1);
        Block block;
        block.spans.reserve(1);
        block.id = next_block_;
        block.size = bytes; block.type = type; block.address = address; block.mapped = mapped;
        block.image = image != VK_NULL_HANDLE; block.dedicated = dedicated;
        VkMemoryDedicatedAllocateInfo single{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        single.buffer = buffer; single.image = image;
        VkMemoryAllocateFlagsInfo flags{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO};
        flags.flags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
        if (address) single.pNext = &flags;
        VkMemoryAllocateInfo info{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        info.pNext = dedicated ? static_cast<const void*>(&single) : address ? static_cast<const void*>(&flags) : nullptr;
        info.allocationSize = bytes; info.memoryTypeIndex = type;
        error = context_.functions.allocate(context_.device, &info, nullptr, &block.memory);
        if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
        if (error != VK_SUCCESS || block.memory == VK_NULL_HANDLE)
            return error == VK_SUCCESS ? Status::vulkan_error : result(error);
        if (mapped) {
            error = context_.functions.map(context_.device, block.memory, 0, bytes, 0, &block.base);
            if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
            if (error != VK_SUCCESS || !block.base) {
                if (error == VK_SUCCESS) context_.functions.unmap(context_.device, block.memory);
                context_.functions.free(context_.device, block.memory, nullptr);
                return error == VK_SUCCESS ? Status::vulkan_error : result(error);
            }
        }
        blocks_.push_back(std::move(block));
        ++next_block_;
        reserved_ += bytes;
        peak_ = std::max(peak_, reserved_);
        publish(blocks_.back(), 0, dedicated ? req.size : occupied, req.size, out);
        return Status::ok;
    } catch (...) { return Status::out_of_memory; }
}

const MemoryAllocator::Span* MemoryAllocator::span(const MemoryAllocation& allocation, const Block*& found) const noexcept {
    for (const auto& block : blocks_) {
        if (block.id != allocation.block || block.memory != allocation.memory) continue;
        for (const auto& span : block.spans)
            if (span.serial == allocation.serial && span.offset == allocation.offset && span.requested == allocation.size) {
                found = &block; return &span;
            }
    }
    return nullptr;
}

void MemoryAllocator::release(const MemoryAllocation& allocation) noexcept {
    for (auto block = blocks_.begin(); block != blocks_.end(); ++block) {
        if (block->id != allocation.block || block->memory != allocation.memory) continue;
        for (auto span = block->spans.begin(); span != block->spans.end(); ++span) {
            if (span->serial != allocation.serial || span->offset != allocation.offset || span->requested != allocation.size) continue;
            used_ -= span->requested; --count_;
            block->spans.erase(span);
            if (block->spans.empty()) {
                if (block->base) context_.functions.unmap(context_.device, block->memory);
                context_.functions.free(context_.device, block->memory, nullptr);
                reserved_ -= block->size;
                blocks_.erase(block);
            }
            return;
        }
    }
}

Status MemoryAllocator::synchronize(const MemoryAllocation& allocation, VkDeviceSize offset, VkDeviceSize bytes,
                                    bool write, VkResult& error) noexcept {
    error = VK_SUCCESS;
    if (lost_) return Status::device_lost;
    const Block* block = nullptr;
    const auto* live = span(allocation, block);
    if (!live || !block->base || offset > live->requested || bytes > live->requested - offset) return Status::invalid_argument;
    if (!bytes || (context_.properties.memoryTypes[block->type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
        return Status::ok;
    const auto atom = context_.atom_size;
    const auto begin = live->offset + offset;
    const auto end = begin + bytes; // validated within the resource and its block
    VkDeviceSize aligned_end = 0;
    if (!align(end, atom, aligned_end)) return Status::invalid_argument;
    aligned_end = std::min(aligned_end, block->size);
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    range.memory = block->memory;
    range.offset = begin - begin % atom;
    range.size = aligned_end - range.offset;
    error = write ? context_.functions.flush(context_.device, 1, &range)
                  : context_.functions.invalidate(context_.device, 1, &range);
    if (error == VK_ERROR_DEVICE_LOST) lost_ = true;
    return error == VK_SUCCESS ? Status::ok : result(error);
}

MemoryStats MemoryAllocator::stats() const noexcept {
    MemoryStats result{context_.limit, reserved_, used_, peak_, count_, blocks_.size(), 0, 0, 0};
    if (context_.heap_budget_enabled && !lost_) {
        VkPhysicalDeviceMemoryBudgetPropertiesEXT budget{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT};
        VkPhysicalDeviceMemoryProperties2 properties{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2, &budget};
        context_.functions.heap_properties(context_.physical, &properties);
        for (std::uint32_t h = 0; h < context_.properties.memoryHeapCount; ++h)
            if (context_.properties.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
                result.heap_budget += budget.heapBudget[h]; result.heap_usage += budget.heapUsage[h];
            }
        result.flags = 1;
    }
    if (lost_) result.flags |= 2;
    return result;
}
}
