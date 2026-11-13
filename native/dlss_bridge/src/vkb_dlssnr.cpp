#define VKB_DLSS_BUILD
#define VK_NO_PROTOTYPES
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "vkb_ngx_state.h"

#if defined(VKB_DLSS_ENABLE_NR) && VKB_DLSS_ENABLE_NR
#include "nvsdk_ngx_helpers_dlssnr_vk.h"
#endif

#include <cmath>
#include <cstddef>

using namespace vkb::ngx;

static_assert(sizeof(VkbDlssNrCaps) == 24);
static_assert(sizeof(VkbDlssNrCreateInfo) == 16);
static_assert(sizeof(VkbDlssNrEvalInfo) == 336);
static_assert(offsetof(VkbDlssNrEvalInfo, color) == 8);
static_assert(offsetof(VkbDlssNrEvalInfo, control_mask) == 232);
static_assert(offsetof(VkbDlssNrEvalInfo, jitter_x) == 288);
static_assert(offsetof(VkbDlssNrEvalInfo, model) == 320);

VKB_DLSS_API uint32_t vkb_dlss_nr_contract_version(void) {
#if defined(VKB_DLSS_ENABLE_NR) && VKB_DLSS_ENABLE_NR
    return 1;
#else
    return 0;
#endif
}

#if defined(VKB_DLSS_ENABLE_NR) && VKB_DLSS_ENABLE_NR

namespace {

ExtensionList instanceExtensions;
ExtensionList deviceExtensions;

bool validImage(const VkbDlssImage& image) {
    return image.image && image.view && image.width && image.height && image.format && image.aspect_mask &&
        image.level_count == 1 && image.layer_count == 1 && image.read_write <= 1 && image.reserved == 0;
}

bool validStrength(float value) { return value >= 0.0f && value <= 1.0f; }

bool validColorInput(uint32_t format) {
    return format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_R16G16B16A16_SFLOAT;
}

bool validColorOutput(uint32_t format) {
    return format == VK_FORMAT_R16G16B16A16_SFLOAT || format == VK_FORMAT_R32G32B32A32_SFLOAT;
}

bool displayExtent(const VkbDlssImage& image, const Feature& feature) {
    return image.width == feature.width && image.height == feature.height;
}

VkCommandBuffer commandBuffer(uint64_t cmd) {
    return reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(cmd));
}

int32_t capabilityParameters(State& state, VkbDlssNrCaps& caps) {
    if (!state.capabilitiesReady && !refreshCapabilityParameters(state)) return VKB_DLSS_ERR_NGX;
    int needsDriver = 0;
    const auto driver = NVSDK_NGX_Parameter_GetI(state.capParams, NVSDK_NGX_Parameter_DLSSNR_NeedsUpdatedDriver, &needsDriver);
    if (NVSDK_NGX_FAILED(driver)) return failNgx("[DLSS-NR] query minimum driver", driver);
    caps.needs_updated_driver = needsDriver != 0;
    NVSDK_NGX_Parameter_GetUI(state.capParams, NVSDK_NGX_Parameter_DLSSNR_MinDriverVersionMajor, &caps.min_driver_major);
    NVSDK_NGX_Parameter_GetUI(state.capParams, NVSDK_NGX_Parameter_DLSSNR_MinDriverVersionMinor, &caps.min_driver_minor);
    int initResult = 0;
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_Parameter_GetI(state.capParams, NVSDK_NGX_Parameter_DLSSNR_FeatureInitResult, &initResult))) {
        caps.feature_init_result = static_cast<uint32_t>(initResult);
    }
    if (caps.needs_updated_driver) return VKB_DLSS_OK;
    int available = 0;
    const auto result = NVSDK_NGX_Parameter_GetI(state.capParams, NVSDK_NGX_Parameter_DLSSNR_Available, &available);
    if (NVSDK_NGX_FAILED(result)) return failNgx("[DLSS-NR] query availability", result);
    caps.available = available != 0 && !NVSDK_NGX_FAILED(caps.feature_init_result);
    return VKB_DLSS_OK;
}

int32_t unexpectedFailure() noexcept {
    // Preserve an owner already published by create, even if allocating the diagnostic fails.
    try { return fail(VKB_DLSS_ERR_NGX, "[DLSS-NR] unexpected native exception"); }
    catch (...) { return VKB_DLSS_ERR_NGX; }
}

} // namespace

VKB_DLSS_API int32_t vkb_dlss_nr_get_required_instance_extensions(const wchar_t* featurePath,
    const wchar_t* dataPath, uint32_t* outCount, const char* const** outNames) try {
    return queryExtensions(NVSDK_NGX_Feature_DLSSNR, 0, 0, featurePath, dataPath,
        instanceExtensions, outCount, outNames);
} catch (...) { return unexpectedFailure(); }

VKB_DLSS_API int32_t vkb_dlss_nr_get_required_device_extensions(uint64_t instance, uint64_t physicalDevice,
    const wchar_t* featurePath, const wchar_t* dataPath, uint32_t* outCount, const char* const** outNames) try {
    if (!instance || !physicalDevice) return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] missing Vulkan device identity");
    return queryExtensions(NVSDK_NGX_Feature_DLSSNR, instance, physicalDevice, featurePath, dataPath,
        deviceExtensions, outCount, outNames);
} catch (...) { return unexpectedFailure(); }

VKB_DLSS_API int32_t vkb_dlss_nr_query_caps(VkbDlssNrCaps* outCaps) try {
    if (!outCaps || outCaps->struct_size != sizeof(*outCaps))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] missing or invalid capability buffer");
    *outCaps = {};
    outCaps->struct_size = sizeof(*outCaps);
    State& state = st();
    std::lock_guard<std::recursive_mutex> lock(state.mutex);
    if (!state.initialised || state.shuttingDown)
        return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "[DLSS-NR] NGX unavailable or retiring");
    clearError();
    return capabilityParameters(state, *outCaps);
} catch (...) { return unexpectedFailure(); }

VKB_DLSS_API int32_t vkb_dlss_nr_create_feature(uint64_t cmd, const VkbDlssNrCreateInfo* info,
    uint64_t* outFeature) try {
    if (outFeature) *outFeature = 0;
    if (!cmd || !info || !outFeature || info->struct_size != sizeof(*info) ||
        !info->width || !info->height || info->reserved != 0)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] invalid creation descriptor or display extent");
    State& state = st();
    std::lock_guard<std::recursive_mutex> lock(state.mutex);
    if (!state.initialised || state.shuttingDown)
        return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "[DLSS-NR] NGX unavailable or retiring");
    VkbDlssNrCaps caps{};
    const int32_t query = capabilityParameters(state, caps);
    if (query != VKB_DLSS_OK) return query;
    if (!caps.available) return fail(VKB_DLSS_ERR_UNSUPPORTED, "[DLSS-NR] feature unavailable on the active NGX device");

    Feature* feature = registerFeature(state, outFeature);
    feature->kind = FeatureKind::neural_rendering;
    feature->width = info->width;
    feature->height = info->height;
    auto result = NVSDK_NGX_VULKAN_AllocateParameters(&feature->params);
    if (NVSDK_NGX_FAILED(result)) return failNgx("[DLSS-NR] NVSDK_NGX_VULKAN_AllocateParameters", result);
    if (!feature->params) return fail(VKB_DLSS_ERR_NGX, "[DLSS-NR] parameter allocation returned null");
    // RTX Remix uses B, the lightweight network. Appearance model is selected independently at evaluation.
    NVSDK_NGX_Parameter_SetI(feature->params, NVSDK_NGX_Parameter_DLSSNR_Hint_Render_Preset,
        NVSDK_NGX_DLSSNR_Hint_Render_Preset_B);
    NVSDK_NGX_DLSSNR_Create_Params params{info->width, info->height};
    result = NGX_VULKAN_CREATE_DLSSNR_EXT1(state.device, commandBuffer(cmd), 1, 1,
        &feature->handle, feature->params, &params);
    if (NVSDK_NGX_FAILED(result)) return failNgx("[DLSS-NR] NGX_VULKAN_CREATE_DLSSNR_EXT1", result);
    if (!feature->handle) return fail(VKB_DLSS_ERR_NGX, "[DLSS-NR] feature creation returned null");
    feature->usable = true;
    clearError();
    return VKB_DLSS_OK;
} catch (...) { return unexpectedFailure(); }

VKB_DLSS_API int32_t vkb_dlss_nr_evaluate(uint64_t cmd, uint64_t owner, const VkbDlssNrEvalInfo* info) try {
    if (!cmd || !owner || !info || info->struct_size != sizeof(*info) || info->reserved != 0)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] invalid evaluation descriptor");
    const bool hasMask = info->control_mask.image || info->control_mask.view;
    if (!validImage(info->color) || !validImage(info->output) || !validImage(info->depth) ||
        !validImage(info->motion_vectors) || (hasMask && !validImage(info->control_mask)) || !info->output.read_write ||
        !validColorInput(info->color.format) || !validColorOutput(info->output.format) ||
        info->output.image == info->color.image || info->output.image == info->motion_vectors.image ||
        info->output.image == info->depth.image || info->output.image == info->control_mask.image)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] invalid resources or aliased writable output");
    if (!std::isfinite(info->jitter_x) || !std::isfinite(info->jitter_y) ||
        !std::isfinite(info->mv_scale_x) || !std::isfinite(info->mv_scale_y) ||
        !validStrength(info->intensity) || !validStrength(info->tone_strength) ||
        !validStrength(info->structure_strength) || !validStrength(info->skin_structure_strength) ||
        info->model > 2 || info->use_auto_mask > 1 || info->depth_inverted > 1 || info->reset > 1)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] invalid motion scales, jitter, flags or model controls");

    State& state = st();
    std::lock_guard<std::recursive_mutex> lock(state.mutex);
    if (!state.initialised || state.shuttingDown)
        return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "[DLSS-NR] NGX unavailable or retiring");
    Feature* feature = findFeature(state, owner);
    if (!feature || feature->kind != FeatureKind::neural_rendering || !feature->usable)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] unknown or release-only feature");
    if (!displayExtent(info->color, *feature) || !displayExtent(info->output, *feature) ||
        (hasMask && !displayExtent(info->control_mask, *feature)))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] input, output and control mask must match the feature extent");

    auto color = toResource(info->color);
    auto output = toResource(info->output);
    auto depth = toResource(info->depth);
    auto motion = toResource(info->motion_vectors);
    NVSDK_NGX_Resource_VK mask{};
    if (hasMask) mask = toResource(info->control_mask);
    NVSDK_NGX_VK_DLSSNR_Eval_Params params{};
    params.pInColor = &color;
    params.pInOutput = &output;
    params.pInDepth = &depth;
    params.pInMVec = &motion;
    params.pInControlMask = hasMask ? &mask : nullptr;
    params.InEnabled = 1;
    params.InReset = static_cast<int>(info->reset);
    params.InDepthInverted = static_cast<int>(info->depth_inverted);
    params.InStyle = info->model;
    params.InUseAutoMask = static_cast<int>(info->use_auto_mask);
    params.InMaskBlendMode = info->use_auto_mask && hasMask
        ? NVSDK_NGX_DLSSNR_Mask_Blend_Mode_Min : NVSDK_NGX_DLSSNR_Mask_Blend_Mode_None;
    params.InIntensity = info->intensity;
    params.InLocalToneStrength = info->tone_strength;
    params.InLocalStructureStrength = info->structure_strength;
    params.InSkinStructureStrength = info->use_auto_mask ? info->skin_structure_strength : 0.0f;
    params.InMVecScaleX = info->mv_scale_x;
    params.InMVecScaleY = info->mv_scale_y;
    params.InColorSubrectSize = {info->color.width, info->color.height};
    params.InOutputSubrectSize = {info->output.width, info->output.height};
    params.InDepthSubrectSize = {info->depth.width, info->depth.height};
    params.InMVecSubrectSize = {info->motion_vectors.width, info->motion_vectors.height};
    if (hasMask) params.InControlMaskSubrectSize = {info->control_mask.width, info->control_mask.height};
    NVSDK_NGX_Parameter_SetF(feature->params, NVSDK_NGX_Parameter_Jitter_Offset_X, info->jitter_x);
    NVSDK_NGX_Parameter_SetF(feature->params, NVSDK_NGX_Parameter_Jitter_Offset_Y, info->jitter_y);
    // An SDK evaluation can fail after recording work. Retain ownership and prohibit further evaluation.
    feature->usable = false;
    const auto result = NGX_VULKAN_EVALUATE_DLSSNR_EXT(commandBuffer(cmd), feature->handle, feature->params, &params);
    if (NVSDK_NGX_FAILED(result)) return failNgx("[DLSS-NR] NGX_VULKAN_EVALUATE_DLSSNR_EXT", result);
    feature->usable = true;
    clearError();
    return VKB_DLSS_OK;
} catch (...) { return unexpectedFailure(); }

VKB_DLSS_API int32_t vkb_dlss_nr_release_feature(uint64_t owner) try {
    State& state = st();
    std::lock_guard<std::recursive_mutex> lock(state.mutex);
    Feature* feature = findFeature(state, owner);
    if (!feature || feature->kind != FeatureKind::neural_rendering)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-NR] unknown feature");
    return vkb_dlss_release_feature(owner);
} catch (...) { return unexpectedFailure(); }

#else

namespace {

int32_t unavailable() noexcept {
    try { return fail(VKB_DLSS_ERR_UNSUPPORTED, "[DLSS-NR] wrapper built without the NVIDIA Vulkan DLSS-NR headers"); }
    catch (...) { return VKB_DLSS_ERR_UNSUPPORTED; }
}

int32_t unavailableExtensions(uint32_t* outCount, const char* const** outNames) {
    if (outCount) *outCount = 0;
    if (outNames) *outNames = nullptr;
    return unavailable();
}

} // namespace

VKB_DLSS_API int32_t vkb_dlss_nr_get_required_instance_extensions(const wchar_t*, const wchar_t*,
    uint32_t* outCount, const char* const** outNames) {
    return unavailableExtensions(outCount, outNames);
}

VKB_DLSS_API int32_t vkb_dlss_nr_get_required_device_extensions(uint64_t, uint64_t, const wchar_t*, const wchar_t*,
    uint32_t* outCount, const char* const** outNames) {
    return unavailableExtensions(outCount, outNames);
}

VKB_DLSS_API int32_t vkb_dlss_nr_query_caps(VkbDlssNrCaps* outCaps) {
    if (outCaps && outCaps->struct_size == sizeof(*outCaps)) {
        *outCaps = {};
        outCaps->struct_size = sizeof(*outCaps);
    }
    return unavailable();
}

VKB_DLSS_API int32_t vkb_dlss_nr_create_feature(uint64_t, const VkbDlssNrCreateInfo*, uint64_t* outFeature) {
    if (outFeature) *outFeature = 0;
    return unavailable();
}

VKB_DLSS_API int32_t vkb_dlss_nr_evaluate(uint64_t, uint64_t, const VkbDlssNrEvalInfo*) { return unavailable(); }
VKB_DLSS_API int32_t vkb_dlss_nr_release_feature(uint64_t) { return unavailable(); }

#endif
