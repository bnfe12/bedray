#include "bedray/memory_allocator.hpp"
#include "bedray/gpu_buffers.hpp"
#include <array>
#include <cstdlib>
#include <cstdio>
#include <map>
#include <memory>
#include <vector>
using namespace bedray::gpu;
namespace {
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr, "memory contract failed at line %d: %s\n", __LINE__, #x); std::abort(); } } while (false)
std::map<VkDeviceMemory, std::vector<unsigned char>> storage;
uint64_t next_memory = 1, next_buffer = 1, external_usage = 0, heap_budget = 8192;
unsigned allocations = 0, frees = 0, maps = 0, unmaps = 0, flushes = 0, invalidations = 0, destroyed = 0;
bool requires_dedicated = false, prefers_dedicated = false, expect_address = false, expect_dedicated = false;
VkResult allocate_error = VK_SUCCESS, map_error = VK_SUCCESS, bind_error = VK_SUCCESS, flush_error = VK_SUCCESS;
VkMappedMemoryRange last_range{};
VkDeviceSize request_size = 128, request_alignment = 64;

VKAPI_ATTR void VKAPI_CALL buffer_requirements(VkDevice, const VkBufferMemoryRequirementsInfo2*, VkMemoryRequirements2* out) {
    out->memoryRequirements = {request_size, request_alignment, 1};
    auto* single = static_cast<VkMemoryDedicatedRequirements*>(out->pNext);
    single->requiresDedicatedAllocation = requires_dedicated; single->prefersDedicatedAllocation = prefers_dedicated;
}
VKAPI_ATTR void VKAPI_CALL image_requirements(VkDevice d, const VkImageMemoryRequirementsInfo2*, VkMemoryRequirements2* out) {
    buffer_requirements(d, nullptr, out);
}
VKAPI_ATTR VkResult VKAPI_CALL allocate(VkDevice, const VkMemoryAllocateInfo* info, const VkAllocationCallbacks*, VkDeviceMemory* out) {
    if (allocate_error != VK_SUCCESS) return allocate_error;
    bool address = false, dedicated = false;
    for (auto* p = static_cast<const VkBaseInStructure*>(info->pNext); p; p = p->pNext) {
        if (p->sType == VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
            address = (reinterpret_cast<const VkMemoryAllocateFlagsInfo*>(p)->flags & VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT) != 0;
        if (p->sType == VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO) {
            auto* single = reinterpret_cast<const VkMemoryDedicatedAllocateInfo*>(p);
            CHECK((single->buffer != VK_NULL_HANDLE) != (single->image != VK_NULL_HANDLE)); dedicated = true;
        }
    }
    CHECK(address == expect_address && dedicated == expect_dedicated);
    *out = reinterpret_cast<VkDeviceMemory>(next_memory++);
    storage.emplace(*out, std::vector<unsigned char>(static_cast<size_t>(info->allocationSize)));
    ++allocations; return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL free_memory(VkDevice, VkDeviceMemory memory, const VkAllocationCallbacks*) {
    CHECK(storage.erase(memory) == 1); ++frees;
}
VKAPI_ATTR VkResult VKAPI_CALL map_memory(VkDevice, VkDeviceMemory memory, VkDeviceSize offset, VkDeviceSize size, VkMemoryMapFlags, void** out) {
    if (map_error != VK_SUCCESS) return map_error;
    CHECK(offset == 0 && size == storage.at(memory).size());
    *out = storage.at(memory).data(); ++maps; return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL unmap_memory(VkDevice, VkDeviceMemory) { ++unmaps; }
VKAPI_ATTR VkResult VKAPI_CALL flush(VkDevice, uint32_t count, const VkMappedMemoryRange* ranges) {
    CHECK(count == 1); last_range = *ranges; ++flushes; return flush_error;
}
VKAPI_ATTR VkResult VKAPI_CALL invalidate(VkDevice, uint32_t, const VkMappedMemoryRange* ranges) {
    last_range = *ranges; ++invalidations; return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL budget(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties2* properties) {
    auto* b = static_cast<VkPhysicalDeviceMemoryBudgetPropertiesEXT*>(properties->pNext);
    b->heapBudget[0] = heap_budget; b->heapUsage[0] = external_usage;
    for (const auto& [_, bytes] : storage) b->heapUsage[0] += bytes.size();
}
VKAPI_ATTR VkResult VKAPI_CALL create_buffer(VkDevice, const VkBufferCreateInfo*, const VkAllocationCallbacks*, VkBuffer* out) {
    *out = reinterpret_cast<VkBuffer>(next_buffer++); return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_buffer(VkDevice, VkBuffer, const VkAllocationCallbacks*) { ++destroyed; }
VKAPI_ATTR void VKAPI_CALL old_requirements(VkDevice, VkBuffer, VkMemoryRequirements* out) {
    *out = {request_size, request_alignment, 1};
}
VKAPI_ATTR VkResult VKAPI_CALL bind(VkDevice, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize offset) {
    CHECK(buffer != VK_NULL_HANDLE && memory != VK_NULL_HANDLE && offset % request_alignment == 0); return bind_error;
}
VKAPI_ATTR VkDeviceAddress VKAPI_CALL address(VkDevice, const VkBufferDeviceAddressInfo*) { return 0x10000; }
MemoryContext context(bool query_budget = false) {
    MemoryContext c;
    c.device = reinterpret_cast<VkDevice>(1); c.physical = reinterpret_cast<VkPhysicalDevice>(2);
    c.properties.memoryTypeCount = 1; c.properties.memoryHeapCount = 1;
    c.properties.memoryTypes[0] = {VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, 0};
    c.properties.memoryHeaps[0] = {8192, VK_MEMORY_HEAP_DEVICE_LOCAL_BIT};
    c.limit = 8192; c.block_size = 1024; c.atom_size = 64; c.max_blocks = 8;
    c.functions = {allocate, free_memory, map_memory, unmap_memory, flush, invalidate, buffer_requirements, image_requirements, budget};
    c.heap_budget_enabled = query_budget;
    return c;
}
DeviceContext owner_context(uint64_t generation) {
    DeviceContext c;
    c.device = reinterpret_cast<VkDevice>(1); c.generation = generation; c.memory_properties = context().properties;
    c.dedicated_allocation_enabled = true; c.buffer_device_address_enabled = true; c.non_coherent_atom_size = 64;
    c.functions = {create_buffer, destroy_buffer, old_requirements, allocate, free_memory, bind,
        map_memory, unmap_memory, flush, invalidate, address};
    return c;
}
MemoryAllocation get(MemoryAllocator& m, bool mapped = false, bool bda = false) {
    MemoryAllocation a; VkResult error;
    CHECK(m.buffer(reinterpret_cast<VkBuffer>(1), 0, bda, mapped, a, error) == Status::ok);
    return a;
}
void pooling_holes_and_accounting() {
    MemoryAllocator m(context()); CHECK(m.valid());
    const auto allocated_before = allocations;
    auto a = get(m), b = get(m);
    CHECK(a.memory == b.memory && a.offset == 0 && b.offset == 128 && allocations == allocated_before + 1);
    CHECK(m.stats().reserved == 1024 && m.stats().used == 256 && m.stats().allocations == 2);
    m.release(a); auto c = get(m);
    CHECK(c.offset == 0 && c.serial != a.serial);
    m.release(a); CHECK(m.stats().used == 256); // stale/double release cannot free a replacement
    m.release(b); m.release(c);
    CHECK(m.stats().reserved == 0 && m.stats().blocks == 0 && m.stats().peak_reserved == 1024);
}
void resource_and_address_classes() {
    MemoryAllocator m(context());
    auto ordinary = get(m);
    expect_address = true; auto addressed = get(m, false, true); expect_address = false;
    auto mapped = get(m, true);
    MemoryAllocation image; VkResult error;
    CHECK(m.image(reinterpret_cast<VkImage>(1), 0, image, error) == Status::ok);
    CHECK(m.stats().blocks == 4);
    CHECK(ordinary.memory != image.memory && addressed.memory != ordinary.memory && mapped.memory != ordinary.memory);
    m.release(ordinary); m.release(addressed); m.release(mapped); m.release(image);
}
void dedicated_and_large() {
    MemoryAllocator m(context());
    requires_dedicated = expect_dedicated = true;
    auto a = get(m); CHECK(m.stats().reserved == request_size && a.offset == 0);
    requires_dedicated = false; m.release(a);
    prefers_dedicated = true; a = get(m); m.release(a); prefers_dedicated = false;
    request_size = 700; a = get(m); CHECK(m.stats().reserved == 700); m.release(a);
    request_size = 128; expect_dedicated = false;
}
void mapped_atoms_and_rebased_ranges() {
    MemoryAllocator m(context()); request_size = 70; request_alignment = 16;
    auto a = get(m, true), b = get(m, true);
    CHECK(a.span == 128 && b.offset == 128 && static_cast<unsigned char*>(b.mapped) - static_cast<unsigned char*>(a.mapped) == 128);
    VkResult error;
    CHECK(m.synchronize(b, 10, 20, true, error) == Status::ok && last_range.offset == 128 && last_range.size == 64);
    CHECK(m.synchronize(b, 65, 5, false, error) == Status::ok && last_range.offset == 192 && last_range.size == 64);
    CHECK(m.synchronize(b, 65, 6, true, error) == Status::invalid_argument);
    m.release(a); m.release(b); request_size = 128; request_alignment = 64;
}
void limits_and_external_pressure() {
    auto c = context(true); c.limit = 1500;
    MemoryAllocator m(c); heap_budget = 1500; external_usage = 1300;
    auto a = get(m);
    CHECK(m.stats().reserved == 128 && m.stats().heap_usage == 1428 && m.stats().heap_budget == 1500);
    MemoryAllocation b; VkResult error;
    CHECK(m.buffer(reinterpret_cast<VkBuffer>(2), 0, false, false, b, error) == Status::budget_exceeded);
    m.release(a); external_usage = 0; heap_budget = 8192;
    c = context(); c.limit = 64; MemoryAllocator tiny(c);
    CHECK(tiny.buffer(reinterpret_cast<VkBuffer>(1), 0, false, false, b, error) == Status::budget_exceeded);
    c = context(); c.max_blocks = 1; MemoryAllocator single(c);
    a = get(single); expect_address = true;
    CHECK(single.buffer(reinterpret_cast<VkBuffer>(2), 0, true, false, b, error) == Status::budget_exceeded);
    expect_address = false; single.release(a);
}
void owners_keep_retired_slices_pinned_and_rollback_bind() {
    auto memory = std::make_shared<MemoryAllocator>(context());
    GpuBuffers a(owner_context(1), 512), b(owner_context(2), 512);
    CHECK(a.share_memory(memory) == Status::ok && b.share_memory(memory) == Status::ok);
    BufferId ai, bi; BufferView av, bv; VkResult error;
    BufferDesc desc{64, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false};
    CHECK(a.create(desc, ai, av, error) == Status::ok && b.create(desc, bi, bv, error) == Status::ok);
    CHECK(memory->stats().blocks == 1 && memory->stats().used == 256);
    UseId use; CHECK(a.begin_use(ai, use) == Status::ok);
    CHECK(a.retire(ai) == Status::ok && a.collect() == Status::ok);
    CHECK(memory->stats().used == 256 && a.share_memory(memory) == Status::in_use);
    CHECK(a.complete_use(use) == Status::ok && a.collect() == Status::ok && memory->stats().used == 128);
    bind_error = VK_ERROR_OUT_OF_DEVICE_MEMORY;
    CHECK(a.create(desc, ai, av, error) == Status::out_of_memory && memory->stats().used == 128);
    bind_error = VK_SUCCESS;
    CHECK(b.retire(bi) == Status::ok && b.collect() == Status::ok && memory->stats().reserved == 0);
    CHECK(a.close() == Status::ok && b.close() == Status::ok);
}
void failure_and_loss_propagation() {
    MemoryAllocator m(context()); MemoryAllocation a; VkResult error;
    map_error = VK_ERROR_OUT_OF_HOST_MEMORY;
    CHECK(m.buffer(reinterpret_cast<VkBuffer>(1), 0, false, true, a, error) == Status::out_of_memory);
    CHECK(m.stats().reserved == 0); map_error = VK_SUCCESS;
    a = get(m, true); flush_error = VK_ERROR_DEVICE_LOST;
    CHECK(m.synchronize(a, 0, 16, true, error) == Status::device_lost && m.lost());
    const auto calls = allocations;
    MemoryAllocation b;
    CHECK(m.buffer(reinterpret_cast<VkBuffer>(1), 0, false, false, b, error) == Status::device_lost && allocations == calls);
    CHECK((m.stats().flags & 2) != 0);
    m.release(a); flush_error = VK_SUCCESS; // explicit quiesced device-loss teardown, not normal completion
}
void destructor_never_frees_unproven_uses() {
    const auto before = frees;
    { MemoryAllocator m(context()); (void)get(m); }
    CHECK(frees == before && !storage.empty());
    storage.clear(); // mock storage only; the real leaked VkDeviceMemory belongs to device teardown
}
}
int main() {
    pooling_holes_and_accounting(); resource_and_address_classes(); dedicated_and_large();
    mapped_atoms_and_rebased_ranges(); limits_and_external_pressure();
    owners_keep_retired_slices_pinned_and_rollback_bind(); failure_and_loss_propagation();
    CHECK(storage.empty()); destructor_never_frees_unproven_uses();
    std::puts("shared memory pooling, dedicated/BDA classes, budget pressure, mapping, exact retirement, rollback and loss passed (mock driver)");
}
