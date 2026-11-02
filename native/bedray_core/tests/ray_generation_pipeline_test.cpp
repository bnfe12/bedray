// Exercises the actual pipeline/SBT owner through its production device function table. No Vulkan loader or GPU.
#include "../src/ray_generation_pipeline.h"
#include "fake_pass_device.h"
#include <algorithm>
#include <cstdio>

namespace {
int failures = 0;
#define CHECK(expression) do { if (!(expression)) { std::fprintf(stderr, "FAIL %d: %s\n", __LINE__, #expression); ++failures; } } while (0)

VkResult pipeline_result = VK_SUCCESS, handle_result = VK_SUCCESS, flush_result = VK_SUCCESS;
VkPhysicalDeviceRayTracingPipelinePropertiesKHR properties{};
VkPipelineCache received_cache = VK_NULL_HANDLE;
VkStridedDeviceAddressRegionKHR recorded_region{};
VkDeviceAddress base_address = 0x100003;
uint32_t limit_counts[]{4, 5, 6}, limit_sizes[]{8, 4, 2};
unsigned created_pipelines = 0, destroyed_pipelines = 0, destroyed_buffers = 0, freed_memories = 0, flushes = 0, traces = 0;
bool missing_trace = false;

void reset() {
    fake::reset();
    pipeline_result = handle_result = flush_result = VK_SUCCESS;
    received_cache = VK_NULL_HANDLE; recorded_region = {};
    created_pipelines = destroyed_pipelines = destroyed_buffers = freed_memories = flushes = traces = 0;
    base_address = 0x100003; missing_trace = false;
    const uint32_t counts[]{4, 5, 6}, sizes[]{8, 4, 2};
    std::copy_n(counts, 3, limit_counts); std::copy_n(sizes, 3, limit_sizes);
    properties = {};
    properties.shaderGroupHandleSize = 24;
    properties.shaderGroupHandleAlignment = 32;
    properties.shaderGroupBaseAlignment = 64;
    properties.maxShaderGroupStride = 256;
    properties.maxRayRecursionDepth = 1;
    properties.maxRayDispatchInvocationCount = 512;
}

VKAPI_ATTR VkResult VKAPI_CALL create_pipeline(VkDevice, VkDeferredOperationKHR deferred, VkPipelineCache cache,
    uint32_t count, const VkRayTracingPipelineCreateInfoKHR* info, const VkAllocationCallbacks*, VkPipeline* out) {
    CHECK(deferred == VK_NULL_HANDLE); CHECK(count == 1);
    CHECK(info->stageCount == 1 && info->groupCount == 1 && info->maxPipelineRayRecursionDepth == 1);
    CHECK(info->pStages[0].stage == VK_SHADER_STAGE_RAYGEN_BIT_KHR);
    CHECK(std::strcmp(info->pStages[0].pName, "raygen_main") == 0);
    CHECK(info->pGroups[0].type == VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR);
    CHECK(info->pGroups[0].generalShader == 0);
    CHECK(info->pGroups[0].closestHitShader == VK_SHADER_UNUSED_KHR);
    CHECK(info->pGroups[0].anyHitShader == VK_SHADER_UNUSED_KHR);
    CHECK(info->pGroups[0].intersectionShader == VK_SHADER_UNUSED_KHR);
    received_cache = cache;
    if (pipeline_result == VK_SUCCESS) { *out = fake::make_handle<VkPipeline>(0x55); ++created_pipelines; }
    return pipeline_result;
}

VKAPI_ATTR VkResult VKAPI_CALL group_handles(VkDevice, VkPipeline pipeline, uint32_t first, uint32_t count,
    size_t bytes, void* data) {
    CHECK(pipeline == fake::make_handle<VkPipeline>(0x55));
    CHECK(first == 0 && count == 1 && bytes == properties.shaderGroupHandleSize);
    if (handle_result == VK_SUCCESS) std::memset(data, 0xa7, bytes);
    return handle_result;
}

VKAPI_ATTR void VKAPI_CALL trace(VkCommandBuffer command, const VkStridedDeviceAddressRegionKHR* raygen,
    const VkStridedDeviceAddressRegionKHR* miss, const VkStridedDeviceAddressRegionKHR* hit,
    const VkStridedDeviceAddressRegionKHR* callable, uint32_t x, uint32_t y, uint32_t z) {
    CHECK(command == fake::make_handle<VkCommandBuffer>(0x77));
    CHECK(x == 16 && y == 16 && z == 2);
    CHECK(miss->deviceAddress == 0 && miss->stride == 0 && miss->size == 0);
    CHECK(hit->deviceAddress == 0 && hit->stride == 0 && hit->size == 0);
    CHECK(callable->deviceAddress == 0 && callable->stride == 0 && callable->size == 0);
    recorded_region = *raygen; ++traces;
}

VKAPI_ATTR VkDeviceAddress VKAPI_CALL address(VkDevice, const VkBufferDeviceAddressInfo*) { return base_address; }
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL get_proc(VkDevice, const char* name) {
    if (std::strcmp(name, "vkCreateRayTracingPipelinesKHR") == 0) return reinterpret_cast<PFN_vkVoidFunction>(create_pipeline);
    if (std::strcmp(name, "vkGetRayTracingShaderGroupHandlesKHR") == 0) return reinterpret_cast<PFN_vkVoidFunction>(group_handles);
    if (std::strcmp(name, "vkCmdTraceRaysKHR") == 0) return missing_trace ? nullptr : reinterpret_cast<PFN_vkVoidFunction>(trace);
    if (std::strcmp(name, "vkGetBufferDeviceAddress") == 0) return reinterpret_cast<PFN_vkVoidFunction>(address);
    return nullptr;
}
VKAPI_ATTR void VKAPI_CALL get_properties(VkPhysicalDevice, VkPhysicalDeviceProperties2* out) {
    auto* ray = static_cast<VkPhysicalDeviceRayTracingPipelinePropertiesKHR*>(out->pNext);
    CHECK(ray->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
    *ray = properties;
    ray->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR;
    std::copy_n(limit_counts, 3, out->properties.limits.maxComputeWorkGroupCount);
    std::copy_n(limit_sizes, 3, out->properties.limits.maxComputeWorkGroupSize);
}
VKAPI_ATTR void VKAPI_CALL destroy_buffer(VkDevice device, VkBuffer buffer, const VkAllocationCallbacks* alloc) {
    ++destroyed_buffers; fake::destroy_buffer(device, buffer, alloc);
}
VKAPI_ATTR void VKAPI_CALL free_memory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks* alloc) {
    CHECK(!fake::memories.at(fake::handle_id(memory)).mapped);
    ++freed_memories; fake::free_memory(device, memory, alloc);
}
VKAPI_ATTR VkResult VKAPI_CALL flush(VkDevice, uint32_t count, const VkMappedMemoryRange* range) {
    CHECK(count == 1 && range->offset == 0 && range->size == 256); ++flushes; return flush_result;
}
DeviceFns device(bool coherent = true) {
    auto f = make_device_fns();
    f.buffer_functions.destroy_buffer = destroy_buffer;
    f.buffer_functions.free_memory = free_memory;
    f.buffer_functions.flush = flush;
    if (!coherent) f.memory_properties.memoryTypes[1].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    return f;
}
VkResult create(bedray::core::RayGenerationPipeline& owner, VkPipeline& pipeline) {
    return owner.create(fake::make_handle<VkPipelineCache>(0x44), fake::make_handle<VkShaderModule>(0x33),
        "raygen_main", fake::make_handle<VkPipelineLayout>(0x22), pipeline);
}
void destroy_caller_pipeline(VkPipeline pipeline) {
    if (pipeline != VK_NULL_HANDLE) { CHECK(pipeline == fake::make_handle<VkPipeline>(0x55)); ++destroyed_pipelines; }
}
void clean() {
    CHECK(fake::buffers.empty()); CHECK(fake::memories.empty()); CHECK(fake::faults == 0);
    CHECK(created_pipelines == destroyed_pipelines);
}

void successful_alignment_limits_and_dispatch(bool coherent) {
    reset();
    {
        bedray::core::RayGenerationPipeline owner(device(coherent), get_proc, get_properties);
        CHECK(!owner.valid_extent(1, 1, 1));
        VkPipeline pipeline = VK_NULL_HANDLE;
        CHECK(create(owner, pipeline) == VK_SUCCESS);
        CHECK(received_cache == fake::make_handle<VkPipelineCache>(0x44));
        CHECK(owner.valid_extent(16, 16, 2)); // exactly maxRayDispatchInvocationCount
        CHECK(!owner.valid_extent(17, 16, 2));
        CHECK(!owner.valid_extent(33, 1, 1)); CHECK(!owner.valid_extent(1, 21, 1)); CHECK(!owner.valid_extent(1, 1, 13));
        CHECK(!owner.valid_extent(0, 1, 1)); CHECK(!owner.valid_extent(1, 0, 1)); CHECK(!owner.valid_extent(1, 1, 0));
        CHECK(!owner.valid_extent(UINT32_MAX, UINT32_MAX, UINT32_MAX));
        owner.record(fake::make_handle<VkCommandBuffer>(0x77), 16, 16, 2);
        CHECK(traces == 1 && recorded_region.deviceAddress == 0x100040);
        CHECK(recorded_region.stride == 32 && recorded_region.size == 32);
        CHECK(fake::buffers.size() == 1 && fake::memories.size() == 1);
        if (!fake::buffers.empty()) {
            const auto& buffer = fake::buffers.begin()->second;
            CHECK(recorded_region.deviceAddress + recorded_region.size <= base_address + buffer.size);
            const auto& bytes = fake::memories.at(buffer.memory).bytes;
            const auto offset = static_cast<size_t>(recorded_region.deviceAddress - base_address);
            CHECK(std::all_of(bytes.begin() + offset, bytes.begin() + offset + properties.shaderGroupHandleSize,
                [](unsigned char value) { return value == 0xa7; }));
        }
        CHECK(flushes == (coherent ? 0u : 1u));
        destroy_caller_pipeline(pipeline); // matches the containing program's ownership contract
    }
    CHECK(destroyed_buffers == 1 && freed_memories == 1);
    clean();
}

template<class Configure>
void failed_registration(Configure configure, VkResult expected, unsigned expected_buffers, unsigned expected_memories) {
    reset(); configure();
    {
        bedray::core::RayGenerationPipeline owner(device(false), get_proc, get_properties);
        VkPipeline pipeline = VK_NULL_HANDLE;
        CHECK(create(owner, pipeline) == expected);
        CHECK(!owner.valid_extent(1, 1, 1)); CHECK(traces == 0);
        destroy_caller_pipeline(pipeline);
    }
    CHECK(destroyed_buffers == expected_buffers && freed_memories == expected_memories);
    clean();
}

void validation_and_failure_cleanup() {
    failed_registration([] { missing_trace = true; }, VK_ERROR_FEATURE_NOT_PRESENT, 0, 0);
    failed_registration([] { properties.shaderGroupHandleAlignment = 3; }, VK_ERROR_FEATURE_NOT_PRESENT, 0, 0);
    failed_registration([] { properties.shaderGroupBaseAlignment = 0; }, VK_ERROR_FEATURE_NOT_PRESENT, 0, 0);
    failed_registration([] { properties.maxRayRecursionDepth = 0; }, VK_ERROR_FEATURE_NOT_PRESENT, 0, 0);
    failed_registration([] { properties.maxRayDispatchInvocationCount = 0; }, VK_ERROR_FEATURE_NOT_PRESENT, 0, 0);
    failed_registration([] { properties.shaderGroupHandleSize = properties.maxShaderGroupStride = 255; }, VK_ERROR_FEATURE_NOT_PRESENT, 0, 0);
    failed_registration([] { pipeline_result = VK_ERROR_DEVICE_LOST; }, VK_ERROR_DEVICE_LOST, 0, 0);
    failed_registration([] { handle_result = VK_ERROR_OUT_OF_HOST_MEMORY; }, VK_ERROR_OUT_OF_HOST_MEMORY, 0, 0);
    failed_registration([] { fake::fail_allocate_after = 0; }, VK_ERROR_OUT_OF_DEVICE_MEMORY, 1, 0);
    failed_registration([] { base_address = 0; }, VK_ERROR_INITIALIZATION_FAILED, 1, 1);
    failed_registration([] { base_address = UINT64_MAX - 10; }, VK_ERROR_INITIALIZATION_FAILED, 1, 1);
    failed_registration([] { flush_result = VK_ERROR_OUT_OF_HOST_MEMORY; }, VK_ERROR_OUT_OF_HOST_MEMORY, 1, 1);
    failed_registration([] { flush_result = VK_ERROR_DEVICE_LOST; }, VK_ERROR_DEVICE_LOST, 1, 1);
}

void launch_product_cannot_wrap_at_64_bits() {
    reset();
    properties.maxRayDispatchInvocationCount = UINT32_MAX;
    std::fill_n(limit_counts, 3, UINT32_MAX); std::fill_n(limit_sizes, 3, UINT32_MAX);
    {
        bedray::core::RayGenerationPipeline owner(device(), get_proc, get_properties);
        VkPipeline pipeline = VK_NULL_HANDLE;
        CHECK(create(owner, pipeline) == VK_SUCCESS);
        CHECK(owner.valid_extent(UINT32_MAX, 1, 1));
        CHECK(!owner.valid_extent(UINT32_MAX, 2, 1));
        // All axes fit; their product is 2^66, which a naive uint64_t multiplication wraps to zero.
        CHECK(!owner.valid_extent(1u << 22, 1u << 22, 1u << 22));
        destroy_caller_pipeline(pipeline);
    }
    clean();
}
}

int main() {
    successful_alignment_limits_and_dispatch(true);
    successful_alignment_limits_and_dispatch(false);
    validation_and_failure_cleanup();
    launch_product_cannot_wrap_at_64_bits();
    if (failures == 0) std::puts("ray-generation pipeline/SBT owner tests passed");
    return failures == 0 ? 0 : 1;
}
