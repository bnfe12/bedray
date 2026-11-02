#define VK_NO_PROTOTYPES
#include "bedray_core_memory.h"
#include "core_internal.h"
#include "bedray/memory_binding.hpp"
#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <new>

namespace {
void clear_error(BedrayError* error) { if (error) { error->status = BEDRAY_OK; error->message[0] = 0; } }
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* format, ...) {
    if (error) {
        error->status = status;
        va_list args; va_start(args, format);
        std::vsnprintf(error->message, sizeof(error->message), format, args);
        va_end(args);
    }
    return status;
}
}
extern "C" {
BEDRAY_API uint32_t BEDRAY_CALL bedray_core_memory_version(void) try { return 1; } catch (...) { return 0; }

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_memory_configure(
    BedrayCore* core, uint64_t limit, uint64_t block, uint32_t flags, BedrayError* error) try {
    clear_error(error);
    const auto state = bedray::core::memory_access(core);
    if (!core || !limit || block < 4096 || block > (256ull << 20) || (block & (block - 1))
        || (flags & ~BEDRAY_MEMORY_HEAP_BUDGET))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "memory service needs a positive budget and power-of-two 4KiB..256MiB blocks");
    if (state.functions->memory || !state.programs_empty)
        return fail(error, BEDRAY_ERROR_IN_USE, "configure shared memory once, before registering programs");
    bedray::gpu::MemoryContext context;
    context.device = state.functions->device; context.physical = state.functions->physical_device;
    context.properties = state.functions->memory_properties;
    context.limit = limit; context.block_size = block;
    context.atom_size = state.functions->non_coherent_atom_size;
    context.max_blocks = std::min(2048u, state.max_allocations);
    context.heap_budget_enabled = (flags & BEDRAY_MEMORY_HEAP_BUDGET) != 0;
    auto& f = context.functions;
    const auto& buffers = state.functions->buffer_functions;
    f.allocate = buffers.allocate_memory; f.free = buffers.free_memory;
    f.map = buffers.map_memory; f.unmap = buffers.unmap_memory; f.flush = buffers.flush; f.invalidate = buffers.invalidate;
    f.buffer_requirements = reinterpret_cast<PFN_vkGetBufferMemoryRequirements2>(state.get_device_proc(state.functions->device, "vkGetBufferMemoryRequirements2"));
    f.image_requirements = reinterpret_cast<PFN_vkGetImageMemoryRequirements2>(state.get_device_proc(state.functions->device, "vkGetImageMemoryRequirements2"));
    f.heap_properties = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties2>(state.get_instance_proc(state.instance, "vkGetPhysicalDeviceMemoryProperties2"));
    auto memory = std::make_shared<bedray::gpu::MemoryAllocator>(context);
    if (!memory->valid()) return fail(error, BEDRAY_ERROR_UNSUPPORTED, "shared memory needs Vulkan 1.1 requirements2, valid heap limits and enabled budget query functions");
    state.functions->memory = std::move(memory);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate shared memory service");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring shared memory");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_memory_attach(
    BedrayCore* core, uint32_t kind, void* owner, BedrayError* error) try {
    clear_error(error);
    const auto state = bedray::core::memory_access(core);
    if (!core || !state.functions->memory || !owner)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "memory attachment needs a configured core and empty owner");
    if (state.functions->memory->lost())
        return fail(error, BEDRAY_ERROR_DEVICE_LOST, "shared allocation service observed device loss");
    auto memory = state.functions->memory;
    bedray::gpu::Status status = bedray::gpu::Status::invalid_argument;
    switch (kind) {
        case BEDRAY_MEMORY_BUFFERS: status = bedray::gpu::attach_memory(static_cast<BedrayGpuOwner*>(owner), memory); break;
        case BEDRAY_MEMORY_IMAGES: status = bedray::gpu::attach_memory(static_cast<BedrayGpuImageOwner*>(owner), memory); break;
        case BEDRAY_MEMORY_SCENE: status = bedray::gpu::attach_memory(static_cast<BedrayScene*>(owner), memory); break;
        default: return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "unknown memory owner kind %u", kind);
    }
    if (status == bedray::gpu::Status::ok) return BEDRAY_OK;
    return fail(error, status == bedray::gpu::Status::in_use ? BEDRAY_ERROR_IN_USE : BEDRAY_ERROR_INVALID_ARGUMENT,
        "memory attachment refused: kind=%u resource_status=%u (owner must be empty and match the core device)", kind, static_cast<uint32_t>(status));
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot attach shared memory service");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception attaching shared memory");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_memory_stats(
    BedrayCore* core, BedrayMemoryStats* out, uint32_t size, BedrayError* error) try {
    clear_error(error);
    const auto state = bedray::core::memory_access(core);
    static_assert(sizeof(BedrayMemoryStats) == 72);
    if (!core || !state.functions->memory || !out || size < sizeof(*out))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "memory stats need a configured core and 72-byte output");
    const auto stats = state.functions->memory->stats();
    *out = {stats.limit, stats.reserved, stats.used, stats.peak_reserved, stats.allocations, stats.blocks,
        stats.heap_budget, stats.heap_usage, stats.flags};
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot query memory service");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception querying shared memory");
}
} // extern "C"

