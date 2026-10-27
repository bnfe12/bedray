#include "bedray/gpu_buffers_c.h"
#include <stddef.h>

/* Links against the standalone DLL import library and checks the actual C
 * exports/layout. It needs no Vulkan driver or game. */
int main(void) {
    BedrayGpuAbiInfo info = {0};
    const uint32_t owner_offsets[] = {
        offsetof(BedrayGpuOwnerDesc, abi_version), offsetof(BedrayGpuOwnerDesc, struct_size),
        offsetof(BedrayGpuOwnerDesc, device), offsetof(BedrayGpuOwnerDesc, generation),
        offsetof(BedrayGpuOwnerDesc, budget_bytes), offsetof(BedrayGpuOwnerDesc, non_coherent_atom_size),
        offsetof(BedrayGpuOwnerDesc, enabled_flags), offsetof(BedrayGpuOwnerDesc, memory_type_count),
        offsetof(BedrayGpuOwnerDesc, memory_type_flags), offsetof(BedrayGpuOwnerDesc, functions)
    };
    const uint32_t buffer_offsets[] = {
        offsetof(BedrayGpuBufferInfo, owner_generation), offsetof(BedrayGpuBufferInfo, serial),
        offsetof(BedrayGpuBufferInfo, buffer), offsetof(BedrayGpuBufferInfo, size),
        offsetof(BedrayGpuBufferInfo, device_address), offsetof(BedrayGpuBufferInfo, memory_flags)
    };
    const uint32_t use_offsets[] = {offsetof(BedrayGpuUseToken, owner_generation), offsetof(BedrayGpuUseToken, serial)};
    struct OwnerAlignment { char lead; BedrayGpuOwnerDesc item; };
    struct BufferAlignment { char lead; BedrayGpuBufferInfo item; };
    struct UseAlignment { char lead; BedrayGpuUseToken item; };
    uint32_t i;
    if (bedray_gpu_abi_version() != BEDRAY_GPU_ABI_VERSION) return 1;
    if (bedray_gpu_abi_info(&info, (uint32_t)sizeof(info)) != BEDRAY_GPU_OK) return 2;
    if (info.sizeof_info != sizeof(info) || info.sizeof_owner_desc != sizeof(BedrayGpuOwnerDesc) ||
        info.sizeof_buffer_info != sizeof(BedrayGpuBufferInfo) || info.sizeof_use_token != sizeof(BedrayGpuUseToken)) return 3;
    if (info.align_owner_desc != offsetof(struct OwnerAlignment, item) ||
        info.align_buffer_info != offsetof(struct BufferAlignment, item) ||
        info.align_use_token != offsetof(struct UseAlignment, item)) return 4;
    for (i = 0; i < 10; ++i) if (info.owner_offsets[i] != owner_offsets[i]) return 5;
    for (i = 0; i < 6; ++i) if (info.buffer_offsets[i] != buffer_offsets[i]) return 6;
    for (i = 0; i < 2; ++i) if (info.use_offsets[i] != use_offsets[i]) return 7;
    return 0;
}
