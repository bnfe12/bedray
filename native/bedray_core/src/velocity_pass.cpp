#include "velocity_pass.h"
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <type_traits>

namespace bedray::core {
namespace {
constexpr std::size_t uniform_bytes = 368;
using Uniforms = std::array<unsigned char, uniform_bytes>;
BedrayStatus fail(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
template<class T> std::uint64_t bits(T value) noexcept {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<std::uintptr_t>(value);
    else return static_cast<std::uint64_t>(value);
}
template<class T> T read(const Uniforms& data, std::size_t offset) noexcept {
    T value; std::memcpy(&value, data.data() + offset, sizeof(value)); return value;
}
bool address_valid(std::uint64_t address, std::uint64_t bytes, std::uint64_t alignment) noexcept {
    return !address || ((address % alignment) == 0 && bytes <= std::numeric_limits<std::uint64_t>::max() - address);
}
bool valid_uniforms(const Uniforms& data, std::uint32_t width, std::uint32_t height) noexcept {
    for (std::size_t offset = 0; offset < uniform_bytes; offset += sizeof(float)) {
        if (offset >= 320 && offset < 352) continue; // uvec BDA words, not floats.
        if (!std::isfinite(read<float>(data, offset))) return false;
    }
    const float convention = read<float>(data, 140);
    const auto pixels = static_cast<std::uint64_t>(width) * height;
    return read<float>(data, 160) == static_cast<float>(width)
        && read<float>(data, 164) == static_cast<float>(height)
        && read<float>(data, 168) == 1.0f / static_cast<float>(width)
        && read<float>(data, 172) == 1.0f / static_cast<float>(height)
        && (convention == 0.0f || convention == 1.0f || convention == 2.0f)
        && address_valid(read<std::uint64_t>(data, 320), pixels * 16, 16)
        && address_valid(read<std::uint64_t>(data, 328), pixels * 4, 4)
        && address_valid(read<std::uint64_t>(data, 336), pixels * 4, 4);
}
void barrier(const DeviceFns& fns, VkCommandBuffer command) {
    VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    memory.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    fns.cmd_pipeline_barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 1, &memory, 0, nullptr, 0, nullptr);
}
}

VelocityPass::~VelocityPass() { (void)destroy(); }

BedrayStatus VelocityPass::gpu_status(gpu::Status status, VkResult vk, BedrayError* error) noexcept {
    if (status == gpu::Status::ok) return BEDRAY_OK;
    if (status == gpu::Status::device_lost || vk == VK_ERROR_DEVICE_LOST) {
        lost_ = true; return fail(error, BEDRAY_ERROR_DEVICE_LOST, "velocity device lost");
    }
    if (status == gpu::Status::in_use) return fail(error, BEDRAY_ERROR_IN_USE, "velocity uploads in use");
    if (status == gpu::Status::unsupported || status == gpu::Status::unavailable)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "velocity upload owner unavailable");
    if (status == gpu::Status::out_of_memory || status == gpu::Status::budget_exceeded || status == gpu::Status::exhausted)
        return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "velocity upload allocation failed");
    if (status == gpu::Status::invalid_argument)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid velocity upload");
    return fail(error, BEDRAY_ERROR_VULKAN, "velocity upload operation failed");
}

BedrayStatus VelocityPass::configure(BedrayCore* core, const DeviceFns& fns,
    const BedrayVelocityDesc& desc, BedrayError* error) {
    if (desc.abi_version != BEDRAY_CORE_ABI_VERSION || desc.struct_size != sizeof(desc))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "velocity ABI/size mismatch");
    if (!core || !desc.program || !desc.nearest_sampler || !fns.cmd_pipeline_barrier)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "velocity program/sampler/barrier missing");
    // Several host adapters borrow this core-owned pass. Reacquiring the same
    // contract must not replace its owner or invalidate another adapter's work.
    if (buffers_ && !lost_ && core_ == core && fns_.device == fns.device && fns_.generation == fns.generation
        && desc_.program == desc.program && desc_.nearest_sampler == desc.nearest_sampler
        && desc_.buffer_budget_bytes == desc.buffer_budget_bytes) return BEDRAY_OK;
    if (has_recordings()) return fail(error, BEDRAY_ERROR_IN_USE, "velocity recordings pending");
    ProgramContract contract{};
    if (!program_contract(core, desc.program, contract) || contract.push_constant_bytes != 0 || contract.binding_count != 3
        || !program_has_binding(core, desc.program, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        || !program_has_binding(core, desc.program, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
        || !program_has_binding(core, desc.program, 2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "velocity program contract mismatch");
    gpu::DeviceContext context{};
    context.device = fns.device; context.functions = fns.buffer_functions;
    context.memory_properties = fns.memory_properties; context.non_coherent_atom_size = fns.non_coherent_atom_size;
    context.dedicated_allocation_enabled = true; context.generation = fns.generation;
    auto buffers = std::make_unique<gpu::GpuBuffers>(context, desc.buffer_budget_bytes ? desc.buffer_budget_bytes : 1ull << 20);
    if (!buffers->valid()) return fail(error, BEDRAY_ERROR_UNSUPPORTED, "velocity buffer owner unavailable");
    const auto status = destroy();
    if (status != BEDRAY_OK) return status;
    buffers_ = std::move(buffers); fns_ = fns; desc_ = desc; core_ = core;
    return BEDRAY_OK;
}

BedrayStatus VelocityPass::render(const BedrayVelocityFrameDesc& frame, BedrayError* error) {
    if (!buffers_ || lost_) return fail(error, BEDRAY_ERROR_UNSUPPORTED, "velocity pass unavailable");
    if (frame.abi_version != BEDRAY_CORE_ABI_VERSION || frame.struct_size != sizeof(frame))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "velocity frame ABI/size mismatch");
    // int pixel indexing in the unchanged shader is signed; disallow overflow.
    const auto pixels = static_cast<std::uint64_t>(frame.width) * frame.height;
    if (!frame.command_buffer || !frame.depth_view || !frame.output_view || frame.depth_view == frame.output_view
        || !frame.width || !frame.height || frame.width > 65535 || frame.height > 65535
        || pixels > static_cast<std::uint64_t>(std::numeric_limits<std::int32_t>::max())
        || !frame.uniforms || frame.uniform_bytes != uniform_bytes || frame.reserved)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid velocity frame");
    Uniforms uniforms{}; std::memcpy(uniforms.data(), frame.uniforms, uniforms.size());
    if (!valid_uniforms(uniforms, frame.width, frame.height))
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid velocity uniform contract");
    auto& uses = recordings_.try_emplace(frame.command_buffer).first->second;
    uses.emplace_back();
    auto& use = uses.back();
    gpu::BufferId id{}; gpu::BufferView upload{}; VkResult vk = VK_SUCCESS;
    const auto result = buffers_->upload(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, uniforms.data(), uniforms.size(), id, upload, use, vk);
    if (result != gpu::Status::ok) {
        const auto status = gpu_status(result, vk, error);
        // The failed call recorded no commands. Preserve all earlier dispatches
        // on this CB, including another host adapter's upload pins.
        uses.pop_back();
        if (uses.empty()) recordings_.erase(frame.command_buffer);
        return status;
    }
    std::array<BedrayResource, 3> resources{};
    resources[0].binding = 0; resources[0].descriptor_type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    resources[0].handle = frame.depth_view; resources[0].sampler = desc_.nearest_sampler;
    resources[0].image_layout = VK_IMAGE_LAYOUT_GENERAL;
    resources[1].binding = 1; resources[1].descriptor_type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    resources[1].handle = frame.output_view; resources[1].image_layout = VK_IMAGE_LAYOUT_GENERAL;
    resources[2].binding = 2; resources[2].descriptor_type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    resources[2].handle = bits(upload.buffer); resources[2].range = uniform_bytes;
    BedrayFrameDesc dispatch{};
    dispatch.abi_version = BEDRAY_CORE_ABI_VERSION; dispatch.command_buffer = frame.command_buffer;
    dispatch.program = desc_.program; dispatch.group_count_x = (frame.width - 1) / 8 + 1;
    dispatch.group_count_y = (frame.height - 1) / 8 + 1; dispatch.group_count_z = 1;
    dispatch.resources = resources.data(); dispatch.resource_count = static_cast<std::uint32_t>(resources.size());
    const auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    barrier(fns_, command);
    const auto status = bedray_core_render_frame(core_, &dispatch, error);
    if (status == BEDRAY_ERROR_DEVICE_LOST) lost_ = true;
    if (status == BEDRAY_OK) barrier(fns_, command);
    return status; // Even a failed recorder needs exact completion/discard proof.
}

BedrayStatus VelocityPass::release_recording(std::uint64_t command) noexcept {
    const auto found = recordings_.find(command);
    if (found == recordings_.end()) return BEDRAY_OK;
    if (lost_) {
        if (recordings_.size() > 1) { recordings_.erase(found); return BEDRAY_OK; }
        buffers_->device_lost();
        const auto status = gpu_status(buffers_->release_after_device_loss(), VK_SUCCESS, nullptr);
        if (status == BEDRAY_OK) { buffers_.reset(); recordings_.erase(found); }
        return status;
    }
    for (auto& use : found->second) if (use.serial) {
        const auto status = gpu_status(buffers_->complete_use(use), VK_SUCCESS, nullptr);
        if (status != BEDRAY_OK) return status;
        use = {};
    }
    const auto status = gpu_status(buffers_->collect(), VK_SUCCESS, nullptr);
    if (status == BEDRAY_OK) recordings_.erase(found);
    return status;
}

BedrayStatus VelocityPass::destroy() noexcept {
    if (has_recordings()) return BEDRAY_ERROR_IN_USE;
    if (buffers_) {
        if (lost_) {
            buffers_->device_lost();
            const auto status = gpu_status(buffers_->release_after_device_loss(), VK_SUCCESS, nullptr);
            if (status != BEDRAY_OK) return status;
        } else {
            auto status = gpu_status(buffers_->retire_uploads(), VK_SUCCESS, nullptr);
            if (status != BEDRAY_OK) return status;
            status = gpu_status(buffers_->collect(), VK_SUCCESS, nullptr);
            if (status != BEDRAY_OK) return status;
            status = gpu_status(buffers_->close(), VK_SUCCESS, nullptr);
            if (status != BEDRAY_OK) return status;
        }
        buffers_.reset();
    }
    core_ = nullptr; desc_ = {}; fns_ = {}; lost_ = false;
    return BEDRAY_OK;
}
}
