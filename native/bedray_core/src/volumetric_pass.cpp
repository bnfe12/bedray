#include "volumetric_pass.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <type_traits>

namespace bedray::core {
namespace {
constexpr std::size_t frame_bytes = 608, march_bytes = 96, apply_bytes = 24;
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
template<class T> std::uint64_t bits(T value) noexcept {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<std::uintptr_t>(value);
    else return static_cast<std::uint64_t>(value);
}
template<class T, std::size_t N> T read(const std::array<unsigned char, N>& data, std::size_t offset) noexcept {
    T value; std::memcpy(&value, data.data() + offset, sizeof(value)); return value;
}
template<class T, std::size_t N> void write(std::array<unsigned char, N>& data, std::size_t offset, T value) noexcept {
    std::memcpy(data.data() + offset, &value, sizeof(value));
}
void barrier(const DeviceFns& fns, VkCommandBuffer command, bool full) {
    VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    memory.srcAccessMask = full ? VK_ACCESS_MEMORY_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;
    memory.dstAccessMask = full ? VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT
        : VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    const auto stage = full ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    fns.cmd_pipeline_barrier(command, stage, stage, 0, 1, &memory, 0, nullptr, 0, nullptr);
}
BedrayResource image(std::uint32_t binding, std::uint64_t view) noexcept {
    BedrayResource resource{};
    resource.binding = binding; resource.descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    resource.image_layout = VK_IMAGE_LAYOUT_GENERAL; resource.handle = view;
    return resource;
}
BedrayResource buffer(std::uint32_t binding, std::uint32_t type,
    std::uint64_t handle, std::uint64_t offset, std::uint64_t bytes) noexcept {
    BedrayResource resource{};
    resource.binding = binding; resource.descriptor_type = type;
    resource.handle = handle; resource.offset = offset; resource.range = bytes;
    return resource;
}
}

VolumetricPass::~VolumetricPass() { (void)destroy(); }

bool VolumetricPass::uses_program(std::uint64_t program) const noexcept {
    return program && (program == desc_.program_march_legacy || program == desc_.program_march_scene || program == desc_.program_apply);
}

BedrayStatus VolumetricPass::gpu_status(gpu::Status status, VkResult vk, BedrayError* error) noexcept {
    if (status == gpu::Status::ok) return BEDRAY_OK;
    if (status == gpu::Status::device_lost || vk == VK_ERROR_DEVICE_LOST) {
        lost_ = true;
        return fail(error, BEDRAY_ERROR_DEVICE_LOST, "volumetric device lost");
    }
    if (status == gpu::Status::in_use) return fail(error, BEDRAY_ERROR_IN_USE, "volumetric resources in use");
    if (status == gpu::Status::unsupported || status == gpu::Status::unavailable)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "volumetric resource contract unsupported");
    if (status == gpu::Status::invalid_argument)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid volumetric resource contract");
    if (status == gpu::Status::out_of_memory || status == gpu::Status::budget_exceeded || status == gpu::Status::exhausted)
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "volumetric allocation failed");
    return fail(error, BEDRAY_ERROR_VULKAN, "volumetric resource operation failed");
}

BedrayStatus VolumetricPass::configure(BedrayCore* core, const DeviceFns& fns,
    const BedrayVolumetricDesc& desc, BedrayError* error) {
    if (has_recordings()) return fail(error, BEDRAY_ERROR_IN_USE, "volumetric recordings pending");
    if (desc.abi_version != BEDRAY_CORE_ABI_VERSION || desc.struct_size != sizeof(desc))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "volumetric ABI/size mismatch");
    if (!core || !fns.cmd_pipeline_barrier || (!desc.program_march_legacy && !desc.program_march_scene) || !desc.program_apply)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "volumetric programs/barrier missing");
    auto validate = [&](std::uint64_t program, bool march) {
        ProgramContract contract{};
        if (!program_contract(core, program, contract) || contract.push_constant_bytes != (march ? march_bytes : apply_bytes)
            || contract.binding_count != (march ? 5u : 4u)) return false;
        if (march && (!program_has_binding(core, program, 0, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            || !program_has_binding(core, program, 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER))) return false;
        if (!march && !program_has_binding(core, program, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)) return false;
        return program_has_binding(core, program, 11, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            && program_has_binding(core, program, 12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            && program_has_binding(core, program, 16, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    };
    if ((desc.program_march_legacy && !validate(desc.program_march_legacy, true))
        || (desc.program_march_scene && !validate(desc.program_march_scene, true)) || !validate(desc.program_apply, false))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "volumetric program contract mismatch");
    gpu::ImageDeviceContext image_context{};
    image_context.physical_device = fns.physical_device; image_context.device = fns.device;
    image_context.functions = fns.image_functions; image_context.memory_properties = fns.memory_properties;
    image_context.dedicated_allocation_enabled = true; image_context.generation = fns.generation;
    auto images = std::make_unique<gpu::GpuImages>(image_context, desc.image_budget_bytes ? desc.image_budget_bytes : 256ull << 20);
    gpu::DeviceContext buffer_context{};
    buffer_context.device = fns.device; buffer_context.functions = fns.buffer_functions;
    buffer_context.memory_properties = fns.memory_properties; buffer_context.non_coherent_atom_size = fns.non_coherent_atom_size;
    buffer_context.dedicated_allocation_enabled = true; buffer_context.generation = fns.generation;
    auto buffers = std::make_unique<gpu::GpuBuffers>(buffer_context, desc.buffer_budget_bytes ? desc.buffer_budget_bytes : 64ull << 20);
    if (!images->valid() || !buffers->valid()) return fail(error, BEDRAY_ERROR_UNSUPPORTED, "volumetric owners unavailable");
    const auto status = destroy();
    if (status != BEDRAY_OK) return status;
    images_ = std::move(images); buffers_ = std::move(buffers);
    fns_ = fns; desc_ = desc; core_ = core;
    return BEDRAY_OK;
}

BedrayStatus VolumetricPass::ensure_image(std::uint32_t width, std::uint32_t height, BedrayError* error) {
    if (width == width_ && height == height_ && !invalidated_) return BEDRAY_OK;
    if (generation_ == std::numeric_limits<std::uint64_t>::max())
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "volumetric generation exhausted");
    const std::uint64_t half_height = height / 2ull + height % 2u;
    if (half_height * 2 > std::numeric_limits<std::uint32_t>::max())
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "volumetric extent overflow");
    gpu::ImageDesc image_desc{};
    image_desc.width = width / 2u + width % 2u; image_desc.height = static_cast<std::uint32_t>(half_height * 2);
    image_desc.format = VK_FORMAT_R32G32B32A32_SFLOAT; image_desc.usage = VK_IMAGE_USAGE_STORAGE_BIT;
    gpu::ImageId next{}; gpu::ImageView view{}; VkResult vk = VK_SUCCESS;
    const auto status = images_->create(image_desc, next, view, vk);
    if (status != gpu::Status::ok) return gpu_status(status, vk, error);
    if (history_id_.serial) (void)images_->retire(history_id_);
    history_id_ = next; history_view_ = view; width_ = width; height_ = height;
    ++generation_; initialize_ = true; invalidated_ = false; history_written_ = false;
    return gpu_status(images_->collect(), VK_SUCCESS, error);
}

BedrayStatus VolumetricPass::render(const BedrayVolumetricFrameDesc& frame, BedrayError* error) {
    if (!images_ || !buffers_ || lost_) return fail(error, BEDRAY_ERROR_UNSUPPORTED, "volumetric pass unavailable");
    if (frame.abi_version != BEDRAY_CORE_ABI_VERSION || frame.struct_size != sizeof(frame))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "volumetric frame ABI/size mismatch");
    constexpr auto allowed = BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE | BEDRAY_VOLUMETRIC_FRAME_RESET_HISTORY;
    if (!frame.command_buffer || !frame.output_view || !frame.depth_view || frame.output_view == frame.depth_view
        || !frame.tlas || !frame.sections || !frame.sections_bytes || frame.sections_bytes % 64u
        || frame.sections_offset > std::numeric_limits<std::uint64_t>::max() - frame.sections_bytes
        || !frame.width || !frame.height || (frame.flags & ~allowed) || frame.reserved
        || !frame.frame_constants || frame.frame_constants_bytes != frame_bytes
        || !frame.march_push || frame.march_push_bytes != march_bytes || !frame.apply_push || frame.apply_push_bytes != apply_bytes)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid volumetric frame");
    const bool native_scene = (frame.flags & BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE) != 0;
    const auto march_program = native_scene ? desc_.program_march_scene : desc_.program_march_legacy;
    if (!march_program) return fail(error, BEDRAY_ERROR_UNSUPPORTED, "volumetric section layout has no matching march program");
    if (recordings_.contains(frame.command_buffer)) return fail(error, BEDRAY_ERROR_IN_USE, "volumetric command pending");
    std::array<unsigned char, frame_bytes> constants{};
    std::array<unsigned char, march_bytes> march{};
    std::array<unsigned char, apply_bytes> apply{};
    std::memcpy(constants.data(), frame.frame_constants, constants.size());
    std::memcpy(march.data(), frame.march_push, march.size());
    std::memcpy(apply.data(), frame.apply_push, apply.size());
    if (read<std::uint32_t>(constants, 400) != frame.width || read<std::uint32_t>(constants, 404) != frame.height)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "volumetric FrameConstants extent mismatch");
    for (std::size_t offset = 0; offset <= 64; offset += 4)
        if (!std::isfinite(read<float>(march, offset))) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "non-finite volumetric march push");
    if (!std::isfinite(read<float>(apply, 0)) || !std::isfinite(read<float>(apply, 4))
        || read<float>(march, 12) < 0 || read<float>(march, 28) <= 0 || read<float>(march, 56) < 0
        || read<float>(march, 64) < 0 || read<std::uint32_t>(march, 80) > 1
        || read<std::uint32_t>(march, 84) != 1 || read<std::uint32_t>(apply, 8) > 1)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid volumetric push contract");
    if (read<float>(march, 28) != read<float>(apply, 4) || read<float>(march, 48) != read<float>(apply, 0))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "volumetric march/apply range or exposure mismatch");
    auto status = ensure_image(frame.width, frame.height, error);
    if (status != BEDRAY_OK) return status;
    const bool history = history_written_ && (frame.flags & BEDRAY_VOLUMETRIC_FRAME_RESET_HISTORY) == 0
        && static_cast<std::uint32_t>(frame.frame_index - last_frame_) == 1u;
    write(march, 72, frame.frame_index); write(march, 76, history ? 1u : 0u); write(apply, 12, frame.frame_index);
    auto& pins = recordings_.try_emplace(frame.command_buffer).first->second;
    pins.generation = generation_;
    const auto pin_status = images_->begin_use(history_id_, pins.image);
    if (pin_status != gpu::Status::ok) {
        (void)release_recording(frame.command_buffer);
        return gpu_status(pin_status, VK_SUCCESS, error);
    }
    gpu::BufferId upload_id{}; gpu::BufferView upload{}; VkResult vk = VK_SUCCESS;
    const auto uploaded = buffers_->upload(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, constants.data(), constants.size(),
        upload_id, upload, pins.upload, vk);
    if (uploaded != gpu::Status::ok) {
        const auto failed = gpu_status(uploaded, vk, error);
        (void)release_recording(frame.command_buffer); // No commands recorded; this call is proven unused.
        return failed;
    }
    history_written_ = false; // A partial group must not publish a producer.
    const auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    barrier(fns_, command, true);
    if (initialize_) {
        VkImageMemoryBarrier transition{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        transition.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        transition.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; transition.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        transition.srcQueueFamilyIndex = transition.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        transition.image = history_view_.image; transition.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        fns_.cmd_pipeline_barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &transition);
        initialize_ = false;
    }
    BedrayResource tlas{}; tlas.binding = 0; tlas.descriptor_type = VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR; tlas.handle = frame.tlas;
    const auto frame_resource = buffer(16, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, bits(upload.buffer), 0, frame_bytes);
    const auto history_resource = image(11, bits(history_view_.view));
    const auto depth_resource = image(12, frame.depth_view);
    std::array<BedrayResource, 5> march_resources{tlas,
        buffer(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, frame.sections, frame.sections_offset, frame.sections_bytes),
        history_resource, depth_resource, frame_resource};
    BedrayFrameDesc call{};
    call.abi_version = BEDRAY_CORE_ABI_VERSION; call.command_buffer = frame.command_buffer; call.program = march_program;
    call.group_count_x = (history_view_.width - 1) / 8 + 1;
    call.group_count_y = (history_view_.height / 2 - 1) / 8 + 1; call.group_count_z = 1;
    call.resources = march_resources.data(); call.resource_count = static_cast<std::uint32_t>(march_resources.size());
    call.push_constants = march.data(); call.push_constant_bytes = static_cast<std::uint32_t>(march.size());
    status = bedray_core_render_frame(core_, &call, error);
    if (status != BEDRAY_OK) { if (status == BEDRAY_ERROR_DEVICE_LOST) lost_ = true; return status; }
    barrier(fns_, command, false);
    std::array<BedrayResource, 4> apply_resources{image(1, frame.output_view), history_resource, depth_resource, frame_resource};
    call.program = desc_.program_apply; call.group_count_x = (frame.width - 1) / 8 + 1; call.group_count_y = (frame.height - 1) / 8 + 1;
    call.resources = apply_resources.data(); call.resource_count = static_cast<std::uint32_t>(apply_resources.size());
    call.push_constants = apply.data(); call.push_constant_bytes = static_cast<std::uint32_t>(apply.size());
    status = bedray_core_render_frame(core_, &call, error);
    if (status != BEDRAY_OK) { if (status == BEDRAY_ERROR_DEVICE_LOST) lost_ = true; return status; }
    barrier(fns_, command, true);
    history_written_ = true; last_frame_ = frame.frame_index;
    return BEDRAY_OK;
}

void VolumetricPass::discard_recording(std::uint64_t command) noexcept {
    const auto found = recordings_.find(command);
    if (found != recordings_.end() && found->second.generation == generation_) {
        invalidated_ = true; history_written_ = false;
    }
}

BedrayStatus VolumetricPass::release_lost_owners() noexcept {
    if (images_) {
        images_->device_lost();
        const auto result = gpu_status(images_->release_after_device_loss(), VK_SUCCESS, nullptr);
        if (result != BEDRAY_OK) return result;
        images_.reset(); history_id_ = {}; history_view_ = {};
    }
    if (buffers_) {
        buffers_->device_lost();
        const auto result = gpu_status(buffers_->release_after_device_loss(), VK_SUCCESS, nullptr);
        if (result != BEDRAY_OK) return result;
        buffers_.reset();
    }
    return BEDRAY_OK;
}

BedrayStatus VolumetricPass::release_recording(std::uint64_t command) noexcept {
    const auto found = recordings_.find(command);
    if (found == recordings_.end()) return BEDRAY_OK;
    if (lost_) {
        if (recordings_.size() > 1) { recordings_.erase(found); return BEDRAY_OK; }
        const auto status = release_lost_owners();
        if (status == BEDRAY_OK) recordings_.erase(found);
        return status;
    }
    if (found->second.image.serial) {
        const auto status = gpu_status(images_->complete_use(found->second.image), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        found->second.image = {};
    }
    if (found->second.upload.serial) {
        const auto status = gpu_status(buffers_->complete_use(found->second.upload), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        found->second.upload = {};
    }
    auto status = gpu_status(images_->collect(), VK_SUCCESS, nullptr);
    if (status != BEDRAY_OK) return status;
    status = gpu_status(buffers_->collect(), VK_SUCCESS, nullptr);
    if (status == BEDRAY_OK) recordings_.erase(found);
    return status;
}

BedrayStatus VolumetricPass::destroy() noexcept {
    if (has_recordings()) return BEDRAY_ERROR_IN_USE;
    if (lost_) {
        const auto status = release_lost_owners();
        if (status != BEDRAY_OK) return status;
    }
    if (images_) {
        if (history_id_.serial) (void)images_->retire(history_id_);
        auto status = gpu_status(images_->collect(), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        status = gpu_status(images_->close(), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        images_.reset();
    }
    if (buffers_) {
        auto status = gpu_status(buffers_->retire_uploads(), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        status = gpu_status(buffers_->collect(), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        status = gpu_status(buffers_->close(), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        buffers_.reset();
    }
    history_id_ = {}; history_view_ = {}; desc_ = {}; fns_ = {}; core_ = nullptr;
    width_ = height_ = last_frame_ = 0; initialize_ = invalidated_ = history_written_ = lost_ = false;
    return BEDRAY_OK;
}
}
