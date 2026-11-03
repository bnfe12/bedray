// Exercise real C exports with allocation failures before owner publication.
// No Vulkan callback is invoked: the nonzero addresses only satisfy validation.
#include "bedray/gpu_buffers_c.h"
#include "bedray/gpu_images_c.h"
#include "bedray/history_c.h"

#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <new>

namespace {
enum class Fault { none, allocation, unexpected };
Fault next_fault = Fault::none;
void check(bool ok, const char* expression, int line) {
    if (!ok) {
        std::fprintf(stderr, "check failed at %d: %s\n", line, expression);
        std::abort();
    }
}
#define CHECK(expression) check((expression), #expression, __LINE__)
}

void* operator new(std::size_t bytes) {
    const auto fault = next_fault;
    next_fault = Fault::none;
    if (fault == Fault::allocation) throw std::bad_alloc{};
    if (fault == Fault::unexpected) throw 42;
    if (void* p = std::malloc(bytes ? bytes : 1)) return p;
    throw std::bad_alloc{};
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }

int main() {
    BedrayGpuOwnerDesc buffer{};
    buffer.abi_version = BEDRAY_GPU_ABI_VERSION;
    buffer.struct_size = sizeof(buffer);
    buffer.device = buffer.generation = buffer.budget_bytes = buffer.non_coherent_atom_size = 1;
    buffer.enabled_flags = BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED;
    buffer.memory_type_count = 1;
    for (auto& f : buffer.functions) f = 1;

    BedrayGpuImageOwnerDesc image{};
    image.abi_version = BEDRAY_GPU_IMAGE_ABI_VERSION;
    image.struct_size = sizeof(image);
    image.physical_device = image.device = image.generation = image.budget_bytes = 1;
    image.enabled_flags = BEDRAY_GPU_DEDICATED_ALLOCATION_ENABLED;
    image.memory_type_count = 1;
    for (auto& f : image.functions) f = 1;

    for (const auto fault : {Fault::allocation, Fault::unexpected}) {
        BedrayGpuOwner* b = reinterpret_cast<BedrayGpuOwner*>(1);
        next_fault = fault;
        const auto bs = bedray_gpu_owner_create(&buffer, &b);
        CHECK(next_fault == Fault::none);
        CHECK(bs == static_cast<BedrayGpuStatus>(fault == Fault::allocation ? BEDRAY_GPU_OUT_OF_MEMORY : BEDRAY_GPU_INTERNAL_ERROR));
        CHECK(b == nullptr);

        BedrayGpuImageOwner* i = reinterpret_cast<BedrayGpuImageOwner*>(1);
        next_fault = fault;
        const auto is = bedray_gpu_image_owner_create(&image, &i);
        CHECK(next_fault == Fault::none);
        CHECK(is == static_cast<BedrayGpuStatus>(fault == Fault::allocation ? BEDRAY_GPU_OUT_OF_MEMORY : BEDRAY_GPU_INTERNAL_ERROR));
        CHECK(i == nullptr);

        BedrayHistory* h = reinterpret_cast<BedrayHistory*>(1);
        next_fault = fault;
        const auto hs = bedray_history_create(1024, &h);
        CHECK(next_fault == Fault::none);
        CHECK(hs == static_cast<BedrayHistoryStatus>(fault == Fault::allocation ? BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY : BEDRAY_HISTORY_INTERNAL_ERROR));
        CHECK(h == nullptr);
    }

    // Successful calls still publish valid owners after every injected failure.
    BedrayGpuOwner* b = nullptr;
    CHECK(bedray_gpu_owner_create(&buffer, &b) == BEDRAY_GPU_OK && b);
    CHECK(bedray_gpu_owner_destroy(b) == BEDRAY_GPU_OK);
    BedrayGpuImageOwner* i = nullptr;
    CHECK(bedray_gpu_image_owner_create(&image, &i) == BEDRAY_GPU_OK && i);
    CHECK(bedray_gpu_image_owner_destroy(i) == BEDRAY_GPU_OK);
    BedrayHistory* h = nullptr;
    CHECK(bedray_history_create(1024, &h) == BEDRAY_HISTORY_OK && h);
    CHECK(bedray_history_destroy(h) == BEDRAY_HISTORY_OK);

    std::puts("C ABI allocation and unexpected-exception containment passed");
}
