#include "path_trace_pass.h"

namespace bedray::core {

BedrayStatus PathTracePass::create_guide_dummies(BedrayError* error) {
    constexpr std::array<VkFormat, 3> formats{VK_FORMAT_R32_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM};
    for (std::size_t i = 0; i < formats.size(); ++i) {
        gpu::ImageDesc desc{};
        desc.width = desc.height = 1;
        desc.format = formats[i];
        desc.usage = VK_IMAGE_USAGE_STORAGE_BIT;
        VkResult vk = VK_SUCCESS;
        const Status status = images_->create(desc, guide_dummy_ids_[i], guide_dummy_views_[i], vk);
        if (status != Status::ok) {
            if (status == Status::device_lost) device_lost();
            return fail(error, status_of(status), "cannot create native guide stand-in: VkResult=%d", static_cast<int>(vk));
        }
    }
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::create_dummy(VkDeviceSize bytes, gpu::BufferId& id,
    gpu::BufferView& view, BedrayError* error) {
    if (!buffers_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "buffer owner is missing");
    }
    gpu::BufferDesc descriptor{};
    descriptor.size = bytes;
    descriptor.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    descriptor.required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    descriptor.preferred = VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    descriptor.mapped = true;

    VkResult result = VK_SUCCESS;
    Status status = buffers_->create(descriptor, id, view, result);
    if (status != Status::ok) {
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot create a %llu-byte dummy buffer: VkResult=%d",
            static_cast<unsigned long long>(bytes), static_cast<int>(result));
    }

    // Sized to the dummy itself: the ReSTIR PT stand-in is 144 bytes, more than any fixed scratch array here held.
    const std::vector<unsigned char> zero(static_cast<std::size_t>(bytes), 0u);
    status = buffers_->write(id, 0, zero.data(), static_cast<std::size_t>(bytes), result);
    if (status != Status::ok) {
        static_cast<void>(buffers_->retire(id));
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot zero a dummy buffer: VkResult=%d",
            static_cast<int>(result));
    }
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::ensure_images(std::uint32_t width, std::uint32_t height,
    BedrayError* error) {
    if (!images_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "image owner is missing");
    }
    if (images_ready_ && image_width_ == width && image_height_ == height) {
        return BEDRAY_OK;
    }
    if (images_ready_) {
        for (gpu::ImageId id : {radiance_id_, motion_id_, accum_id_[0], accum_id_[1],
                 history_depth_id_[0], history_depth_id_[1]}) {
            if (id.serial != 0) {
                static_cast<void>(images_->retire(id));
            }
        }
        static_cast<void>(images_->collect());
        images_ready_ = false;
        images_initialized_ = false;
    }

    auto create = [&](VkFormat format, gpu::ImageId& id, gpu::ImageView& view) -> BedrayStatus {
        gpu::ImageDesc descriptor{};
        descriptor.width = width;
        descriptor.height = height;
        descriptor.format = format;
        descriptor.usage = VK_IMAGE_USAGE_STORAGE_BIT;
        VkResult result = VK_SUCCESS;
        const Status status = images_->create(descriptor, id, view, result);
        if (status != Status::ok) {
            if (status == Status::device_lost) {
                device_lost();
            }
            return fail(error, status_of(status), "cannot create a %ux%u path-trace image: VkResult=%d",
                width, height, static_cast<int>(result));
        }
        return BEDRAY_OK;
    };

    BedrayStatus status = create(VK_FORMAT_R16G16B16A16_SFLOAT, radiance_id_, radiance_view_);
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R16G16B16A16_SFLOAT, motion_id_, motion_view_);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32G32B32A32_SFLOAT, accum_id_[0], accum_view_[0]);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32G32B32A32_SFLOAT, accum_id_[1], accum_view_[1]);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32_SFLOAT, history_depth_id_[0], history_depth_view_[0]);
    }
    if (status == BEDRAY_OK) {
        status = create(VK_FORMAT_R32_SFLOAT, history_depth_id_[1], history_depth_view_[1]);
    }
    if (status != BEDRAY_OK) {
        for (gpu::ImageId id : {radiance_id_, motion_id_, accum_id_[0], accum_id_[1],
                 history_depth_id_[0], history_depth_id_[1]}) {
            if (id.serial != 0) {
                static_cast<void>(images_->retire(id));
            }
        }
        static_cast<void>(images_->collect());
        return status;
    }

    images_ready_ = true;
    images_initialized_ = false;
    image_width_ = width;
    image_height_ = height;
    history_parity_ = 0;
    history_written_ = false;
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::pin_image(gpu::ImageId id, RecordingPins& pins, BedrayError* error) {
    if (!images_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "image owner is missing");
    }
    gpu::UseId token{};
    const Status status = images_->begin_use(id, token);
    if (status != Status::ok) {
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot pin a path-trace image");
    }
    pins.image_uses.push_back(token);
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::pin_buffer(gpu::BufferId id, RecordingPins& pins, BedrayError* error) {
    if (!buffers_) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "buffer owner is missing");
    }
    gpu::UseId token{};
    const Status status = buffers_->begin_use(id, token);
    if (status != Status::ok) {
        if (status == Status::device_lost) {
            device_lost();
        }
        return fail(error, status_of(status), "cannot pin a path-trace buffer");
    }
    pins.buffer_uses.push_back(token);
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::ensure_reservoirs(std::uint64_t bytes, BedrayError* error) {
    if (owned_reservoirs_id_.serial != 0 && owned_reservoirs_view_.size == bytes) return BEDRAY_OK;
    gpu::BufferId next{};
    gpu::BufferView view{};
    VkResult vk = VK_SUCCESS;
    const auto result = buffers_->create({bytes,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false}, next, view, vk);
    if (result != Status::ok)
        return fail(error, status_of(result), "cannot allocate native DI ring: VkResult=%d", static_cast<int>(vk));
    if (owned_reservoirs_id_.serial != 0) static_cast<void>(buffers_->retire(owned_reservoirs_id_));
    owned_reservoirs_id_ = next;
    owned_reservoirs_view_ = view;
    owned_di_parity_ = 0;
    owned_di_history_ = false;
    owned_di_needs_clear_ = true;
    static_cast<void>(buffers_->collect());
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::ensure_pt_ring(std::uint64_t bytes, BedrayError* error) {
    if (owned_pt_id_.serial != 0 && owned_pt_view_.size == bytes) return BEDRAY_OK;
    gpu::BufferId next{};
    gpu::BufferView view{};
    VkResult vk = VK_SUCCESS;
    const auto result = buffers_->create({bytes,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false}, next, view, vk);
    if (result != Status::ok)
        return fail(error, status_of(result), "cannot allocate the native ReSTIR PT ring of %llu bytes: VkResult=%d",
            static_cast<unsigned long long>(bytes), static_cast<int>(vk));
    if (owned_pt_id_.serial != 0) static_cast<void>(buffers_->retire(owned_pt_id_));
    owned_pt_id_ = next;
    owned_pt_view_ = view;
    owned_pt_parity_ = 0;
    owned_pt_history_ = false;
    owned_pt_reset_pending_ = true;
    static_cast<void>(buffers_->collect());
    return BEDRAY_OK;
}

void PathTracePass::retire_pt_ring() noexcept {
    if (owned_pt_id_.serial == 0) return;
    static_cast<void>(buffers_->retire(owned_pt_id_));
    owned_pt_id_ = {};
    owned_pt_view_ = {};
    owned_pt_parity_ = 0;
    owned_pt_reset_pending_ = true;
    static_cast<void>(buffers_->collect());
}


} // namespace bedray::core
