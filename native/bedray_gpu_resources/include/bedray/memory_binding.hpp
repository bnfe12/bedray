#pragma once
#include "bedray/gpu_buffers.hpp"
struct BedrayGpuOwner;
struct BedrayGpuImageOwner;
struct BedrayScene;
namespace bedray::gpu {
// Internal C++ binding only; public clients use the checked additive core exports.
Status attach_memory(BedrayGpuOwner*, std::shared_ptr<MemoryAllocator>) noexcept;
Status attach_memory(BedrayGpuImageOwner*, std::shared_ptr<MemoryAllocator>) noexcept;
Status attach_memory(BedrayScene*, std::shared_ptr<MemoryAllocator>) noexcept;
}
