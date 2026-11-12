#define VK_NO_PROTOTYPES
#include "core_state.h"

#include <map>

extern "C" {

static BedrayStatus register_program(
    BedrayCore* core,
    const BedrayProgramDesc* desc,
    VkPipelineCache pipeline_cache,
    uint64_t* out_program,
    BedrayError* error) try {
    clear_error(error);
    if (out_program != nullptr) *out_program = 0;
    if (core == nullptr || desc == nullptr || out_program == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc/out_program is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (desc->name == nullptr || desc->name[0] == '\0') {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program name is empty");
    }
    const bool ray_generation = (desc->flags & BEDRAY_PROGRAM_RAYGEN) != 0;
    if ((desc->flags & ~(BEDRAY_PROGRAM_RAYGEN | BEDRAY_PROGRAM_SER_NV)) != 0
        || ((desc->flags & BEDRAY_PROGRAM_SER_NV) != 0 && !ray_generation))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid program flags 0x%x", desc->flags);
    uint32_t required = ray_generation ? BEDRAY_CAP_RAY_TRACING_PIPELINE | BEDRAY_CAP_BUFFER_DEVICE_ADDRESS : 0u;
    if ((desc->flags & BEDRAY_PROGRAM_SER_NV) != 0) required |= BEDRAY_CAP_INVOCATION_REORDER_NV;
    if ((core->capabilities & required) != required)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "program flags 0x%x require enabled capabilities 0x%x (have 0x%x)",
            desc->flags, required, core->capabilities);
    const VkShaderStageFlags shader_stage = ray_generation ? VK_SHADER_STAGE_RAYGEN_BIT_KHR : VK_SHADER_STAGE_COMPUTE_BIT;
    if (desc->entry_point == nullptr || desc->entry_point[0] == '\0') {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "entry point is empty");
    }
    if (desc->spirv == nullptr || desc->spirv_word_count == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "SPIR-V words are empty");
    }
    if (desc->spirv_word_count > (SIZE_MAX / sizeof(uint32_t))) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "SPIR-V word count overflows size_t");
    }
    if (desc->spirv[0] != kSpirvMagic) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "SPIR-V magic is missing");
    }
    if (desc->binding_count > 0 && desc->bindings == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "binding_count > 0 but bindings is null");
    }
    if (desc->push_constant_bytes > core->properties.limits.maxPushConstantsSize) {
        return fail(error, BEDRAY_ERROR_UNSUPPORTED,
            "push constants %u exceed the device limit %u",
            desc->push_constant_bytes, core->properties.limits.maxPushConstantsSize);
    }
    if ((desc->push_constant_bytes & 3u) != 0u) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "push constant range must be a multiple of four bytes");
    }

    std::vector<BedrayDescriptorBinding> bindings;
    try {
        if (desc->binding_count != 0) {
            bindings.assign(desc->bindings, desc->bindings + desc->binding_count);
        }
    } catch (const std::bad_alloc&) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot copy descriptor bindings");
    }
    std::sort(bindings.begin(), bindings.end(),
        [](const BedrayDescriptorBinding& a, const BedrayDescriptorBinding& b) {
            return a.binding < b.binding;
        });
    for (size_t i = 0; i < bindings.size(); ++i) {
        const BedrayDescriptorBinding& binding = bindings[i];
        if (i > 0 && bindings[i - 1].binding == binding.binding) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "duplicate descriptor binding %u", binding.binding);
        }
        if (binding.descriptor_count != 1) {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: descriptor arrays are not supported in ABI v2", binding.binding);
        }
        if (!supported_descriptor_type(binding.descriptor_type)) {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: descriptor type %u is not supported in ABI v2",
                binding.binding, binding.descriptor_type);
        }
        if ((binding.stage_flags & shader_stage) == 0) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "binding %u: stage flags 0x%x do not match program stage 0x%x", binding.binding, binding.stage_flags, shader_stage);
        }
        if (binding.descriptor_type == VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
            && (core->capabilities & BEDRAY_CAP_ACCELERATION_STRUCTURE) == 0) {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: acceleration structure capability is not enabled", binding.binding);
        }
    }

    auto deleter = [core](BedrayProgram* owned) { destroy_program(core, owned); };
    std::unique_ptr<BedrayProgram, decltype(deleter)> owner(new (std::nothrow) BedrayProgram(), deleter);
    auto* program = owner.get();
    if (program == nullptr) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate program");
    }
    if (core->next_program_handle == 0) {
        return fail(error, BEDRAY_ERROR_INTERNAL, "program handle space exhausted");
    }
    program->handle = core->next_program_handle++;
    program->name = desc->name;
    program->entry_point = desc->entry_point;
    program->bindings = std::move(bindings);
    program->push_constant_bytes = desc->push_constant_bytes;
    program->shader_stage = shader_stage;

    VkShaderModuleCreateInfo module_info{};
    module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    module_info.codeSize = static_cast<size_t>(desc->spirv_word_count) * sizeof(uint32_t);
    module_info.pCode = desc->spirv;
    VkResult result = core->create_shader_module(core->device, &module_info, nullptr, &program->module);
    if (result != VK_SUCCESS) {
        const BedrayStatus status = vk_status(result);
        return fail(error, status, "vkCreateShaderModule(%s) failed: VkResult=%d",
            desc->name, static_cast<int>(result));
    }

    std::vector<VkDescriptorSetLayoutBinding> layout_bindings;
    layout_bindings.reserve(program->bindings.size());
    for (const BedrayDescriptorBinding& binding : program->bindings) {
        VkDescriptorSetLayoutBinding layout_binding{};
        layout_binding.binding = binding.binding;
        layout_binding.descriptorType = static_cast<VkDescriptorType>(binding.descriptor_type);
        layout_binding.descriptorCount = binding.descriptor_count;
        layout_binding.stageFlags = binding.stage_flags;
        layout_bindings.push_back(layout_binding);
    }

    if (!layout_bindings.empty()) {
        VkDescriptorSetLayoutCreateInfo layout_info{};
        layout_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layout_info.bindingCount = static_cast<uint32_t>(layout_bindings.size());
        layout_info.pBindings = layout_bindings.data();
        result = core->create_descriptor_set_layout(
            core->device, &layout_info, nullptr, &program->set_layout);
        if (result != VK_SUCCESS) {
            const BedrayStatus status = vk_status(result);
            return fail(error, status, "vkCreateDescriptorSetLayout(%s) failed: VkResult=%d",
                desc->name, static_cast<int>(result));
        }
    }

    VkPushConstantRange push_range{};
    push_range.stageFlags = shader_stage;
    push_range.offset = 0;
    push_range.size = program->push_constant_bytes;
    VkPipelineLayoutCreateInfo pipeline_layout_info{};
    pipeline_layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    if (program->set_layout != VK_NULL_HANDLE) {
        pipeline_layout_info.setLayoutCount = 1;
        pipeline_layout_info.pSetLayouts = &program->set_layout;
    }
    if (program->push_constant_bytes > 0) {
        pipeline_layout_info.pushConstantRangeCount = 1;
        pipeline_layout_info.pPushConstantRanges = &push_range;
    }
    result = core->create_pipeline_layout(
        core->device, &pipeline_layout_info, nullptr, &program->pipeline_layout);
    if (result != VK_SUCCESS) {
        const BedrayStatus status = vk_status(result);
        return fail(error, status, "vkCreatePipelineLayout(%s) failed: VkResult=%d",
            desc->name, static_cast<int>(result));
    }

    if (program->set_layout != VK_NULL_HANDLE) {
        std::map<uint32_t, uint32_t> pool_sizes;
        for (const BedrayDescriptorBinding& binding : program->bindings) {
            pool_sizes[binding.descriptor_type] += binding.descriptor_count;
        }
        program->pool_sizes.reserve(pool_sizes.size());
        for (const auto& entry : pool_sizes) {
            VkDescriptorPoolSize size{};
            size.type = static_cast<VkDescriptorType>(entry.first);
            size.descriptorCount = entry.second;
            program->pool_sizes.push_back(size);
        }
    }

    if (ray_generation) {
        program->ray_generation = std::make_unique<bedray::core::RayGenerationPipeline>(
            core->device_fns, core->get_device_proc_addr, core->get_physical_device_properties2);
        result = program->ray_generation->create(pipeline_cache, program->module, program->entry_point.c_str(),
            program->pipeline_layout, program->pipeline);
    } else {
        VkComputePipelineCreateInfo pipeline_info{};
        pipeline_info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeline_info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeline_info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeline_info.stage.module = program->module;
        pipeline_info.stage.pName = program->entry_point.c_str();
        pipeline_info.layout = program->pipeline_layout;
        result = core->create_compute_pipelines(
            core->device, pipeline_cache, 1, &pipeline_info, nullptr, &program->pipeline);
    }
    if (result != VK_SUCCESS) {
        const BedrayStatus status = vk_status(result);
        return fail(error, status, "%s pipeline/SBT creation (%s) failed: VkResult=%d",
            ray_generation ? "raygen" : "compute", desc->name, static_cast<int>(result));
    }

    try {
        core->programs.push_back(program);
    } catch (const std::bad_alloc&) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot store program");
    }
    *out_program = program->handle;
    owner.release();
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate program state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception registering program");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_register_program(
    BedrayCore* core, const BedrayProgramDesc* desc, uint64_t* out_program, BedrayError* error) {
    return register_program(core, desc, VK_NULL_HANDLE, out_program, error);
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_register_program_cached(
    BedrayCore* core, const BedrayProgramDesc* desc, uint64_t pipeline_cache,
    uint64_t* out_program, BedrayError* error) {
    return register_program(core, desc, reinterpret_cast<VkPipelineCache>(static_cast<uintptr_t>(pipeline_cache)),
        out_program, error);
}

BEDRAY_API uint32_t BEDRAY_CALL bedray_core_program_feature_mask(void) {
    return BEDRAY_PROGRAM_RAYGEN | BEDRAY_PROGRAM_SER_NV;
}

BEDRAY_API void BEDRAY_CALL bedray_core_destroy_program(
    BedrayCore* core,
    uint64_t program_handle) try {
    if (core == nullptr || program_handle == 0) {
        return;
    }
    BedrayProgram* program = find_program(core, program_handle);
    if (program == nullptr) {
        return;
    }
    program->retired = true;
    const bool in_use = std::any_of(core->recordings.begin(), core->recordings.end(),
        [program](const RecordingUse& use) { return use.program == program; })
        || (core->path_trace && core->path_trace->uses_program(program_handle))
        || (core->bloom && core->bloom->uses_program(program_handle))
        || (core->volumetric && core->volumetric->uses_program(program_handle))
        || (core->velocity && core->velocity->uses_program(program_handle));
    if (!in_use) {
        core->programs.erase(std::remove(core->programs.begin(), core->programs.end(), program), core->programs.end());
        destroy_program(core, program);
    }
} catch (...) {
    // Legacy void ABI cannot report a status; never unwind into the host.
}


} // extern "C"
