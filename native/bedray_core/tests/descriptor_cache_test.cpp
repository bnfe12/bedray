// Exercise the actual recording/cache implementation with Vulkan entry-point
// doubles. No Vulkan loader, device, driver or GPU is used by this test.
#include "../src/core.cpp"

#include <cstdlib>

namespace {
uint64_t next_handle = 100;
unsigned created = 0, destroyed = 0, dispatched = 0;
std::vector<unsigned> phases;
unsigned barriers = 0;
std::unordered_set<VkDescriptorSet> pending_sets;

void require(bool condition) { if (!condition) std::abort(); }

VKAPI_ATTR VkResult VKAPI_CALL create_pool(VkDevice, const VkDescriptorPoolCreateInfo*,
    const VkAllocationCallbacks*, VkDescriptorPool* out) {
    *out = reinterpret_cast<VkDescriptorPool>(++next_handle);
    ++created;
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroy_pool(VkDevice, VkDescriptorPool, const VkAllocationCallbacks*) {
    ++destroyed;
}
VKAPI_ATTR VkResult VKAPI_CALL allocate_sets(VkDevice, const VkDescriptorSetAllocateInfo*, VkDescriptorSet* out) {
    *out = reinterpret_cast<VkDescriptorSet>(++next_handle);
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL update_sets(VkDevice, uint32_t count, const VkWriteDescriptorSet* writes,
    uint32_t, const VkCopyDescriptorSet*) {
    require(count == 1);
    // Updating a set that is still recorded would mutate an earlier dispatch.
    require(pending_sets.insert(writes[0].dstSet).second);
}
VKAPI_ATTR void VKAPI_CALL bind_pipeline(VkCommandBuffer, VkPipelineBindPoint, VkPipeline) {}
VKAPI_ATTR void VKAPI_CALL bind_sets(VkCommandBuffer, VkPipelineBindPoint, VkPipelineLayout,
    uint32_t, uint32_t, const VkDescriptorSet*, uint32_t, const uint32_t*) {}
VKAPI_ATTR void VKAPI_CALL dispatch(VkCommandBuffer, uint32_t, uint32_t, uint32_t) { ++dispatched; }
VKAPI_ATTR void VKAPI_CALL destroy_layout(VkDevice, VkDescriptorSetLayout, const VkAllocationCallbacks*) {}
VKAPI_ATTR void VKAPI_CALL push_constants(VkCommandBuffer, VkPipelineLayout, VkShaderStageFlags,
    uint32_t, uint32_t size, const void* bytes) {
    require(size == 16);
    uint32_t phase = 0;
    std::memcpy(&phase, static_cast<const unsigned char*>(bytes) + 8, 4);
    phases.push_back(phase);
}
VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer, VkPipelineStageFlags source, VkPipelineStageFlags destination,
    VkDependencyFlags, uint32_t count, const VkMemoryBarrier* memory, uint32_t, const VkBufferMemoryBarrier*,
    uint32_t, const VkImageMemoryBarrier*) {
    require(source == VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT && destination == source && count == 1);
    require(memory->srcAccessMask == VK_ACCESS_SHADER_WRITE_BIT);
    require(memory->dstAccessMask == (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT));
    ++barriers;
}
}

int main() {
    BedrayCore core;
    core.create_descriptor_pool = create_pool;
    core.destroy_descriptor_pool = destroy_pool;
    core.allocate_descriptor_sets = allocate_sets;
    core.update_descriptor_sets = update_sets;
    core.cmd_bind_pipeline = bind_pipeline;
    core.cmd_bind_descriptor_sets = bind_sets;
    core.cmd_dispatch = dispatch;
    core.destroy_descriptor_set_layout = destroy_layout;
    for (auto& limit : core.properties.limits.maxComputeWorkGroupCount) limit = 65535;

    BedrayProgram& program = *new BedrayProgram;
    program.handle = 1;
    program.set_layout = reinterpret_cast<VkDescriptorSetLayout>(1);
    program.bindings.push_back({0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, 0});
    program.pool_sizes.push_back({VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1});
    core.programs.push_back(&program);

    BedrayResource resource{};
    resource.binding = 0;
    resource.descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    resource.handle = 7;
    resource.range = 64;
    BedrayFrameDesc frame{};
    frame.abi_version = BEDRAY_CORE_ABI_VERSION;
    frame.command_buffer = 5;
    frame.program = 1;
    frame.group_count_x = frame.group_count_y = frame.group_count_z = 1;
    frame.resource_count = 1;
    frame.resources = &resource;
    BedrayError error{};

    for (unsigned i = 0; i < 10; ++i)
        require(bedray_core_render_frame(&core, &frame, &error) == BEDRAY_OK);
    require(created == 10 && destroyed == 0 && dispatched == 10);
    require(program.available_descriptor_count == 0 && pending_sets.size() == 10);
    require(bedray_core_release_recording(&core, frame.command_buffer) == BEDRAY_OK);
    pending_sets.clear(); // The host has proved the released recording safe.
    require(core.recordings.empty() && program.available_descriptor_count == 8 && destroyed == 2);

    // Validation failure must put a borrowed cache entry back without recording.
    resource.handle = 0;
    require(bedray_core_render_frame(&core, &frame, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    require(core.recordings.empty() && program.available_descriptor_count == 8 && created == 10);
    resource.handle = 7;
    for (unsigned i = 0; i < 8; ++i)
        require(bedray_core_render_frame(&core, &frame, &error) == BEDRAY_OK);
    require(created == 10 && pending_sets.size() == 8 && dispatched == 18);
    require(bedray_core_release_recording(&core, frame.command_buffer) == BEDRAY_OK);
    pending_sets.clear();
    require(program.available_descriptor_count == 8);

    // Retirement with an outstanding recording keeps the program alive until
    // release, then destroys both the live pool and every cached allocation.
    require(bedray_core_render_frame(&core, &frame, &error) == BEDRAY_OK);
    bedray_core_destroy_program(&core, frame.program);
    require(core.programs.size() == 1 && program.retired);
    require(bedray_core_release_recording(&core, frame.command_buffer) == BEDRAY_OK);
    require(core.programs.empty());
    require(destroyed == created);

    // A complete checker stage is one native call, with immutable host input
    // and one retained program reference even though two dispatches are recorded.
    auto* checker = new BedrayProgram;
    checker->handle = 2;
    checker->push_constant_bytes = 16;
    core.programs.push_back(checker);
    core.cmd_push_constants = push_constants;
    core.cmd_pipeline_barrier = barrier;
    uint32_t push[4] = {10, 20, 99, 40};
    frame.program = 2;
    frame.resource_count = 0;
    frame.resources = nullptr;
    frame.push_constants = push;
    frame.push_constant_bytes = sizeof(push);
    require(bedray_core_render_phases(&core, &frame, 16, 2, &error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    require(phases.empty() && barriers == 0);
    require(bedray_core_render_phases(&core, &frame, 8, 2, &error) == BEDRAY_OK);
    require(phases == std::vector<unsigned>({0, 1}) && barriers == 2 && push[2] == 99);
    require(core.recordings.size() == 1);
    bedray_core_destroy_program(&core, 2);
    require(bedray_core_release_recording(&core, frame.command_buffer) == BEDRAY_OK);
    require(core.programs.empty());
    return 0;
}
