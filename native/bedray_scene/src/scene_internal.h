#ifndef BEDRAY_SCENE_INTERNAL_H
#define BEDRAY_SCENE_INTERNAL_H

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

#include "bedray_scene.h"

#include <array>
#include <cstdint>
#include <map>
#include <memory>
#include <vector>

namespace bedray_scene {

constexpr uint32_t kRequiredCapabilities =
    BEDRAY_SCENE_CAP_ACCELERATION_STRUCTURE | BEDRAY_SCENE_CAP_DEVICE_ADDRESS;
constexpr uint64_t kBufferAlignment = 256;
constexpr uint64_t kDefaultScratchAlignment = 256;
constexpr uint64_t kNoRevision = ~0ull;

struct Scene;

struct VulkanFns {
    PFN_vkGetPhysicalDeviceMemoryProperties get_memory_properties = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 get_properties2 = nullptr;
    PFN_vkCreateBuffer create_buffer = nullptr;
    PFN_vkDestroyBuffer destroy_buffer = nullptr;
    PFN_vkGetBufferMemoryRequirements get_buffer_memory_requirements = nullptr;
    PFN_vkAllocateMemory allocate_memory = nullptr;
    PFN_vkFreeMemory free_memory = nullptr;
    PFN_vkBindBufferMemory bind_buffer_memory = nullptr;
    PFN_vkMapMemory map_memory = nullptr;
    PFN_vkUnmapMemory unmap_memory = nullptr;
    PFN_vkFlushMappedMemoryRanges flush_mapped_memory_ranges = nullptr;
    PFN_vkGetBufferDeviceAddressKHR get_buffer_device_address = nullptr;
    PFN_vkGetAccelerationStructureBuildSizesKHR get_as_build_sizes = nullptr;
    PFN_vkCreateAccelerationStructureKHR create_as = nullptr;
    PFN_vkDestroyAccelerationStructureKHR destroy_as = nullptr;
    PFN_vkGetAccelerationStructureDeviceAddressKHR get_as_address = nullptr;
    PFN_vkCmdBuildAccelerationStructuresKHR cmd_build_as = nullptr;
    PFN_vkCmdCopyBuffer cmd_copy_buffer = nullptr;
    PFN_vkCmdPipelineBarrier cmd_pipeline_barrier = nullptr;
};

struct Buffer {
    VkBuffer handle = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint64_t size = 0;
    uint64_t address = 0;
    void* mapped = nullptr;
    bool coherent = true;
    bool host_visible = false;
};

struct Acceleration {
    VkAccelerationStructureKHR handle = VK_NULL_HANDLE;
    Buffer storage;
    uint64_t address = 0;
    uint64_t capacity = 0;
};

struct GeometryResource {
    Buffer gpu;
    uint64_t vertex_address = 0;
    uint64_t index_address = 0;
    Acceleration blas;
};

struct PendingMesh {
    std::vector<uint8_t> vertices;
    std::vector<uint8_t> indices;
    uint64_t vertex_count = 0;
    uint64_t index_count = 0;
    uint32_t vertex_stride = 0;
    uint32_t index_type = BEDRAY_SCENE_INDEX_NONE;
    uint64_t geometry_revision = 0;
    uint64_t topology_revision = 0;
};

struct Section {
    uint64_t id = 0;
    uint64_t revision = 0;
    uint64_t vertex_count = 0;
    uint64_t index_count = 0;
    uint32_t vertex_stride = 0;
    uint32_t index_type = BEDRAY_SCENE_INDEX_NONE;
    std::unique_ptr<GeometryResource> geometry;
    bool has_pending = false;
    PendingMesh pending;
};

struct Entity {
    uint64_t id = 0;
    uint64_t geometry_revision = 0;
    uint64_t topology_revision = 0;
    uint64_t vertex_count = 0;
    uint64_t index_count = 0;
    uint32_t vertex_stride = 0;
    uint32_t index_type = BEDRAY_SCENE_INDEX_NONE;
    std::array<float, 12> transform{};
    uint32_t instance_mask = 0xFF;
    std::unique_ptr<GeometryResource> geometry;
    bool has_pending = false;
    PendingMesh pending;
    bool transform_pending = false;
    std::array<float, 12> pending_transform{};
    uint32_t pending_mask = 0xFF;
};

struct Table {
    Buffer gpu;
    uint64_t revision = kNoRevision;
    bool has_pending = false;
    uint64_t pending_revision = kNoRevision;
    std::vector<uint8_t> pending;
};

struct RetiredBuffer {
    Buffer buffer;
    uint64_t after_frame = 0;
};

struct RetiredAcceleration {
    Acceleration acceleration;
    uint64_t after_frame = 0;
};

struct FrameSlot {
    bool free = true;
    uint64_t frame_index = 0;
    uint64_t command_buffer = 0;
    std::vector<Buffer> transient;
    Acceleration tlas;
    Buffer instance_buffer;
    Buffer tlas_scratch;
    uint64_t tlas_scratch_capacity = 0;
    uint64_t fingerprint = 0;
    uint32_t instance_count = 0;
    bool tlas_valid = false;
    uint64_t built_version = 0;
    std::vector<VkAccelerationStructureKHR> blas_in_use;
};

struct Scene {
    BedraySceneDesc desc{};
    VulkanFns fns;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memory_properties{};
    uint64_t scratch_alignment = kDefaultScratchAlignment;
    uint32_t capabilities = 0;

    bool failed = false;
    BedraySceneStatus failure_status = BEDRAY_SCENE_OK;
    char failure_message[192] = {};

    std::map<uint64_t, Section> sections;
    std::map<uint64_t, Entity> entities;
    std::array<Table, BEDRAY_SCENE_TABLE_COUNT> tables;

    std::vector<FrameSlot> slots;
    std::vector<RetiredBuffer> retired_buffers;
    std::vector<RetiredAcceleration> retired_accelerations;

    Buffer section_table;
    uint64_t section_table_bytes = 0;
    bool section_table_dirty = true;

    bool membership_changed = false;
    uint64_t version = 0;
    uint64_t last_frame_index = 0;
    uint32_t outstanding = 0;

    BedraySceneResources last_resources{};
    bool has_last_resources = false;
};

struct BlasView {
    uint64_t vertex_address = 0;
    uint64_t index_address = 0;
    uint64_t vertex_count = 0;
    uint64_t index_count = 0;
    uint32_t vertex_stride = 0;
    uint32_t index_type = BEDRAY_SCENE_INDEX_NONE;
};

struct InstanceEntry {
    std::array<float, 12> transform{};
    uint64_t blas_address = 0;
    VkAccelerationStructureKHR blas_handle = VK_NULL_HANDLE;
    uint32_t custom_index = 0;
    uint32_t mask = 0xFF;
};

uint64_t align_up(uint64_t value, uint64_t alignment);
uint64_t mesh_index_bytes(uint32_t index_type, uint64_t index_count);

void clear_error(BedraySceneError* error);
BedraySceneStatus fail(BedraySceneError* error, BedraySceneStatus status, const char* format, ...);
BedraySceneStatus vk_status(VkResult result);

void mark_failed(Scene& scene, BedraySceneStatus status, const char* message);
BedraySceneStatus failed_status(Scene& scene, BedraySceneError* error);

/* acceleration_scene.cpp */
bool load_vulkan(Scene& scene, BedraySceneError* error);
bool create_buffer(
    Scene& scene,
    uint64_t size,
    VkBufferUsageFlags usage,
    bool host_visible,
    Buffer& out,
    BedraySceneError* error);
void destroy_buffer(Scene& scene, Buffer& buffer);
bool create_acceleration_object(
    Scene& scene,
    VkAccelerationStructureTypeKHR type,
    uint64_t storage_size,
    Acceleration& out,
    BedraySceneError* error);
void destroy_acceleration(Scene& scene, Acceleration& acceleration);
bool flush_buffer(
    Scene& scene,
    Buffer& buffer,
    uint64_t offset,
    uint64_t size,
    BedraySceneError* error);
bool create_geometry_buffer(
    Scene& scene,
    const PendingMesh& mesh,
    Buffer& out,
    uint64_t& index_offset,
    BedraySceneError* error);
bool query_blas_sizes(
    Scene& scene,
    const BlasView& view,
    uint64_t& storage_size,
    uint64_t& build_scratch,
    uint64_t& update_scratch,
    BedraySceneError* error);
bool query_tlas_sizes(
    Scene& scene,
    uint32_t instance_count,
    uint64_t& storage_size,
    uint64_t& build_scratch,
    uint64_t& update_scratch,
    BedraySceneError* error);

void record_buffer_barriers(
    Scene& scene,
    VkCommandBuffer command_buffer,
    VkPipelineStageFlags src_stage,
    VkPipelineStageFlags dst_stage,
    const VkBufferMemoryBarrier* barriers,
    uint32_t barrier_count);
void record_memory_barrier(
    Scene& scene,
    VkCommandBuffer command_buffer,
    VkPipelineStageFlags src_stage,
    VkPipelineStageFlags dst_stage,
    VkAccessFlags src_access,
    VkAccessFlags dst_access);
void record_copy(
    Scene& scene,
    VkCommandBuffer command_buffer,
    const Buffer& src,
    uint64_t src_offset,
    const Buffer& dst,
    uint64_t dst_offset,
    uint64_t size);
void record_blas_build(
    Scene& scene,
    VkCommandBuffer command_buffer,
    const BlasView& view,
    VkAccelerationStructureKHR dst,
    VkAccelerationStructureKHR src,
    bool update,
    uint64_t scratch_address);
void record_tlas_build(
    Scene& scene,
    VkCommandBuffer command_buffer,
    uint64_t instance_buffer_address,
    uint32_t instance_count,
    VkAccelerationStructureKHR dst,
    VkAccelerationStructureKHR src,
    bool update,
    uint64_t scratch_address);

} // namespace bedray_scene

struct BedrayScene {
    bedray_scene::Scene impl;
};

#endif /* BEDRAY_SCENE_INTERNAL_H */
