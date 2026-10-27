#include "scene_internal.h"

#include <cstdint>

namespace bedray_scene {
namespace {

template <typename T>
bool load_instance(Scene& scene, T& function, const char* name) {
    auto get_proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        static_cast<uintptr_t>(scene.desc.get_instance_proc_addr));
    function = reinterpret_cast<T>(get_proc(scene.instance, name));
    return function != nullptr;
}

template <typename T>
bool load_device(Scene& scene, T& function, const char* name) {
    auto get_proc = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
        static_cast<uintptr_t>(scene.desc.get_device_proc_addr));
    function = reinterpret_cast<T>(get_proc(scene.device, name));
    return function != nullptr;
}

int32_t find_memory_type(
    const Scene& scene,
    uint32_t type_bits,
    VkMemoryPropertyFlags required) {
    for (uint32_t i = 0; i < scene.memory_properties.memoryTypeCount; ++i) {
        if ((type_bits & (1u << i)) == 0) {
            continue;
        }
        if ((scene.memory_properties.memoryTypes[i].propertyFlags & required) == required) {
            return static_cast<int32_t>(i);
        }
    }
    return -1;
}

bool create_buffer_impl(
    Scene& scene,
    uint64_t size,
    VkBufferUsageFlags usage,
    bool host_visible,
    Buffer& out,
    BedraySceneError* error) {
    if (size == 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "cannot create a zero-sized buffer") == BEDRAY_SCENE_OK;
    }
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkBuffer handle = VK_NULL_HANDLE;
    VkResult result = scene.fns.create_buffer(scene.device, &info, nullptr, &handle);
    if (result != VK_SUCCESS) {
        const BedraySceneStatus status = vk_status(result);
        if (status == BEDRAY_SCENE_ERROR_DEVICE_LOST) {
            mark_failed(scene, status, "vkCreateBuffer failed with VK_ERROR_DEVICE_LOST");
        }
        return fail(error, status, "vkCreateBuffer failed: VkResult=%d", static_cast<int>(result)) == BEDRAY_SCENE_OK;
    }

    VkMemoryRequirements requirements{};
    scene.fns.get_buffer_memory_requirements(scene.device, handle, &requirements);

    VkMemoryPropertyFlags required = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    if (host_visible) {
        required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    }
    int32_t memory_type = find_memory_type(scene, requirements.memoryTypeBits, required);
    if (memory_type < 0 && host_visible) {
        memory_type = find_memory_type(scene, requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }
    if (memory_type < 0) {
        scene.fns.destroy_buffer(scene.device, handle, nullptr);
        return fail(error, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "no memory type for buffer (host_visible=%d, bits=0x%08x)",
            host_visible ? 1 : 0, requirements.memoryTypeBits) == BEDRAY_SCENE_OK;
    }

    VkMemoryAllocateInfo allocate{};
    allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocate.allocationSize = requirements.size;
    allocate.memoryTypeIndex = static_cast<uint32_t>(memory_type);

    VkDeviceMemory memory = VK_NULL_HANDLE;
    result = scene.fns.allocate_memory(scene.device, &allocate, nullptr, &memory);
    if (result != VK_SUCCESS) {
        scene.fns.destroy_buffer(scene.device, handle, nullptr);
        const BedraySceneStatus status = vk_status(result);
        if (status == BEDRAY_SCENE_ERROR_DEVICE_LOST) {
            mark_failed(scene, status, "vkAllocateMemory failed with VK_ERROR_DEVICE_LOST");
        }
        return fail(error, status, "vkAllocateMemory failed: VkResult=%d", static_cast<int>(result)) == BEDRAY_SCENE_OK;
    }

    result = scene.fns.bind_buffer_memory(scene.device, handle, memory, 0);
    if (result != VK_SUCCESS) {
        scene.fns.free_memory(scene.device, memory, nullptr);
        scene.fns.destroy_buffer(scene.device, handle, nullptr);
        const BedraySceneStatus status = vk_status(result);
        if (status == BEDRAY_SCENE_ERROR_DEVICE_LOST) {
            mark_failed(scene, status, "vkBindBufferMemory failed with VK_ERROR_DEVICE_LOST");
        }
        return fail(error, status, "vkBindBufferMemory failed: VkResult=%d", static_cast<int>(result)) == BEDRAY_SCENE_OK;
    }

    Buffer buffer{};
    buffer.handle = handle;
    buffer.memory = memory;
    buffer.size = size;
    buffer.host_visible = host_visible;
    buffer.coherent = true;

    const VkMemoryPropertyFlags actual =
        scene.memory_properties.memoryTypes[memory_type].propertyFlags;
    if (host_visible) {
        buffer.coherent = (actual & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        result = scene.fns.map_memory(scene.device, memory, 0, VK_WHOLE_SIZE, 0, &buffer.mapped);
        if (result != VK_SUCCESS) {
            scene.fns.free_memory(scene.device, memory, nullptr);
            scene.fns.destroy_buffer(scene.device, handle, nullptr);
            const BedraySceneStatus status = vk_status(result);
            if (status == BEDRAY_SCENE_ERROR_DEVICE_LOST) {
                mark_failed(scene, status, "vkMapMemory failed with VK_ERROR_DEVICE_LOST");
            }
            return fail(error, status, "vkMapMemory failed: VkResult=%d", static_cast<int>(result)) == BEDRAY_SCENE_OK;
        }
    }

    if ((usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
        VkBufferDeviceAddressInfo address_info{};
        address_info.sType = VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO;
        address_info.buffer = handle;
        buffer.address = scene.fns.get_buffer_device_address(scene.device, &address_info);
    }

    out = buffer;
    return true;
}

} // namespace

uint64_t align_up(uint64_t value, uint64_t alignment) {
    if (alignment == 0) {
        return value;
    }
    return (value + alignment - 1) & ~(alignment - 1);
}

uint64_t mesh_index_bytes(uint32_t index_type, uint64_t index_count) {
    switch (index_type) {
        case BEDRAY_SCENE_INDEX_UINT16:
            return index_count * 2;
        case BEDRAY_SCENE_INDEX_UINT32:
            return index_count * 4;
        default:
            return 0;
    }
}

bool load_vulkan(Scene& scene, BedraySceneError* error) {
    const bool instance_ok =
        load_instance(scene, scene.fns.get_memory_properties, "vkGetPhysicalDeviceMemoryProperties")
        && load_instance(scene, scene.fns.get_properties2, "vkGetPhysicalDeviceProperties2");
    if (!instance_ok) {
        return fail(error, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "required Vulkan 1.1 instance functions are missing") == BEDRAY_SCENE_OK;
    }

    if (!load_device(scene, scene.fns.create_buffer, "vkCreateBuffer")
        || !load_device(scene, scene.fns.destroy_buffer, "vkDestroyBuffer")
        || !load_device(scene, scene.fns.get_buffer_memory_requirements, "vkGetBufferMemoryRequirements")
        || !load_device(scene, scene.fns.allocate_memory, "vkAllocateMemory")
        || !load_device(scene, scene.fns.free_memory, "vkFreeMemory")
        || !load_device(scene, scene.fns.bind_buffer_memory, "vkBindBufferMemory")
        || !load_device(scene, scene.fns.map_memory, "vkMapMemory")
        || !load_device(scene, scene.fns.unmap_memory, "vkUnmapMemory")
        || !load_device(scene, scene.fns.flush_mapped_memory_ranges, "vkFlushMappedMemoryRanges")
        || !load_device(scene, scene.fns.get_as_build_sizes, "vkGetAccelerationStructureBuildSizesKHR")
        || !load_device(scene, scene.fns.create_as, "vkCreateAccelerationStructureKHR")
        || !load_device(scene, scene.fns.destroy_as, "vkDestroyAccelerationStructureKHR")
        || !load_device(scene, scene.fns.get_as_address, "vkGetAccelerationStructureDeviceAddressKHR")
        || !load_device(scene, scene.fns.cmd_build_as, "vkCmdBuildAccelerationStructuresKHR")
        || !load_device(scene, scene.fns.cmd_copy_buffer, "vkCmdCopyBuffer")
        || !load_device(scene, scene.fns.cmd_pipeline_barrier, "vkCmdPipelineBarrier")) {
        return fail(error, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "required Vulkan device functions are missing") == BEDRAY_SCENE_OK;
    }

    if (!load_device(scene, scene.fns.get_buffer_device_address, "vkGetBufferDeviceAddressKHR")
        && !load_device(scene, scene.fns.get_buffer_device_address, "vkGetBufferDeviceAddress")) {
        return fail(error, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "vkGetBufferDeviceAddress is missing") == BEDRAY_SCENE_OK;
    }

    scene.fns.get_memory_properties(scene.physical_device, &scene.memory_properties);

    VkPhysicalDeviceProperties2 properties{};
    properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    VkPhysicalDeviceAccelerationStructurePropertiesKHR as_properties{};
    as_properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR;
    properties.pNext = &as_properties;
    scene.fns.get_properties2(scene.physical_device, &properties);

    scene.scratch_alignment = as_properties.minAccelerationStructureScratchOffsetAlignment;
    if (scene.scratch_alignment == 0) {
        scene.scratch_alignment = kDefaultScratchAlignment;
    }
    if ((scene.scratch_alignment & (scene.scratch_alignment - 1)) != 0) {
        return fail(error, BEDRAY_SCENE_ERROR_UNSUPPORTED,
            "reported scratch alignment %llu is not a power of two",
            static_cast<unsigned long long>(scene.scratch_alignment)) == BEDRAY_SCENE_OK;
    }
    return true;
}

bool create_buffer(
    Scene& scene,
    uint64_t size,
    VkBufferUsageFlags usage,
    bool host_visible,
    Buffer& out,
    BedraySceneError* error) {
    return create_buffer_impl(scene, size, usage, host_visible, out, error);
}

bool flush_buffer(
    Scene& scene,
    Buffer& buffer,
    uint64_t offset,
    uint64_t size,
    BedraySceneError* error) {
    if (buffer.memory == VK_NULL_HANDLE || buffer.coherent || size == 0) {
        return true;
    }
    (void)offset;
    (void)size;
    VkMappedMemoryRange range{};
    range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
    range.memory = buffer.memory;
    /* Whole-memory flushes avoid the nonCoherentAtomSize multiple-of-size rule
       and every mapped buffer in this library is fully written before use. */
    range.offset = 0;
    range.size = VK_WHOLE_SIZE;
    const VkResult result = scene.fns.flush_mapped_memory_ranges(scene.device, 1, &range);
    if (result != VK_SUCCESS) {
        const BedraySceneStatus status = vk_status(result);
        if (status == BEDRAY_SCENE_ERROR_DEVICE_LOST) {
            mark_failed(scene, status, "vkFlushMappedMemoryRanges failed with VK_ERROR_DEVICE_LOST");
        }
        return fail(error, status,
            "vkFlushMappedMemoryRanges failed: VkResult=%d", static_cast<int>(result)) == BEDRAY_SCENE_OK;
    }
    return true;
}

void destroy_buffer(Scene& scene, Buffer& buffer) {
    if (buffer.mapped != nullptr && buffer.memory != VK_NULL_HANDLE) {
        scene.fns.unmap_memory(scene.device, buffer.memory);
        buffer.mapped = nullptr;
    }
    if (buffer.handle != VK_NULL_HANDLE) {
        scene.fns.destroy_buffer(scene.device, buffer.handle, nullptr);
    }
    if (buffer.memory != VK_NULL_HANDLE) {
        scene.fns.free_memory(scene.device, buffer.memory, nullptr);
    }
    buffer = Buffer{};
}

bool create_acceleration_object(
    Scene& scene,
    VkAccelerationStructureTypeKHR type,
    uint64_t storage_size,
    Acceleration& out,
    BedraySceneError* error) {
    Acceleration acceleration{};
    const VkBufferUsageFlags usage =
        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    if (!create_buffer_impl(scene, storage_size, usage, false, acceleration.storage, error)) {
        return false;
    }

    VkAccelerationStructureCreateInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR;
    info.buffer = acceleration.storage.handle;
    info.offset = 0;
    info.size = storage_size;
    info.type = type;

    VkAccelerationStructureKHR handle = VK_NULL_HANDLE;
    VkResult result = scene.fns.create_as(scene.device, &info, nullptr, &handle);
    if (result != VK_SUCCESS) {
        destroy_buffer(scene, acceleration.storage);
        const BedraySceneStatus status = vk_status(result);
        if (status == BEDRAY_SCENE_ERROR_DEVICE_LOST) {
            mark_failed(scene, status, "vkCreateAccelerationStructureKHR failed with VK_ERROR_DEVICE_LOST");
        }
        return fail(error, status,
            "vkCreateAccelerationStructureKHR failed: VkResult=%d", static_cast<int>(result)) == BEDRAY_SCENE_OK;
    }

    acceleration.handle = handle;
    acceleration.capacity = storage_size;

    VkAccelerationStructureDeviceAddressInfoKHR address_info{};
    address_info.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR;
    address_info.accelerationStructure = handle;
    acceleration.address = scene.fns.get_as_address(scene.device, &address_info);

    out = acceleration;
    return true;
}

void destroy_acceleration(Scene& scene, Acceleration& acceleration) {
    if (acceleration.handle != VK_NULL_HANDLE) {
        scene.fns.destroy_as(scene.device, acceleration.handle, nullptr);
    }
    destroy_buffer(scene, acceleration.storage);
    acceleration = Acceleration{};
}

bool create_geometry_buffer(
    Scene& scene,
    const PendingMesh& mesh,
    Buffer& out,
    uint64_t& index_offset,
    BedraySceneError* error) {
    const uint64_t vertex_bytes = mesh.vertex_count * mesh.vertex_stride;
    const uint64_t index_bytes = mesh_index_bytes(mesh.index_type, mesh.index_count);
    index_offset = index_bytes > 0 ? align_up(vertex_bytes, kBufferAlignment) : 0;
    const uint64_t total = vertex_bytes + index_bytes + (index_bytes > 0 ? index_offset - vertex_bytes : 0);

    const VkBufferUsageFlags usage =
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
        | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
        | VK_BUFFER_USAGE_INDEX_BUFFER_BIT;

    return create_buffer_impl(scene, total, usage, false, out, error);
}

bool query_blas_sizes(
    Scene& scene,
    const BlasView& view,
    uint64_t& storage_size,
    uint64_t& build_scratch,
    uint64_t& update_scratch,
    BedraySceneError* error) {
    if (view.vertex_count == 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "BLAS has no vertices") == BEDRAY_SCENE_OK;
    }

    VkAccelerationStructureGeometryTrianglesDataKHR triangles{};
    triangles.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR;
    triangles.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;
    triangles.vertexData.deviceAddress = view.vertex_address;
    triangles.vertexStride = view.vertex_stride;
    triangles.maxVertex = static_cast<uint32_t>(view.vertex_count - 1);
    switch (view.index_type) {
        case BEDRAY_SCENE_INDEX_UINT16:
            triangles.indexType = VK_INDEX_TYPE_UINT16;
            triangles.indexData.deviceAddress = view.index_address;
            break;
        case BEDRAY_SCENE_INDEX_UINT32:
            triangles.indexType = VK_INDEX_TYPE_UINT32;
            triangles.indexData.deviceAddress = view.index_address;
            break;
        default:
            triangles.indexType = VK_INDEX_TYPE_NONE_KHR;
            break;
    }

    VkAccelerationStructureGeometryKHR geometry{};
    geometry.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
    geometry.geometryType = VK_GEOMETRY_TYPE_TRIANGLES_KHR;
    geometry.flags = 0;
    geometry.geometry.triangles = triangles;

    VkAccelerationStructureBuildGeometryInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
    info.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
    info.flags = (scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0
        ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
        : 0;
    info.geometryCount = 1;
    info.pGeometries = &geometry;

    const uint32_t primitive_count = view.index_count > 0
        ? static_cast<uint32_t>(view.index_count / 3)
        : static_cast<uint32_t>(view.vertex_count / 3);

    VkAccelerationStructureBuildSizesInfoKHR sizes{};
    sizes.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR;
    scene.fns.get_as_build_sizes(scene.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
        &info, &primitive_count, &sizes);

    storage_size = sizes.accelerationStructureSize;
    build_scratch = sizes.buildScratchSize;
    update_scratch = sizes.updateScratchSize;
    return true;
}

bool query_tlas_sizes(
    Scene& scene,
    uint32_t instance_count,
    uint64_t& storage_size,
    uint64_t& build_scratch,
    uint64_t& update_scratch,
    BedraySceneError* error) {
    if (instance_count == 0) {
        return fail(error, BEDRAY_SCENE_ERROR_INVALID_ARGUMENT, "TLAS has no instances") == BEDRAY_SCENE_OK;
    }

    VkAccelerationStructureGeometryInstancesDataKHR instances{};
    instances.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;
    instances.arrayOfPointers = VK_FALSE;

    VkAccelerationStructureGeometryKHR geometry{};
    geometry.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
    geometry.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR;
    geometry.geometry.instances = instances;

    VkAccelerationStructureBuildGeometryInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
    info.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    info.flags = (scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0
        ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
        : 0;
    info.geometryCount = 1;
    info.pGeometries = &geometry;

    VkAccelerationStructureBuildSizesInfoKHR sizes{};
    sizes.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR;
    scene.fns.get_as_build_sizes(scene.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
        &info, &instance_count, &sizes);

    storage_size = sizes.accelerationStructureSize;
    build_scratch = sizes.buildScratchSize;
    update_scratch = sizes.updateScratchSize;
    return true;
}

void record_buffer_barriers(
    Scene& scene,
    VkCommandBuffer command_buffer,
    VkPipelineStageFlags src_stage,
    VkPipelineStageFlags dst_stage,
    const VkBufferMemoryBarrier* barriers,
    uint32_t barrier_count) {
    if (barrier_count == 0) {
        return;
    }
    scene.fns.cmd_pipeline_barrier(command_buffer, src_stage, dst_stage, 0,
        0, nullptr, barrier_count, barriers, 0, nullptr);
}

void record_memory_barrier(
    Scene& scene,
    VkCommandBuffer command_buffer,
    VkPipelineStageFlags src_stage,
    VkPipelineStageFlags dst_stage,
    VkAccessFlags src_access,
    VkAccessFlags dst_access) {
    VkMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;
    scene.fns.cmd_pipeline_barrier(command_buffer, src_stage, dst_stage, 0,
        1, &barrier, 0, nullptr, 0, nullptr);
}

void record_copy(
    Scene& scene,
    VkCommandBuffer command_buffer,
    const Buffer& src,
    uint64_t src_offset,
    const Buffer& dst,
    uint64_t dst_offset,
    uint64_t size) {
    VkBufferCopy copy{};
    copy.srcOffset = src_offset;
    copy.dstOffset = dst_offset;
    copy.size = size;
    scene.fns.cmd_copy_buffer(command_buffer, src.handle, dst.handle, 1, &copy);
}

void record_blas_build(
    Scene& scene,
    VkCommandBuffer command_buffer,
    const BlasView& view,
    VkAccelerationStructureKHR dst,
    VkAccelerationStructureKHR src,
    bool update,
    uint64_t scratch_address) {
    VkAccelerationStructureGeometryTrianglesDataKHR triangles{};
    triangles.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR;
    triangles.vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;
    triangles.vertexData.deviceAddress = view.vertex_address;
    triangles.vertexStride = view.vertex_stride;
    triangles.maxVertex = static_cast<uint32_t>(view.vertex_count - 1);
    switch (view.index_type) {
        case BEDRAY_SCENE_INDEX_UINT16:
            triangles.indexType = VK_INDEX_TYPE_UINT16;
            triangles.indexData.deviceAddress = view.index_address;
            break;
        case BEDRAY_SCENE_INDEX_UINT32:
            triangles.indexType = VK_INDEX_TYPE_UINT32;
            triangles.indexData.deviceAddress = view.index_address;
            break;
        default:
            triangles.indexType = VK_INDEX_TYPE_NONE_KHR;
            break;
    }

    VkAccelerationStructureGeometryKHR geometry{};
    geometry.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
    geometry.geometryType = VK_GEOMETRY_TYPE_TRIANGLES_KHR;
    geometry.geometry.triangles = triangles;

    VkAccelerationStructureBuildGeometryInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
    info.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
    info.flags = (scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0
        ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
        : 0;
    info.mode = update
        ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
        : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    info.srcAccelerationStructure = src;
    info.dstAccelerationStructure = dst;
    info.geometryCount = 1;
    info.pGeometries = &geometry;
    info.scratchData.deviceAddress = scratch_address;

    VkAccelerationStructureBuildRangeInfoKHR range{};
    range.primitiveCount = view.index_count > 0
        ? static_cast<uint32_t>(view.index_count / 3)
        : static_cast<uint32_t>(view.vertex_count / 3);

    const VkAccelerationStructureBuildRangeInfoKHR* ranges[] = { &range };
    scene.fns.cmd_build_as(command_buffer, 1, &info, ranges);
}

void record_tlas_build(
    Scene& scene,
    VkCommandBuffer command_buffer,
    uint64_t instance_buffer_address,
    uint32_t instance_count,
    VkAccelerationStructureKHR dst,
    VkAccelerationStructureKHR src,
    bool update,
    uint64_t scratch_address) {
    VkAccelerationStructureGeometryInstancesDataKHR instances{};
    instances.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR;
    instances.arrayOfPointers = VK_FALSE;
    instances.data.deviceAddress = instance_buffer_address;

    VkAccelerationStructureGeometryKHR geometry{};
    geometry.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR;
    geometry.geometryType = VK_GEOMETRY_TYPE_INSTANCES_KHR;
    geometry.geometry.instances = instances;

    VkAccelerationStructureBuildGeometryInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR;
    info.type = VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR;
    info.flags = (scene.capabilities & BEDRAY_SCENE_CAP_AS_UPDATE) != 0
        ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
        : 0;
    info.mode = update
        ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
        : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;
    info.srcAccelerationStructure = src;
    info.dstAccelerationStructure = dst;
    info.geometryCount = 1;
    info.pGeometries = &geometry;
    info.scratchData.deviceAddress = scratch_address;

    VkAccelerationStructureBuildRangeInfoKHR range{};
    range.primitiveCount = instance_count;
    const VkAccelerationStructureBuildRangeInfoKHR* ranges[] = { &range };
    scene.fns.cmd_build_as(command_buffer, 1, &info, ranges);
}

} // namespace bedray_scene
