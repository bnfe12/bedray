#define VK_NO_PROTOTYPES
#include "core_state.h"

extern "C" {

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(
    BedrayCore* core,
    const BedrayFrameDesc* desc,
    BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || desc == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (desc->command_buffer == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "command_buffer is null");
    }
    if (desc->program == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program handle is zero");
    }
    BedrayProgram* program = find_program(core, desc->program);
    if (program == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "program handle is not registered");
    }
    if (desc->group_count_x == 0 || desc->group_count_y == 0 || desc->group_count_z == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "dispatch group counts must be non-zero");
    }
    const uint32_t* limits = core->properties.limits.maxComputeWorkGroupCount;
    if (program->ray_generation ? !program->ray_generation->valid_extent(
            desc->group_count_x, desc->group_count_y, desc->group_count_z)
        : (desc->group_count_x > limits[0]
        || desc->group_count_y > limits[1]
        || desc->group_count_z > limits[2])) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "dispatch %ux%ux%u exceeds the device limit %ux%ux%u",
            desc->group_count_x, desc->group_count_y, desc->group_count_z,
            limits[0], limits[1], limits[2]);
    }
    if (desc->push_constant_bytes > program->push_constant_bytes) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "push constants %u exceed the program range %u",
            desc->push_constant_bytes, program->push_constant_bytes);
    }
    if ((desc->push_constant_bytes & 3u) != 0u) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "push constant size must be a multiple of four bytes");
    }
    if (desc->push_constant_bytes > 0 && desc->push_constants == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "push_constants is null");
    }
    if (desc->resource_count > 0 && desc->resources == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "resources is null");
    }
    if (desc->resource_count != program->bindings.size()) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "resource count %u does not match program binding count %zu",
            desc->resource_count, program->bindings.size());
    }
    // Validate the whole binding set before changing any descriptor state.
    // A missing binding or duplicate must never reuse a prior frame's value.
    for (uint32_t i = 0; i < desc->resource_count; ++i) {
        for (uint32_t j = 0; j < i; ++j) {
            if (desc->resources[i].binding == desc->resources[j].binding) {
                return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                    "duplicate resource binding %u", desc->resources[i].binding);
            }
        }
    }

    std::vector<VkWriteDescriptorSet> writes;
    std::vector<VkDescriptorBufferInfo> buffer_infos;
    std::vector<VkDescriptorImageInfo> image_infos;
    std::vector<VkWriteDescriptorSetAccelerationStructureKHR> acceleration_infos;
    std::vector<VkAccelerationStructureKHR> acceleration_handles;
    try {
        writes.reserve(desc->resource_count);
        buffer_infos.reserve(desc->resource_count);
        image_infos.reserve(desc->resource_count);
        acceleration_infos.reserve(desc->resource_count);
        acceleration_handles.reserve(desc->resource_count);
    } catch (const std::bad_alloc&) {
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate descriptor write list");
    }

    VkDescriptorPool dispatch_pool = VK_NULL_HANDLE;
    VkDescriptorSet dispatch_set = VK_NULL_HANDLE;
    auto pool_deleter = [core, program, &dispatch_set](VkDescriptorPool_T* pool) {
        recycle_descriptors(core, program, pool, dispatch_set);
    };
    std::unique_ptr<VkDescriptorPool_T, decltype(pool_deleter)> pool_owner(nullptr, pool_deleter);
    if (program->set_layout != VK_NULL_HANDLE && program->available_descriptor_count > 0) {
        const DescriptorAllocation cached = program->available_descriptors[--program->available_descriptor_count];
        dispatch_pool = cached.pool;
        dispatch_set = cached.set;
        pool_owner.reset(dispatch_pool);
    } else if (program->set_layout != VK_NULL_HANDLE) {
        VkDescriptorPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pool_info.maxSets = 1;
        pool_info.poolSizeCount = static_cast<uint32_t>(program->pool_sizes.size());
        pool_info.pPoolSizes = program->pool_sizes.data();
        VkResult result = core->create_descriptor_pool(core->device, &pool_info, nullptr, &dispatch_pool);
        if (result != VK_SUCCESS) return fail(error, vk_status(result), "vkCreateDescriptorPool failed: VkResult=%d", static_cast<int>(result));
        pool_owner.reset(dispatch_pool);
        VkDescriptorSetAllocateInfo allocate_info{};
        allocate_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate_info.descriptorPool = dispatch_pool;
        allocate_info.descriptorSetCount = 1;
        allocate_info.pSetLayouts = &program->set_layout;
        result = core->allocate_descriptor_sets(core->device, &allocate_info, &dispatch_set);
        if (result != VK_SUCCESS) return fail(error, vk_status(result), "vkAllocateDescriptorSets failed: VkResult=%d", static_cast<int>(result));
    }

    for (uint32_t i = 0; i < desc->resource_count; ++i) {
        const BedrayResource& resource = desc->resources[i];
        const BedrayDescriptorBinding* binding = find_binding(program, resource.binding);
        if (binding == nullptr) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "resource binding %u is not declared by program %s",
                resource.binding, program->name.c_str());
        }
        if (resource.descriptor_type != binding->descriptor_type) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "binding %u: resource type %u does not match program type %u",
                resource.binding, resource.descriptor_type, binding->descriptor_type);
        }
        if (resource.handle == 0) {
            return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                "binding %u: resource handle is zero", resource.binding);
        }

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = dispatch_set;
        write.dstBinding = resource.binding;
        write.dstArrayElement = 0;
        write.descriptorCount = 1;
        write.descriptorType = static_cast<VkDescriptorType>(resource.descriptor_type);

        if (is_image_type(resource.descriptor_type)) {
            if (resource.image_layout == VK_IMAGE_LAYOUT_UNDEFINED) {
                return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                    "binding %u: image layout is UNDEFINED", resource.binding);
            }
            if (resource.descriptor_type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                && resource.sampler == 0) {
                return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
                    "binding %u: combined image sampler has no sampler", resource.binding);
            }
            VkDescriptorImageInfo info{};
            info.sampler = reinterpret_cast<VkSampler>(static_cast<uintptr_t>(resource.sampler));
            info.imageView = reinterpret_cast<VkImageView>(static_cast<uintptr_t>(resource.handle));
            info.imageLayout = static_cast<VkImageLayout>(resource.image_layout);
            image_infos.push_back(info);
            write.pImageInfo = &image_infos.back();
        } else if (resource.descriptor_type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
            || resource.descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) {
            VkDescriptorBufferInfo info{};
            info.buffer = reinterpret_cast<VkBuffer>(static_cast<uintptr_t>(resource.handle));
            info.offset = resource.offset;
            info.range = resource.range;
            if (info.range == 0) {
                info.range = VK_WHOLE_SIZE;
            }
            buffer_infos.push_back(info);
            write.pBufferInfo = &buffer_infos.back();
        } else if (resource.descriptor_type == VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR) {
            if ((core->capabilities & BEDRAY_CAP_ACCELERATION_STRUCTURE) == 0) {
                return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                    "binding %u: acceleration structure capability is not enabled", resource.binding);
            }
            VkAccelerationStructureKHR handle =
                reinterpret_cast<VkAccelerationStructureKHR>(static_cast<uintptr_t>(resource.handle));
            acceleration_handles.push_back(handle);
            VkWriteDescriptorSetAccelerationStructureKHR info{};
            info.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;
            info.accelerationStructureCount = 1;
            info.pAccelerationStructures = &acceleration_handles.back();
            acceleration_infos.push_back(info);
            write.pNext = &acceleration_infos.back();
        } else {
            return fail(error, BEDRAY_ERROR_UNSUPPORTED,
                "binding %u: descriptor type %u is not supported in ABI v2",
                resource.binding, resource.descriptor_type);
        }
        writes.push_back(write);
    }

    VkCommandBuffer command_buffer =
        reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(desc->command_buffer));
    // Register before recording: allocation failure must leave the caller's
    // command buffer untouched. This also retains programs without bindings.
    // Descriptor-free kernels can run for many sections in one command buffer.
    // One retained program reference covers all those dispatches until release;
    // descriptor-bearing dispatches still require one exclusive allocation each.
    const bool already_retained = dispatch_pool == VK_NULL_HANDLE
        && std::any_of(core->recordings.begin(), core->recordings.end(),
            [command_buffer, program](const RecordingUse& use) {
                return use.command_buffer == command_buffer && use.program == program && use.pool == VK_NULL_HANDLE;
            });
    if (!already_retained)
        core->recordings.push_back(RecordingUse{command_buffer, dispatch_pool, program, dispatch_set});
    pool_owner.release();
    if (!writes.empty()) {
        core->update_descriptor_sets(core->device,
            static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    const auto bind_point = program->ray_generation ? VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR : VK_PIPELINE_BIND_POINT_COMPUTE;
    core->cmd_bind_pipeline(command_buffer, bind_point, program->pipeline);
    if (dispatch_set != VK_NULL_HANDLE) {
        core->cmd_bind_descriptor_sets(command_buffer, bind_point,
            program->pipeline_layout, 0, 1, &dispatch_set, 0, nullptr);
    }
    if (desc->push_constant_bytes > 0) {
        core->cmd_push_constants(command_buffer, program->pipeline_layout,
            program->shader_stage, 0, desc->push_constant_bytes, desc->push_constants);
    }
    if (program->ray_generation) program->ray_generation->record(command_buffer,
        desc->group_count_x, desc->group_count_y, desc->group_count_z);
    else core->cmd_dispatch(command_buffer, desc->group_count_x, desc->group_count_y, desc->group_count_z);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate frame state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording frame");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_phases(
    BedrayCore* core, const BedrayFrameDesc* desc, uint32_t phase_offset,
    uint32_t phase_count, BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || desc == nullptr || desc->push_constants == nullptr
        || phase_count == 0 || phase_count > 8 || (phase_offset & 3u) != 0
        || desc->push_constant_bytes < 4 || phase_offset > desc->push_constant_bytes - 4)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid compute phase range");
    BedrayProgram* program = find_program(core, desc->program);
    if (program == nullptr || program->ray_generation || desc->push_constant_bytes > program->push_constant_bytes)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid compute phase program/push size");
    std::vector<unsigned char> push(desc->push_constant_bytes);
    std::memcpy(push.data(), desc->push_constants, push.size());
    BedrayFrameDesc phase_desc = *desc;
    phase_desc.push_constants = push.data();
    const VkCommandBuffer command = reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(desc->command_buffer));
    VkMemoryBarrier memory{};
    memory.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memory.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    for (uint32_t phase = 0; phase < phase_count; ++phase) {
        std::memcpy(push.data() + phase_offset, &phase, sizeof(phase));
        BedrayStatus status = bedray_core_render_frame(core, &phase_desc, error);
        if (status != BEDRAY_OK) return status;
        core->cmd_pipeline_barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &memory, 0, nullptr, 0, nullptr);
    }
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate compute phase state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording compute phases");
}


} // extern "C"
