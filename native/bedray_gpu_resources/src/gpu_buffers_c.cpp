#include "bedray/gpu_buffers_c.h"
#include "bedray/gpu_buffers.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <new>
#include <memory>
#include <type_traits>

struct BedrayGpuOwner {
    explicit BedrayGpuOwner(bedray::gpu::DeviceContext context, std::uint64_t budget) noexcept
        : buffers(context, budget) {}
    bedray::gpu::GpuBuffers buffers;
    bool released = false;
};

namespace {
using namespace bedray::gpu;
static_assert(sizeof(std::uintptr_t) == sizeof(std::uint64_t), "GPU C ABI targets a 64-bit process");
#define CHECK_STATUS(name, abi) static_assert(static_cast<std::uint32_t>(Status::name) == abi)
CHECK_STATUS(ok, BEDRAY_GPU_OK);
CHECK_STATUS(invalid_argument, BEDRAY_GPU_INVALID_ARGUMENT);
CHECK_STATUS(unavailable, BEDRAY_GPU_UNAVAILABLE);
CHECK_STATUS(unknown_buffer, BEDRAY_GPU_UNKNOWN_BUFFER);
CHECK_STATUS(unknown_use, BEDRAY_GPU_UNKNOWN_USE);
CHECK_STATUS(in_use, BEDRAY_GPU_IN_USE);
CHECK_STATUS(retired, BEDRAY_GPU_RETIRED);
CHECK_STATUS(budget_exceeded, BEDRAY_GPU_BUDGET_EXCEEDED);
CHECK_STATUS(unsupported, BEDRAY_GPU_UNSUPPORTED);
CHECK_STATUS(exhausted, BEDRAY_GPU_EXHAUSTED);
CHECK_STATUS(out_of_memory, BEDRAY_GPU_OUT_OF_MEMORY);
CHECK_STATUS(device_lost, BEDRAY_GPU_DEVICE_LOST);
CHECK_STATUS(vulkan_error, BEDRAY_GPU_VULKAN_ERROR);
#undef CHECK_STATUS
static_assert(VK_MAX_MEMORY_TYPES == BEDRAY_GPU_MAX_MEMORY_TYPES);

template<typename T> T function_address(std::uint64_t address) noexcept {
    return reinterpret_cast<T>(static_cast<std::uintptr_t>(address));
}
template<typename T> std::uint64_t handle_value(T handle) noexcept {
    if constexpr (std::is_pointer_v<T>) return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(handle));
    else return static_cast<std::uint64_t>(handle);
}
std::uint32_t status(Status value) noexcept { return static_cast<std::uint32_t>(value); }
BedrayGpuBufferInfo buffer_info(BufferId id, const BufferView& view) noexcept {
    return {id.owner_generation, id.serial, handle_value(view.buffer), view.size, view.device_address, view.memory_flags};
}
}

extern "C" {
uint32_t BEDRAY_GPU_CALL bedray_gpu_abi_version(void) try { return BEDRAY_GPU_ABI_VERSION; } catch (...) { return 0; }
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_abi_info(BedrayGpuAbiInfo* out, uint32_t out_size) try {
    if (!out || out_size < sizeof(BedrayGpuAbiInfo)) return BEDRAY_GPU_INVALID_ARGUMENT;
    BedrayGpuAbiInfo info{};
    info.abi_version = BEDRAY_GPU_ABI_VERSION;
    info.sizeof_info = sizeof(info);
    info.sizeof_owner_desc = sizeof(BedrayGpuOwnerDesc); info.align_owner_desc = alignof(BedrayGpuOwnerDesc);
    info.sizeof_buffer_info = sizeof(BedrayGpuBufferInfo); info.align_buffer_info = alignof(BedrayGpuBufferInfo);
    info.sizeof_use_token = sizeof(BedrayGpuUseToken); info.align_use_token = alignof(BedrayGpuUseToken);
#define OFFSET(T, F) static_cast<uint32_t>(offsetof(T, F))
    const uint32_t owner_offsets[] = {OFFSET(BedrayGpuOwnerDesc, abi_version), OFFSET(BedrayGpuOwnerDesc, struct_size),
        OFFSET(BedrayGpuOwnerDesc, device), OFFSET(BedrayGpuOwnerDesc, generation), OFFSET(BedrayGpuOwnerDesc, budget_bytes),
        OFFSET(BedrayGpuOwnerDesc, non_coherent_atom_size), OFFSET(BedrayGpuOwnerDesc, enabled_flags),
        OFFSET(BedrayGpuOwnerDesc, memory_type_count), OFFSET(BedrayGpuOwnerDesc, memory_type_flags),
        OFFSET(BedrayGpuOwnerDesc, functions)};
    const uint32_t buffer_offsets[] = {OFFSET(BedrayGpuBufferInfo, owner_generation), OFFSET(BedrayGpuBufferInfo, serial),
        OFFSET(BedrayGpuBufferInfo, buffer), OFFSET(BedrayGpuBufferInfo, size), OFFSET(BedrayGpuBufferInfo, device_address),
        OFFSET(BedrayGpuBufferInfo, memory_flags)};
    const uint32_t use_offsets[] = {OFFSET(BedrayGpuUseToken, owner_generation), OFFSET(BedrayGpuUseToken, serial)};
#undef OFFSET
    std::copy_n(owner_offsets, 10, info.owner_offsets);
    std::copy_n(buffer_offsets, 6, info.buffer_offsets);
    std::copy_n(use_offsets, 2, info.use_offsets);
    *out = info;
    return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}

BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_owner_create(const BedrayGpuOwnerDesc* desc, BedrayGpuOwner** out) try {
    if (!desc || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    *out = nullptr;
    if (desc->abi_version != BEDRAY_GPU_ABI_VERSION) return BEDRAY_GPU_ABI_MISMATCH;
    if (desc->struct_size < sizeof(BedrayGpuOwnerDesc)) return BEDRAY_GPU_ABI_MISMATCH;
    constexpr uint32_t supported_flags = BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED | BEDRAY_GPU_DEVICE_ADDRESS_ENABLED;
    if ((desc->enabled_flags & ~supported_flags) != 0 ||
        (desc->enabled_flags & BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED) == 0 ||
        desc->device == 0 || desc->generation == 0 || desc->budget_bytes == 0 ||
        desc->non_coherent_atom_size == 0 ||
        (desc->non_coherent_atom_size & (desc->non_coherent_atom_size - 1)) != 0 ||
        desc->memory_type_count == 0 || desc->memory_type_count > BEDRAY_GPU_MAX_MEMORY_TYPES)
        return BEDRAY_GPU_INVALID_ARGUMENT;
    for (uint32_t i = 0; i < BEDRAY_GPU_GET_BUFFER_ADDRESS; ++i)
        if (desc->functions[i] == 0) return BEDRAY_GPU_INVALID_ARGUMENT;
    if ((desc->enabled_flags & BEDRAY_GPU_DEVICE_ADDRESS_ENABLED) != 0 &&
        desc->functions[BEDRAY_GPU_GET_BUFFER_ADDRESS] == 0) return BEDRAY_GPU_INVALID_ARGUMENT;

    DeviceContext context{};
    context.device = function_address<VkDevice>(desc->device);
    context.generation = desc->generation;
    context.non_coherent_atom_size = desc->non_coherent_atom_size;
    context.dedicated_allocation_enabled = true;
    context.buffer_device_address_enabled = (desc->enabled_flags & BEDRAY_GPU_DEVICE_ADDRESS_ENABLED) != 0;
    context.memory_properties.memoryTypeCount = desc->memory_type_count;
    for (uint32_t i = 0; i < desc->memory_type_count; ++i)
        context.memory_properties.memoryTypes[i].propertyFlags = desc->memory_type_flags[i];
    auto& f = context.functions;
    f.create_buffer = function_address<PFN_vkCreateBuffer>(desc->functions[BEDRAY_GPU_CREATE_BUFFER]);
    f.destroy_buffer = function_address<PFN_vkDestroyBuffer>(desc->functions[BEDRAY_GPU_DESTROY_BUFFER]);
    f.get_requirements = function_address<PFN_vkGetBufferMemoryRequirements>(desc->functions[BEDRAY_GPU_GET_REQUIREMENTS]);
    f.allocate_memory = function_address<PFN_vkAllocateMemory>(desc->functions[BEDRAY_GPU_ALLOCATE_MEMORY]);
    f.free_memory = function_address<PFN_vkFreeMemory>(desc->functions[BEDRAY_GPU_FREE_MEMORY]);
    f.bind_buffer_memory = function_address<PFN_vkBindBufferMemory>(desc->functions[BEDRAY_GPU_BIND_MEMORY]);
    f.map_memory = function_address<PFN_vkMapMemory>(desc->functions[BEDRAY_GPU_MAP_MEMORY]);
    f.unmap_memory = function_address<PFN_vkUnmapMemory>(desc->functions[BEDRAY_GPU_UNMAP_MEMORY]);
    f.flush = function_address<PFN_vkFlushMappedMemoryRanges>(desc->functions[BEDRAY_GPU_FLUSH]);
    f.invalidate = function_address<PFN_vkInvalidateMappedMemoryRanges>(desc->functions[BEDRAY_GPU_INVALIDATE]);
    f.get_buffer_device_address = function_address<PFN_vkGetBufferDeviceAddress>(desc->functions[BEDRAY_GPU_GET_BUFFER_ADDRESS]);
    auto owner = std::make_unique<BedrayGpuOwner>(context, desc->budget_bytes);
    if (!owner->buffers.valid()) return BEDRAY_GPU_INVALID_ARGUMENT;
    *out = owner.release();
    return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}

BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_owner_destroy(BedrayGpuOwner* owner) try {
    if (!owner) return BEDRAY_GPU_INVALID_ARGUMENT;
    if (!owner->released) {
        const Status closed = owner->buffers.close();
        if (closed != Status::ok) return status(closed);
    }
    delete owner;
    return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_create(BedrayGpuOwner* owner, uint64_t size, uint32_t usage,
    uint32_t required, uint32_t preferred, uint32_t mapped, BedrayGpuBufferInfo* out, int32_t* vk_result) try {
    if (!owner || !out || !vk_result || mapped > 1) return BEDRAY_GPU_INVALID_ARGUMENT;
    VkResult vk = VK_SUCCESS; BufferId id{}; BufferView view{};
    const Status result = owner->buffers.create(BufferDesc{size, usage, required, preferred, mapped != 0}, id, view, vk);
    *vk_result = static_cast<int32_t>(vk);
    if (result == Status::ok) *out = buffer_info(id, view);
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_view(BedrayGpuOwner* owner, uint64_t generation, uint64_t serial,
    BedrayGpuBufferInfo* out) try {
    if (!owner || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    const BufferId id{generation, serial}; BufferView view{};
    const Status result = owner->buffers.view(id, view);
    if (result == Status::ok) *out = buffer_info(id, view);
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_write(BedrayGpuOwner* owner, uint64_t generation, uint64_t serial,
    uint64_t offset, const void* data, uint64_t bytes, int32_t* vk_result) try {
    if (!owner || !vk_result) return BEDRAY_GPU_INVALID_ARGUMENT;
    VkResult vk = VK_SUCCESS;
    const Status result = owner->buffers.write({generation, serial}, offset, data, bytes, vk);
    *vk_result = static_cast<int32_t>(vk); return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_read(BedrayGpuOwner* owner, uint64_t generation, uint64_t serial,
    uint64_t offset, void* data, uint64_t bytes, int32_t* vk_result) try {
    if (!owner || !vk_result) return BEDRAY_GPU_INVALID_ARGUMENT;
    VkResult vk = VK_SUCCESS;
    const Status result = owner->buffers.read({generation, serial}, offset, data, bytes, vk);
    *vk_result = static_cast<int32_t>(vk); return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_use_begin(BedrayGpuOwner* owner, uint64_t generation, uint64_t serial,
    BedrayGpuUseToken* out) try {
    if (!owner || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    UseId token{}; const Status result = owner->buffers.begin_use({generation, serial}, token);
    if (result == Status::ok) *out = BedrayGpuUseToken{token.owner_generation, token.serial};
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_upload(BedrayGpuOwner* owner, uint32_t usage,
    const void* data, uint64_t bytes, BedrayGpuBufferInfo* out, BedrayGpuUseToken* use, int32_t* vk_result) try {
    if (!owner || !out || !use || !vk_result) return BEDRAY_GPU_INVALID_ARGUMENT;
    VkResult vk = VK_SUCCESS; BufferId id{}; BufferView view{}; UseId token{};
    const Status result = owner->buffers.upload(usage, data, bytes, id, view, token, vk);
    *vk_result = static_cast<int32_t>(vk);
    if (result == Status::ok) {
        *out = buffer_info(id, view);
        *use = {token.owner_generation, token.serial};
    }
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_uploads_retire(BedrayGpuOwner* owner) try {
    return owner ? status(owner->buffers.retire_uploads()) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_use_complete(BedrayGpuOwner* owner, uint64_t generation, uint64_t serial) try {
    return owner ? status(owner->buffers.complete_use({generation, serial})) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_buffer_retire(BedrayGpuOwner* owner, uint64_t generation, uint64_t serial) try {
    return owner ? status(owner->buffers.retire({generation, serial})) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_collect(BedrayGpuOwner* owner) try {
    return owner ? status(owner->buffers.collect()) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_resident_bytes(BedrayGpuOwner* owner, uint64_t* out) try {
    if (!owner || !out) return BEDRAY_GPU_INVALID_ARGUMENT;
    if (owner->released) return BEDRAY_GPU_UNAVAILABLE;
    *out = owner->buffers.resident_bytes(); return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_device_idle(BedrayGpuOwner* owner) try {
    return owner ? status(owner->buffers.device_idle()) : BEDRAY_GPU_INVALID_ARGUMENT;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_device_lost(BedrayGpuOwner* owner) try {
    if (!owner) return BEDRAY_GPU_INVALID_ARGUMENT;
    if (owner->released) return BEDRAY_GPU_UNAVAILABLE;
    owner->buffers.device_lost(); return BEDRAY_GPU_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
BedrayGpuStatus BEDRAY_GPU_CALL bedray_gpu_release_after_device_loss(BedrayGpuOwner* owner) try {
    if (!owner) return BEDRAY_GPU_INVALID_ARGUMENT;
    const Status result = owner->buffers.release_after_device_loss();
    if (result == Status::ok) owner->released = true;
    return status(result);
} catch (const std::bad_alloc&) {
    return BEDRAY_GPU_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_GPU_INTERNAL_ERROR;
}
}
