#define VK_NO_PROTOTYPES
#include "core_state.h"

#include "particles_pass.h"
#include "sodium_pass.h"
#include "nrd_pack_pass.h"
#include "fg_prepare_pass.h"
#include "spatial_pass.h"

extern "C" {

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_bloom(
    BedrayCore* core, const BedrayBloomDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/bloom desc is null");
    if (core->bloom && core->bloom->has_recordings()) return fail(error, BEDRAY_ERROR_IN_USE, "bloom recordings pending");
    auto pass = std::make_unique<bedray::core::BloomPass>();
    auto status = pass->configure(core, core->device_fns, *desc, error);
    if (status != BEDRAY_OK) return status;
    if (core->bloom) {
        status = core->bloom->destroy();
        if (status != BEDRAY_OK) return status;
    }
    core->bloom = std::move(pass);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate bloom pass");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring bloom");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_particles(
    BedrayCore* core, const BedrayParticlesFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/particle frame is null");
    return bedray::core::record_particles(core, core->cmd_pipeline_barrier, *desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate particle recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording particles");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_velocity(
    BedrayCore* core, const BedrayVelocityDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/velocity descriptor is null");
    constexpr auto required = BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64
        | BEDRAY_CAP_STORAGE_IMAGE_EXTENDED_FORMATS;
    if ((core->capabilities & required) != required)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "velocity BDA/Int64/extended image formats are not enabled");
    if (!core->velocity) core->velocity = std::make_unique<bedray::core::VelocityPass>();
    return core->velocity->configure(core, core->device_fns, *desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate velocity owner");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring velocity");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_velocity(
    BedrayCore* core, const BedrayVelocityFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc || !core->velocity)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "velocity owner/frame is missing");
    return core->velocity->render(*desc,error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate velocity recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording velocity");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_velocity(BedrayCore* core) try {
    if (!core) return BEDRAY_ERROR_INVALID_ARGUMENT;
    if (!core->velocity) return BEDRAY_OK;
    auto status = core->velocity->destroy();
    if (status == BEDRAY_OK) core->velocity.reset();
    return status;
} catch (...) { return BEDRAY_ERROR_INTERNAL; }

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_nrd_pack(
    BedrayCore* core, const BedrayNrdPackFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error,BEDRAY_ERROR_INVALID_ARGUMENT,"core/NRD pack descriptor is null");
    if (desc->phase == BEDRAY_NRD_PACK) {
        const auto required = BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64
            | (desc->denoiser != 2u ? BEDRAY_CAP_STORAGE_IMAGE_EXTENDED_FORMATS : 0u);
        if ((core->capabilities & required) != required)
            return fail(error,BEDRAY_ERROR_UNSUPPORTED,"NRD pack shader features are not enabled");
    }
    return bedray::core::record_nrd_pack(core,core->cmd_pipeline_barrier,*desc,error);
} catch (const std::bad_alloc&) {
    return fail(error,BEDRAY_ERROR_OUT_OF_MEMORY,"cannot allocate NRD pack recording");
} catch (...) {
    return fail(error,BEDRAY_ERROR_INTERNAL,"unexpected exception recording NRD pack");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_spatial(
    BedrayCore* core, const BedraySpatialFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/spatial descriptor is null");
    return bedray::core::record_spatial(core, core->cmd_pipeline_barrier, *desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate spatial recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording spatial upscaling");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_fg_prepare(
    BedrayCore* core, const BedrayFgPrepareFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/FG producer descriptor is null");
    if ((core->capabilities & BEDRAY_CAP_STORAGE_IMAGE_EXTENDED_FORMATS) == 0)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "FG producer extended image formats are not enabled");
    return bedray::core::record_fg_prepare(core, core->cmd_pipeline_barrier,
        core->cmd_blit_image, *desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate FG producer recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording FG producer");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_decode_sodium(
    BedrayCore* core, const BedraySodiumDecodeDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/Sodium decode desc is null");
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION || desc->struct_size != sizeof(*desc))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "Sodium decode ABI/size mismatch");
    constexpr auto required = BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64;
    if ((core->capabilities & required) != required)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "Sodium decode requires enabled BDA/Int64");
    return bedray::core::record_sodium(core, core->cmd_pipeline_barrier,
        core->properties.limits.maxComputeWorkGroupCount[0], *desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate Sodium decode recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording Sodium decode");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_bloom(
    BedrayCore* core, const BedrayBloomFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc || !core->bloom) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "bloom not configured");
    return core->bloom->render(*desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate bloom recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording bloom");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_bloom(BedrayCore* core) try {
    if (!core) return BEDRAY_ERROR_INVALID_ARGUMENT;
    if (!core->bloom) return BEDRAY_OK;
    const auto status = core->bloom->destroy();
    if (status == BEDRAY_OK) core->bloom.reset();
    return status;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_configure_volumetric(
    BedrayCore* core, const BedrayVolumetricDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc) return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "core/volumetric desc is null");
    if (desc->abi_version != BEDRAY_CORE_ABI_VERSION || desc->struct_size != sizeof(*desc))
        return fail(error, BEDRAY_ERROR_ABI_MISMATCH, "volumetric ABI/size mismatch");
    constexpr auto required = BEDRAY_CAP_RAY_QUERY | BEDRAY_CAP_ACCELERATION_STRUCTURE
        | BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64;
    if ((core->capabilities & required) != required)
        return fail(error, BEDRAY_ERROR_UNSUPPORTED, "volumetric ray-query/BDA/Int64 capabilities are not enabled");
    if (core->volumetric && core->volumetric->has_recordings())
        return fail(error, BEDRAY_ERROR_IN_USE, "volumetric recordings pending");
    auto pass = std::make_unique<bedray::core::VolumetricPass>();
    auto status = pass->configure(core, core->device_fns, *desc, error);
    if (status != BEDRAY_OK) return status;
    if (core->volumetric) {
        status = core->volumetric->destroy();
        if (status != BEDRAY_OK) return status;
    }
    core->volumetric = std::move(pass);
    return BEDRAY_OK;
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate volumetric pass");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception configuring volumetric pass");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_volumetric(
    BedrayCore* core, const BedrayVolumetricFrameDesc* desc, BedrayError* error) try {
    clear_error(error);
    if (!core || !desc || !core->volumetric)
        return fail(error, BEDRAY_ERROR_INVALID_ARGUMENT, "volumetric pass is not configured");
    return core->volumetric->render(*desc, error);
} catch (const std::bad_alloc&) {
    return fail(error, BEDRAY_ERROR_OUT_OF_MEMORY, "cannot allocate volumetric recording");
} catch (...) {
    return fail(error, BEDRAY_ERROR_INTERNAL, "unexpected exception recording volumetric pass");
}

BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_destroy_volumetric(BedrayCore* core) try {
    if (!core) return BEDRAY_ERROR_INVALID_ARGUMENT;
    if (!core->volumetric) return BEDRAY_OK;
    const auto status = core->volumetric->destroy();
    if (status == BEDRAY_OK) core->volumetric.reset();
    return status;
} catch (const std::bad_alloc&) {
    return BEDRAY_ERROR_OUT_OF_MEMORY;
} catch (...) {
    return BEDRAY_ERROR_INTERNAL;
}


} // extern "C"
