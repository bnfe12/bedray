#define VK_NO_PROTOTYPES
#include "core_state.h"

extern "C" {

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_path_trace(
    BedrayCore* core,
    const BedrayPathTraceDesc* desc,
    BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || desc == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (core->path_trace && core->path_trace->has_recordings()) {
        return fail(error, BEDRAY_ERROR_IN_USE,
            "path-trace recordings must be released before reconfiguration");
    }
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    if (core->frame_graph && core->frame_graph->has_recordings())
        return fail(error, BEDRAY_ERROR_IN_USE, "native graph recordings must be released before reconfiguration");
#endif
    if (core->path_trace) {
        const BedrayStatus status = core->path_trace->destroy();
        if (status != BEDRAY_OK) {
            return status;
        }
        core->path_trace.reset();
    }

    auto pass = std::make_unique<bedray::core::PathTracePass>();
    const BedrayStatus status = pass->configure(core, core->device_fns, *desc, error);
    if (status != BEDRAY_OK) {
        return status;
    }
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    auto frame_graph = std::make_unique<bedray::core::FrameGraphOwner>();
    const auto record = [](void* pointer, const BedrayPathTraceFrameDesc& frame,
        const BedrayPathTraceGuides* guides, const BedrayPathTraceBuffer* motion, const BedrayPathTraceBuffer* stats,
        BedrayError* err) {
        return static_cast<BedrayCore*>(pointer)->path_trace->render(frame, err, guides, motion, stats);
    };
    const auto release = [](void* pointer, std::uint64_t command) {
        return bedray_core_release_recording(static_cast<BedrayCore*>(pointer), command);
    };
    const BedrayStatus graph_status = frame_graph->configure(core->capabilities, core->queue_family,
        core->cmd_pipeline_barrier, record, release, core, error);
    if (graph_status != BEDRAY_OK) return graph_status;
    core->frame_graph = std::move(frame_graph);
#endif
    core->path_trace = std::move(pass);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace pass");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring path trace");
}

static BedrayStatus record_path_trace(BedrayCore* core, const BedrayPathTraceFrameDesc* desc,
    const BedrayPathTraceGuides* guides, const BedrayPathTraceBuffer* motion, BedrayError* error,
    const BedrayPathTraceBuffer* stats_slot = nullptr) {
    clear_error(error);
    if (core == nullptr || desc == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/desc is null");
    }
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION) {
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH,
            "ABI mismatch: host=%u core=%u", desc->abi_version, BEDRAY_CORE_ABI_VERSION);
    }
    if (!core->path_trace) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path trace is not configured");
    }
    if (guides && (guides->abi_version != BEDRAY_CORE_ABI_VERSION || guides->struct_size != sizeof(*guides)))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "path-trace guide ABI/size mismatch");
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    return core->frame_graph->record(*desc, error, guides, motion, stats_slot);
#else
    return core->path_trace->render(*desc, error, guides, motion, stats_slot);
#endif
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace_ext(
    BedrayCore* core, const BedrayPathTraceFrameDesc* desc, const BedrayPathTraceGuides* guides,
    const BedrayPathTraceBuffer* motion, const BedrayPathTraceBuffer* stats_slot, BedrayError* error) try {
    clear_error(error);
    if (motion && !guides) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "motion output needs the guide outputs");
    return record_path_trace(core, desc, guides, motion, error, stats_slot);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace frame");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path trace");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_path_trace_pt(
    BedrayCore* core, uint64_t program_pt_spatial, uint64_t program_pt_spatial_scene, BedrayError* error) try {
    clear_error(error);
    if (core == nullptr || !core->path_trace)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "ReSTIR PT needs a configured path-trace pass");
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    if (core->frame_graph && core->frame_graph->has_recordings())
        return fail(error, BEDRAY_ERROR_IN_USE, "native graph recordings must be released before reconfiguration");
#endif
    return core->path_trace->configure_pt(program_pt_spatial, program_pt_spatial_scene, error);
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring ReSTIR PT");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_radiance_cascades(
    BedrayCore* core, const BedrayRCDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc || !core->path_trace)
        return fail(error,BEDRAY_ERROR_INVALID_ARGUMENT,"RC requires a configured native path owner");
    constexpr auto required = BEDRAY_CAP_RAY_QUERY | BEDRAY_CAP_ACCELERATION_STRUCTURE
        | BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64;
    if ((core->capabilities & required) != required)
        return fail(error,BEDRAY_ERROR_UNSUPPORTED,"RC ray-query/AS/BDA/Int64 features are not enabled");
    return core->path_trace->configure_rc(*desc,core->properties.limits.maxComputeWorkGroupCount[0],error);
} catch (const std::bad_alloc&) {
    return fail(error,BEDRAY_ERROR_OUT_OF_MEMORY,"cannot allocate RC field owner");
} catch (...) {
    return fail(error,BEDRAY_ERROR_INTERNAL,"unexpected exception configuring RC field");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace(
    BedrayCore* core, const BedrayPathTraceFrameDesc* desc, BedrayError* error) try {
    return record_path_trace(core, desc, nullptr, nullptr, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace frame");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path trace");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace_guides(
    BedrayCore* core, const BedrayPathTraceFrameDesc* desc,
    const BedrayPathTraceGuides* guides, BedrayError* error) try {
    clear_error(error);
    if (!guides) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "guide outputs are null");
    return record_path_trace(core, desc, guides, nullptr, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate guided path-trace frame");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording guided path trace");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_path_trace_motion(
    BedrayCore* core, const BedrayPathTraceFrameDesc* desc, const BedrayPathTraceGuides* guides,
    const BedrayPathTraceBuffer* motion, BedrayError* error) try {
    clear_error(error);
    if (!guides || !motion) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "guide/motion outputs are null");
    return record_path_trace(core, desc, guides, motion, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace motion frame");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path-trace motion");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_record_path_trace_stats(
    BedrayCore* core, uint64_t command_buffer, uint64_t stats_buffer,
    uint64_t stats_offset, uint64_t stats_bytes, uint32_t frame_index,
    BedrayError* error) try {
    clear_error(error);
    if (core == nullptr) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core is null");
    }
    if (!core->path_trace) {
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "path trace is not configured");
    }
    // The stage rides the recording the render call already made: same command
    // buffer, same per-dispatch descriptor set, same release proof.
    return core->path_trace->record_stats(command_buffer, stats_buffer, stats_offset,
        stats_bytes, frame_index, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate path-trace statistics dispatch");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording path-trace statistics");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_path_trace(BedrayCore* core) try {
    if (core == nullptr) {
        return BEDRAY_ERROR_INVALID_ARGUMENT;
    }
    if (!core->path_trace) {
        return BEDRAY_OK;
    }
    if (core->path_trace->has_recordings()) {
        return BEDRAY_ERROR_IN_USE;
    }
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    if (core->frame_graph && core->frame_graph->has_recordings()) return BEDRAY_ERROR_IN_USE;
#endif
    const BedrayStatus status = core->path_trace->destroy();
    if (status != BEDRAY_OK) {
        return status;
    }
    core->path_trace.reset();
#if defined(BEDRAY_CORE_FRAME_GRAPH)
    core->frame_graph.reset();
#endif
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}


} // extern "C"
