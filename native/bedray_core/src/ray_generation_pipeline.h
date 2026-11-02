#pragma once

#include "core_internal.h"
#include <memory>

namespace bedray::core {

// A ray-generation-only pipeline can run the same RayQuery integrator as the
// compute backend. Its one SBT record is immutable and lives with the program;
// the core's existing recording references retain both until exact retirement.
// This owner never submits work or creates a queue/device/command buffer.
class RayGenerationPipeline final {
public:
    RayGenerationPipeline(const DeviceFns& device, PFN_vkGetDeviceProcAddr get_proc,
        PFN_vkGetPhysicalDeviceProperties2 get_properties);
    ~RayGenerationPipeline();
    RayGenerationPipeline(const RayGenerationPipeline&) = delete;
    RayGenerationPipeline& operator=(const RayGenerationPipeline&) = delete;

    [[nodiscard]] VkResult create(VkPipelineCache cache, VkShaderModule module,
        const char* entry, VkPipelineLayout layout, VkPipeline& pipeline);
    [[nodiscard]] bool valid_extent(uint32_t x, uint32_t y, uint32_t z) const noexcept;
    void record(VkCommandBuffer command, uint32_t x, uint32_t y, uint32_t z) const noexcept;

private:
    VkDevice device_ = VK_NULL_HANDLE;
    PFN_vkCreateRayTracingPipelinesKHR create_ = nullptr;
    PFN_vkGetRayTracingShaderGroupHandlesKHR handles_ = nullptr;
    PFN_vkCmdTraceRaysKHR trace_ = nullptr;
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR properties_{};
    VkPhysicalDeviceLimits limits_{};
    std::unique_ptr<gpu::GpuBuffers> buffers_;
    gpu::BufferId sbt_{};
    VkStridedDeviceAddressRegionKHR region_{};
};

} // namespace bedray::core
