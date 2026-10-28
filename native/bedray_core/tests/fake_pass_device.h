#pragma once
// Shared single-translation-unit fake driver/recorder for native pass contract tests.
#include "../src/core_internal.h"
#include <cstdint>
#include <cstring>
#include <map>
#include <set>
#include <type_traits>
#include <utility>
#include <vector>

namespace fake {

struct BufferState {
    VkDeviceSize size = 0;
    std::uint64_t memory = 0;
};

struct MemoryState {
    std::vector<unsigned char> bytes;
    bool mapped = false;
};

struct ImageState {
    VkDeviceSize allocation = 0;
    std::uint64_t memory = 0;
};

struct RenderCall {
    std::uint64_t command_buffer = 0;
    std::uint64_t program = 0;
    std::uint32_t group_x = 0;
    std::uint32_t group_y = 0;
    std::uint32_t group_z = 0;
    std::uint32_t push_bytes = 0;
    std::vector<BedrayResource> resources;
    std::vector<unsigned char> push;
};

struct PhaseCall {
    std::uint64_t command_buffer = 0;
    std::uint64_t program = 0;
    std::uint32_t phase_offset = 0;
    std::uint32_t phase_count = 0;
    std::uint32_t push_bytes = 0;
    std::vector<BedrayResource> resources;
    std::vector<unsigned char> push;
};

std::map<std::uint64_t, BufferState> buffers;
std::map<std::uint64_t, MemoryState> memories;
std::map<std::uint64_t, ImageState> images;
std::set<std::uint64_t> views;

std::vector<RenderCall> render_calls;
std::vector<PhaseCall> phase_calls;
std::uint32_t fills = 0;
int render_call_index = 0;
int fail_render_call = -1;
BedrayStatus render_fail_status = BEDRAY_OK;
bool fail_phase_call = false;
BedrayStatus phase_fail_status = BEDRAY_ERROR_VULKAN;

std::uint64_t next_handle = 0x1000;
int fail_allocate_after = -1;
VkResult fail_result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
int faults = 0;
std::uint32_t barriers = 0;

template <typename T>
std::uint64_t handle_id(T handle) {
    if constexpr (std::is_pointer_v<T>) {
        return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(handle));
    } else {
        return static_cast<std::uint64_t>(handle);
    }
}

template <typename T>
T make_handle(std::uint64_t id) {
    return reinterpret_cast<T>(static_cast<std::uintptr_t>(id));
}

void fail_render_at(int index, BedrayStatus status) {
    render_call_index = 0;
    fail_render_call = index;
    render_fail_status = status;
}

void reset() {
    buffers.clear();
    memories.clear();
    images.clear();
    views.clear();
    render_calls.clear();
    phase_calls.clear();
    fills = 0;
    render_call_index = 0;
    fail_render_call = -1;
    render_fail_status = BEDRAY_OK;
    fail_phase_call = false;
    phase_fail_status = BEDRAY_ERROR_VULKAN;
    next_handle = 0x1000;
    fail_allocate_after = -1;
    fail_result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
    faults = 0;
    barriers = 0;
}

VKAPI_ATTR void VKAPI_CALL cmd_fill_buffer(
    VkCommandBuffer, VkBuffer buffer, VkDeviceSize offset, VkDeviceSize size, std::uint32_t value) {
    const auto it = buffers.find(handle_id(buffer));
    if (it == buffers.end() || offset != 0 || size != it->second.size || value != 0) ++faults;
    ++fills;
}

VKAPI_ATTR VkResult VKAPI_CALL create_buffer(
    VkDevice, const VkBufferCreateInfo* info, const VkAllocationCallbacks*, VkBuffer* out) {
    if (info == nullptr || out == nullptr) {
        ++faults;
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    const std::uint64_t id = next_handle++;
    buffers.emplace(id, BufferState{info->size, 0});
    *out = make_handle<VkBuffer>(id);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL destroy_buffer(
    VkDevice, VkBuffer buffer, const VkAllocationCallbacks*) {
    if (buffers.erase(handle_id(buffer)) != 1) {
        ++faults;
    }
}

VKAPI_ATTR void VKAPI_CALL get_buffer_requirements(
    VkDevice, VkBuffer buffer, VkMemoryRequirements* out) {
    const auto it = buffers.find(handle_id(buffer));
    if (it == buffers.end()) {
        ++faults;
        std::memset(out, 0, sizeof(*out));
        return;
    }
    out->size = it->second.size < 256 ? 256 : it->second.size;
    out->alignment = 16;
    out->memoryTypeBits = 0x3u;
}

VKAPI_ATTR VkResult VKAPI_CALL allocate_memory(
    VkDevice, const VkMemoryAllocateInfo* info, const VkAllocationCallbacks*, VkDeviceMemory* out) {
    if (fail_allocate_after == 0) {
        const VkResult result = fail_result;
        fail_allocate_after = -1;
        return result;
    }
    if (fail_allocate_after > 0) {
        --fail_allocate_after;
    }
    if (info == nullptr || out == nullptr) {
        ++faults;
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    const std::uint64_t id = next_handle++;
    MemoryState state{};
    state.bytes.assign(static_cast<std::size_t>(info->allocationSize), 0);
    memories.emplace(id, std::move(state));
    *out = make_handle<VkDeviceMemory>(id);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL free_memory(
    VkDevice, VkDeviceMemory memory, const VkAllocationCallbacks*) {
    if (memories.erase(handle_id(memory)) != 1) {
        ++faults;
    }
}

VKAPI_ATTR VkResult VKAPI_CALL bind_buffer_memory(
    VkDevice, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize) {
    const auto it = buffers.find(handle_id(buffer));
    if (it == buffers.end()) {
        ++faults;
        return VK_ERROR_UNKNOWN;
    }
    it->second.memory = handle_id(memory);
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL map_memory(VkDevice, VkDeviceMemory memory, VkDeviceSize offset,
    VkDeviceSize, VkMemoryMapFlags, void** data) {
    const auto it = memories.find(handle_id(memory));
    if (it == memories.end() || offset > it->second.bytes.size()) {
        ++faults;
        return VK_ERROR_MEMORY_MAP_FAILED;
    }
    it->second.mapped = true;
    *data = it->second.bytes.data() + static_cast<std::size_t>(offset);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL unmap_memory(VkDevice, VkDeviceMemory memory) {
    const auto it = memories.find(handle_id(memory));
    if (it == memories.end()) {
        ++faults;
        return;
    }
    it->second.mapped = false;
}

VKAPI_ATTR VkResult VKAPI_CALL flush_memory(
    VkDevice, std::uint32_t, const VkMappedMemoryRange*) {
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL invalidate_memory(
    VkDevice, std::uint32_t, const VkMappedMemoryRange*) {
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL get_image_format_properties(VkPhysicalDevice, VkFormat,
    VkImageType, VkImageTiling, VkImageUsageFlags, VkImageCreateFlags,
    VkImageFormatProperties* out) {
    std::memset(out, 0, sizeof(*out));
    out->maxExtent.width = 16384;
    out->maxExtent.height = 16384;
    out->maxExtent.depth = 1;
    out->maxMipLevels = 1;
    out->maxArrayLayers = 1;
    out->sampleCounts = VK_SAMPLE_COUNT_1_BIT;
    out->maxResourceSize = 1ull << 30;
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL create_image(
    VkDevice, const VkImageCreateInfo* info, const VkAllocationCallbacks*, VkImage* out) {
    if (info == nullptr || out == nullptr) {
        ++faults;
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    const VkDeviceSize pixels =
        static_cast<VkDeviceSize>(info->extent.width) * static_cast<VkDeviceSize>(info->extent.height);
    const std::uint64_t id = next_handle++;
    images.emplace(id, ImageState{pixels * 16u < 4096u ? 4096u : pixels * 16u, 0});
    *out = make_handle<VkImage>(id);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL destroy_image(VkDevice, VkImage image, const VkAllocationCallbacks*) {
    if (images.erase(handle_id(image)) != 1) {
        ++faults;
    }
}

VKAPI_ATTR void VKAPI_CALL get_image_requirements(
    VkDevice, VkImage image, VkMemoryRequirements* out) {
    const auto it = images.find(handle_id(image));
    if (it == images.end()) {
        ++faults;
        std::memset(out, 0, sizeof(*out));
        return;
    }
    out->size = it->second.allocation;
    out->alignment = 16;
    out->memoryTypeBits = 0x3u;
}

VKAPI_ATTR VkResult VKAPI_CALL bind_image_memory(
    VkDevice, VkImage image, VkDeviceMemory memory, VkDeviceSize) {
    const auto it = images.find(handle_id(image));
    if (it == images.end()) {
        ++faults;
        return VK_ERROR_UNKNOWN;
    }
    it->second.memory = handle_id(memory);
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL create_view(
    VkDevice, const VkImageViewCreateInfo* info, const VkAllocationCallbacks*, VkImageView* out) {
    if (info == nullptr || images.find(handle_id(info->image)) == images.end()) {
        ++faults;
        return VK_ERROR_UNKNOWN;
    }
    const std::uint64_t id = next_handle++;
    views.insert(id);
    *out = make_handle<VkImageView>(id);
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL destroy_view(
    VkDevice, VkImageView view, const VkAllocationCallbacks*) {
    if (views.erase(handle_id(view)) != 1) {
        ++faults;
    }
}

VKAPI_ATTR void VKAPI_CALL cmd_pipeline_barrier(VkCommandBuffer, VkPipelineStageFlags,
    VkPipelineStageFlags, VkDependencyFlags, std::uint32_t, const VkMemoryBarrier*,
    std::uint32_t, const VkBufferMemoryBarrier*, std::uint32_t, const VkImageMemoryBarrier*) {
    ++barriers;
}

} // namespace fake

extern "C" BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(
    BedrayCore*, const BedrayFrameDesc* desc, BedrayError*) {
    fake::RenderCall call{};
    if (desc != nullptr) {
        call.command_buffer = desc->command_buffer;
        call.program = desc->program;
        call.group_x = desc->group_count_x;
        call.group_y = desc->group_count_y;
        call.group_z = desc->group_count_z;
        call.push_bytes = desc->push_constant_bytes;
        if (desc->resources != nullptr) {
            call.resources.assign(desc->resources, desc->resources + desc->resource_count);
        }
        if (desc->push_constants != nullptr && desc->push_constant_bytes != 0) {
            const auto* bytes = static_cast<const unsigned char*>(desc->push_constants);
            call.push.assign(bytes, bytes + desc->push_constant_bytes);
        }
    }
    const int index = fake::render_call_index++;
    fake::render_calls.push_back(std::move(call));
    if (index == fake::fail_render_call) {
        return fake::render_fail_status;
    }
    return BEDRAY_OK;
}

extern "C" BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_phases(
    BedrayCore*, const BedrayFrameDesc* desc, std::uint32_t phase_offset,
    std::uint32_t phase_count, BedrayError*) {
    fake::PhaseCall call{};
    if (desc != nullptr) {
        call.command_buffer = desc->command_buffer;
        call.program = desc->program;
        call.phase_offset = phase_offset;
        call.phase_count = phase_count;
        call.push_bytes = desc->push_constant_bytes;
        if (desc->resources != nullptr) {
            call.resources.assign(desc->resources, desc->resources + desc->resource_count);
        }
        if (desc->push_constants != nullptr && desc->push_constant_bytes != 0) {
            const auto* bytes = static_cast<const unsigned char*>(desc->push_constants);
            call.push.assign(bytes, bytes + desc->push_constant_bytes);
        }
    }
    fake::phase_calls.push_back(std::move(call));
    if (fake::fail_phase_call) {
        return fake::phase_fail_status;
    }
    return BEDRAY_OK;
}

namespace {

using bedray::core::DeviceFns;

DeviceFns make_device_fns() {
    DeviceFns fns{};
    fns.physical_device = fake::make_handle<VkPhysicalDevice>(0x10);
    fns.device = fake::make_handle<VkDevice>(0x11);
    fns.memory_properties.memoryTypeCount = 2;
    fns.memory_properties.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    fns.memory_properties.memoryTypes[0].heapIndex = 0;
    fns.memory_properties.memoryTypes[1].propertyFlags =
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    fns.memory_properties.memoryTypes[1].heapIndex = 1;
    fns.memory_properties.memoryHeapCount = 2;
    fns.memory_properties.memoryHeaps[0].size = 1ull << 30;
    fns.memory_properties.memoryHeaps[0].flags = VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
    fns.memory_properties.memoryHeaps[1].size = 1ull << 28;
    fns.non_coherent_atom_size = 256;
    fns.generation = 1;
    fns.image_functions.create_image = &fake::create_image;
    fns.image_functions.destroy_image = &fake::destroy_image;
    fns.image_functions.get_requirements = &fake::get_image_requirements;
    fns.image_functions.allocate_memory = &fake::allocate_memory;
    fns.image_functions.free_memory = &fake::free_memory;
    fns.image_functions.bind_image_memory = &fake::bind_image_memory;
    fns.image_functions.create_view = &fake::create_view;
    fns.image_functions.destroy_view = &fake::destroy_view;
    fns.image_functions.image_format_properties = &fake::get_image_format_properties;
    fns.buffer_functions.create_buffer = &fake::create_buffer;
    fns.buffer_functions.destroy_buffer = &fake::destroy_buffer;
    fns.buffer_functions.get_requirements = &fake::get_buffer_requirements;
    fns.buffer_functions.allocate_memory = &fake::allocate_memory;
    fns.buffer_functions.free_memory = &fake::free_memory;
    fns.buffer_functions.bind_buffer_memory = &fake::bind_buffer_memory;
    fns.buffer_functions.map_memory = &fake::map_memory;
    fns.buffer_functions.unmap_memory = &fake::unmap_memory;
    fns.buffer_functions.flush = &fake::flush_memory;
    fns.buffer_functions.invalidate = &fake::invalidate_memory;
    fns.cmd_pipeline_barrier = &fake::cmd_pipeline_barrier;
    fns.cmd_fill_buffer = &fake::cmd_fill_buffer;
    return fns;
}

} // namespace
