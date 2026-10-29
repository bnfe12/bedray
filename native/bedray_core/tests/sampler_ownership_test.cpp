// Real C ABI with Vulkan function doubles; no device or GPU execution.
#include "../src/core.cpp"
#include <cstdlib>

namespace {
unsigned creates = 0, destroys = 0;
bool fail_next = true;
bool throw_allocation = false;
void require(bool condition) { if (!condition) std::abort(); }
VKAPI_ATTR VkResult VKAPI_CALL throwing_create(VkDevice, const VkSamplerCreateInfo*,
    const VkAllocationCallbacks*, VkSampler*) {
    if (throw_allocation) throw std::bad_alloc{};
    throw 42;
}
VKAPI_ATTR void VKAPI_CALL throwing_destroy(VkDevice, VkSampler, const VkAllocationCallbacks*) {
    if (throw_allocation) throw std::bad_alloc{};
    throw 42;
}
void exception_boundaries() {
    auto* core = new BedrayCore;
    core->create_sampler = throwing_create;
    BedrayError error{};
    for (unsigned fault = 0; fault < 2; ++fault) {
        throw_allocation = fault == 0;
        const auto expected = throw_allocation ? BEDRAY_ERROR_OUT_OF_MEMORY : BEDRAY_ERROR_INTERNAL;
        uint64_t sampler = 55;
        require(bedray_core_atlas_sampler(core, &sampler, &error) == expected);
        require(sampler == 0 && error.status == expected);
        require(bedray_core_linear_sampler(core, &sampler, &error) == expected);
        require(sampler == 0 && error.status == expected);
        core->atlas_sampler = reinterpret_cast<VkSampler>(100);
        core->destroy_sampler = throwing_destroy;
        require(bedray_core_destroy(core) == expected);
        core->atlas_sampler = VK_NULL_HANDLE;
    }
    require(bedray_core_destroy(core) == BEDRAY_OK);
}
VKAPI_ATTR VkResult VKAPI_CALL create(VkDevice, const VkSamplerCreateInfo* info,
    const VkAllocationCallbacks*, VkSampler* out) {
    ++creates;
    require(info->sType == VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
    require((info->minFilter == VK_FILTER_NEAREST || info->minFilter == VK_FILTER_LINEAR)
        && info->magFilter == info->minFilter);
    require(info->mipmapMode == VK_SAMPLER_MIPMAP_MODE_NEAREST);
    require(info->addressModeU == VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
        && info->addressModeV == info->addressModeU && info->addressModeW == info->addressModeU);
    require(info->minLod == 0 && info->maxLod == 0 && !info->unnormalizedCoordinates);
    require(!info->anisotropyEnable && !info->compareEnable);
    if (fail_next) { fail_next = false; return VK_ERROR_OUT_OF_HOST_MEMORY; }
    *out = reinterpret_cast<VkSampler>(static_cast<uintptr_t>(info->minFilter == VK_FILTER_LINEAR ? 101 : 100));
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy(VkDevice, VkSampler sampler, const VkAllocationCallbacks*) {
    require(sampler == reinterpret_cast<VkSampler>(100) || sampler == reinterpret_cast<VkSampler>(101));
    ++destroys;
}
}

int main() {
    exception_boundaries();
    auto* core = new BedrayCore;
    core->create_sampler = create;
    core->destroy_sampler = destroy;
    BedrayError error{};
    uint64_t handle = 55;
    require(bedray_core_atlas_sampler(nullptr, &handle, &error) == BEDRAY_ERROR_INVALID_ARGUMENT && handle == 0);
    require(bedray_core_atlas_sampler(core, nullptr, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    require(bedray_core_atlas_sampler(core, &handle, &error) == BEDRAY_ERROR_OUT_OF_MEMORY && handle == 0);
    require(core->atlas_sampler == VK_NULL_HANDLE && destroys == 0);
    require(bedray_core_atlas_sampler(core, &handle, &error) == BEDRAY_OK && handle == 100);
    require(bedray_core_atlas_sampler(core, &handle, &error) == BEDRAY_OK && handle == 100 && creates == 2);
    require(bedray_core_linear_sampler(core, &handle, &error) == BEDRAY_OK && handle == 101 && creates == 3);
    require(bedray_core_linear_sampler(core, &handle, &error) == BEDRAY_OK && handle == 101 && creates == 3);
    core->recordings.push_back({reinterpret_cast<VkCommandBuffer>(1), VK_NULL_HANDLE, nullptr, VK_NULL_HANDLE});
    require(bedray_core_destroy(core) == BEDRAY_ERROR_IN_USE && destroys == 0);
    core->recordings.clear(); // Host proof of discarded/completed recording.
    require(bedray_core_destroy(core) == BEDRAY_OK && destroys == 2);
    return 0;
}
