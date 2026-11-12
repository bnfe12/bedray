#include "path_trace_pass.h"

namespace bedray::core {

BedrayStatus PathTracePass::record_pt_spatial_stage(const BedrayPathTraceFrameDesc& desc,
    const std::array<BedrayResource, 30>& trace_resources, const BedrayResource& pt_resource,
    const PathTracePush& trace_push, std::uint64_t program_pt_spatial, bool pt_spatial,
    VkCommandBuffer command, BedrayError* error) {
    BedrayStatus status = BEDRAY_OK;
    // ReSTIR PT spatial stage (restir/pt_spatial.slang): reads the half the trace just wrote (PT_PARITY of the same
    // push) and publishes the pooled records into the other half.
    if (pt_spatial) {
        const std::array<BedrayResource, 14> pt_spatial_resources{
            trace_resources[0], trace_resources[2], trace_resources[3], trace_resources[5], trace_resources[6],
            trace_resources[8], trace_resources[9], trace_resources[12], trace_resources[13], trace_resources[14],
            trace_resources[15], trace_resources[16], trace_resources[17], pt_resource};
        BedrayFrameDesc pt_spatial_desc{};
        pt_spatial_desc.abi_version = BEDRAY_CORE_ABI_VERSION;
        pt_spatial_desc.command_buffer = desc.command_buffer;
        pt_spatial_desc.program = program_pt_spatial;
        pt_spatial_desc.group_count_x = groups(desc.camera.width);
        pt_spatial_desc.group_count_y = groups(desc.camera.height);
        pt_spatial_desc.group_count_z = 1;
        pt_spatial_desc.resource_count = static_cast<std::uint32_t>(pt_spatial_resources.size());
        pt_spatial_desc.resources = pt_spatial_resources.data();
        pt_spatial_desc.push_constants = &trace_push;
        pt_spatial_desc.push_constant_bytes = static_cast<std::uint32_t>(sizeof(trace_push));
        status = bedray_core_render_frame(core_, &pt_spatial_desc, error);
        if (status != BEDRAY_OK) {
            if (status == BEDRAY_ERROR_DEVICE_LOST) device_lost();
            return status;
        }
        compute_barrier(fns_, command);
    }
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::record_di_spatial_stage(const BedrayPathTraceFrameDesc& desc,
    std::uint32_t di_flags, std::uint32_t di_parity, const BedrayPathTraceBuffer& emitters,
    const BedrayPathTraceBuffer& reservoirs, const BedrayPathTraceRange& entity_rects,
    const BedrayPathTraceRange& entity_texels, const gpu::BufferView& frame_view,
    VkCommandBuffer command, BedrayError* error) {
    BedrayStatus status = BEDRAY_OK;
    const bool spatial_enabled = program_spatial_ != 0
        && (desc.flags & BEDRAY_PATH_TRACE_FRAME_SPATIAL) != 0
        && (di_flags & BEDRAY_PATH_TRACE_DI_ENABLED) != 0
        && desc.path.emitter_count > 0
        && desc.scene.reservoirs.handle != 0;
    if (spatial_enabled) {
        SpatialPush64 spatial_push{};
        spatial_push.base.state[0] = desc.frame_index;
        spatial_push.base.state[1] = di_parity;
        spatial_push.base.state[2] = 0;
        spatial_push.base.state[3] = kSpatialEnabled;
        copy4(spatial_push.base.camera_relative, desc.path.emitter_camera_relative);
        spatial_push.base.max_distance = desc.path.max_distance;
        spatial_push.entity_atlas_size = desc.path.entity_state[0];

        std::array<BedrayResource, 8> spatial_resources{};
        spatial_resources[0] = image_resource(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            as_view(desc.scene.albedo_view), as_sampler(desc.scene.atlas_sampler));
        spatial_resources[1] = image_resource(9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            as_view(desc.scene.material_view), as_sampler(desc.scene.atlas_sampler));
        spatial_resources[2] = image_resource(10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            as_view(desc.scene.normal_view), as_sampler(desc.scene.atlas_sampler));
        spatial_resources[3] = uniform_resource(16, frame_view.buffer, 0, sizeof(FrameConstants));
        spatial_resources[4] = buffer_resource(18, as_buffer(emitters.handle),
            emitters.offset, emitters.bytes);
        spatial_resources[5] = buffer_resource(19, as_buffer(reservoirs.handle),
            reservoirs.offset, reservoirs.bytes);
        spatial_resources[6] = buffer_resource(23, as_buffer(entity_rects.handle),
            entity_rects.offset, entity_rects.bytes);
        spatial_resources[7] = buffer_resource(24, as_buffer(entity_texels.handle),
            entity_texels.offset, entity_texels.bytes);

        BedrayFrameDesc spatial_desc{};
        spatial_desc.abi_version = BEDRAY_CORE_ABI_VERSION;
        spatial_desc.command_buffer = desc.command_buffer;
        spatial_desc.program = program_spatial_;
        spatial_desc.group_count_x = groups(desc.camera.width);
        spatial_desc.group_count_y = groups(desc.camera.height);
        spatial_desc.group_count_z = 1;
        spatial_desc.resource_count = static_cast<std::uint32_t>(spatial_resources.size());
        spatial_desc.resources = spatial_resources.data();
        spatial_desc.push_constants = &spatial_push;
        spatial_desc.push_constant_bytes = spatial_push_bytes_;

        status = bedray_core_render_phases(core_, &spatial_desc, 8, 2, error);
        if (status != BEDRAY_OK) {
            if (status == BEDRAY_ERROR_DEVICE_LOST) device_lost();
            return status;
        }
        compute_barrier(fns_, command);
    }
    return BEDRAY_OK;
}

BedrayStatus PathTracePass::record_display_resolve(const BedrayPathTraceFrameDesc& desc,
    std::uint32_t write, std::uint32_t read, const gpu::BufferView& frame_view,
    bool reset_history, BedrayError* error) {
    BedrayStatus status = BEDRAY_OK;
    std::uint32_t display_flags = BEDRAY_PATH_DISPLAY_TONEMAP;
    if ((desc.flags & BEDRAY_PATH_TRACE_FRAME_ACCUMULATE) != 0) {
        display_flags |= BEDRAY_PATH_DISPLAY_ACCUMULATE;
    }
    if ((desc.flags & BEDRAY_PATH_TRACE_FRAME_CAMERA_STATIC) != 0) {
        display_flags |= BEDRAY_PATH_DISPLAY_CAMERA_STATIC;
    }
    if ((desc.flags & BEDRAY_PATH_TRACE_FRAME_FLIP_Y) != 0) {
        display_flags |= BEDRAY_PATH_DISPLAY_FLIP_Y;
    }
    if (history_written_) {
        display_flags |= BEDRAY_PATH_DISPLAY_REPROJECT;
    }
    display_flags |= desc.display_flags;

    PathDisplayPush display_push{};
    // A reset means the accumulation half is fresh; a non-zero count would let
    // the same-pixel branch read a history image that was just recreated.
    display_push.accum_frames = reset_history ? 0u : desc.accum_frames;
    display_push.flags = display_flags;
    display_push.exposure = desc.lighting.exposure;
    display_push.max_radiance = desc.lighting.max_radiance > 0.0f
        ? desc.lighting.max_radiance
        : desc.path.max_radiance;
    display_push.reuse[0] = desc.path.di_camera_delta[0];
    display_push.reuse[1] = desc.path.di_camera_delta[1];
    display_push.reuse[2] = desc.path.di_camera_delta[2];
    display_push.reuse[3] = desc.lighting.reprojection_tolerance > 0.0f
        ? desc.lighting.reprojection_tolerance
        : 0.02f;

    std::array<BedrayResource, 9> display_resources{};
    display_resources[0] = image_resource(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, radiance_view_.view);
    display_resources[1] = image_resource(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, accum_view_[write].view);
    display_resources[2] = image_resource(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, as_view(desc.output_view));
    display_resources[3] = image_resource(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, as_view(desc.depth_view));
    display_resources[4] = image_resource(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        history_depth_view_[write].view);
    display_resources[5] = uniform_resource(5, frame_view.buffer, 0, sizeof(FrameConstants));
    display_resources[6] = image_resource(6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, motion_view_.view);
    display_resources[7] = image_resource(7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, accum_view_[read].view);
    display_resources[8] = image_resource(8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        history_depth_view_[read].view);

    BedrayFrameDesc display_desc{};
    display_desc.abi_version = BEDRAY_CORE_ABI_VERSION;
    display_desc.command_buffer = desc.command_buffer;
    display_desc.program = program_display_;
    display_desc.group_count_x = groups(desc.camera.width);
    display_desc.group_count_y = groups(desc.camera.height);
    display_desc.group_count_z = 1;
    display_desc.resource_count = static_cast<std::uint32_t>(display_resources.size());
    display_desc.resources = display_resources.data();
    display_desc.push_constants = &display_push;
    display_desc.push_constant_bytes = static_cast<std::uint32_t>(sizeof(display_push));

    status = bedray_core_render_frame(core_, &display_desc, error);
    if (status != BEDRAY_OK) {
        if (status == BEDRAY_ERROR_DEVICE_LOST) device_lost();
        return status;
    }
    return BEDRAY_OK;
}

void PathTracePass::publish_frame(bool native_di_active, bool native_pt_active, bool pt_spatial,
    VkCommandBuffer command) {
    history_written_ = true;
    history_parity_ ^= 1u;
    if (native_di_active) {
        owned_di_history_ = true;
        owned_di_needs_clear_ = false;
        owned_di_parity_ ^= 1u;
    }
    if (native_pt_active) {
        owned_pt_history_ = true;
        owned_pt_reset_pending_ = false;
        owned_pt_parity_ ^= 1u;                   // the trace wrote its half
        if (pt_spatial) owned_pt_parity_ ^= 1u;   // and the spatial stage published the other one
    }

    exit_barrier(fns_, command);
}

} // namespace bedray::core
