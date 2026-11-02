#include "ray_generation_pipeline.h"
#include <limits>
#include <vector>

namespace bedray::core {
namespace {
bool power_of_two(uint32_t value) noexcept { return value != 0 && (value & (value - 1u)) == 0; }
VkResult buffer_result(gpu::Status status, VkResult result) noexcept {
    if (status == gpu::Status::ok) return VK_SUCCESS;
    if (result != VK_SUCCESS) return result;
    return status == gpu::Status::out_of_memory || status == gpu::Status::budget_exceeded
        ? VK_ERROR_OUT_OF_DEVICE_MEMORY : VK_ERROR_INITIALIZATION_FAILED;
}
}

RayGenerationPipeline::RayGenerationPipeline(const DeviceFns& device,
    PFN_vkGetDeviceProcAddr get_proc, PFN_vkGetPhysicalDeviceProperties2 get_properties)
    : device_(device.device) {
    if (!get_proc || !get_properties) return;
    create_ = reinterpret_cast<PFN_vkCreateRayTracingPipelinesKHR>(get_proc(device_, "vkCreateRayTracingPipelinesKHR"));
    handles_ = reinterpret_cast<PFN_vkGetRayTracingShaderGroupHandlesKHR>(get_proc(device_, "vkGetRayTracingShaderGroupHandlesKHR"));
    trace_ = reinterpret_cast<PFN_vkCmdTraceRaysKHR>(get_proc(device_, "vkCmdTraceRaysKHR"));
    properties_.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR;
    VkPhysicalDeviceProperties2 properties{};
    properties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    properties.pNext = &properties_;
    get_properties(device.physical_device, &properties);
    limits_ = properties.properties.limits;

    gpu::DeviceContext context{};
    context.device = device_;
    context.functions = device.buffer_functions;
    context.functions.get_buffer_device_address = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(
        get_proc(device_, "vkGetBufferDeviceAddress"));
    if (!context.functions.get_buffer_device_address)
        context.functions.get_buffer_device_address = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(
            get_proc(device_, "vkGetBufferDeviceAddressKHR"));
    context.memory_properties = device.memory_properties;
    context.memory = device.memory;
    context.non_coherent_atom_size = device.non_coherent_atom_size;
    context.dedicated_allocation_enabled = true; // the core requires Vulkan 1.1+
    context.buffer_device_address_enabled = true; // checked before constructing this owner
    context.generation = device.generation;
    buffers_ = std::make_unique<gpu::GpuBuffers>(context, 16u * 1024u * 1024u);
}

RayGenerationPipeline::~RayGenerationPipeline() {
    if (!buffers_) return;
    if (sbt_.serial != 0 && buffers_->retire(sbt_) == gpu::Status::device_lost) {
        // The program owner has retired every recording, or registration failed before the SBT could be submitted.
        // This private owner has no independent users; ordinary collect/close refuse a loss recorded by SBT flush.
        static_cast<void>(buffers_->release_after_device_loss());
        return;
    }
    static_cast<void>(buffers_->collect());
    static_cast<void>(buffers_->close());
}

VkResult RayGenerationPipeline::create(VkPipelineCache cache, VkShaderModule module,
    const char* entry, VkPipelineLayout layout, VkPipeline& pipeline) {
    const auto& p = properties_;
    if (!create_ || !handles_ || !trace_ || !buffers_ || !buffers_->valid()
        || !power_of_two(p.shaderGroupHandleAlignment) || !power_of_two(p.shaderGroupBaseAlignment)
        || p.shaderGroupHandleSize == 0 || p.shaderGroupHandleSize > p.maxShaderGroupStride
        || p.maxRayRecursionDepth < 1 || p.maxRayDispatchInvocationCount == 0 || sbt_.serial != 0)
        return VK_ERROR_FEATURE_NOT_PRESENT;
    const uint64_t stride = (uint64_t(p.shaderGroupHandleSize) + p.shaderGroupHandleAlignment - 1u)
        & ~(uint64_t(p.shaderGroupHandleAlignment) - 1u);
    if (stride > p.maxShaderGroupStride || stride > 1024u * 1024u
        || p.shaderGroupBaseAlignment > 1024u * 1024u) return VK_ERROR_FEATURE_NOT_PRESENT;
    // Allocate host bookkeeping before Vulkan accepts the pipeline handle.
    std::vector<uint8_t> handle(p.shaderGroupHandleSize);
    VkPipelineShaderStageCreateInfo stage{};
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    stage.module = module;
    stage.pName = entry;
    VkRayTracingShaderGroupCreateInfoKHR group{};
    group.sType = VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR;
    group.type = VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR;
    group.generalShader = 0;
    group.closestHitShader = group.anyHitShader = group.intersectionShader = VK_SHADER_UNUSED_KHR;
    VkRayTracingPipelineCreateInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR;
    info.stageCount = info.groupCount = 1;
    info.pStages = &stage;
    info.pGroups = &group;
    info.maxPipelineRayRecursionDepth = 1;
    info.layout = layout;
    VkResult result = create_(device_, VK_NULL_HANDLE, cache, 1, &info, nullptr, &pipeline);
    if (result != VK_SUCCESS) return result;
    result = handles_(device_, pipeline, 0, 1, handle.size(), handle.data());
    if (result != VK_SUCCESS) return result;
    gpu::BufferView view{};
    const auto created = buffers_->create({stride + p.shaderGroupBaseAlignment - 1u,
        VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, true}, sbt_, view, result);
    if (created != gpu::Status::ok) return buffer_result(created, result);
    const auto address = (view.device_address + p.shaderGroupBaseAlignment - 1u)
        & ~(VkDeviceAddress(p.shaderGroupBaseAlignment) - 1u);
    if (view.device_address == 0 || address < view.device_address)
        return VK_ERROR_INITIALIZATION_FAILED;
    const auto written = buffers_->write(sbt_, address - view.device_address, handle.data(), handle.size(), result);
    if (written != gpu::Status::ok) return buffer_result(written, result);
    region_ = {address, stride, stride};
    return VK_SUCCESS;
}

bool RayGenerationPipeline::valid_extent(uint32_t x, uint32_t y, uint32_t z) const noexcept {
    const uint32_t dimensions[]{x, y, z};
    uint64_t remaining = properties_.maxRayDispatchInvocationCount;
    for (unsigned i = 0; i < 3; ++i) {
        if (dimensions[i] == 0 || uint64_t(dimensions[i]) > uint64_t(limits_.maxComputeWorkGroupCount[i])
            * limits_.maxComputeWorkGroupSize[i] || dimensions[i] > remaining) return false;
        remaining /= dimensions[i];
    }
    return region_.deviceAddress != 0;
}

void RayGenerationPipeline::record(VkCommandBuffer command, uint32_t x, uint32_t y, uint32_t z) const noexcept {
    const VkStridedDeviceAddressRegionKHR empty{};
    trace_(command, &region_, &empty, &empty, &empty, x, y, z);
}

} // namespace bedray::core
