#include "bloom_pass.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <limits>
#include <type_traits>

namespace bedray::core {
namespace {
BedrayStatus bloom_error(BedrayError* error, BedrayStatus status, const char* message) noexcept {
    if (error) { error->status = status; std::snprintf(error->message, sizeof(error->message), "%s", message); }
    return status;
}
template<class T> std::uint64_t bits(T value) noexcept {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<std::uintptr_t>(value);
    else return static_cast<std::uint64_t>(value);
}
void memory_barrier(const DeviceFns& fns, VkCommandBuffer command, bool full) {
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = full ? VK_ACCESS_MEMORY_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = full ? VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT
        : VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    auto stage = full ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    fns.cmd_pipeline_barrier(command, stage, stage, 0, 1, &barrier, 0, nullptr, 0, nullptr);
}
BedrayResource image_binding(std::uint32_t binding, bool sampled, std::uint64_t view, std::uint64_t sampler = 0) {
    BedrayResource resource{};
    resource.binding = binding;
    resource.descriptor_type = sampled ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    resource.image_layout = VK_IMAGE_LAYOUT_GENERAL;
    resource.handle = view;
    resource.sampler = sampler;
    return resource;
}
}

BloomPass::~BloomPass() { (void)destroy(); }

std::uint32_t BloomPass::mip_size(std::uint32_t size, std::uint32_t level) noexcept {
    if (level >= 31) return 1;
    const std::uint64_t divisor = 1ull << (level + 1);
    return static_cast<std::uint32_t>(std::max(1ull, (size + divisor - 1) / divisor));
}

bool BloomPass::uses_program(std::uint64_t program) const noexcept {
    return program && std::find(std::begin(desc_.programs), std::end(desc_.programs), program) != std::end(desc_.programs);
}

BedrayStatus BloomPass::gpu_status(gpu::Status status, VkResult vk, BedrayError* error) noexcept {
    if (status == gpu::Status::ok) return BEDRAY_OK;
    if (status == gpu::Status::device_lost || vk == VK_ERROR_DEVICE_LOST) {
        lost_ = true;
        // The image owner may already be lost and refuse individual use APIs.
        // Keep every allocation until all outstanding host recordings are proven.
        return bloom_error(error, BEDRAY_ERROR_DEVICE_LOST, "bloom device lost");
    }
    if (status == gpu::Status::in_use) return bloom_error(error, BEDRAY_ERROR_IN_USE, "bloom images in use");
    if (status == gpu::Status::out_of_memory || status == gpu::Status::budget_exceeded)
        return bloom_error(error, BEDRAY_ERROR_OUT_OF_MEMORY, "bloom image allocation failed");
    return bloom_error(error, BEDRAY_ERROR_VULKAN, "bloom image operation failed");
}

BedrayStatus BloomPass::configure(BedrayCore* core, const DeviceFns& fns,
    const BedrayBloomDesc& desc, BedrayError* error) {
    if (has_recordings()) return bloom_error(error, BEDRAY_ERROR_IN_USE, "bloom recording pending");
    if (!core || desc.abi_version != BEDRAY_CORE_ABI_VERSION || desc.struct_size != sizeof(desc))
        return bloom_error(error, BEDRAY_ERROR_ABI_MISMATCH, "bloom ABI/size mismatch");
    if (!desc.linear_sampler || !desc.nearest_sampler || !fns.cmd_pipeline_barrier)
        return bloom_error(error, BEDRAY_ERROR_INVALID_ARGUMENT, "bloom samplers/barrier missing");
    // Binding numbers are the unchanged AOT contracts, including sparse apply bindings.
    const std::uint32_t bindings[4][3] = {{0,1,3}, {0,1,0}, {0,1,0}, {0,2,3}};
    for (std::size_t stage = 0; stage < 4; ++stage) {
        ProgramContract contract{};
        const std::uint32_t count = stage == 0 || stage == 3 ? 3u : 2u;
        if (!program_contract(core, desc.programs[stage], contract) || contract.push_constant_bytes != 128
            || contract.binding_count != count)
            return bloom_error(error, BEDRAY_ERROR_INVALID_ARGUMENT, "bloom program contract mismatch");
        for (std::uint32_t i = 0; i < count; ++i)
            if (!program_has_binding(core, desc.programs[stage], bindings[stage][i],
                i == 1 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER))
                return bloom_error(error, BEDRAY_ERROR_INVALID_ARGUMENT, "bloom binding contract mismatch");
    }
    gpu::ImageDeviceContext context{};
    context.physical_device = fns.physical_device;
    context.device = fns.device;
    context.functions = fns.image_functions;
    context.memory_properties = fns.memory_properties;
    context.dedicated_allocation_enabled = true;
    context.generation = fns.generation;
    auto images = std::make_unique<gpu::GpuImages>(context, desc.image_budget_bytes ? desc.image_budget_bytes : 256ull << 20);
    if (!images->valid()) return bloom_error(error, BEDRAY_ERROR_UNSUPPORTED, "bloom image owner unavailable");
    auto status = destroy();
    if (status != BEDRAY_OK) return status;
    images_ = std::move(images);
    desc_ = desc;
    fns_ = fns;
    core_ = core;
    return BEDRAY_OK;
}

BedrayStatus BloomPass::ensure_images(std::uint32_t width, std::uint32_t height, BedrayError* error) {
    if (width_ == width && height_ == height && !invalidated_) return BEDRAY_OK;
    if (generation_ == std::numeric_limits<std::uint64_t>::max())
        return bloom_error(error, BEDRAY_ERROR_INTERNAL, "bloom generation exhausted");
    std::array<gpu::ImageId, levels> next{};
    std::array<gpu::ImageView, levels> views{};
    for (std::size_t i = 0; i < levels; ++i) {
        gpu::ImageDesc image{};
        image.width = mip_size(width, static_cast<std::uint32_t>(i));
        image.height = mip_size(height, static_cast<std::uint32_t>(i));
        image.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        image.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        VkResult vk = VK_SUCCESS;
        const auto status = images_->create(image, next[i], views[i], vk);
        if (status != gpu::Status::ok) {
            for (auto id : next) if (id.serial) (void)images_->retire(id);
            (void)images_->collect();
            return gpu_status(status, vk, error);
        }
    }
    for (auto id : ids_) if (id.serial) (void)images_->retire(id);
    ids_ = next;
    views_ = views;
    width_ = width; height_ = height;
    ++generation_;
    initialize_ = true; invalidated_ = false;
    return gpu_status(images_->collect(), VK_SUCCESS, error);
}

BedrayStatus BloomPass::render(const BedrayBloomFrameDesc& frame, BedrayError* error) {
    if (!images_ || lost_) return bloom_error(error, BEDRAY_ERROR_UNSUPPORTED, "bloom pass unavailable");
    if (frame.abi_version != BEDRAY_CORE_ABI_VERSION || frame.struct_size != sizeof(frame))
        return bloom_error(error, BEDRAY_ERROR_ABI_MISMATCH, "bloom frame ABI/size mismatch");
    if (!frame.command_buffer || !frame.width || !frame.height || !frame.output_view || !frame.depth_view
        || !frame.push_constants || frame.push_bytes != 128 || frame.bloom_enabled > 1)
        return bloom_error(error, BEDRAY_ERROR_INVALID_ARGUMENT, "invalid bloom frame");
    if (recordings_.contains(frame.command_buffer)) return bloom_error(error, BEDRAY_ERROR_IN_USE, "bloom command pending");
    std::array<unsigned char, 128> push{};
    std::memcpy(push.data(), frame.push_constants, push.size());
    float strength = 0;
    std::memcpy(&strength, push.data() + 104, sizeof(strength));
    if (!frame.bloom_enabled && strength != 0)
        return bloom_error(error, BEDRAY_ERROR_INVALID_ARGUMENT, "glare-only bloom strength must be zero");
    auto status = ensure_images(frame.width, frame.height, error);
    if (status != BEDRAY_OK) return status;
    auto& pins = recordings_.try_emplace(frame.command_buffer).first->second;
    pins.generation = generation_;
    for (std::size_t i = 0; i < levels; ++i) {
        const auto result = images_->begin_use(ids_[i], pins.uses[i]);
        if (result != gpu::Status::ok) {
            (void)release_recording(frame.command_buffer); // No command has been recorded yet.
            return gpu_status(result, VK_SUCCESS, error);
        }
    }
    const auto command = reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    memory_barrier(fns_, command, true);
    if (initialize_) for (const auto& view : views_) {
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = view.image;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        fns_.cmd_pipeline_barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);
    }
    initialize_ = false;
    auto dispatch = [&](std::size_t stage, std::uint32_t width, std::uint32_t height, std::uint64_t source,
                        std::uint64_t target) {
        std::array<BedrayResource, 3> resources{image_binding(0, true, source, desc_.linear_sampler),
            image_binding(stage == 3 ? 2 : 1, false, target), image_binding(3, true, frame.depth_view, desc_.nearest_sampler)};
        BedrayFrameDesc call{};
        call.abi_version = BEDRAY_CORE_ABI_VERSION;
        call.command_buffer = frame.command_buffer; call.program = desc_.programs[stage];
        call.group_count_x = (width - 1) / 8 + 1; call.group_count_y = (height - 1) / 8 + 1; call.group_count_z = 1;
        call.resources = resources.data(); call.resource_count = stage == 0 || stage == 3 ? 3 : 2;
        call.push_constants = push.data(); call.push_constant_bytes = static_cast<std::uint32_t>(push.size());
        const auto result = bedray_core_render_frame(core_, &call, error);
        if (result == BEDRAY_ERROR_DEVICE_LOST) lost_ = true;
        if (result == BEDRAY_OK) memory_barrier(fns_, command, stage == 3);
        return result;
    };
    if (frame.bloom_enabled) {
        status = dispatch(0, views_[0].width, views_[0].height, frame.output_view, bits(views_[0].view));
        if (status != BEDRAY_OK) return status;
        for (std::size_t i = 1; i < levels; ++i) {
            status = dispatch(1, views_[i].width, views_[i].height, bits(views_[i-1].view), bits(views_[i].view));
            if (status != BEDRAY_OK) return status;
        }
        for (std::size_t i = levels - 1; i > 0; --i) {
            status = dispatch(2, views_[i-1].width, views_[i-1].height, bits(views_[i].view), bits(views_[i-1].view));
            if (status != BEDRAY_OK) return status;
        }
    }
    return dispatch(3, frame.width, frame.height, bits(views_[0].view), frame.output_view);
}

void BloomPass::discard_recording(std::uint64_t command) noexcept {
    const auto found = recordings_.find(command);
    if (found != recordings_.end() && found->second.generation == generation_) invalidated_ = true;
}

BedrayStatus BloomPass::release_recording(std::uint64_t command) noexcept {
    const auto found = recordings_.find(command);
    if (found == recordings_.end()) return BEDRAY_OK;
    if (lost_) {
        if (recordings_.size() > 1) {
            recordings_.erase(found); // This proof is known; other recordings still keep the whole pool alive.
            return BEDRAY_OK;
        }
        images_->device_lost();
        const auto result = gpu_status(images_->release_after_device_loss(), VK_SUCCESS, nullptr);
        if (result == BEDRAY_OK) {
            recordings_.erase(found);
            images_.reset();
            ids_ = {}; views_ = {};
        }
        return result; // Retain the last proven ticket if final cleanup needs retry.
    }
    for (auto& use : found->second.uses) if (use.serial) {
        const auto result = images_->complete_use(use);
        if (result != gpu::Status::ok) return gpu_status(result, VK_SUCCESS, nullptr);
        use = {};
    }
    const auto result = gpu_status(images_->collect(), VK_SUCCESS, nullptr);
    if (result == BEDRAY_OK) recordings_.erase(found);
    return result;
}

BedrayStatus BloomPass::destroy() noexcept {
    if (has_recordings()) return BEDRAY_ERROR_IN_USE;
    if (!images_) {
        desc_ = {}; core_ = nullptr; width_ = height_ = 0;
        initialize_ = invalidated_ = lost_ = false;
        return BEDRAY_OK;
    }
    if (!lost_) {
        for (auto id : ids_) if (id.serial) (void)images_->retire(id);
        const auto collected = images_->collect();
        if (collected != gpu::Status::ok) return gpu_status(collected, VK_SUCCESS, nullptr);
    }
    if (lost_) images_->device_lost();
    const auto result = lost_ ? images_->release_after_device_loss() : images_->close();
    if (result != gpu::Status::ok) return gpu_status(result, VK_SUCCESS, nullptr);
    images_.reset(); recordings_.clear(); ids_ = {}; views_ = {}; desc_ = {}; core_ = nullptr;
    width_ = height_ = 0; initialize_ = invalidated_ = lost_ = false;
    return BEDRAY_OK;
}
}
