#include "bedray/gpu_buffers.hpp"
#include "bedray/gpu_buffers_c.h"
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

using namespace bedray::gpu;

namespace {
int created = 0, destroyed = 0, allocated = 0, freed = 0, flushed = 0, invalidated = 0;
VkMappedMemoryRange last_range{};
unsigned char fake_memory[256]{};
bool expect_address_allocation = false;

void require(bool condition) { if (!condition) std::abort(); }
VKAPI_ATTR VkResult VKAPI_CALL create_buffer(VkDevice, const VkBufferCreateInfo*, const VkAllocationCallbacks*, VkBuffer* out) {
    *out = reinterpret_cast<VkBuffer>(static_cast<std::uintptr_t>(++created));
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_buffer(VkDevice, VkBuffer, const VkAllocationCallbacks*) { ++destroyed; }
VKAPI_ATTR void VKAPI_CALL requirements(VkDevice, VkBuffer, VkMemoryRequirements* out) {
    *out = {}; out->size = 256; out->alignment = 64; out->memoryTypeBits = 1;
}
VKAPI_ATTR VkResult VKAPI_CALL allocate(VkDevice, const VkMemoryAllocateInfo* info, const VkAllocationCallbacks*, VkDeviceMemory* out) {
    require(info->pNext != nullptr);
    auto* dedicated = static_cast<const VkMemoryDedicatedAllocateInfo*>(info->pNext);
    require(dedicated->sType == VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO && dedicated->buffer != VK_NULL_HANDLE);
    if (expect_address_allocation) {
        require(dedicated->pNext != nullptr);
        auto* flags = static_cast<const VkMemoryAllocateFlagsInfo*>(dedicated->pNext);
        require(flags->sType == VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO &&
                (flags->flags & VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT) != 0);
    }
    *out = reinterpret_cast<VkDeviceMemory>(static_cast<std::uintptr_t>(++allocated));
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL free_memory(VkDevice, VkDeviceMemory, const VkAllocationCallbacks*) { ++freed; }
VKAPI_ATTR VkResult VKAPI_CALL bind(VkDevice, VkBuffer, VkDeviceMemory, VkDeviceSize) { return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL map(VkDevice, VkDeviceMemory, VkDeviceSize, VkDeviceSize, VkMemoryMapFlags, void** out) {
    *out = fake_memory; return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL unmap(VkDevice, VkDeviceMemory) {}
VKAPI_ATTR VkResult VKAPI_CALL flush(VkDevice, uint32_t count, const VkMappedMemoryRange* range) {
    require(count == 1); last_range = *range; ++flushed; return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL invalidate(VkDevice, uint32_t count, const VkMappedMemoryRange* range) {
    require(count == 1); last_range = *range; ++invalidated; return VK_SUCCESS;
}
VKAPI_ATTR VkDeviceAddress VKAPI_CALL address(VkDevice, const VkBufferDeviceAddressInfo* info) {
    require(info->buffer != VK_NULL_HANDLE); return 4096;
}
DeviceContext context() {
    DeviceContext c{};
    c.device = reinterpret_cast<VkDevice>(static_cast<std::uintptr_t>(1));
    c.functions = {create_buffer, destroy_buffer, requirements, allocate, free_memory, bind, map, unmap, flush, invalidate};
    c.memory_properties.memoryTypeCount = 1;
    c.memory_properties.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    c.non_coherent_atom_size = 64;
    c.dedicated_allocation_enabled = true;
    c.generation = 1;
    return c;
}
void normal_lifecycle() {
    GpuBuffers buffers(context(), 512);
    require(buffers.valid());
    BufferId id{}; BufferView view{}; VkResult error{};
    BufferDesc desc{128, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0, 0, true};
    require(buffers.create(desc, id, view, error) == Status::ok);
    require(buffers.resident_bytes() == 256 && view.size == 128);
    unsigned char src[4]{1, 2, 3, 4}, dst[4]{};
    require(buffers.write(id, 3, src, 4, error) == Status::ok);
    require(flushed == 1 && last_range.offset == 0 && last_range.size == 64);
    require(buffers.read(id, 3, dst, 4, error) == Status::ok && std::memcmp(src, dst, 4) == 0);
    require(invalidated == 1 && last_range.offset == 0 && last_range.size == 64);
    UseId use{};
    require(buffers.begin_use(id, use) == Status::ok);
    require(buffers.write(id, 0, src, 4, error) == Status::in_use);
    require(buffers.retire(id) == Status::ok);
    int old_destroyed = destroyed;
    require(buffers.collect() == Status::ok && destroyed == old_destroyed);
    require(buffers.complete_use(use) == Status::ok);
    require(buffers.complete_use(use) == Status::unknown_use);
    require(buffers.collect() == Status::ok && destroyed == old_destroyed + 1 && buffers.resident_bytes() == 0);
    require(buffers.close() == Status::ok);
}
void loss_and_budget() {
    GpuBuffers buffers(context(), 256);
    BufferId id{}; BufferView view{}; VkResult error{};
    BufferDesc desc{128, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0, 0, true};
    require(buffers.create(desc, id, view, error) == Status::ok);
    BufferId ignored{}; BufferView ignored_view{};
    require(buffers.create(desc, ignored, ignored_view, error) == Status::budget_exceeded);
    UseId use{}; require(buffers.begin_use(id, use) == Status::ok);
    buffers.device_lost();
    int old_destroyed = destroyed;
    require(buffers.collect() == Status::device_lost && destroyed == old_destroyed);
    require(buffers.complete_use(use) == Status::device_lost);
    require(buffers.release_after_device_loss() == Status::ok && destroyed == old_destroyed + 1);
    require(allocated == freed);
}

void owner_generations_reject_stale_handles() {
    DeviceContext first = context();
    DeviceContext second = context(); second.generation = 2;
    GpuBuffers a(first, 256), b(second, 256);
    BufferId first_id{}, second_id{}; BufferView view{}; VkResult error{};
    BufferDesc desc{128, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, 0, 0, true};
    require(a.create(desc, first_id, view, error) == Status::ok);
    require(b.create(desc, second_id, view, error) == Status::ok);
    require(b.view(first_id, view) == Status::unknown_buffer);
    UseId first_use{}, second_use{};
    require(a.begin_use(first_id, first_use) == Status::ok);
    require(b.begin_use(second_id, second_use) == Status::ok);
    require(b.complete_use(first_use) == Status::unknown_use);
    require(a.complete_use(first_use) == Status::ok);
    require(b.complete_use(second_use) == Status::ok);
    require(a.retire(first_id) == Status::ok && a.collect() == Status::ok);
    require(b.retire(second_id) == Status::ok && b.collect() == Status::ok);
    require(a.close() == Status::ok && b.close() == Status::ok);
}
void device_address_capability() {
    DeviceContext c = context();
    GpuBuffers unsupported(c, 256);
    BufferDesc desc{128, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, 0, 0, false};
    BufferId id{}; BufferView view{}; VkResult error{};
    require(unsupported.create(desc, id, view, error) == Status::unsupported);
    c.buffer_device_address_enabled = true;
    c.functions.get_buffer_device_address = address;
    c.generation = 3;
    GpuBuffers buffers(c, 256);
    expect_address_allocation = true;
    require(buffers.create(desc, id, view, error) == Status::ok && view.device_address == 4096);
    expect_address_allocation = false;
    require(buffers.retire(id) == Status::ok && buffers.collect() == Status::ok);
    require(buffers.close() == Status::ok);
}
template<typename T> std::uint64_t pointer_value(T pointer) {
    return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(pointer));
}
BedrayGpuOwnerDesc c_description() {
    BedrayGpuOwnerDesc d{};
    d.abi_version = BEDRAY_GPU_ABI_VERSION;
    d.struct_size = sizeof(d);
    d.device = 1; d.generation = 4; d.budget_bytes = 512;
    d.non_coherent_atom_size = 64;
    d.enabled_flags = BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED;
    d.memory_type_count = 1;
    d.memory_type_flags[0] = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    d.functions[BEDRAY_GPU_CREATE_BUFFER] = pointer_value(create_buffer);
    d.functions[BEDRAY_GPU_DESTROY_BUFFER] = pointer_value(destroy_buffer);
    d.functions[BEDRAY_GPU_GET_REQUIREMENTS] = pointer_value(requirements);
    d.functions[BEDRAY_GPU_ALLOCATE_MEMORY] = pointer_value(allocate);
    d.functions[BEDRAY_GPU_FREE_MEMORY] = pointer_value(free_memory);
    d.functions[BEDRAY_GPU_BIND_MEMORY] = pointer_value(bind);
    d.functions[BEDRAY_GPU_MAP_MEMORY] = pointer_value(map);
    d.functions[BEDRAY_GPU_UNMAP_MEMORY] = pointer_value(unmap);
    d.functions[BEDRAY_GPU_FLUSH] = pointer_value(flush);
    d.functions[BEDRAY_GPU_INVALIDATE] = pointer_value(invalidate);
    return d;
}
void c_abi_contract() {
    BedrayGpuAbiInfo info{}; info.abi_version = 0xfeed;
    require(bedray_gpu_abi_info(&info, sizeof(info) - 1) == BEDRAY_GPU_INVALID_ARGUMENT && info.abi_version == 0xfeed);
    require(bedray_gpu_abi_info(&info, sizeof(info)) == BEDRAY_GPU_OK);
    require(info.abi_version == BEDRAY_GPU_ABI_VERSION && info.sizeof_owner_desc == sizeof(BedrayGpuOwnerDesc));
    require(info.owner_offsets[9] == offsetof(BedrayGpuOwnerDesc, functions));
    require(info.buffer_offsets[5] == offsetof(BedrayGpuBufferInfo, memory_flags));
    require(info.use_offsets[1] == offsetof(BedrayGpuUseToken, serial));
    BedrayGpuOwnerDesc d = c_description();
    BedrayGpuOwner* owner = nullptr;
    d.abi_version = 999;
    require(bedray_gpu_owner_create(&d, &owner) == BEDRAY_GPU_ABI_MISMATCH && owner == nullptr);
    d.abi_version = BEDRAY_GPU_ABI_VERSION;
    d.struct_size--;
    require(bedray_gpu_owner_create(&d, &owner) == BEDRAY_GPU_ABI_MISMATCH && owner == nullptr);
    d.struct_size++;
    d.functions[BEDRAY_GPU_FLUSH] = 0;
    require(bedray_gpu_owner_create(&d, &owner) == BEDRAY_GPU_INVALID_ARGUMENT && owner == nullptr);
    d.functions[BEDRAY_GPU_FLUSH] = pointer_value(flush);
    require(bedray_gpu_owner_create(&d, &owner) == BEDRAY_GPU_OK && owner != nullptr);
    BedrayGpuBufferInfo buffer{}; int32_t vk_result = -1;
    require(bedray_gpu_buffer_create(owner, 128, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
        0, 0, 1, &buffer, &vk_result) == BEDRAY_GPU_OK && vk_result == VK_SUCCESS);
    require(buffer.owner_generation == d.generation && buffer.serial != 0 && buffer.buffer != 0);
    require(bedray_gpu_owner_destroy(owner) == BEDRAY_GPU_IN_USE);
    BedrayGpuUseToken token{};
    require(bedray_gpu_use_begin(owner, buffer.owner_generation, buffer.serial, &token) == BEDRAY_GPU_OK);
    require(bedray_gpu_buffer_retire(owner, buffer.owner_generation, buffer.serial) == BEDRAY_GPU_OK);
    require(bedray_gpu_collect(owner) == BEDRAY_GPU_OK);
    uint64_t bytes{};
    require(bedray_gpu_resident_bytes(owner, &bytes) == BEDRAY_GPU_OK && bytes == 256);
    require(bedray_gpu_use_complete(owner, token.owner_generation + 1, token.serial) == BEDRAY_GPU_UNKNOWN_USE);
    require(bedray_gpu_use_complete(owner, token.owner_generation, token.serial) == BEDRAY_GPU_OK);
    require(bedray_gpu_collect(owner) == BEDRAY_GPU_OK);
    require(bedray_gpu_owner_destroy(owner) == BEDRAY_GPU_OK);
}
void c_abi_loss() {
    BedrayGpuOwnerDesc d = c_description(); d.generation = 5;
    BedrayGpuOwner* owner = nullptr;
    require(bedray_gpu_owner_create(&d, &owner) == BEDRAY_GPU_OK);
    BedrayGpuBufferInfo buffer{}; int32_t vk_result{};
    require(bedray_gpu_buffer_create(owner, 128, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
        0, 0, 1, &buffer, &vk_result) == BEDRAY_GPU_OK);
    BedrayGpuUseToken token{};
    require(bedray_gpu_use_begin(owner, buffer.owner_generation, buffer.serial, &token) == BEDRAY_GPU_OK);
    require(bedray_gpu_device_lost(owner) == BEDRAY_GPU_OK);
    require(bedray_gpu_owner_destroy(owner) == BEDRAY_GPU_DEVICE_LOST);
    require(bedray_gpu_collect(owner) == BEDRAY_GPU_DEVICE_LOST);
    require(bedray_gpu_release_after_device_loss(owner) == BEDRAY_GPU_OK);
    require(bedray_gpu_owner_destroy(owner) == BEDRAY_GPU_OK);
}
}

int main() {
    normal_lifecycle(); loss_and_budget(); owner_generations_reject_stale_handles(); device_address_capability();
    c_abi_contract(); c_abi_loss();
}
