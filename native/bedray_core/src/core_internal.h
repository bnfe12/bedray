#pragma once

#include "bedray_core.h"

#include <bedray/gpu_buffers.hpp>
#include <bedray/gpu_images.hpp>

#include <vulkan/vulkan_core.h>

#include <cstdint>

namespace bedray::core {

// Device entry points the internal passes may use. The core still creates no
// device, queue, swapchain or submission; every function here was obtained from
// the host-supplied vkGetDeviceProcAddr/vkGetInstanceProcAddr.
struct DeviceFns {
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memory_properties{};
    VkDeviceSize non_coherent_atom_size = 0;
    std::uint64_t generation = 0;
    gpu::ImageFunctions image_functions{};
    gpu::BufferFunctions buffer_functions{};
    PFN_vkCmdPipelineBarrier cmd_pipeline_barrier = nullptr;
    PFN_vkCmdFillBuffer cmd_fill_buffer = nullptr;
};

struct ProgramContract {
    std::uint32_t push_constant_bytes = 0;
    std::uint32_t binding_count = 0;
};

// Read-only queries over the core's registered programs. They do not retain or
// retire anything; the caller must keep the handle valid for the whole call.
bool program_contract(BedrayCore* core, std::uint64_t handle, ProgramContract& out) noexcept;
bool program_has_binding(BedrayCore* core, std::uint64_t handle,
    std::uint32_t binding, std::uint32_t descriptor_type) noexcept;

} // namespace bedray::core
