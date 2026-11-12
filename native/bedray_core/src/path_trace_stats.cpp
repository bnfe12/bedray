#include "path_trace_pass.h"

namespace bedray::core {

BedrayStatus PathTracePass::record_stats(std::uint64_t command_buffer, std::uint64_t stats_buffer,
    std::uint64_t stats_offset, std::uint64_t stats_bytes, std::uint32_t frame_index,
    BedrayError* error) try {
    const auto it = recordings_.find(command_buffer);
    if (it == recordings_.end()) {
        // Nothing to meter: the radiance and accumulation images a recording
        // reads belong to that recording, so a stray call must fail, not skip.
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "command buffer 0x%llx holds no path-trace recording to measure",
            static_cast<unsigned long long>(command_buffer));
    }
    if (program_stats_ == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "program_stats is unset: query BEDRAY_PATH_TRACE_FEATURE_FRAME_STATS and lend frame_stats.slang");
    }
    if (stats_buffer == 0) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "stats_buffer is null");
    }
    if (stats_bytes < kStatsCounterBytes) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "a %llu-byte statistics range cannot cover the four counters at stats[0..3]",
            static_cast<unsigned long long>(stats_bytes));
    }
    if ((stats_offset & 3u) != 0u) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT,
            "stats_offset %llu is not a multiple of the four-byte counter word",
            static_cast<unsigned long long>(stats_offset));
    }
    if (stats_offset > std::numeric_limits<std::uint64_t>::max() - stats_bytes) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "statistics descriptor range overflows");
    }
    const RecordingPins& recording = it->second;
    if (recording.radiance.view == VK_NULL_HANDLE || recording.accum.view == VK_NULL_HANDLE
        || recording.depth_view == 0 || recording.width == 0 || recording.height == 0) {
        return fail(error, BEDRAY_ERROR_INTERNAL, "the pending recording has no statistics snapshot");
    }

    // The four bindings of passes/frame_stats.slang, in its own order. The
    // accumulation half is the one the display stage of THIS recording folded,
    // the depth view and the counter range are borrowed and never retained.
    std::array<BedrayResource, 4> stats_resources{};
    stats_resources[0] = image_resource(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, recording.radiance.view);
    stats_resources[1] = image_resource(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, recording.accum.view);
    stats_resources[2] = image_resource(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, as_view(recording.depth_view));
    stats_resources[3] = buffer_resource(3, as_buffer(stats_buffer), stats_offset, stats_bytes);

    FrameStatsPush push{};
    push.frame_index = frame_index;
    push.reserved = 0;

    BedrayFrameDesc stats_desc{};
    stats_desc.abi_version = BEDRAY_CORE_ABI_VERSION;
    stats_desc.command_buffer = command_buffer;
    stats_desc.program = program_stats_;
    // The same full-extent 8x8 grid the display stage used; the shader's own
    // 8x8x1 workgroup and per-texel guard drop the threads past the extent.
    stats_desc.group_count_x = groups(recording.width);
    stats_desc.group_count_y = groups(recording.height);
    stats_desc.group_count_z = 1;
    stats_desc.resource_count = static_cast<std::uint32_t>(stats_resources.size());
    stats_desc.resources = stats_resources.data();
    stats_desc.push_constants = &push;
    stats_desc.push_constant_bytes = static_cast<std::uint32_t>(sizeof(push));

    // The display writes sit behind the recording's exit barrier and the pass's
    // images stay pinned, so the stage needs no barrier, pool or pin of its own:
    // bedray_core_render_frame gives it the per-dispatch descriptor set that
    // release_recording for this command buffer already retires.
    const BedrayStatus status = bedray_core_render_frame(core_, &stats_desc, error);
    if (status == BEDRAY_ERROR_DEVICE_LOST) {
        device_lost();
    }
    return status;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace statistics state");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path-trace statistics");
}


} // namespace bedray::core
