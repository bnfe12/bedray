// The real C ABI and registration rollback, with driver entry-point doubles. No GPU.
#include "../src/core.cpp"
#include <cstdlib>

namespace {
VkPipelineCache received_cache = VK_NULL_HANDLE;
VkResult pipeline_result = VK_SUCCESS;
int fault = 0;
unsigned modules = 0, layouts = 0, pipelines = 0;
void require(bool value) { if (!value) std::abort(); }
VKAPI_ATTR VkResult VKAPI_CALL create_module(VkDevice, const VkShaderModuleCreateInfo*, const VkAllocationCallbacks*, VkShaderModule* out) {
    ++modules; *out = reinterpret_cast<VkShaderModule>(101); return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL create_layout(VkDevice, const VkPipelineLayoutCreateInfo*, const VkAllocationCallbacks*, VkPipelineLayout* out) {
    ++layouts; *out = reinterpret_cast<VkPipelineLayout>(102); return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL create_pipeline(VkDevice, VkPipelineCache cache, uint32_t count,
    const VkComputePipelineCreateInfo*, const VkAllocationCallbacks*, VkPipeline* out) {
    require(count == 1); received_cache = cache;
    if (fault == 1) throw std::bad_alloc{};
    if (fault == 2) throw 42;
    if (pipeline_result == VK_SUCCESS) { ++pipelines; *out = reinterpret_cast<VkPipeline>(103); }
    return pipeline_result;
}
VKAPI_ATTR void VKAPI_CALL destroy_module(VkDevice, VkShaderModule, const VkAllocationCallbacks*) { require(modules == 1); --modules; }
VKAPI_ATTR void VKAPI_CALL destroy_layout(VkDevice, VkPipelineLayout, const VkAllocationCallbacks*) { require(layouts == 1); --layouts; }
VKAPI_ATTR void VKAPI_CALL destroy_pipeline(VkDevice, VkPipeline, const VkAllocationCallbacks*) { require(pipelines == 1); --pipelines; }
}

int main() {
    auto* core = new BedrayCore;
    core->properties.limits.maxPushConstantsSize = 128;
    core->create_shader_module = create_module;
    core->create_pipeline_layout = create_layout;
    core->create_compute_pipelines = create_pipeline;
    core->destroy_shader_module = destroy_module;
    core->destroy_pipeline_layout = destroy_layout;
    core->destroy_pipeline = destroy_pipeline;
    uint32_t spirv[] = {kSpirvMagic, 0x00010500, 0, 1, 0};
    BedrayProgramDesc desc{};
    desc.abi_version = BEDRAY_CORE_ABI_VERSION;
    desc.name = "cache_probe"; desc.entry_point = "main";
    desc.spirv = spirv; desc.spirv_word_count = 5;
    BedrayError error{};
    uint64_t program = 99;
    require(bedray_core_register_program_cached(nullptr, &desc, 77, &program, &error) == BEDRAY_ERROR_INVALID_ARGUMENT && program == 0);
    require(bedray_core_register_program_cached(core, &desc, 77, &program, &error) == BEDRAY_OK && program != 0);
    require(received_cache == reinterpret_cast<VkPipelineCache>(77));
    bedray_core_destroy_program(core, program);
    require(modules == 0 && layouts == 0 && pipelines == 0);
    require(bedray_core_register_program(core, &desc, &program, &error) == BEDRAY_OK && received_cache == VK_NULL_HANDLE);
    bedray_core_destroy_program(core, program);
    pipeline_result = VK_ERROR_DEVICE_LOST;
    require(bedray_core_register_program_cached(core, &desc, 88, &program, &error) == BEDRAY_ERROR_DEVICE_LOST);
    require(program == 0 && error.status == BEDRAY_ERROR_DEVICE_LOST && core->programs.empty());
    pipeline_result = VK_SUCCESS;
    for (fault = 1; fault <= 2; ++fault) {
        const auto expected = fault == 1 ? BEDRAY_ERROR_OUT_OF_MEMORY : BEDRAY_ERROR_INTERNAL;
        require(bedray_core_register_program_cached(core, &desc, 99, &program, &error) == expected);
        require(program == 0 && error.status == expected && modules == 0 && layouts == 0 && pipelines == 0);
    }
    // A cache borrow is never retained in core; cache destruction has no callback here.
    require(bedray_core_destroy(core) == BEDRAY_OK);
}
