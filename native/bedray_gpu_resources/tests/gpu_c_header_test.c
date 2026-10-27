#include "bedray/gpu_buffers_c.h"

/* The public interface must be consumable by a C compiler without Vulkan or
 * C++ headers. Runtime ABI checks are in the mock-driver test. */
int main(void) {
    BedrayGpuOwnerDesc desc = {0};
    desc.abi_version = BEDRAY_GPU_ABI_VERSION;
    return desc.abi_version == 1u && BEDRAY_GPU_MAX_MEMORY_TYPES == 32u ? 0 : 1;
}
