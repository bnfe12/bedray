/*
 * Contract test for the bedray_scene C ABI.
 *
 * The test installs a fake Vulkan function table through the proc-address
 * fields of BedraySceneDesc. It needs no GPU, loader or window: the fake records
 * every command the scene records and emulates host-visible uploads, buffer
 * copies and acceleration-structure addresses. The test proves the ownership
 * and lifetime rules documented in SCENE_NOTES.md, not GPU behaviour.
 */

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

#include "bedray_scene.h"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

static int g_failures = 0;

#define CHECK(cond) \
    do { \
        if (!(cond)) { \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures; \
        } \
    } while (0)

#define CHECK_STATUS(expr, expected) \
    do { \
        const BedraySceneStatus actual_status = (expr); \
        if (actual_status != (expected)) { \
            std::fprintf(stderr, "FAIL %s:%d: %s returned %d, expected %d (%s)\n", \
                __FILE__, __LINE__, #expr, static_cast<int>(actual_status), \
                static_cast<int>(expected), g_error.message); \
            ++g_failures; \
        } \
    } while (0)

static BedraySceneError g_error{};

static_assert(sizeof(BedraySceneError) == 1028, "error layout");
static_assert(sizeof(BedraySceneAbiInfo) == 48, "abi info layout");
static_assert(sizeof(BedraySceneDesc) == 88, "desc layout");
static_assert(sizeof(BedraySceneSectionInput) == 96, "section input layout");
static_assert(sizeof(BedraySceneEntityInput) == 136, "entity input layout");
static_assert(sizeof(BedraySceneUpdate) == 56, "update layout");
static_assert(sizeof(BedraySceneTableDesc) == 40, "table desc layout");
static_assert(sizeof(BedraySceneFrameDesc) == 24, "frame desc layout");
static_assert(sizeof(BedraySceneFrameInfo) == 80, "frame info layout");
static_assert(sizeof(BedraySceneResources) == 200, "resources layout");
static_assert(sizeof(BedraySceneSectionRange) == 8, "section range layout");
static_assert(sizeof(BedraySceneSectionRecord) == 64, "section record layout");

static_assert(offsetof(BedraySceneSectionInput, origin) == 56, "section origin offset");
static_assert(offsetof(BedraySceneSectionInput, geometry_count) == 68, "geometry count offset");
static_assert(offsetof(BedraySceneSectionInput, quad_start) == 72, "quad start offset");
static_assert(offsetof(BedraySceneSectionInput, pass_bits) == 84, "pass bits offset");
static_assert(offsetof(BedraySceneSectionInput, flags) == 88, "section flags offset");
static_assert(offsetof(BedraySceneSectionInput, reserved) == 92, "section reserved offset");
static_assert(offsetof(BedraySceneSectionRecord, origin) == 0, "record origin offset");
static_assert(offsetof(BedraySceneSectionRecord, meta) == 16, "record meta offset");
static_assert(offsetof(BedraySceneSectionRecord, vertex_address) == 32, "record vertex offset");
static_assert(offsetof(BedraySceneSectionRecord, ranges) == 40, "record ranges offset");
static_assert(offsetof(BedraySceneSectionRange, first_quad) == 0, "range first quad offset");
static_assert(offsetof(BedraySceneSectionRange, quad_count_pass) == 4, "range count/pass offset");

namespace fake {

struct MemoryState {
    uint64_t size = 0;
    uint32_t type_index = 0;
    VkMemoryAllocateFlags flags = 0;
    std::vector<uint8_t> bytes;
};

struct BufferState {
    VkBufferUsageFlags usage = 0;
    uint64_t size = 0;
    uint64_t memory = 0;
    uint64_t address = 0;
};

struct AsState {
    VkAccelerationStructureTypeKHR type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
    uint64_t buffer = 0;
    uint64_t address = 0;
    std::vector<uint64_t> blas_references;
};

struct BuildGeometryRecord {
    VkGeometryFlagsKHR flags = 0;
    uint32_t primitive_count = 0;
    uint32_t primitive_offset = 0;
    uint32_t first_vertex = 0;
    uint32_t max_vertex = 0;
    uint32_t vertex_stride = 0;
    uint64_t vertex_address = 0;
    uint64_t index_address = 0;
    uint32_t index_type = VK_INDEX_TYPE_NONE_KHR;
    uint64_t instance_address = 0;
};

struct BuildRecord {
    VkAccelerationStructureTypeKHR type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
    VkBuildAccelerationStructureModeKHR mode = VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    VkBuildAccelerationStructureFlagsKHR flags = 0;
    VkAccelerationStructureKHR src = VK_NULL_HANDLE;
    VkAccelerationStructureKHR dst = VK_NULL_HANDLE;
    uint32_t primitive_count = 0;
    uint64_t scratch = 0;
    uint64_t vertex_address = 0;
    uint64_t index_address = 0;
    uint32_t index_type = VK_INDEX_TYPE_NONE_KHR;
    uint64_t instance_buffer_address = 0;
    uint32_t geometry_count = 0;
    std::vector<BuildGeometryRecord> geometries;
};

struct SizeQueryRecord {
    VkAccelerationStructureTypeKHR type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
    uint32_t geometry_count = 0;
    std::vector<uint32_t> max_counts;
};

struct CopyRecord {
    VkBuffer src = VK_NULL_HANDLE;
    VkBuffer dst = VK_NULL_HANDLE;
    uint64_t src_offset = 0;
    uint64_t dst_offset = 0;
    uint64_t size = 0;
    std::vector<uint8_t> payload;
};

struct BarrierRecord {
    VkPipelineStageFlags src_stage = 0;
    VkPipelineStageFlags dst_stage = 0;
    bool has_memory = false;
    VkMemoryBarrier memory{};
    std::vector<VkBufferMemoryBarrier> buffers;
    std::vector<VkImageMemoryBarrier> images;
};

struct State {
    std::map<uint64_t, BufferState> buffers;
    std::map<uint64_t, MemoryState> memories;
    std::map<uint64_t, AsState> accels;
    std::map<VkImage, VkExtent3D> images;
    std::map<VkImageView, VkImage> views;
    std::vector<VkBuffer> image_copies;
    uint64_t next_image = 0x3000;
    std::vector<BuildRecord> builds;
    std::vector<SizeQueryRecord> size_queries;
    std::vector<CopyRecord> copies;
    std::vector<BarrierRecord> barriers;
    std::vector<std::string> unknown_proc_requests;
    uint64_t next_buffer = 0x100;
    uint64_t next_memory = 0x1000;
    uint64_t next_accel = 0x2000;
    uint64_t next_buffer_address = 0x100000010ull; // valid buffer address, deliberately not AS scratch-aligned
    uint64_t next_accel_address = 0x200000000ull;
    int fail_create_buffer_countdown = -1;
    VkResult create_buffer_failure = VK_ERROR_DEVICE_LOST;
    bool zero_device_address = false;
    int faults = 0;
};

static State g;

static void reset() {
    g = State{};
}

static void fault(const char* message) {
    std::fprintf(stderr, "FAKE VULKAN FAULT: %s\n", message);
    ++g.faults;
}

static uint64_t buffer_id(VkBuffer buffer) {
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(buffer));
}

static uint64_t memory_id(VkDeviceMemory memory) {
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(memory));
}

static uint64_t accel_id(VkAccelerationStructureKHR accel) {
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(accel));
}

static BufferState* find_buffer(VkBuffer buffer) {
    const auto it = g.buffers.find(buffer_id(buffer));
    return it == g.buffers.end() ? nullptr : &it->second;
}

static AsState* find_accel(VkAccelerationStructureKHR accel) {
    const auto it = g.accels.find(accel_id(accel));
    return it == g.accels.end() ? nullptr : &it->second;
}

static BufferState* find_buffer_by_address(uint64_t address) {
    for (auto& entry : g.buffers) {
        if (entry.second.address == address && entry.second.address != 0) {
            return &entry.second;
        }
    }
    return nullptr;
}

static std::vector<uint8_t>* bytes_of(BufferState& buffer) {
    const auto it = g.memories.find(buffer.memory);
    if (it == g.memories.end()) {
        return nullptr;
    }
    return &it->second.bytes;
}

static std::vector<uint8_t>* bytes_at_address(uint64_t address) {
    BufferState* buffer = find_buffer_by_address(address);
    return buffer == nullptr ? nullptr : bytes_of(*buffer);
}

static bool accel_is_alive(VkAccelerationStructureKHR accel) {
    return g.accels.find(accel_id(accel)) != g.accels.end();
}

static int find_barrier(VkPipelineStageFlags src, VkPipelineStageFlags dst, bool memory) {
    for (size_t i = 0; i < g.barriers.size(); ++i) {
        const BarrierRecord& barrier = g.barriers[i];
        if (barrier.src_stage == src && barrier.dst_stage == dst && barrier.has_memory == memory) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

static int find_copy(const std::vector<uint8_t>& payload) {
    for (size_t i = 0; i < g.copies.size(); ++i) {
        if (g.copies[i].payload.size() == payload.size()
            && std::memcmp(g.copies[i].payload.data(), payload.data(), payload.size()) == 0) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

} // namespace fake

static void VKAPI_CALL fakeGetPhysicalDeviceMemoryProperties(
    VkPhysicalDevice, VkPhysicalDeviceMemoryProperties* out) {
    std::memset(out, 0, sizeof(*out));
    out->memoryTypeCount = 2;
    out->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    out->memoryTypes[0].heapIndex = 0;
    out->memoryTypes[1].propertyFlags =
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    out->memoryTypes[1].heapIndex = 1;
    out->memoryHeapCount = 2;
    out->memoryHeaps[0].size = 1ull << 30;
    out->memoryHeaps[0].flags = VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
    out->memoryHeaps[1].size = 1ull << 28;
}

static void VKAPI_CALL fakeGetPhysicalDeviceProperties2(
    VkPhysicalDevice, VkPhysicalDeviceProperties2* out) {
    out->properties.apiVersion = VK_API_VERSION_1_3;
    auto* as = reinterpret_cast<VkPhysicalDeviceAccelerationStructurePropertiesKHR*>(out->pNext);
    if (as != nullptr
        && as->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR) {
        as->minAccelerationStructureScratchOffsetAlignment = 256;
    }
}

static VkResult VKAPI_CALL fakeCreateBuffer(
    VkDevice, const VkBufferCreateInfo* info, const VkAllocationCallbacks*, VkBuffer* out) {
    if (fake::g.fail_create_buffer_countdown == 0) {
        return fake::g.create_buffer_failure;
    }
    if (fake::g.fail_create_buffer_countdown > 0) {
        --fake::g.fail_create_buffer_countdown;
    }
    const uint64_t id = fake::g.next_buffer++;
    fake::BufferState state;
    state.size = info->size;
    state.usage = info->usage;
    fake::g.buffers.emplace(id, state);
    *out = reinterpret_cast<VkBuffer>(static_cast<uintptr_t>(id));
    return VK_SUCCESS;
}

static void VKAPI_CALL fakeDestroyBuffer(VkDevice, VkBuffer buffer, const VkAllocationCallbacks*) {
    const auto it = fake::g.buffers.find(fake::buffer_id(buffer));
    if (it == fake::g.buffers.end()) {
        fake::fault("vkDestroyBuffer on an unknown or double-destroyed handle");
        return;
    }
    fake::g.buffers.erase(it);
}

static void VKAPI_CALL fakeGetBufferMemoryRequirements(
    VkDevice, VkBuffer buffer, VkMemoryRequirements* out) {
    fake::BufferState* state = fake::find_buffer(buffer);
    std::memset(out, 0, sizeof(*out));
    if (state == nullptr) {
        fake::fault("vkGetBufferMemoryRequirements on an unknown handle");
        return;
    }
    out->size = (state->size + 255ull) & ~255ull;
    out->alignment = 256;
    out->memoryTypeBits = 3;
}

static VkResult VKAPI_CALL fakeAllocateMemory(
    VkDevice, const VkMemoryAllocateInfo* info, const VkAllocationCallbacks*, VkDeviceMemory* out) {
    const uint64_t id = fake::g.next_memory++;
    fake::MemoryState state;
    state.size = info->allocationSize;
    state.type_index = info->memoryTypeIndex;
    if (info->pNext != nullptr) {
        const auto* flags = static_cast<const VkMemoryAllocateFlagsInfo*>(info->pNext);
        if (flags->sType == VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO) state.flags = flags->flags;
        else CHECK(flags->sType == VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO);
    }
    state.bytes.resize(static_cast<size_t>(info->allocationSize));
    fake::g.memories.emplace(id, std::move(state));
    *out = reinterpret_cast<VkDeviceMemory>(static_cast<uintptr_t>(id));
    return VK_SUCCESS;
}

static void VKAPI_CALL fakeFreeMemory(VkDevice, VkDeviceMemory memory, const VkAllocationCallbacks*) {
    const auto it = fake::g.memories.find(fake::memory_id(memory));
    if (it == fake::g.memories.end()) {
        fake::fault("vkFreeMemory on an unknown or double-freed handle");
        return;
    }
    fake::g.memories.erase(it);
}

static VkResult VKAPI_CALL fakeBindBufferMemory(
    VkDevice, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize offset) {
    fake::BufferState* state = fake::find_buffer(buffer);
    if (state == nullptr || offset != 0) {
        fake::fault("vkBindBufferMemory with an unknown buffer or non-zero offset");
        return VK_ERROR_UNKNOWN;
    }
    state->memory = fake::memory_id(memory);
    const auto found = fake::g.memories.find(state->memory);
    CHECK(found != fake::g.memories.end());
    if (found != fake::g.memories.end()) {
        CHECK(((state->usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0)
            == ((found->second.flags & VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT) != 0));
    }
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL fakeMapMemory(
    VkDevice, VkDeviceMemory memory, VkDeviceSize, VkDeviceSize, VkMemoryMapFlags, void** out) {
    const auto it = fake::g.memories.find(fake::memory_id(memory));
    if (it == fake::g.memories.end()) {
        fake::fault("vkMapMemory on an unknown handle");
        return VK_ERROR_UNKNOWN;
    }
    *out = it->second.bytes.data();
    return VK_SUCCESS;
}

static void VKAPI_CALL fakeUnmapMemory(VkDevice, VkDeviceMemory) {
}

static VkResult VKAPI_CALL fakeFlushMappedMemoryRanges(
    VkDevice, uint32_t count, const VkMappedMemoryRange* ranges) {
    for (uint32_t i = 0; i < count; ++i) {
        if (fake::g.memories.find(fake::memory_id(ranges[i].memory)) == fake::g.memories.end()) {
            fake::fault("vkFlushMappedMemoryRanges on an unknown handle");
            return VK_ERROR_UNKNOWN;
        }
    }
    return VK_SUCCESS;
}

static VkDeviceAddress VKAPI_CALL fakeGetBufferDeviceAddress(
    VkDevice, const VkBufferDeviceAddressInfo* info) {
    if (fake::g.zero_device_address) return 0;
    fake::BufferState* state = fake::find_buffer(info->buffer);
    if (state == nullptr) {
        fake::fault("vkGetBufferDeviceAddress on an unknown handle");
        return 0;
    }
    if (state->address == 0) {
        state->address = fake::g.next_buffer_address;
        fake::g.next_buffer_address += 0x10000;
    }
    return state->address;
}

static void VKAPI_CALL fakeGetAccelerationStructureBuildSizes(
    VkDevice,
    VkAccelerationStructureBuildTypeKHR,
    const VkAccelerationStructureBuildGeometryInfoKHR* info,
    const uint32_t* max_counts,
    VkAccelerationStructureBuildSizesInfoKHR* out) {
    fake::SizeQueryRecord query;
    if (info != nullptr) {
        query.type = info->type;
        query.geometry_count = info->geometryCount;
        for (uint32_t i = 0; i < info->geometryCount; ++i) {
            query.max_counts.push_back(max_counts != nullptr ? max_counts[i] : 0);
        }
    }
    fake::g.size_queries.push_back(std::move(query));
    const uint64_t count = max_counts != nullptr ? *max_counts : 0;
    out->accelerationStructureSize = 4096 + 256 * count;
    out->updateScratchSize = 1024 + 64 * count;
    out->buildScratchSize = 2048 + 128 * count;
}

static VkResult VKAPI_CALL fakeCreateAccelerationStructure(
    VkDevice,
    const VkAccelerationStructureCreateInfoKHR* info,
    const VkAllocationCallbacks*,
    VkAccelerationStructureKHR* out) {
    const uint64_t id = fake::g.next_accel++;
    fake::AsState state;
    state.type = info->type;
    state.buffer = fake::buffer_id(info->buffer);
    state.address = fake::g.next_accel_address;
    fake::g.next_accel_address += 0x10000;
    fake::g.accels.emplace(id, state);
    *out = reinterpret_cast<VkAccelerationStructureKHR>(static_cast<uintptr_t>(id));
    return VK_SUCCESS;
}

static void VKAPI_CALL fakeDestroyAccelerationStructure(
    VkDevice, VkAccelerationStructureKHR accel, const VkAllocationCallbacks*) {
    const auto it = fake::g.accels.find(fake::accel_id(accel));
    if (it == fake::g.accels.end()) {
        fake::fault("vkDestroyAccelerationStructureKHR on an unknown handle");
        return;
    }
    fake::g.accels.erase(it);
}

static VkDeviceAddress VKAPI_CALL fakeGetAccelerationStructureDeviceAddress(
    VkDevice, const VkAccelerationStructureDeviceAddressInfoKHR* info) {
    fake::AsState* state = fake::find_accel(info->accelerationStructure);
    if (state == nullptr) {
        fake::fault("vkGetAccelerationStructureDeviceAddressKHR on an unknown handle");
        return 0;
    }
    return state->address;
}

static void VKAPI_CALL fakeCmdBuildAccelerationStructures(
    VkCommandBuffer,
    uint32_t count,
    const VkAccelerationStructureBuildGeometryInfoKHR* infos,
    const VkAccelerationStructureBuildRangeInfoKHR* const* ranges) {
    for (uint32_t i = 0; i < count; ++i) {
        const VkAccelerationStructureBuildGeometryInfoKHR& info = infos[i];
        fake::BuildRecord record;
        record.type = info.type;
        record.mode = info.mode;
        record.flags = info.flags;
        record.src = info.srcAccelerationStructure;
        record.dst = info.dstAccelerationStructure;
        record.scratch = info.scratchData.deviceAddress;
        if (record.scratch % 256 != 0) fake::fault("AS scratch address is not aligned");
        record.geometry_count = info.geometryCount;
        for (uint32_t g = 0; g < info.geometryCount; ++g) {
            fake::BuildGeometryRecord geometry_record;
            if (ranges != nullptr && ranges[i] != nullptr) {
                geometry_record.primitive_count = ranges[i][g].primitiveCount;
                geometry_record.primitive_offset = ranges[i][g].primitiveOffset;
                geometry_record.first_vertex = ranges[i][g].firstVertex;
            }
            if (info.pGeometries != nullptr) {
                const VkAccelerationStructureGeometryKHR& geometry = info.pGeometries[g];
                geometry_record.flags = geometry.flags;
                if (geometry.geometryType == VK_GEOMETRY_TYPE_TRIANGLES_KHR) {
                    geometry_record.vertex_address = geometry.geometry.triangles.vertexData.deviceAddress;
                    geometry_record.index_address = geometry.geometry.triangles.indexData.deviceAddress;
                    geometry_record.index_type =
                        static_cast<uint32_t>(geometry.geometry.triangles.indexType);
                    geometry_record.max_vertex = geometry.geometry.triangles.maxVertex;
                    geometry_record.vertex_stride =
                        static_cast<uint32_t>(geometry.geometry.triangles.vertexStride);
                } else if (geometry.geometryType == VK_GEOMETRY_TYPE_INSTANCES_KHR) {
                    geometry_record.instance_address =
                        geometry.geometry.instances.data.deviceAddress;
                }
            }
            record.geometries.push_back(geometry_record);
        }
        if (!record.geometries.empty()) {
            record.primitive_count = record.geometries[0].primitive_count;
            record.vertex_address = record.geometries[0].vertex_address;
            record.index_address = record.geometries[0].index_address;
            record.index_type = record.geometries[0].index_type;
            record.instance_buffer_address = record.geometries[0].instance_address;
        }
        if (info.type == VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR) {
            auto alive = [](uint64_t address) {
                for (const auto& entry : fake::g.accels)
                    if (entry.second.address == address) return true;
                return false;
            };
            if (info.mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR) {
                auto* source = fake::find_accel(info.srcAccelerationStructure);
                CHECK(source != nullptr);
                if (source != nullptr) for (uint64_t address : source->blas_references)
                    CHECK(alive(address)); // Old cached TLAS dependencies must survive through its UPDATE.
            }
            std::vector<uint64_t> references;
            auto* bytes = fake::bytes_at_address(record.instance_buffer_address);
            CHECK(bytes != nullptr && bytes->size() >= record.primitive_count * 64ull);
            if (bytes != nullptr && bytes->size() >= record.primitive_count * 64ull) {
                for (uint32_t instance = 0; instance < record.primitive_count; ++instance) {
                    uint64_t address = 0;
                    std::memcpy(&address, bytes->data() + instance * 64ull + 56, sizeof(address));
                    CHECK(address != 0 && alive(address));
                    references.push_back(address);
                }
            }
            auto* destination = fake::find_accel(info.dstAccelerationStructure);
            CHECK(destination != nullptr);
            if (destination != nullptr) destination->blas_references = std::move(references);
        }
        fake::g.builds.push_back(record);
    }
}

static void VKAPI_CALL fakeCmdCopyBuffer(
    VkCommandBuffer, VkBuffer src, VkBuffer dst, uint32_t region_count, const VkBufferCopy* regions) {
    fake::BufferState* source = fake::find_buffer(src);
    fake::BufferState* target = fake::find_buffer(dst);
    if (source == nullptr || target == nullptr) {
        fake::fault("vkCmdCopyBuffer with an unknown buffer");
        return;
    }
    std::vector<uint8_t>* source_bytes = fake::bytes_of(*source);
    std::vector<uint8_t>* target_bytes = fake::bytes_of(*target);
    for (uint32_t i = 0; i < region_count; ++i) {
        fake::CopyRecord record;
        record.src = src;
        record.dst = dst;
        record.src_offset = regions[i].srcOffset;
        record.dst_offset = regions[i].dstOffset;
        record.size = regions[i].size;
        if (source_bytes != nullptr
            && regions[i].srcOffset + regions[i].size <= source_bytes->size()) {
            const auto begin = source_bytes->begin()
                + static_cast<ptrdiff_t>(regions[i].srcOffset);
            record.payload.assign(begin, begin + static_cast<ptrdiff_t>(regions[i].size));
            if (target_bytes != nullptr
                && regions[i].dstOffset + regions[i].size <= target_bytes->size()) {
                std::memcpy(target_bytes->data() + regions[i].dstOffset,
                    record.payload.data(), record.payload.size());
            }
        }
        fake::g.copies.push_back(std::move(record));
    }
}

static void VKAPI_CALL fakeCmdPipelineBarrier(
    VkCommandBuffer,
    VkPipelineStageFlags src_stage,
    VkPipelineStageFlags dst_stage,
    VkDependencyFlags,
    uint32_t memory_count,
    const VkMemoryBarrier* memory_barriers,
    uint32_t buffer_count,
    const VkBufferMemoryBarrier* buffer_barriers,
    uint32_t image_count,
    const VkImageMemoryBarrier* image_barriers) {
    fake::BarrierRecord record;
    record.src_stage = src_stage;
    record.dst_stage = dst_stage;
    if (memory_count > 0 && memory_barriers != nullptr) {
        record.has_memory = true;
        record.memory = memory_barriers[0];
    }
    for (uint32_t i = 0; i < buffer_count; ++i) {
        record.buffers.push_back(buffer_barriers[i]);
    }
    if (image_count) record.images.assign(image_barriers, image_barriers + image_count);
    fake::g.barriers.push_back(std::move(record));
}

static VkResult VKAPI_CALL fakeCreateImage(VkDevice, const VkImageCreateInfo* info,
    const VkAllocationCallbacks*, VkImage* out) {
    *out = reinterpret_cast<VkImage>(fake::g.next_image++);
    fake::g.images.emplace(*out, info->extent);
    return VK_SUCCESS;
}

static void VKAPI_CALL fakeDestroyImage(VkDevice, VkImage image, const VkAllocationCallbacks*) {
    CHECK(fake::g.images.erase(image) == 1);
}

static void VKAPI_CALL fakeGetImageMemoryRequirements(VkDevice, VkImage image, VkMemoryRequirements* out) {
    const auto extent = fake::g.images.at(image);
    *out = {uint64_t(extent.width) * extent.height * 4, 16, 1};
}

static VkResult VKAPI_CALL fakeBindImageMemory(VkDevice, VkImage image, VkDeviceMemory memory, VkDeviceSize) {
    CHECK(fake::g.images.contains(image));
    CHECK(fake::g.memories.contains(fake::memory_id(memory)));
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL fakeCreateImageView(VkDevice, const VkImageViewCreateInfo* info,
    const VkAllocationCallbacks*, VkImageView* out) {
    *out = reinterpret_cast<VkImageView>(fake::g.next_image++);
    CHECK(fake::g.images.contains(info->image));
    fake::g.views.emplace(*out, info->image);
    return VK_SUCCESS;
}

static void VKAPI_CALL fakeDestroyImageView(VkDevice, VkImageView view, const VkAllocationCallbacks*) {
    CHECK(fake::g.views.erase(view) == 1);
}

static VkResult VKAPI_CALL fakeGetPhysicalDeviceImageFormatProperties(VkPhysicalDevice, VkFormat, VkImageType,
    VkImageTiling, VkImageUsageFlags, VkImageCreateFlags, VkImageFormatProperties* out) {
    *out = {{4096, 4096, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, uint64_t(1) << 30};
    return VK_SUCCESS;
}

static void VKAPI_CALL fakeCmdCopyBufferToImage(VkCommandBuffer, VkBuffer buffer, VkImage image,
    VkImageLayout layout, uint32_t count, const VkBufferImageCopy* copies) {
    CHECK(layout == VK_IMAGE_LAYOUT_GENERAL);
    auto* src = fake::find_buffer(buffer);
    CHECK(src && fake::g.images.contains(image));
    if (src) {
        for (uint32_t i = 0; i < count; ++i) {
            const auto& c = copies[i];
            CHECK(c.bufferOffset + uint64_t(c.imageExtent.width) * c.imageExtent.height * 4 <= src->size);
        }
    }
    fake::g.image_copies.push_back(buffer);
}

static PFN_vkVoidFunction VKAPI_CALL fakeGetDeviceProcAddr(VkDevice, const char* name) {
    if (std::strcmp(name, "vkCreateImage") == 0) return reinterpret_cast<PFN_vkVoidFunction>(&fakeCreateImage);
    if (std::strcmp(name, "vkDestroyImage") == 0) return reinterpret_cast<PFN_vkVoidFunction>(&fakeDestroyImage);
    if (std::strcmp(name, "vkGetImageMemoryRequirements") == 0) return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetImageMemoryRequirements);
    if (std::strcmp(name, "vkBindImageMemory") == 0) return reinterpret_cast<PFN_vkVoidFunction>(&fakeBindImageMemory);
    if (std::strcmp(name, "vkCreateImageView") == 0) return reinterpret_cast<PFN_vkVoidFunction>(&fakeCreateImageView);
    if (std::strcmp(name, "vkDestroyImageView") == 0) return reinterpret_cast<PFN_vkVoidFunction>(&fakeDestroyImageView);
    if (std::strcmp(name, "vkCmdCopyBufferToImage") == 0) return reinterpret_cast<PFN_vkVoidFunction>(&fakeCmdCopyBufferToImage);
    if (std::strcmp(name, "vkCreateBuffer") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeCreateBuffer);
    }
    if (std::strcmp(name, "vkDestroyBuffer") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeDestroyBuffer);
    }
    if (std::strcmp(name, "vkGetBufferMemoryRequirements") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetBufferMemoryRequirements);
    }
    if (std::strcmp(name, "vkAllocateMemory") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeAllocateMemory);
    }
    if (std::strcmp(name, "vkFreeMemory") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeFreeMemory);
    }
    if (std::strcmp(name, "vkBindBufferMemory") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeBindBufferMemory);
    }
    if (std::strcmp(name, "vkMapMemory") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeMapMemory);
    }
    if (std::strcmp(name, "vkUnmapMemory") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeUnmapMemory);
    }
    if (std::strcmp(name, "vkFlushMappedMemoryRanges") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeFlushMappedMemoryRanges);
    }
    if (std::strcmp(name, "vkGetBufferDeviceAddressKHR") == 0
        || std::strcmp(name, "vkGetBufferDeviceAddress") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetBufferDeviceAddress);
    }
    if (std::strcmp(name, "vkGetAccelerationStructureBuildSizesKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetAccelerationStructureBuildSizes);
    }
    if (std::strcmp(name, "vkCreateAccelerationStructureKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeCreateAccelerationStructure);
    }
    if (std::strcmp(name, "vkDestroyAccelerationStructureKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeDestroyAccelerationStructure);
    }
    if (std::strcmp(name, "vkGetAccelerationStructureDeviceAddressKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetAccelerationStructureDeviceAddress);
    }
    if (std::strcmp(name, "vkCmdBuildAccelerationStructuresKHR") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeCmdBuildAccelerationStructures);
    }
    if (std::strcmp(name, "vkCmdCopyBuffer") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeCmdCopyBuffer);
    }
    if (std::strcmp(name, "vkCmdPipelineBarrier") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeCmdPipelineBarrier);
    }
    fake::g.unknown_proc_requests.emplace_back(name);
    return nullptr;
}

static PFN_vkVoidFunction VKAPI_CALL fakeGetInstanceProcAddr(VkInstance, const char* name) {
    if (std::strcmp(name, "vkGetPhysicalDeviceImageFormatProperties") == 0)
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetPhysicalDeviceImageFormatProperties);
    if (std::strcmp(name, "vkGetPhysicalDeviceMemoryProperties") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetPhysicalDeviceMemoryProperties);
    }
    if (std::strcmp(name, "vkGetPhysicalDeviceProperties2") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&fakeGetPhysicalDeviceProperties2);
    }
    fake::g.unknown_proc_requests.emplace_back(name);
    return nullptr;
}

namespace {

struct Mesh {
    std::vector<float> vertices;
    std::vector<uint16_t> indices;

    std::vector<uint8_t> vertex_bytes() const {
        std::vector<uint8_t> bytes(vertices.size() * sizeof(float));
        std::memcpy(bytes.data(), vertices.data(), bytes.size());
        return bytes;
    }
};

Mesh quad_mesh(float x, float y, float z) {
    Mesh mesh;
    mesh.vertices = {
        x, y, z,
        x + 1.0f, y, z,
        x + 1.0f, y + 1.0f, z,
        x, y + 1.0f, z,
    };
    mesh.indices = { 0, 1, 2, 0, 2, 3 };
    return mesh;
}

Mesh triangle_mesh(float x, float y, float z) {
    Mesh mesh;
    mesh.vertices = {
        x, y, z,
        x + 1.0f, y, z,
        x, y + 1.0f, z,
    };
    mesh.indices = { 0, 1, 2 };
    return mesh;
}

BedraySceneSectionInput section_input(
    uint64_t id, uint64_t revision, const Mesh& mesh) {
    BedraySceneSectionInput input{};
    input.section_id = id;
    input.geometry_revision = revision;
    input.vertices = mesh.vertices.data();
    input.vertex_count = mesh.vertices.size() / 3;
    input.vertex_stride = 12;
    input.index_type = BEDRAY_SCENE_INDEX_UINT16;
    input.indices = mesh.indices.data();
    input.index_count = mesh.indices.size();
    input.origin[0] = 0;
    input.origin[1] = 0;
    input.origin[2] = 0;
    input.geometry_count = 1;
    input.quad_start[0] = 0;
    input.quad_start[1] = 0;
    input.quad_start[2] = 0;
    input.pass_bits = 0;
    input.flags = 0;
    return input;
}

BedraySceneEntityInput entity_input(
    uint64_t id,
    uint64_t geometry_revision,
    uint64_t topology_revision,
    const Mesh& mesh,
    float tx = 0.0f,
    float ty = 0.0f,
    float tz = 0.0f,
    uint32_t mask = 0) {
    BedraySceneEntityInput input{};
    input.entity_id = id;
    input.geometry_revision = geometry_revision;
    input.topology_revision = topology_revision;
    input.vertices = mesh.vertices.data();
    input.vertex_count = mesh.vertices.size() / 3;
    input.vertex_stride = 12;
    input.index_type = BEDRAY_SCENE_INDEX_UINT16;
    input.indices = mesh.indices.data();
    input.index_count = mesh.indices.size();
    input.instance_mask = mask;
    const float transform[12] = {
        1.0f, 0.0f, 0.0f, tx,
        0.0f, 1.0f, 0.0f, ty,
        0.0f, 0.0f, 1.0f, tz,
    };
    std::memcpy(input.transform, transform, sizeof(transform));
    return input;
}

struct Fixture {
    BedrayScene* scene = nullptr;
    uint32_t frames = 0;

    explicit Fixture(
        uint32_t frames_in_flight,
        uint32_t capabilities = 0,
        uint32_t max_geometry_bytes = 1u << 20) {
        fake::reset();
        frames = frames_in_flight;
        if (capabilities == 0) {
            capabilities = BEDRAY_SCENE_CAP_ACCELERATION_STRUCTURE
                | BEDRAY_SCENE_CAP_DEVICE_ADDRESS
                | BEDRAY_SCENE_CAP_AS_UPDATE;
        }
        BedraySceneDesc desc{};
        desc.abi_version = BEDRAY_SCENE_ABI_VERSION;
        desc.instance = 0x1;
        desc.physical_device = 0x2;
        desc.device = 0x3;
        desc.get_instance_proc_addr =
            static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&fakeGetInstanceProcAddr));
        desc.get_device_proc_addr =
            static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&fakeGetDeviceProcAddr));
        desc.api_version = VK_API_VERSION_1_2;
        desc.capabilities = capabilities;
        desc.frames_in_flight = frames_in_flight;
        desc.max_sections = 16;
        desc.max_entities = 16;
        desc.max_instances = 64;
        desc.max_table_bytes = 4096;
        desc.max_geometry_bytes = max_geometry_bytes;
        g_error = BedraySceneError{};
        const BedraySceneStatus status = bedray_scene_create(&desc, &scene, &g_error);
        if (status != BEDRAY_SCENE_OK) {
            std::fprintf(stderr, "FAIL fixture create: %d (%s)\n",
                static_cast<int>(status), g_error.message);
            ++g_failures;
        }
        CHECK(fake::g.unknown_proc_requests.empty());
    }

    ~Fixture() {
        if (scene != nullptr) {
            const BedraySceneStatus status = bedray_scene_destroy(scene);
            if (status != BEDRAY_SCENE_OK) {
                std::fprintf(stderr, "FAIL fixture destroy: %d (%s)\n",
                    static_cast<int>(status), g_error.message);
                ++g_failures;
            }
            scene = nullptr;
        }
        CHECK(fake::g.buffers.empty());
        CHECK(fake::g.memories.empty());
        CHECK(fake::g.accels.empty());
        CHECK(fake::g.faults == 0);
    }

    BedraySceneFrameInfo prepare(uint64_t frame_index, uint64_t command_buffer) {
        BedraySceneFrameDesc desc{};
        desc.abi_version = BEDRAY_SCENE_ABI_VERSION;
        desc.frame_index = frame_index;
        desc.command_buffer = command_buffer;
        BedraySceneFrameInfo info{};
        g_error = BedraySceneError{};
        const BedraySceneStatus status =
            bedray_scene_prepare_frame(scene, &desc, &info, &g_error);
        if (status != BEDRAY_SCENE_OK) {
            std::fprintf(stderr, "FAIL prepare(%llu): %d (%s)\n",
                static_cast<unsigned long long>(frame_index),
                static_cast<int>(status), g_error.message);
            ++g_failures;
        }
        return info;
    }

    BedraySceneStatus prepare_status(
        uint64_t frame_index, uint64_t command_buffer, BedraySceneFrameInfo* out_info = nullptr) {
        BedraySceneFrameDesc desc{};
        desc.abi_version = BEDRAY_SCENE_ABI_VERSION;
        desc.frame_index = frame_index;
        desc.command_buffer = command_buffer;
        BedraySceneFrameInfo info{};
        g_error = BedraySceneError{};
        const BedraySceneStatus status =
            bedray_scene_prepare_frame(scene, &desc, &info, &g_error);
        if (out_info != nullptr) {
            *out_info = info;
        }
        return status;
    }

    void release(uint64_t frame_index) {
        g_error = BedraySceneError{};
        CHECK_STATUS(bedray_scene_release_frame(scene, frame_index, &g_error), BEDRAY_SCENE_OK);
    }

    BedraySceneStatus add_section(uint64_t id, uint64_t revision, const Mesh& mesh) {
        BedraySceneSectionInput input = section_input(id, revision, mesh);
        BedraySceneUpdate update{};
        update.abi_version = BEDRAY_SCENE_ABI_VERSION;
        update.sections = &input;
        update.section_count = 1;
        g_error = BedraySceneError{};
        return bedray_scene_update(scene, &update, &g_error);
    }

    BedraySceneStatus add_section_input(const BedraySceneSectionInput& input) {
        BedraySceneUpdate update{};
        update.abi_version = BEDRAY_SCENE_ABI_VERSION;
        update.sections = &input;
        update.section_count = 1;
        g_error = BedraySceneError{};
        return bedray_scene_update(scene, &update, &g_error);
    }

    BedraySceneStatus remove_section(uint64_t id) {
        BedraySceneUpdate update{};
        update.abi_version = BEDRAY_SCENE_ABI_VERSION;
        update.removed_sections = &id;
        update.removed_section_count = 1;
        g_error = BedraySceneError{};
        return bedray_scene_update(scene, &update, &g_error);
    }

    BedraySceneStatus add_entity(const BedraySceneEntityInput& input) {
        BedraySceneUpdate update{};
        update.abi_version = BEDRAY_SCENE_ABI_VERSION;
        update.entities = &input;
        update.entity_count = 1;
        g_error = BedraySceneError{};
        return bedray_scene_update(scene, &update, &g_error);
    }

    BedraySceneStatus update_table(uint32_t kind, uint64_t revision, const std::vector<uint8_t>& data) {
        BedraySceneTableDesc desc{};
        desc.abi_version = BEDRAY_SCENE_ABI_VERSION;
        desc.table_kind = kind;
        desc.revision = revision;
        desc.data = data.empty() ? nullptr : data.data();
        desc.size = data.size();
        g_error = BedraySceneError{};
        return bedray_scene_update_table(scene, &desc, &g_error);
    }

    BedraySceneResources resources() {
        BedraySceneResources out{};
        CHECK_STATUS(bedray_scene_query_resources(scene, &out), BEDRAY_SCENE_OK);
        uint64_t resident = 0, driver_bytes = 0;
        for (const auto& entry : fake::g.memories) driver_bytes += entry.second.size;
        CHECK_STATUS(bedray_scene_resident_bytes(scene, &resident), BEDRAY_SCENE_OK);
        CHECK(resident == driver_bytes);
        return out;
    }
};

} // namespace

static bool bytes_start_with(
    const std::vector<uint8_t>* buffer, const std::vector<uint8_t>& expected) {
    return buffer != nullptr && buffer->size() >= expected.size()
        && std::memcmp(buffer->data(), expected.data(), expected.size()) == 0;
}

static BedraySceneSectionRecord read_section_record(
    const BedraySceneResources& resources, size_t index) {
    BedraySceneSectionRecord record{};
    std::vector<uint8_t>* table = fake::bytes_at_address(resources.section_table_address);
    if (table != nullptr
        && (index + 1) * BEDRAY_SCENE_SECTION_RECORD_BYTES <= table->size()) {
        std::memcpy(&record, table->data() + index * BEDRAY_SCENE_SECTION_RECORD_BYTES,
            sizeof(record));
    }
    return record;
}

static uint32_t instance_custom_index(const BedraySceneResources& resources, size_t index) {
    uint32_t value = 0;
    std::vector<uint8_t>* bytes = fake::bytes_at_address(resources.instance_buffer_address);
    if (bytes != nullptr && (index + 1) * BEDRAY_SCENE_INSTANCE_RECORD_BYTES <= bytes->size()) {
        std::memcpy(&value, bytes->data() + index * BEDRAY_SCENE_INSTANCE_RECORD_BYTES + 48,
            sizeof(value));
    }
    /* The 32-bit word packs instanceCustomIndex:24 | mask:8. */
    return value & 0xFFFFFFu;
}

static void read_instance_transform(
    const BedraySceneResources& resources, size_t index, float out[12]) {
    std::memset(out, 0, 12 * sizeof(float));
    std::vector<uint8_t>* bytes = fake::bytes_at_address(resources.instance_buffer_address);
    if (bytes != nullptr && (index + 1) * BEDRAY_SCENE_INSTANCE_RECORD_BYTES <= bytes->size()) {
        std::memcpy(out, bytes->data() + index * BEDRAY_SCENE_INSTANCE_RECORD_BYTES,
            sizeof(float) * 12);
    }
}

static bool near_value(float actual, double expected) {
    return std::fabs(static_cast<double>(actual) - expected) <= 1e-4;
}

static void check_identity_rotation(const float transform[12]) {
    CHECK(near_value(transform[0], 1.0));
    CHECK(near_value(transform[1], 0.0));
    CHECK(near_value(transform[2], 0.0));
    CHECK(near_value(transform[4], 0.0));
    CHECK(near_value(transform[5], 1.0));
    CHECK(near_value(transform[6], 0.0));
    CHECK(near_value(transform[8], 0.0));
    CHECK(near_value(transform[9], 0.0));
    CHECK(near_value(transform[10], 1.0));
}

static void test_abi_layout() {
    CHECK(bedray_scene_abi_version() == BEDRAY_SCENE_ABI_VERSION);
    BedraySceneAbiInfo info{};
    bedray_scene_abi_info(&info);
    CHECK(info.abi_version == BEDRAY_SCENE_ABI_VERSION);
    CHECK(info.sizeof_desc == sizeof(BedraySceneDesc));
    CHECK(info.sizeof_section_input == sizeof(BedraySceneSectionInput));
    CHECK(info.sizeof_entity_input == sizeof(BedraySceneEntityInput));
    CHECK(info.sizeof_update == sizeof(BedraySceneUpdate));
    CHECK(info.sizeof_table_desc == sizeof(BedraySceneTableDesc));
    CHECK(info.sizeof_frame_desc == sizeof(BedraySceneFrameDesc));
    CHECK(info.sizeof_frame_info == sizeof(BedraySceneFrameInfo));
    CHECK(info.sizeof_resources == sizeof(BedraySceneResources));
    CHECK(info.sizeof_section_record == BEDRAY_SCENE_SECTION_RECORD_BYTES);
    CHECK(info.sizeof_error == sizeof(BedraySceneError));
    CHECK(info.reserved == 0);
}

static void test_create_validation() {
    fake::reset();
    BedraySceneDesc desc{};
    desc.abi_version = BEDRAY_SCENE_ABI_VERSION;
    desc.instance = 1;
    desc.physical_device = 2;
    desc.device = 3;
    desc.get_instance_proc_addr =
        static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&fakeGetInstanceProcAddr));
    desc.get_device_proc_addr =
        static_cast<uint64_t>(reinterpret_cast<uintptr_t>(&fakeGetDeviceProcAddr));
    desc.api_version = VK_API_VERSION_1_2;
    desc.frames_in_flight = 2;
    desc.max_sections = 4;
    desc.max_entities = 4;
    desc.max_instances = 8;
    desc.max_table_bytes = 1024;
    desc.max_geometry_bytes = 1u << 16;

    BedrayScene* scene = nullptr;
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error), BEDRAY_SCENE_ERROR_UNSUPPORTED);
    CHECK(scene == nullptr);

    desc.capabilities = BEDRAY_SCENE_CAP_ACCELERATION_STRUCTURE
        | BEDRAY_SCENE_CAP_DEVICE_ADDRESS;
    desc.frames_in_flight = 0;
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    desc.frames_in_flight = BEDRAY_SCENE_MAX_FRAMES_IN_FLIGHT + 1;
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    desc.frames_in_flight = 2;
    desc.api_version = VK_API_VERSION_1_0;
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error), BEDRAY_SCENE_ERROR_UNSUPPORTED);

    desc.api_version = VK_API_VERSION_1_2;
    desc.abi_version = 99;
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error),
        BEDRAY_SCENE_ERROR_ABI_MISMATCH);

    desc.abi_version = BEDRAY_SCENE_ABI_VERSION;
    CHECK_STATUS(bedray_scene_create(&desc, nullptr, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    /* Entity instance custom indices spend bit 0x800000 as a tag, so both
       slot spaces are capped below it. */
    desc.max_instances = 0xFFFFFFu;
    desc.max_sections = 0x800001u;
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);
    desc.max_sections = 4;
    desc.max_entities = 0x800001u;
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);
    desc.max_entities = 4;
    desc.max_instances = 8;

    desc.max_sections = 0;
    CHECK_STATUS(bedray_scene_create(&desc, &scene, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    CHECK(fake::g.buffers.empty());
    CHECK(fake::g.faults == 0);
}

static void test_first_frame_records_full_pipeline() {
    Fixture fixture(2);
    const Mesh section_mesh = quad_mesh(0.0f, 0.0f, 0.0f);
    const Mesh entity_mesh = triangle_mesh(4.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_section(7, 1, section_mesh), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.add_entity(entity_input(42, 1, 1, entity_mesh)), BEDRAY_SCENE_OK);

    std::vector<uint8_t> material(64);
    for (size_t i = 0; i < material.size(); ++i) {
        material[i] = static_cast<uint8_t>(0xA0 + i);
    }
    std::vector<uint8_t> light(32, 0x5A);
    CHECK_STATUS(fixture.update_table(BEDRAY_SCENE_TABLE_MATERIAL, 1, material), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.update_table(BEDRAY_SCENE_TABLE_LIGHT, 1, light), BEDRAY_SCENE_OK);

    const BedraySceneFrameInfo info = fixture.prepare(1, 0xABC);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_TLAS_REBUILT) != 0);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_BLAS_REBUILT) != 0);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_UPLOADED) != 0);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_TABLES_UPLOADED) != 0);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_IDLE) == 0);
    CHECK(info.frame_index == 1);
    CHECK(info.tlas != 0);
    CHECK(info.instance_count == 2);
    CHECK(info.blas_builds == 2);
    CHECK(info.blas_refits == 0);
    CHECK(info.deferred_updates == 0);
    CHECK(info.section_count == 1);
    CHECK(info.upload_bytes > 0);

    const BedraySceneResources resources = fixture.resources();
    CHECK(resources.abi_version == BEDRAY_SCENE_ABI_VERSION);
    CHECK(resources.tlas == info.tlas);
    CHECK(resources.section_count == 1);
    CHECK(resources.instance_count == 2);
    CHECK(resources.section_table_buffer != 0);
    CHECK(resources.section_table_address != 0);
    CHECK(resources.section_table_bytes == BEDRAY_SCENE_SECTION_RECORD_BYTES);
    CHECK(resources.material_table_buffer != 0);
    CHECK(resources.material_table_bytes == material.size());
    CHECK(resources.light_table_bytes == light.size());
    CHECK(resources.instance_buffer != 0);
    CHECK(resources.instance_buffer_address != 0);

    CHECK(fake::g.builds.size() == 3);
    if (fake::g.builds.size() == 3) {
        const fake::BuildRecord& section_build = fake::g.builds[0];
        const fake::BuildRecord& entity_build = fake::g.builds[1];
        const fake::BuildRecord& tlas_build = fake::g.builds[2];
        CHECK((section_build.flags & VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR) == 0);
        CHECK((entity_build.flags & VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR) != 0);
        CHECK(section_build.type == VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        CHECK(section_build.mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
        CHECK(section_build.primitive_count == 2);
        CHECK(section_build.index_type == VK_INDEX_TYPE_UINT16);
        CHECK(section_build.vertex_address != 0);
        CHECK(section_build.index_address != 0);
        CHECK(section_build.geometry_count == 1);
        if (section_build.geometries.size() == 1) {
            const fake::BuildGeometryRecord& range = section_build.geometries[0];
            CHECK(range.primitive_count == 2);
            CHECK(range.primitive_offset == 0);
            CHECK(range.first_vertex == 0);
            CHECK(range.max_vertex == 3);
            CHECK(range.vertex_stride == 12);
            CHECK(range.index_type == VK_INDEX_TYPE_UINT16);
            CHECK((range.flags & VK_GEOMETRY_OPAQUE_BIT_KHR) != 0);
        }
        CHECK(entity_build.type == VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        CHECK(entity_build.primitive_count == 1);
        CHECK(entity_build.geometry_count == 1);
        CHECK(tlas_build.type == VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        CHECK(tlas_build.mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
        CHECK(tlas_build.primitive_count == 2);
        CHECK(tlas_build.instance_buffer_address == resources.instance_buffer_address);
    }

    const int host_transfer = fake::find_barrier(
        VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, false);
    const int transfer_build = fake::find_barrier(
        VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, false);
    const int build_build = fake::find_barrier(
        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, true);
    const int final_shader = fake::find_barrier(
        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
            | VK_PIPELINE_STAGE_TRANSFER_BIT
            | VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, true);
    CHECK(host_transfer >= 0);
    CHECK(transfer_build >= 0);
    CHECK(build_build >= 0);
    CHECK(final_shader >= 0);
    CHECK(host_transfer < transfer_build);
    CHECK(transfer_build < build_build);
    CHECK(build_build < final_shader);
    if (build_build >= 0) {
        const VkMemoryBarrier& barrier = fake::g.barriers[build_build].memory;
        CHECK(barrier.srcAccessMask == VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        CHECK(barrier.dstAccessMask == VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
    }
    if (final_shader >= 0) {
        const fake::BarrierRecord& final = fake::g.barriers[final_shader];
        // Compute and ray-generation consumers both read the AS and uploaded shader tables.
        CHECK(final.memory.srcAccessMask == VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        CHECK(final.memory.dstAccessMask == (VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT));
        CHECK(!final.buffers.empty());
        for (const VkBufferMemoryBarrier& buffer : final.buffers) {
            CHECK(buffer.dstAccessMask == VK_ACCESS_SHADER_READ_BIT);
        }
    }

    /* 4 mesh copies + 2 tables + 1 section table. */
    CHECK(fake::g.copies.size() == 7);
    CHECK(fake::find_copy(section_mesh.vertex_bytes()) >= 0);
    CHECK(fake::find_copy(material) >= 0);
    CHECK(fake::find_copy(light) >= 0);

    std::vector<uint8_t>* section_table = fake::bytes_at_address(resources.section_table_address);
    CHECK(section_table != nullptr && section_table->size() >= BEDRAY_SCENE_SECTION_RECORD_BYTES);
    if (section_table != nullptr && section_table->size() >= BEDRAY_SCENE_SECTION_RECORD_BYTES) {
        BedraySceneSectionRecord record{};
        std::memcpy(&record, section_table->data(), sizeof(record));
        CHECK(record.meta[0] == section_mesh.vertices.size() / 3);
        CHECK(record.meta[1] == 1);
        CHECK(record.meta[2] == 0);
        CHECK(record.origin[3] != 0);
        CHECK(record.ranges[0].first_quad == 0);
        CHECK((record.ranges[0].quad_count_pass & 0xFFFFu) == 1);
        CHECK(((record.ranges[0].quad_count_pass >> 16u) & 0x3u) == 0);
        CHECK(record.vertex_address == fake::g.builds[0].vertex_address);
    }

    std::vector<uint8_t>* material_table = fake::bytes_at_address(resources.material_table_address);
    CHECK(bytes_start_with(material_table, material));

    std::vector<uint8_t>* instance_bytes = fake::bytes_at_address(resources.instance_buffer_address);
    CHECK(instance_bytes != nullptr);
    if (instance_bytes != nullptr && instance_bytes->size() >= 2 * 64) {
        /* Instance 0 is the section: its custom index is the section table
           slot (0). Instance 1 is the entity: slot 0 plus the entity tag. */
        uint32_t section_word = 0;
        std::memcpy(&section_word, instance_bytes->data() + 48, sizeof(section_word));
        CHECK((section_word & 0xFFFFFFu) == 0);
        CHECK((section_word >> 24) == 0x01u);
        CHECK(((section_word >> 24) & 0x02u) == 0u);
        uint32_t packed = 0;
        std::memcpy(&packed, instance_bytes->data() + 64 + 48, sizeof(packed));
        CHECK((packed & 0xFFFFFFu) == 0x800000u);
        CHECK((packed >> 24) == 0xFF);
        uint64_t reference = 0;
        std::memcpy(&reference, instance_bytes->data() + 64 + 56, sizeof(reference));
        const fake::AsState* entity_accel = fake::find_accel(fake::g.builds[1].dst);
        CHECK(entity_accel != nullptr);
        if (entity_accel != nullptr) {
            CHECK(reference == entity_accel->address);
        }
    }

    fixture.release(1);
}

static void test_idle_frame_reuses_slot_state() {
    Fixture fixture(1);
    const Mesh mesh = triangle_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(5, 1, 1, mesh)), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo first = fixture.prepare(1, 0x100);
    CHECK(first.flags != 0);
    const auto resources = fixture.resources();
    CHECK(resources.section_count == 0);
    CHECK(resources.section_table_buffer != 0);
    CHECK(resources.section_table_bytes == BEDRAY_SCENE_SECTION_RECORD_BYTES);
    const auto* dummy = fake::bytes_at_address(resources.section_table_address);
    CHECK(dummy != nullptr && dummy->size() >= BEDRAY_SCENE_SECTION_RECORD_BYTES);
    if (dummy != nullptr) {
        for (size_t i = 0; i < BEDRAY_SCENE_SECTION_RECORD_BYTES; ++i) CHECK((*dummy)[i] == 0);
    }
    const size_t builds_after_first = fake::g.builds.size();
    const size_t copies_after_first = fake::g.copies.size();
    const size_t barriers_after_first = fake::g.barriers.size();
    fixture.release(1);

    const BedraySceneFrameInfo idle = fixture.prepare(2, 0x200);
    CHECK(idle.flags == BEDRAY_SCENE_FRAME_IDLE);
    CHECK(idle.tlas == first.tlas);
    CHECK(idle.blas_builds == 0);
    CHECK(idle.blas_refits == 0);
    CHECK(idle.upload_bytes == 0);
    CHECK(fake::g.builds.size() == builds_after_first);
    CHECK(fake::g.copies.size() == copies_after_first);
    CHECK(fake::g.barriers.size() == barriers_after_first);
    fixture.release(2);
}

static void test_transform_update_touches_only_tlas() {
    Fixture fixture(1);
    const Mesh mesh = quad_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_section(1, 1, mesh), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.add_entity(entity_input(2, 1, 1, mesh)), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo first = fixture.prepare(1, 0x300);
    fixture.release(1);
    const size_t builds_before = fake::g.builds.size();
    const size_t copies_before = fake::g.copies.size();

    CHECK_STATUS(fixture.add_entity(entity_input(2, 1, 1, mesh, 1.0f, 2.0f, 3.0f)),
        BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo info = fixture.prepare(2, 0x301);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_TLAS_UPDATED) != 0);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_BLAS_REBUILT) == 0);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_BLAS_REFIT) == 0);
    CHECK(info.blas_builds == 0);
    CHECK(info.blas_refits == 0);
    CHECK(info.upload_bytes == 0);
    CHECK(fake::g.builds.size() == builds_before + 1);
    CHECK(fake::g.copies.size() == copies_before);
    CHECK(info.tlas == first.tlas);
    if (fake::g.builds.size() == builds_before + 1) {
        const fake::BuildRecord& build = fake::g.builds.back();
        CHECK(build.type == VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        CHECK(build.mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR);
        CHECK(build.src == build.dst);
        CHECK(build.src == reinterpret_cast<VkAccelerationStructureKHR>(
            static_cast<uintptr_t>(info.tlas)));
    }

    CHECK(!fake::g.builds.empty());
    if (!fake::g.builds.empty()) {
        std::vector<uint8_t>* instances =
            fake::bytes_at_address(fake::g.builds.back().instance_buffer_address);
        CHECK(instances != nullptr);
        if (instances != nullptr && instances->size() >= 2 * 64) {
            float transform[12] = {};
            std::memcpy(transform, instances->data() + 64, sizeof(transform));
            CHECK(transform[3] == 1.0f);
            CHECK(transform[7] == 2.0f);
            CHECK(transform[11] == 3.0f);
            uint32_t packed = 0;
            std::memcpy(&packed, instances->data() + 64 + 48, sizeof(packed));
            CHECK((packed >> 24) == 0xFF);
        }
    }
    fixture.release(2);

    /* Instance mask definitions can update without touching the BLAS. */
    CHECK_STATUS(fixture.add_entity(entity_input(2, 1, 1, mesh, 1.0f, 2.0f, 3.0f, 0x40)),
        BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo masked = fixture.prepare(3, 0x302);
    CHECK((masked.flags & BEDRAY_SCENE_FRAME_TLAS_UPDATED) != 0);
    CHECK(masked.blas_builds == 0);
    CHECK(masked.blas_refits == 0);
    fixture.release(3);
}

static void test_section_instances_follow_view() {
    Fixture fixture(1);
    const Mesh section_mesh = quad_mesh(0.0f, 0.0f, 0.0f);
    const Mesh entity_mesh = triangle_mesh(4.0f, 0.0f, 0.0f);
    BedraySceneSectionInput section = section_input(1, 1, section_mesh);
    section.origin[0] = 16;
    section.origin[1] = 32;
    section.origin[2] = -8;
    CHECK_STATUS(fixture.add_section_input(section), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.add_entity(entity_input(2, 1, 1, entity_mesh, 5.0f, 6.0f, 7.0f)),
        BEDRAY_SCENE_OK);

    /* set_view validates its arguments and leaves the scene unchanged. */
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_set_view(nullptr, 1.0, 2.0, 3.0, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_set_view(fixture.scene, std::nan(""), 70.25, -3.0, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_set_view(fixture.scene, 100.5, INFINITY, -3.0, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    /* Without a view the section keeps the historical identity placement even
       though its origin is not zero; the entity keeps its own transform. */
    const BedraySceneFrameInfo unset = fixture.prepare(1, 0xC00);
    CHECK((unset.flags & BEDRAY_SCENE_FRAME_TLAS_REBUILT) != 0);
    BedraySceneResources resources = fixture.resources();
    float transform[12] = {};
    read_instance_transform(resources, 0, transform);
    check_identity_rotation(transform);
    CHECK(near_value(transform[3], 0.0));
    CHECK(near_value(transform[7], 0.0));
    CHECK(near_value(transform[11], 0.0));
    read_instance_transform(resources, 1, transform);
    check_identity_rotation(transform);
    CHECK(near_value(transform[3], 5.0));
    CHECK(near_value(transform[7], 6.0));
    CHECK(near_value(transform[11], 7.0));
    fixture.release(1);

    /* A section is placed at translate(origin - view) in the camera-relative
       space the renderer traces; the entity transform is unchanged. */
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_set_view(fixture.scene, 100.5, 70.25, -3.0, &g_error),
        BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo placed = fixture.prepare(2, 0xC01);
    CHECK((placed.flags & BEDRAY_SCENE_FRAME_TLAS_UPDATED) != 0);
    CHECK(placed.blas_builds == 0);
    CHECK(placed.blas_refits == 0);
    resources = fixture.resources();
    read_instance_transform(resources, 0, transform);
    check_identity_rotation(transform);
    CHECK(near_value(transform[3], 16.0 - 100.5));
    CHECK(near_value(transform[7], 32.0 - 70.25));
    CHECK(near_value(transform[11], -8.0 - (-3.0)));
    read_instance_transform(resources, 1, transform);
    check_identity_rotation(transform);
    CHECK(near_value(transform[3], 5.0));
    CHECK(near_value(transform[7], 6.0));
    CHECK(near_value(transform[11], 7.0));
    fixture.release(2);

    /* A different eye forces the slot to rewrite its instance buffer and take
       the in-place TLAS update path, with no BLAS work at all. The null error
       pointer must be tolerated. */
    CHECK_STATUS(bedray_scene_set_view(fixture.scene, 110.0, 72.5, -4.0, nullptr),
        BEDRAY_SCENE_OK);
    const size_t builds_before = fake::g.builds.size();
    const BedraySceneFrameInfo moved = fixture.prepare(3, 0xC02);
    CHECK((moved.flags & BEDRAY_SCENE_FRAME_TLAS_UPDATED) != 0);
    CHECK((moved.flags & BEDRAY_SCENE_FRAME_TLAS_REBUILT) == 0);
    CHECK(moved.blas_builds == 0);
    CHECK(moved.blas_refits == 0);
    CHECK(moved.upload_bytes == 0);
    CHECK(moved.tlas == placed.tlas);
    CHECK(fake::g.builds.size() == builds_before + 1);
    if (fake::g.builds.size() == builds_before + 1) {
        const fake::BuildRecord& build = fake::g.builds.back();
        CHECK(build.type == VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        CHECK(build.mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR);
        CHECK(build.src == build.dst);
    }
    resources = fixture.resources();
    read_instance_transform(resources, 0, transform);
    check_identity_rotation(transform);
    CHECK(near_value(transform[3], 16.0 - 110.0));
    CHECK(near_value(transform[7], 32.0 - 72.5));
    CHECK(near_value(transform[11], -8.0 - (-4.0)));
    read_instance_transform(resources, 1, transform);
    CHECK(near_value(transform[3], 5.0));
    CHECK(near_value(transform[7], 6.0));
    CHECK(near_value(transform[11], 7.0));

    /* The slot remembers the view it prepared with, so an unchanged view
       returns to an idle frame. */
    fixture.release(3);
    const BedraySceneFrameInfo idle = fixture.prepare(4, 0xC03);
    CHECK(idle.flags == BEDRAY_SCENE_FRAME_IDLE);
    CHECK(idle.blas_builds == 0);
    CHECK(idle.blas_refits == 0);
    fixture.release(4);
}

static void test_refit_copies_pinned_geometry_and_updates_after_completion() {
    Fixture fixture(2);
    const Mesh mesh = triangle_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(9, 1, 1, mesh)), BEDRAY_SCENE_OK);
    fixture.prepare(1, 0x400);
    CHECK(fake::g.builds.size() == 2);
    const fake::BuildRecord initial = fake::g.builds[0];
    const uint64_t initial_vertex = initial.vertex_address;

    const Mesh moved = triangle_mesh(0.5f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(9, 2, 1, moved)), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo copied = fixture.prepare(2, 0x401);
    CHECK(copied.deferred_updates == 0);
    CHECK(copied.blas_refits == 0);
    CHECK(copied.blas_builds == 1);
    CHECK((copied.flags & BEDRAY_SCENE_FRAME_BLAS_COPY_ON_WRITE) != 0);
    CHECK(fake::g.builds.size() == 4);
    const fake::BuildRecord replacement = fake::g.builds[2];
    CHECK(replacement.mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
    CHECK(replacement.dst != initial.dst);
    CHECK(fake::find_copy(moved.vertex_bytes()) >= 0);
    CHECK(fake::accel_is_alive(initial.dst));
    CHECK(fake::find_buffer_by_address(initial_vertex) != nullptr);
    uint64_t reference = 0;
    auto* instances = fake::bytes_at_address(fixture.resources().instance_buffer_address);
    CHECK(instances != nullptr);
    if (instances != nullptr) std::memcpy(&reference, instances->data() + 56, sizeof(reference));
    CHECK(reference == fake::find_accel(replacement.dst)->address);

    fixture.release(1);
    fixture.release(2);
    CHECK(fake::find_buffer_by_address(initial_vertex) == nullptr);
    CHECK(fake::accel_is_alive(initial.dst)); // cached slot 0 remains a valid TLAS UPDATE source.

    const Mesh latest = triangle_mesh(1.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(9, 3, 1, latest)), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo refit = fixture.prepare(3, 0x402);
    CHECK(refit.blas_refits == 1);
    CHECK(refit.blas_builds == 0);
    CHECK(refit.deferred_updates == 0);
    CHECK((refit.flags & BEDRAY_SCENE_FRAME_TLAS_UPDATED) != 0);
    CHECK(fake::g.builds.size() == 6);
    CHECK(fake::g.builds[4].mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR);
    CHECK(fake::g.builds[4].src == replacement.dst && fake::g.builds[4].dst == replacement.dst);
    CHECK(fake::g.builds[5].mode == VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR);
    CHECK(fake::accel_is_alive(initial.dst)); // still read by frame 3's TLAS source.
    fixture.release(3);
    CHECK(!fake::accel_is_alive(initial.dst));
    CHECK(fake::accel_is_alive(replacement.dst));
}

static void test_nonfinite_activation_changes_rebuild_instead_of_refitting() {
    Fixture fixture(1);
    const Mesh mesh = triangle_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(8, 1, 1, mesh)), BEDRAY_SCENE_OK);
    fixture.prepare(1, 0x410); fixture.release(1);
    Mesh inactive = mesh;
    inactive.vertices[0] = std::nanf("");
    CHECK_STATUS(fixture.add_entity(entity_input(8, 2, 1, inactive)), BEDRAY_SCENE_OK);
    CHECK(fixture.prepare(2, 0x411).blas_builds == 1); fixture.release(2);
    CHECK_STATUS(fixture.add_entity(entity_input(8, 3, 1, mesh)), BEDRAY_SCENE_OK);
    CHECK(fixture.prepare(3, 0x412).blas_builds == 1); fixture.release(3);
    const Mesh moved = triangle_mesh(0.5f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(8, 4, 1, moved)), BEDRAY_SCENE_OK);
    CHECK(fixture.prepare(4, 0x413).blas_refits == 1); fixture.release(4);
}

static void test_replacement_blas_relinks_a_live_cached_tlas_without_a_build() {
    Fixture fixture(1);
    const Mesh first = triangle_mesh(0, 0, 0);
    CHECK_STATUS(fixture.add_entity(entity_input(3, 1, 1, first)), BEDRAY_SCENE_OK);
    const auto initial_frame = fixture.prepare(1, 0x420);
    const auto initial_blas = fake::g.builds.front().dst;
    fixture.release(1);
    const Mesh replacement = quad_mesh(0, 0, 0);
    CHECK_STATUS(fixture.add_entity(entity_input(3, 2, 2, replacement)), BEDRAY_SCENE_OK);
    const auto relinked = fixture.prepare(2, 0x421);
    CHECK(relinked.blas_builds == 1 && relinked.blas_refits == 0);
    CHECK((relinked.flags & BEDRAY_SCENE_FRAME_TLAS_UPDATED) != 0);
    CHECK(relinked.tlas == initial_frame.tlas);
    CHECK(fake::accel_is_alive(initial_blas));
    fixture.release(2);
    CHECK(!fake::accel_is_alive(initial_blas));
}

static void test_pipelined_cow_tlas_updates_keep_dependencies_bounded() {
    Fixture fixture(2);
    for (uint64_t frame = 1; frame <= 20; ++frame) {
        if (frame > 2) fixture.release(frame - 2);
        const Mesh moved = triangle_mesh(static_cast<float>(frame), 0, 0);
        CHECK_STATUS(fixture.add_entity(entity_input(9, frame, 1, moved)), BEDRAY_SCENE_OK);
        const auto info = fixture.prepare(frame, 0x430 + frame);
        CHECK(info.blas_builds == 1 && info.blas_refits == 0 && info.deferred_updates == 0);
        if (frame > 1) CHECK((info.flags & BEDRAY_SCENE_FRAME_BLAS_COPY_ON_WRITE) != 0);
        if (frame > 2) CHECK((info.flags & BEDRAY_SCENE_FRAME_TLAS_UPDATED) != 0);
        CHECK(fake::g.accels.size() <= 6); // Two cached TLAS, two current BLAS, two in-flight source BLAS.
    }
    fixture.release(19); fixture.release(20);
    CHECK(fake::g.accels.size() == 4); // Only the two reusable, valid TLAS/BLAS pairs remain.
}

static void test_rebuild_without_update_capability_retires_after_frame() {
    Fixture fixture(2, BEDRAY_SCENE_CAP_ACCELERATION_STRUCTURE
        | BEDRAY_SCENE_CAP_DEVICE_ADDRESS);
    const Mesh mesh = triangle_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(3, 1, 1, mesh)), BEDRAY_SCENE_OK);
    fixture.prepare(1, 0x500);
    CHECK(fake::g.builds.size() == 2);
    const fake::BuildRecord initial = fake::g.builds[0];
    CHECK((initial.flags & VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR) == 0);
    fake::BufferState* old_vertex = fake::find_buffer_by_address(initial.vertex_address);
    CHECK(old_vertex != nullptr);
    const uint64_t old_address = initial.vertex_address;

    const Mesh bigger = quad_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(3, 2, 2, bigger)), BEDRAY_SCENE_OK);

    const BedraySceneFrameInfo rebuilt = fixture.prepare(2, 0x501);
    CHECK(rebuilt.blas_builds == 1);
    CHECK(rebuilt.blas_refits == 0);
    CHECK(rebuilt.deferred_updates == 0);
    CHECK((rebuilt.flags & BEDRAY_SCENE_FRAME_BLAS_REBUILT) != 0);
    CHECK((rebuilt.flags & BEDRAY_SCENE_FRAME_TLAS_REBUILT) != 0);
    CHECK(fake::find_buffer_by_address(old_address) != nullptr);
    CHECK(fake::accel_is_alive(initial.dst));

    fixture.release(1);
    CHECK(fake::find_buffer_by_address(old_address) == nullptr);
    CHECK(!fake::accel_is_alive(initial.dst));
    fixture.release(2);
}

static void test_update_copies_inputs_and_coalesces_pending() {
    Fixture fixture(1);
    Mesh mesh = quad_mesh(0.0f, 0.0f, 0.0f);
    const std::vector<uint8_t> original = mesh.vertex_bytes();
    CHECK_STATUS(fixture.add_section(10, 1, mesh), BEDRAY_SCENE_OK);

    mesh.vertices[0] = 99.0f;
    mesh.indices[0] = 3;
    CHECK_STATUS(fixture.add_section(10, 1, mesh), BEDRAY_SCENE_OK);

    const BedraySceneFrameInfo info = fixture.prepare(1, 0xB00);
    CHECK(info.blas_builds == 1);
    CHECK(fake::find_copy(original) >= 0);
    CHECK(fake::find_copy(mesh.vertex_bytes()) < 0);
    size_t matching_copies = 0;
    for (const fake::CopyRecord& copy : fake::g.copies) {
        if (copy.payload == original) {
            ++matching_copies;
        }
    }
    CHECK(matching_copies == 1);
    fixture.release(1);
}

static void test_table_coalescing_and_clear() {
    Fixture fixture(1);
    std::vector<uint8_t> first(32, 0xAA);
    std::vector<uint8_t> second(48, 0xBB);
    std::vector<uint8_t> ignored(64, 0xCC);
    CHECK_STATUS(fixture.update_table(BEDRAY_SCENE_TABLE_MATERIAL, 0, first), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.update_table(BEDRAY_SCENE_TABLE_MATERIAL, 6, second), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.update_table(BEDRAY_SCENE_TABLE_MATERIAL, 6, ignored), BEDRAY_SCENE_OK);

    const BedraySceneFrameInfo info = fixture.prepare(1, 0x600);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_TABLES_UPLOADED) != 0);
    CHECK((info.flags & BEDRAY_SCENE_FRAME_TLAS_REBUILT) == 0);
    CHECK(info.instance_count == 0);
    CHECK(info.tlas == 0);
    BedraySceneResources resources = fixture.resources();
    CHECK(resources.material_table_bytes == second.size());
    CHECK(fake::find_copy(second) >= 0);
    CHECK(fake::find_copy(ignored) < 0);
    CHECK(fake::g.copies.size() == 2); // material upload plus zeroed section descriptor
    std::vector<uint8_t>* table_bytes = fake::bytes_at_address(resources.material_table_address);
    CHECK(bytes_start_with(table_bytes, second));
    fixture.release(1);

    const size_t copies_before = fake::g.copies.size();
    CHECK_STATUS(fixture.update_table(BEDRAY_SCENE_TABLE_MATERIAL, 6, ignored), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo idle = fixture.prepare(2, 0x601);
    CHECK(idle.flags == BEDRAY_SCENE_FRAME_IDLE);
    CHECK(fake::g.copies.size() == copies_before);
    fixture.release(2);

    CHECK_STATUS(fixture.update_table(BEDRAY_SCENE_TABLE_MATERIAL, 7, {}), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo cleared = fixture.prepare(3, 0x602);
    CHECK((cleared.flags & BEDRAY_SCENE_FRAME_TABLES_UPLOADED) != 0);
    resources = fixture.resources();
    CHECK(resources.material_table_buffer == 0);
    CHECK(resources.material_table_bytes == 0);
    fixture.release(3);
}

static void test_stale_in_use_and_destroy() {
    Fixture fixture(1);
    const Mesh mesh = triangle_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(1, 1, 1, mesh)), BEDRAY_SCENE_OK);
    fixture.prepare(1, 0x700);

    CHECK_STATUS(fixture.prepare_status(1, 0x701), BEDRAY_SCENE_ERROR_STALE);
    CHECK_STATUS(fixture.prepare_status(2, 0x700), BEDRAY_SCENE_ERROR_IN_USE);
    CHECK_STATUS(fixture.prepare_status(2, 0x701), BEDRAY_SCENE_ERROR_IN_USE);
    CHECK_STATUS(bedray_scene_destroy(fixture.scene), BEDRAY_SCENE_ERROR_IN_USE);

    fixture.release(1);
    CHECK_STATUS(bedray_scene_destroy(fixture.scene), BEDRAY_SCENE_OK);
    fixture.scene = nullptr;
    CHECK(fake::g.buffers.empty());
    CHECK(fake::g.memories.empty());
    CHECK(fake::g.accels.empty());
}

static void test_update_is_transactional() {
    Fixture fixture(1);
    const Mesh mesh = quad_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_section(1, 1, mesh), BEDRAY_SCENE_OK);

    const Mesh second = quad_mesh(2.0f, 0.0f, 0.0f);
    BedraySceneSectionInput sections[1] = { section_input(2, 1, second) };
    BedraySceneEntityInput entity = entity_input(3, 1, 1, mesh);
    entity.transform[0] = std::nanf("");
    BedraySceneUpdate update{};
    update.abi_version = BEDRAY_SCENE_ABI_VERSION;
    update.sections = sections;
    update.section_count = 1;
    update.entities = &entity;
    update.entity_count = 1;
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_update(fixture.scene, &update, &g_error),
        BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    const BedraySceneFrameInfo info = fixture.prepare(1, 0x800);
    CHECK(info.section_count == 1);
    CHECK(info.instance_count == 1);
    CHECK(fake::g.builds.size() == 2);
    fixture.release(1);
}

static void test_capacity_limits() {
    Fixture fixture(1);
    const Mesh mesh = quad_mesh(0.0f, 0.0f, 0.0f);
    BedraySceneSectionInput sections[17];
    for (uint32_t i = 0; i < 17; ++i) {
        sections[i] = section_input(i + 1, 1, mesh);
    }
    BedraySceneUpdate update{};
    update.abi_version = BEDRAY_SCENE_ABI_VERSION;
    update.sections = sections;
    update.section_count = 17;
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_update(fixture.scene, &update, &g_error),
        BEDRAY_SCENE_ERROR_LIMIT);
    CHECK(fake::g.buffers.empty());

    update.section_count = 1;
    CHECK_STATUS(bedray_scene_update(fixture.scene, &update, &g_error), BEDRAY_SCENE_OK);
    update.section_count = 17;
    CHECK_STATUS(bedray_scene_update(fixture.scene, &update, &g_error),
        BEDRAY_SCENE_ERROR_LIMIT);
}

static void test_remove_entity_clears_tlas_and_retires_now() {
    Fixture fixture(1);
    const Mesh mesh = triangle_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(1, 1, 1, mesh)), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo first = fixture.prepare(1, 0x900);
    fixture.release(1);
    CHECK(fake::g.builds.size() == 2);
    const VkAccelerationStructureKHR blas = fake::g.builds[0].dst;
    const uint64_t vertex_address = fake::g.builds[0].vertex_address;

    const uint64_t removed = 1;
    BedraySceneUpdate update{};
    update.abi_version = BEDRAY_SCENE_ABI_VERSION;
    update.removed_entities = &removed;
    update.removed_entity_count = 1;
    g_error = BedraySceneError{};
    CHECK_STATUS(bedray_scene_update(fixture.scene, &update, &g_error), BEDRAY_SCENE_OK);
    CHECK(fake::accel_is_alive(blas));
    CHECK(fake::find_buffer_by_address(vertex_address) == nullptr);

    const BedraySceneFrameInfo cleared = fixture.prepare(2, 0x901);
    CHECK(cleared.instance_count == 0);
    CHECK(cleared.tlas == 0);
    CHECK((cleared.flags & BEDRAY_SCENE_FRAME_TLAS_REBUILT) != 0);
    CHECK(first.tlas != 0);
    fixture.release(2);
    CHECK(!fake::accel_is_alive(blas));
}

static void test_device_loss_poisons_scene() {
    Fixture fixture(1);
    const Mesh mesh = triangle_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_entity(entity_input(1, 1, 1, mesh)), BEDRAY_SCENE_OK);
    fake::g.fail_create_buffer_countdown = 0;

    BedraySceneFrameInfo info{};
    CHECK_STATUS(fixture.prepare_status(1, 0xA00, &info), BEDRAY_SCENE_ERROR_DEVICE_LOST);
    CHECK(g_error.status == BEDRAY_SCENE_ERROR_DEVICE_LOST);

    CHECK_STATUS(fixture.prepare_status(2, 0xA01), BEDRAY_SCENE_ERROR_DEVICE_LOST);
    fake::g.fail_create_buffer_countdown = -1;
}

static Mesh section_quad_mesh(uint32_t quad_count) {
    Mesh mesh;
    for (uint32_t quad = 0; quad < quad_count; ++quad) {
        const float x = static_cast<float>(quad);
        const float vertices[12] = {
            x, 0.0f, 0.0f,
            x + 1.0f, 0.0f, 0.0f,
            x + 1.0f, 1.0f, 0.0f,
            x, 1.0f, 0.0f,
        };
        mesh.vertices.insert(mesh.vertices.end(), vertices, vertices + 12);
        /* Index data is local to the start of the pass range that contains the
           quad. With one quad per range this is the section-local
           (0,1,2, 2,3,0) pattern named by the contract. */
        mesh.indices.insert(mesh.indices.end(), { 0, 1, 2, 0, 2, 3 });
    }
    return mesh;
}

static void test_section_contract_validation() {
    Fixture fixture(1);
    CHECK_STATUS(fixture.remove_section(1234), BEDRAY_SCENE_ERROR_UNKNOWN_ID);
    const Mesh quad = quad_mesh(0.0f, 0.0f, 0.0f);
    const Mesh two_quads = section_quad_mesh(2);
    const Mesh triangle = triangle_mesh(0.0f, 0.0f, 0.0f);

    BedraySceneSectionInput input = section_input(1, 1, quad);
    input.quad_start[0] = 1;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, two_quads);
    input.geometry_count = 2;
    input.quad_start[0] = 0;
    input.quad_start[1] = 2;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, two_quads);
    input.geometry_count = 2;
    input.quad_start[0] = 0;
    input.quad_start[1] = 1;
    input.pass_bits = 1u; /* first range pass 1, second range pass 0 */
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, two_quads);
    input.geometry_count = 2;
    input.quad_start[0] = 0;
    input.quad_start[1] = 0;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.pass_bits = 3u; /* pass 3 does not exist */
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.pass_bits = 1u << 2u; /* bits beyond geometry_count */
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.geometry_count = 0;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.geometry_count = BEDRAY_SCENE_MAX_SECTION_GEOMETRIES + 1u;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.flags = 0x2u;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_UNSUPPORTED);

    input = section_input(1, 1, quad);
    input.flags = BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, triangle);
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.index_count = 3;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.index_type = BEDRAY_SCENE_INDEX_NONE;
    input.index_count = 0;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    input = section_input(1, 1, quad);
    input.vertex_stride = 8;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);

    /* Rejected updates are transactional: nothing was created or committed. */
    BedraySceneResources resources{};
    CHECK_STATUS(bedray_scene_query_resources(fixture.scene, &resources), BEDRAY_SCENE_OK);
    CHECK(resources.section_count == 0);
    CHECK(resources.instance_count == 0);
    CHECK(fake::g.buffers.empty());
}

static void test_section_pass_ranges() {
    Fixture fixture(1);
    const Mesh mesh = section_quad_mesh(3);
    BedraySceneSectionInput input = section_input(11, 1, mesh);
    input.geometry_count = 3;
    input.quad_start[0] = 0;
    input.quad_start[1] = 1;
    input.quad_start[2] = 2;
    input.pass_bits = (1u << 2u) | (2u << 4u); /* solid, cutout, translucent */
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_OK);

    const BedraySceneFrameInfo info = fixture.prepare(1, 0xB10);
    CHECK(info.blas_builds == 1);
    CHECK(fake::g.builds.size() == 2);

    /* The size query must ask for one geometry per non-empty pass range. */
    CHECK(fake::g.size_queries.size() == 2);
    if (fake::g.size_queries.size() == 2) {
        const fake::SizeQueryRecord& blas = fake::g.size_queries[0];
        CHECK(blas.type == VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        CHECK(blas.geometry_count == 3);
        CHECK(blas.max_counts.size() == 3);
        if (blas.max_counts.size() == 3) {
            CHECK(blas.max_counts[0] == 2);
            CHECK(blas.max_counts[1] == 2);
            CHECK(blas.max_counts[2] == 2);
        }
        const fake::SizeQueryRecord& tlas = fake::g.size_queries[1];
        CHECK(tlas.type == VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        CHECK(tlas.geometry_count == 1);
        CHECK(tlas.max_counts.size() == 1);
        if (tlas.max_counts.size() == 1) {
            CHECK(tlas.max_counts[0] == 1);
        }
    }

    if (fake::g.builds.size() == 2) {
        const fake::BuildRecord& build = fake::g.builds[0];
        CHECK(build.geometry_count == 3);
        CHECK(build.geometries.size() == 3);
        if (build.geometries.size() == 3) {
            const uint32_t expected_offsets[3] = { 0, 12, 24 };
            const uint32_t expected_first[3] = { 0, 4, 8 };
            for (uint32_t g = 0; g < 3; ++g) {
                const fake::BuildGeometryRecord& range = build.geometries[g];
                CHECK(range.primitive_count == 2);
                CHECK(range.primitive_offset == expected_offsets[g]);
                CHECK(range.first_vertex == expected_first[g]);
                CHECK(range.max_vertex == 11);
                CHECK(range.vertex_stride == 12);
                CHECK(range.index_type == VK_INDEX_TYPE_UINT16);
                CHECK(range.vertex_address == build.vertex_address);
                CHECK(range.index_address == build.index_address);
                const bool opaque = (range.flags & VK_GEOMETRY_OPAQUE_BIT_KHR) != 0;
                CHECK(opaque == (g == 0));
            }
        }
    }

    const BedraySceneResources resources = fixture.resources();
    const BedraySceneSectionRecord record = read_section_record(resources, 0);
    CHECK(record.meta[0] == 12);
    CHECK(record.meta[1] == 3);
    CHECK(record.meta[2] == 0);
    CHECK(record.meta[3] == 0);
    CHECK(record.origin[0] == 0);
    CHECK(record.origin[1] == 0);
    CHECK(record.origin[2] == 0);
    CHECK(record.origin[3] != 0);
    CHECK(record.vertex_address == fake::g.builds[0].vertex_address);
    for (uint32_t g = 0; g < 3; ++g) {
        CHECK(record.ranges[g].first_quad == g);
        CHECK((record.ranges[g].quad_count_pass & 0xFFFFu) == 1);
        CHECK(((record.ranges[g].quad_count_pass >> 16u) & 0x3u) == g);
        CHECK(((record.ranges[g].quad_count_pass >> 18u) & 0x3FFFu) == 0);
    }
    /* The section instance points at its table slot, not a compact index. */
    CHECK(instance_custom_index(resources, 0) == 0);
    const int32_t identity = record.origin[3];

    fixture.release(1);

    /* A content update of the same section rebuilds the table but must keep
       the identity hash stable. */
    CHECK_STATUS(fixture.add_section(11, 2, mesh), BEDRAY_SCENE_OK);
    fixture.prepare(2, 0xB11);
    const BedraySceneSectionRecord rebuilt = read_section_record(fixture.resources(), 0);
    CHECK(rebuilt.origin[3] == identity);
    fixture.release(2);
}

static void test_tlas_custom_indices_follow_table_slots() {
    Fixture fixture(1);
    CHECK_STATUS(fixture.add_section(30, 1, quad_mesh(0.0f, 0.0f, 0.0f)), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.add_section(10, 1, quad_mesh(1.0f, 0.0f, 0.0f)), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.add_section(20, 1, quad_mesh(2.0f, 0.0f, 0.0f)), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.add_entity(entity_input(5, 1, 1, triangle_mesh(4.0f, 0.0f, 0.0f))),
        BEDRAY_SCENE_OK);

    const BedraySceneFrameInfo info = fixture.prepare(1, 0xB20);
    CHECK(info.instance_count == 4);
    /* Builds: sections in id order (10, 20, 30), the entity, then the TLAS. */
    CHECK(fake::g.builds.size() == 5);
    const BedraySceneResources resources = fixture.resources();
    CHECK(resources.section_table_bytes == 3 * BEDRAY_SCENE_SECTION_RECORD_BYTES);

    if (fake::g.builds.size() == 5) {
        for (size_t slot = 0; slot < 3; ++slot) {
            const BedraySceneSectionRecord record = read_section_record(resources, slot);
            CHECK(record.vertex_address == fake::g.builds[slot].vertex_address);
            CHECK(instance_custom_index(resources, slot) == slot);
            CHECK(record.origin[3] != 0);
            CHECK(record.origin[3] != static_cast<int32_t>(slot));
        }
        CHECK(instance_custom_index(resources, 3) == 0x800000u);
    }
    const int32_t identity20 = read_section_record(resources, 1).origin[3];
    fixture.release(1);

    /* Removing the lowest id compacts the slots: id 20 moves from slot 1 to
       slot 0, keeps its identity hash, and the instances follow the table. */
    CHECK_STATUS(fixture.remove_section(10), BEDRAY_SCENE_OK);
    const BedraySceneFrameInfo after = fixture.prepare(2, 0xB21);
    CHECK(after.instance_count == 3);
    const BedraySceneResources compacted = fixture.resources();
    CHECK(compacted.section_table_bytes == 2 * BEDRAY_SCENE_SECTION_RECORD_BYTES);
    const BedraySceneSectionRecord record20 = read_section_record(compacted, 0);
    CHECK(record20.origin[3] == identity20);
    CHECK(instance_custom_index(compacted, 0) == 0);
    CHECK(instance_custom_index(compacted, 1) == 1);
    CHECK(instance_custom_index(compacted, 2) == 0x800000u);
    fixture.release(2);
}

static void test_geometry_vertex_section_stride() {
    Fixture fixture(1);
    std::vector<uint8_t> vertices(4u * BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES, 0);
    for (int vertex = 0; vertex < 4; ++vertex) {
        const float position[3] = {
            static_cast<float>(vertex & 1), static_cast<float>((vertex >> 1) & 1), 0.0f };
        std::memcpy(vertices.data() + vertex * BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES,
            position, sizeof(position));
    }
    std::vector<uint16_t> indices = { 0, 1, 2, 0, 2, 3 };

    BedraySceneSectionInput input{};
    input.section_id = 21;
    input.geometry_revision = 1;
    input.vertices = vertices.data();
    input.vertex_count = 4;
    input.vertex_stride = BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES;
    input.index_type = BEDRAY_SCENE_INDEX_UINT16;
    input.indices = indices.data();
    input.index_count = indices.size();
    input.geometry_count = 1;
    input.quad_start[0] = 0;
    input.flags = BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX;
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_OK);

    fixture.prepare(1, 0xB30);
    CHECK(fake::g.builds.size() == 2);
    if (fake::g.builds.size() == 2) {
        CHECK(fake::g.builds[0].geometry_count == 1);
        if (fake::g.builds[0].geometries.size() == 1) {
            const fake::BuildGeometryRecord& range = fake::g.builds[0].geometries[0];
            CHECK(range.vertex_stride == BEDRAY_SCENE_GEOMETRY_VERTEX_BYTES);
            CHECK(range.max_vertex == 3);
            CHECK(range.primitive_count == 2);
        }
    }
    CHECK(fake::find_copy(vertices) >= 0);
    const BedraySceneSectionRecord record = read_section_record(fixture.resources(), 0);
    CHECK(record.meta[0] == 4);
    CHECK(record.meta[2] == BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX);
    fixture.release(1);
}

static void test_section_quad_limit() {
    Fixture fixture(1, 0, 8u << 20);
    Mesh mesh;
    mesh.vertices.assign(static_cast<size_t>(0x10000u) * 4u * 3u, 0.0f);
    mesh.indices.assign(static_cast<size_t>(0x10000u) * 6u, 0);
    BedraySceneSectionInput input = section_input(1, 1, mesh);
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_ERROR_LIMIT);
    CHECK(fake::g.buffers.empty());

    /* Exactly 65535 quads is the supported maximum: the record stores the
       range count in 16 bits. The boundary is validated at update time; it is
       not prepared here to keep the fake allocations small. */
    mesh.vertices.assign(static_cast<size_t>(0xFFFFu) * 4u * 3u, 0.0f);
    mesh.indices.assign(static_cast<size_t>(0xFFFFu) * 6u, 0);
    input = section_input(1, 1, mesh);
    CHECK_STATUS(fixture.add_section_input(input), BEDRAY_SCENE_OK);
    CHECK(fake::g.buffers.empty());
}

static void test_zero_device_address_is_rejected() {
    Fixture fixture(1);
    const Mesh mesh = quad_mesh(0.0f, 0.0f, 0.0f);
    CHECK_STATUS(fixture.add_section(1, 1, mesh), BEDRAY_SCENE_OK);
    fake::g.zero_device_address = true;
    CHECK_STATUS(fixture.prepare_status(1, 0xF01), BEDRAY_SCENE_ERROR_UNSUPPORTED);
    CHECK(fake::g.builds.empty());
}

static void test_producer_command_can_be_reused_while_its_generation_is_pinned() {
    Fixture fixture(2);
    const Mesh mesh = quad_mesh(0, 0, 0);
    CHECK_STATUS(fixture.add_section(1, 1, mesh), BEDRAY_SCENE_OK);
    const auto first = fixture.prepare(1, 0xDA01);
    CHECK_STATUS(fixture.prepare_status(2, 0xDA01), BEDRAY_SCENE_ERROR_IN_USE);
    CHECK_STATUS(bedray_scene_retire_producer(fixture.scene, 1, &g_error), BEDRAY_SCENE_OK);
    CHECK_STATUS(bedray_scene_destroy(fixture.scene), BEDRAY_SCENE_ERROR_IN_USE);
    const auto second = fixture.prepare(2, 0xDA01);
    CHECK(first.tlas != 0 && second.tlas != 0 && first.tlas != second.tlas);
    fixture.release(1);
    CHECK_STATUS(bedray_scene_destroy(fixture.scene), BEDRAY_SCENE_ERROR_IN_USE);
    fixture.release(2);
}

static void test_discarded_writes_cannot_be_reused() {
    Fixture fixture(2);
    const Mesh mesh = quad_mesh(0, 0, 0);
    CHECK_STATUS(fixture.add_section(1, 1, mesh), BEDRAY_SCENE_OK);
    fixture.prepare(1, 0xDA01);
    fixture.prepare(2, 0xDA02);
    CHECK_STATUS(bedray_scene_discard_frame(fixture.scene, 99, &g_error), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);
    CHECK_STATUS(bedray_scene_discard_frame(fixture.scene, 2, &g_error), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.prepare_status(3, 0xDA03), BEDRAY_SCENE_ERROR_INTERNAL);
    BedraySceneResources resources{};
    CHECK_STATUS(bedray_scene_query_resources(fixture.scene, &resources), BEDRAY_SCENE_ERROR_INTERNAL);
    CHECK(resources.tlas == 0 && resources.section_table_buffer == 0);
    CHECK_STATUS(fixture.add_section(1, 2, mesh), BEDRAY_SCENE_ERROR_INTERNAL);
    CHECK_STATUS(bedray_scene_destroy(fixture.scene), BEDRAY_SCENE_ERROR_IN_USE);
    fixture.release(1); // completion/release remains available on a poisoned scene
}

static void test_native_residency_and_committed_section_revisions() {
    Fixture fixture(2);
    BedraySceneCandidate candidates[] = {{2, 10, 5, 0, 0, 1}, {1, 20, 0, 0, 0, 1}, {3, 30, 0, 5, 0, 1}};
    uint64_t requests[2]{}, committed[4]{};
    BedraySceneSelection selection{BEDRAY_SCENE_ABI_VERSION, 1, candidates, 3, 0, 0, 0, 6, 1, 1, 1,
        100, requests, 0, 0};
    CHECK_STATUS(bedray_scene_select_sections(fixture.scene, &selection, &g_error), BEDRAY_SCENE_OK);
    CHECK(selection.pending == 2 && selection.requested == 0); // no budget for either mesh
    selection.refresh = 0; selection.count = 0; selection.budget_bytes = 0;
    CHECK_STATUS(bedray_scene_select_sections(fixture.scene, &selection, &g_error), BEDRAY_SCENE_OK);
    CHECK(selection.requested == 1 && requests[0] == 1); // camera-near first
    const Mesh mesh = quad_mesh(0, 0, 0);
    CHECK_STATUS(fixture.add_section(1, 20, mesh), BEDRAY_SCENE_OK);
    CHECK(bedray_scene_section_ids(fixture.scene, committed, 2) == 0);
    fixture.prepare(1, 0xDE01);
    CHECK(bedray_scene_section_ids(fixture.scene, committed, 2) == 1);
    CHECK(committed[0] == 1 && committed[1] == 20);
    const auto epoch = fixture.resources().section_epoch;
    selection.x = 5; selection.radius_xz = 1;
    CHECK_STATUS(bedray_scene_select_sections(fixture.scene, &selection, &g_error), BEDRAY_SCENE_OK);
    CHECK(selection.requested == 1 && requests[0] == 2);
    CHECK(bedray_scene_section_ids(fixture.scene, committed, 2) == 0);
    CHECK_STATUS(fixture.add_section(2, 10, mesh), BEDRAY_SCENE_OK);
    fixture.prepare(2, 0xDE02); // first frame remains pinned while the scene advances
    CHECK(fixture.resources().section_epoch > epoch);
    CHECK(bedray_scene_section_ids(fixture.scene, committed, 2) == 1);
    CHECK(committed[0] == 2 && committed[1] == 10);
    fixture.release(1);
    fixture.release(2);
}

static void test_near_section_can_displace_far_section_under_budget_pressure() {
    Fixture fixture(2);
    BedraySceneCandidate candidates[] = {{1, 1, 5, 0, 0, 1}, {2, 1, 4, 0, 0, 1}, {3, 1, 0, 0, 0, 1}};
    const Mesh mesh = quad_mesh(0, 0, 0);
    CHECK_STATUS(fixture.add_section(1, 1, mesh), BEDRAY_SCENE_OK);
    CHECK_STATUS(fixture.add_section(2, 1, mesh), BEDRAY_SCENE_OK);
    fixture.prepare(1, 0xDE03);
    uint64_t request = 0, committed[4]{};
    BedraySceneSelection selection{BEDRAY_SCENE_ABI_VERSION, 1, candidates, 3, 0, 0, 0, 6, 1, 1, 1,
        8192, &request, 0, 0};
    CHECK_STATUS(bedray_scene_select_sections(fixture.scene, &selection, &g_error), BEDRAY_SCENE_OK);
    CHECK(selection.requested == 0);
    CHECK(bedray_scene_section_ids(fixture.scene, committed, 2) == 1 && committed[0] == 2);
    selection.refresh = 0; selection.count = 0;
    CHECK_STATUS(bedray_scene_select_sections(fixture.scene, &selection, &g_error), BEDRAY_SCENE_OK);
    CHECK(selection.requested == 0); // previous retirement is still pinned; do not evict another section
    CHECK(bedray_scene_section_ids(fixture.scene, committed, 2) == 1 && committed[0] == 2);
    fixture.release(1);
    CHECK_STATUS(bedray_scene_select_sections(fixture.scene, &selection, &g_error), BEDRAY_SCENE_OK);
    CHECK(selection.requested == 1 && request == 3);
}

static void test_packed_entity_streams_share_the_blas_generation() {
    Fixture fixture(2);
    const Mesh mesh = triangle_mesh(0, 0, 0);
    float previous[9] = {10, 20, 30};
    uint32_t attributes[12] = {0xDEADBEEF};
    auto input = entity_input(42, 1, 1, mesh);
    uint32_t indices[3] = {0, 1, 2};
    input.indices = indices;
    input.index_type = BEDRAY_SCENE_INDEX_UINT32;
    input.index_count = 3;
    input.previous_positions = previous;
    CHECK_STATUS(fixture.add_entity(input), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);
    input.attributes = attributes;
    CHECK_STATUS(fixture.add_entity(input), BEDRAY_SCENE_OK);
    fixture.prepare(1, 0xDE11);
    const auto first = fixture.resources();
    CHECK(first.entity_buffer != 0 && first.entity_positions_bytes == 36 && first.entity_indices_bytes == 12);
    CHECK(first.entity_indices_offset >= 36 && first.entity_previous_offset > first.entity_indices_offset
        && first.entity_attributes_offset > first.entity_previous_offset);
    input.geometry_revision = 2;
    CHECK_STATUS(fixture.add_entity(input), BEDRAY_SCENE_OK);
    fixture.prepare(2, 0xDE12);
    const auto second = fixture.resources();
    CHECK(second.entity_buffer != 0 && second.entity_buffer != first.entity_buffer);
    fixture.release(1);
    fixture.release(2);
    input.geometry_revision = 3;
    input.previous_positions = nullptr;
    input.attributes = nullptr;
    CHECK_STATUS(fixture.add_entity(input), BEDRAY_SCENE_OK);
    CHECK(fixture.prepare(3, 0xDE13).blas_refits == 1);
    CHECK(fixture.resources().entity_buffer == 0);
    fixture.release(3);
    input.geometry_revision = 4;
    input.previous_positions = previous;
    input.attributes = attributes;
    CHECK_STATUS(fixture.add_entity(input), BEDRAY_SCENE_OK);
    CHECK(fixture.prepare(4, 0xDE14).blas_refits == 1);
    CHECK(fixture.resources().entity_attributes_offset > fixture.resources().entity_previous_offset);
    fixture.release(4);
}

static void test_material_atlas_and_table_lifetime() {
    Fixture fixture(2);
    fixture.prepare(1, 0xDE20);
    uint32_t data[32]{};
    BedraySceneMaterialRegion region{0, 0, 0, 2, 2};
    BedraySceneMaterialUpload atlas{1, 2, 2, 1, 0, &region, data, 16, 0, 0};
    region.width = 1;
    CHECK_STATUS(bedray_scene_upload_materials(fixture.scene, &atlas, &g_error), BEDRAY_SCENE_ERROR_INVALID_ARGUMENT);
    region.width = 2;
    CHECK_STATUS(bedray_scene_upload_materials(fixture.scene, &atlas, &g_error), BEDRAY_SCENE_OK);
    auto view = reinterpret_cast<VkImageView>(atlas.material_view);
    CHECK(fake::g.views.size() == 2 && fake::g.views.contains(view));
    CHECK(fake::g.image_copies.size() == 2);
    auto upload = fake::g.image_copies.front();
    CHECK(fake::g.barriers[fake::g.barriers.size() - 2].images[0].oldLayout == VK_IMAGE_LAYOUT_UNDEFINED);
    CHECK(fake::g.barriers.back().images[0].dstAccessMask == VK_ACCESS_SHADER_READ_BIT);
    BedraySceneFrameTable light{1, BEDRAY_SCENE_TABLE_LIGHT, 0, 1, data, 16, 0, 0};
    CHECK_STATUS(bedray_scene_publish_frame_table(fixture.scene, &light, &g_error), BEDRAY_SCENE_OK);
    auto old_address = light.address;
    CHECK(fake::find_buffer_by_address(old_address));
    BedraySceneFrameTable sky{1, BEDRAY_SCENE_TABLE_SKY, 0, 1, data, sizeof(data), 0, 0};
    CHECK_STATUS(bedray_scene_publish_frame_table(fixture.scene, &sky, &g_error), BEDRAY_SCENE_OK);
    CHECK(sky.address && sky.address != light.address);

    // Mandatory atlases and temporary upload memory can exceed the chunk budget.
    uint64_t resident = 0, request = 0;
    CHECK_STATUS(bedray_scene_resident_bytes(fixture.scene, &resident), BEDRAY_SCENE_OK);
    CHECK(resident > 640);
    const BedraySceneCandidate candidate{1, 1, 0, 0, 0, 1};
    BedraySceneSelection selection{BEDRAY_SCENE_ABI_VERSION, 1, &candidate, 1, 0, 0, 0, 6, 1, 1, 1,
        640, &request, 0, 0};
    CHECK_STATUS(bedray_scene_select_sections(fixture.scene, &selection, &g_error), BEDRAY_SCENE_OK);
    CHECK(selection.requested == 1 && request == 1);

    fixture.prepare(2, 0xDE21);
    light.frame_index = 2; light.revision = 2;
    fake::g.create_buffer_failure = VK_ERROR_OUT_OF_DEVICE_MEMORY;
    fake::g.fail_create_buffer_countdown = 0;
    CHECK_STATUS(bedray_scene_publish_frame_table(fixture.scene, &light, &g_error), BEDRAY_SCENE_ERROR_OUT_OF_MEMORY);
    CHECK(fake::find_buffer_by_address(old_address));
    fake::g.fail_create_buffer_countdown = -1;
    CHECK_STATUS(bedray_scene_publish_frame_table(fixture.scene, &light, &g_error), BEDRAY_SCENE_OK);
    CHECK(light.address != old_address && fake::find_buffer_by_address(old_address));
    atlas.frame_index = 2;
    CHECK_STATUS(bedray_scene_upload_materials(fixture.scene, &atlas, &g_error), BEDRAY_SCENE_OK);
    CHECK(atlas.material_view == reinterpret_cast<uint64_t>(view));
    CHECK(fake::g.barriers[fake::g.barriers.size() - 2].images[0].oldLayout == VK_IMAGE_LAYOUT_GENERAL);

    atlas.width = 4; atlas.plane_bytes = 32; region.width = 4;
    CHECK_STATUS(bedray_scene_upload_materials(fixture.scene, &atlas, &g_error), BEDRAY_SCENE_OK);
    CHECK(fake::g.views.size() == 4 && fake::g.views.contains(view));
    fixture.release(2);
    CHECK(fake::find_buffer(upload) && fake::g.views.contains(view) && fake::find_buffer_by_address(old_address));
    fixture.release(1);
    CHECK(!fake::find_buffer(upload) && !fake::g.views.contains(view) && !fake::find_buffer_by_address(old_address));
}

int main() {
    test_material_atlas_and_table_lifetime();
    test_native_residency_and_committed_section_revisions();
    test_near_section_can_displace_far_section_under_budget_pressure();
    test_packed_entity_streams_share_the_blas_generation();
    test_producer_command_can_be_reused_while_its_generation_is_pinned();
    test_discarded_writes_cannot_be_reused();
    test_zero_device_address_is_rejected();
    test_abi_layout();
    test_create_validation();
    test_section_contract_validation();
    test_first_frame_records_full_pipeline();
    test_section_pass_ranges();
    test_tlas_custom_indices_follow_table_slots();
    test_geometry_vertex_section_stride();
    test_section_quad_limit();
    test_idle_frame_reuses_slot_state();
    test_update_copies_inputs_and_coalesces_pending();
    test_transform_update_touches_only_tlas();
    test_section_instances_follow_view();
    test_refit_copies_pinned_geometry_and_updates_after_completion();
    test_pipelined_cow_tlas_updates_keep_dependencies_bounded();
    test_nonfinite_activation_changes_rebuild_instead_of_refitting();
    test_replacement_blas_relinks_a_live_cached_tlas_without_a_build();
    test_rebuild_without_update_capability_retires_after_frame();
    test_table_coalescing_and_clear();
    test_stale_in_use_and_destroy();
    test_update_is_transactional();
    test_capacity_limits();
    test_remove_entity_clears_tlas_and_retires_now();
    test_device_loss_poisons_scene();

    if (g_failures != 0) {
        std::fprintf(stderr, "bedray_scene contract test FAILED: %d failure(s)\n", g_failures);
        return 1;
    }
    std::printf("bedray_scene contract test passed\n");
    return 0;
}
