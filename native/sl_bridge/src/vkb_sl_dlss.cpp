/*
 * vkb_sl_dlss.cpp - the vkb_dlss.h API (DLSS Super Resolution + Ray Reconstruction) implemented on top of the Streamline plugins
 * sl.dlss and sl.dlss_d instead of raw NGX.
 *
 * Why: when Streamline is the Vulkan library of the process (DLSS Frame Generation), NGX is a process-wide singleton that
 * Streamline has initialised for ITS features. A second NGX_VULKAN_Init from our own vkb_dlss.dll joins that session and the
 * capability query answers "SuperSampling.Available=0, FeatureInitResult=0xBAD00004" (log of the NGX project id 141959980 of
 * sl.dlss_g inside our nvngx.log). Streamline's own DLSS/DLSS-RR plugins are the supported way to use all three features
 * together (ProgrammingGuideDLSS.md, ProgrammingGuideDLSS_RR.md, ProgrammingGuideDLSS_G.md "DLSS-G is an extension of the same
 * SL session").
 *
 * The exports have exactly the names and structs of native/dlss_bridge/include/vkb_dlss.h so the Java side (DlssNative,
 * DlssRayReconstruction) only needs another library path. Single viewport 0 for every feature (constants, tags and options of
 * one frame must share the viewport, ProgrammingGuideDLSS_RR.md "frameToken and myViewport must match across all API calls").
 * Recording: slSetTagForFrame + slEvaluateFeature on the caller's VkCommandBuffer (ProgrammingGuideDLSS.md section "Evaluate").
 */
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

#include "sl.h"
#include "sl_consts.h"
#include "sl_dlss.h"
#include "sl_dlss_d.h"

#define VKB_DLSS_BUILD 1
#include "../../dlss_bridge/include/vkb_dlss.h"

// shared with vkb_sl.cpp
extern sl::FrameToken* g_token;
extern bool g_inited;
extern PFun_slSetTagForFrame* p_slSetTagForFrame;
extern PFun_slGetFeatureFunction* p_slGetFeatureFunction;
extern PFun_slIsFeatureSupported* p_slIsFeatureSupported;
extern PFun_slIsFeatureLoaded* p_slIsFeatureLoaded;
extern PFun_slEvaluateFeature* p_slEvaluateFeature;
extern PFun_slFreeResources* p_slFreeResources;

namespace {
thread_local std::string t_err;
uint8_t g_luid[8];
bool g_luidValid = false;

PFun_slDLSSGetOptimalSettings* p_getOptimal = nullptr;
PFun_slDLSSSetOptions* p_srSetOptions = nullptr;
PFun_slDLSSDSetOptions* p_rrSetOptions = nullptr;
bool g_resolved = false;
uint32_t g_rrDepthLinear = 1;

sl::Result fail(const char* what, sl::Result r) {
    t_err = std::string(what) + " returned sl::Result " + std::to_string(static_cast<int>(r)) + " (sl_result.h)";
    return r;
}

bool resolve() {
    if (g_resolved) return true;
    if (!g_inited || !p_slGetFeatureFunction) return false;
    void* p = nullptr;
    if (p_slGetFeatureFunction(sl::kFeatureDLSS, "slDLSSGetOptimalSettings", p) == sl::Result::eOk) p_getOptimal = reinterpret_cast<PFun_slDLSSGetOptimalSettings*>(p);
    p = nullptr;
    if (p_slGetFeatureFunction(sl::kFeatureDLSS, "slDLSSSetOptions", p) == sl::Result::eOk) p_srSetOptions = reinterpret_cast<PFun_slDLSSSetOptions*>(p);
    p = nullptr;
    if (p_slGetFeatureFunction(sl::kFeatureDLSS_RR, "slDLSSDSetOptions", p) == sl::Result::eOk) p_rrSetOptions = reinterpret_cast<PFun_slDLSSDSetOptions*>(p);
    g_resolved = p_getOptimal && p_srSetOptions;
    return g_resolved;
}

sl::DLSSMode toSlMode(int32_t m) {
    switch (m) {
        case VKB_DLSS_MODE_ULTRA_PERFORMANCE: return sl::DLSSMode::eUltraPerformance;
        case VKB_DLSS_MODE_MAX_PERFORMANCE: return sl::DLSSMode::eMaxPerformance;
        case VKB_DLSS_MODE_BALANCED: return sl::DLSSMode::eBalanced;
        case VKB_DLSS_MODE_MAX_QUALITY: return sl::DLSSMode::eMaxQuality;
        case VKB_DLSS_MODE_ULTRA_QUALITY: return sl::DLSSMode::eUltraQuality;
        default: return sl::DLSSMode::eDLAA;
    }
}

struct SrState { sl::DLSSOptions opts; bool valid = false; } g_sr;
struct RrState { sl::DLSSDOptions opts; bool valid = false; } g_rr;

uint32_t usageFor(const VkbDlssImage& i) {
    uint32_t u = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (i.read_write) u |= VK_IMAGE_USAGE_STORAGE_BIT;
    if (i.aspect_mask & VK_IMAGE_ASPECT_DEPTH_BIT) u |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
    else u |= VK_IMAGE_USAGE_STORAGE_BIT;
    return u;
}

struct TagBuilder {
    std::vector<sl::Resource> res;
    std::vector<sl::ResourceTag> tags;
    std::vector<sl::Extent> ext;
    TagBuilder() { res.reserve(16); tags.reserve(16); ext.reserve(16); }
    void add(const VkbDlssImage& i, sl::BufferType type, uint32_t layout) {
        if (!i.image || !i.view) return;
        sl::Resource r(sl::ResourceType::eTex2d, reinterpret_cast<void*>(i.image), nullptr, reinterpret_cast<void*>(i.view), layout);
        r.width = i.width; r.height = i.height; r.nativeFormat = i.format; r.mipLevels = 1; r.arrayLayers = 1; r.flags = 0;
        r.usage = usageFor(i);
        res.push_back(r);
        ext.push_back(sl::Extent{0, 0, i.width, i.height});
    }
    // tags must be built after all resources are pushed (vectors reallocate otherwise)
    void finish(const std::vector<sl::BufferType>& types) {
        for (size_t k = 0; k < res.size(); ++k)
            tags.emplace_back(&res[k], types[k], sl::ResourceLifecycle::eValidUntilEvaluate, &ext[k]);
    }
};
} // namespace

#define API extern "C" __declspec(dllexport)

// Exception containment (ADR-0005 rule 4): exports reached from Java through an FFM downcall must not
// let a C++ exception escape (it becomes an unhandled native exception in the JVM and kills the process).
// Records the message in this file's thread-local t_err (the vkb_dlss.h error string) and returns the
// generic VKB_DLSS_ERR_UNSUPPORTED; the code numbering is unchanged, matching the vkb_nrd.cpp reference.
#define SLDL_TRY try {
#define SLDL_CATCH()                                                                            \
    }                                                                                           \
    catch (const std::exception& e_) {                                                          \
        t_err = std::string("unexpected C++ exception: ") + e_.what();                           \
        return VKB_DLSS_ERR_UNSUPPORTED;                                                        \
    }                                                                                           \
    catch (...) {                                                                               \
        t_err = "Unknown unhandled C++ exception";                                              \
        return VKB_DLSS_ERR_UNSUPPORTED;                                                        \
    }

API uint32_t vkb_dlss_get_api_version(void) { return VKB_DLSS_API_VERSION; }
API uint32_t vkb_dlss_get_ngx_api_version(void) { return 0; }
API const char* vkb_dlss_get_last_error_string(void) { return t_err.c_str(); }
API uint32_t vkb_dlss_get_last_ngx_result(void) { return 0; }
API const char* vkb_dlss_result_to_string(int32_t r) {
    switch (r) {
        case VKB_DLSS_OK: return "OK";
        case VKB_DLSS_ERR_INVALID_ARG: return "invalid argument";
        case VKB_DLSS_ERR_NOT_INITIALIZED: return "not initialised";
        case VKB_DLSS_ERR_NGX: return "Streamline/NGX error (see last error string)";
        case VKB_DLSS_ERR_UNSUPPORTED: return "unsupported";
        default: return "error";
    }
}
API void vkb_dlss_set_log_callback(VkbDlssLogCallback) {}

// SL adds the instance/device extensions through its vkCreateInstance/vkCreateDevice proxies: nothing for the host to enable.
API int32_t vkb_dlss_get_required_instance_extensions(const wchar_t*, const wchar_t*, uint32_t* outCount, const char* const** outNames) {
    *outCount = 0; *outNames = nullptr; return VKB_DLSS_OK;
}
API int32_t vkb_dlss_get_required_device_extensions(uint64_t, uint64_t, const wchar_t*, const wchar_t*, uint32_t* outCount, const char* const** outNames) {
    *outCount = 0; *outNames = nullptr; return VKB_DLSS_OK;
}

static void fillCaps(VkbDlssCaps* c) {
    std::memset(c, 0, sizeof(*c));
    c->struct_size = sizeof(VkbDlssCaps);
    bool loaded = false;
    if (g_inited && p_slIsFeatureLoaded) p_slIsFeatureLoaded(sl::kFeatureDLSS, loaded);
    bool ok = false;
    if (loaded && g_luidValid && p_slIsFeatureSupported) {
        uint8_t luid[8]; std::memcpy(luid, g_luid, 8);
        sl::AdapterInfo ai{}; ai.deviceLUID = luid; ai.deviceLUIDSizeInBytes = 8;
        sl::Result r = p_slIsFeatureSupported(sl::kFeatureDLSS, ai);
        ok = r == sl::Result::eOk;
        if (!ok) t_err = "slIsFeatureSupported(DLSS) returned " + std::to_string(static_cast<int>(r));
        if (r == sl::Result::eErrorDriverOutOfDate) c->needs_updated_driver = 1;
    } else if (!loaded) {
        t_err = "sl.dlss plugin is not loaded (copy sl.dlss.dll and nvngx_dlss.dll next to sl.interposer.dll)";
    }
    c->super_sampling_available = ok ? 1 : 0;
}

API int32_t vkb_dlss_init(uint64_t instance, uint64_t physicalDevice, uint64_t device, void* gipa, void*, const wchar_t*, const wchar_t*,
                          const char*, const char*, VkbDlssCaps* outCaps) {
    SLDL_TRY
    (void)device;
    if (!g_inited) { t_err = "Streamline is not initialised"; return VKB_DLSS_ERR_NOT_INITIALIZED; }
    if (!g_luidValid && gipa && instance && physicalDevice) {
        auto getProps2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa)(reinterpret_cast<VkInstance>(instance), "vkGetPhysicalDeviceProperties2"));
        if (getProps2) {
            VkPhysicalDeviceIDProperties id{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
            VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &id};
            getProps2(reinterpret_cast<VkPhysicalDevice>(physicalDevice), &p2);
            if (id.deviceLUIDValid) { std::memcpy(g_luid, id.deviceLUID, 8); g_luidValid = true; }
        }
    }
    resolve();
    if (outCaps) fillCaps(outCaps);
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API int32_t vkb_dlss_query_caps(VkbDlssCaps* outCaps) {
    SLDL_TRY
    if (!outCaps) return VKB_DLSS_ERR_INVALID_ARG;
    fillCaps(outCaps);
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API void vkb_dlss_shutdown(void) { g_sr.valid = false; g_rr.valid = false; }

API int32_t vkb_dlss_query_optimal_settings(int32_t qualityMode, uint32_t outWidth, uint32_t outHeight, VkbDlssOptimalSettings* out) {
    SLDL_TRY
    if (!out) return VKB_DLSS_ERR_INVALID_ARG;
    if (!resolve()) { t_err = "sl.dlss functions not available"; return VKB_DLSS_ERR_NOT_INITIALIZED; }
    sl::DLSSOptions o{};
    o.mode = toSlMode(qualityMode);
    o.outputWidth = outWidth;
    o.outputHeight = outHeight;
    sl::DLSSOptimalSettings s{};
    sl::Result r = p_getOptimal(o, s);
    if (r != sl::Result::eOk) { fail("slDLSSGetOptimalSettings", r); return VKB_DLSS_ERR_NGX; }
    std::memset(out, 0, sizeof(*out));
    out->struct_size = sizeof(*out);
    out->render_width = s.optimalRenderWidth; out->render_height = s.optimalRenderHeight;
    out->min_width = s.renderWidthMin; out->min_height = s.renderHeightMin;
    out->max_width = s.renderWidthMax; out->max_height = s.renderHeightMax;
    out->sharpness = s.optimalSharpness;
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

// ---- Super Resolution ------------------------------------------------------------------------------------------------------

API int32_t vkb_dlss_create_feature(uint64_t, const VkbDlssCreateInfo* info, uint64_t* outFeature) {
    SLDL_TRY
    if (!info || !outFeature) return VKB_DLSS_ERR_INVALID_ARG;
    if (!resolve()) { t_err = "sl.dlss functions not available"; return VKB_DLSS_ERR_NOT_INITIALIZED; }
    sl::DLSSOptions o{};
    o.mode = toSlMode(info->quality_mode);
    o.outputWidth = info->output_width;
    o.outputHeight = info->output_height;
    o.colorBuffersHDR = (info->flags & VKB_DLSS_FLAG_IS_HDR) ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    o.useAutoExposure = (info->flags & VKB_DLSS_FLAG_AUTO_EXPOSURE) ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    sl::Result r = p_srSetOptions(sl::ViewportHandle(0u), o);
    if (r != sl::Result::eOk) { fail("slDLSSSetOptions", r); return VKB_DLSS_ERR_NGX; }
    g_sr.opts = o; g_sr.valid = true;
    *outFeature = 1;
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API int32_t vkb_dlss_evaluate(uint64_t cmd, uint64_t feature, const VkbDlssEvalInfo* info) {
    SLDL_TRY
    if (!info || feature != 1 || !g_sr.valid) return VKB_DLSS_ERR_INVALID_ARG;
    if (!g_token) { t_err = "no frame token (frame head hook did not run)"; return VKB_DLSS_ERR_NOT_INITIALIZED; }
    TagBuilder tb;
    std::vector<sl::BufferType> types;
    // The Java side (DlssBackend.dispatch) transitions colour, depth and motion vectors to SHADER_READ_ONLY_OPTIMAL around the call and keeps
    // the output in GENERAL; the tagged state must be the state at evaluate time (ProgrammingGuide.md "resource state").
    const uint32_t GENERAL = VK_IMAGE_LAYOUT_GENERAL;
    const uint32_t SRO = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    auto add = [&](const VkbDlssImage& i, sl::BufferType t, uint32_t layout) { size_t n = tb.res.size(); tb.add(i, t, layout); if (tb.res.size() > n) types.push_back(t); };
    add(info->color, sl::kBufferTypeScalingInputColor, SRO);
    add(info->output, sl::kBufferTypeScalingOutputColor, GENERAL);
    add(info->depth, sl::kBufferTypeDepth, SRO);
    add(info->motion_vectors, sl::kBufferTypeMotionVectors, SRO);
    add(info->exposure, sl::kBufferTypeExposure, SRO);
    tb.finish(types);
    sl::ViewportHandle vp(0u);
    sl::Result r = p_slSetTagForFrame(*g_token, vp, tb.tags.data(), static_cast<uint32_t>(tb.tags.size()), reinterpret_cast<sl::CommandBuffer*>(cmd));
    if (r != sl::Result::eOk) { fail("slSetTagForFrame(DLSS)", r); return VKB_DLSS_ERR_NGX; }
    const sl::BaseStructure* inputs[] = {&vp};
    r = p_slEvaluateFeature(sl::kFeatureDLSS, *g_token, inputs, 1, reinterpret_cast<sl::CommandBuffer*>(cmd));
    if (r != sl::Result::eOk) { fail("slEvaluateFeature(DLSS)", r); return VKB_DLSS_ERR_NGX; }
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API int32_t vkb_dlss_release_feature(uint64_t feature) {
    SLDL_TRY
    if (feature != 1) return VKB_DLSS_ERR_INVALID_ARG;
    g_sr.valid = false;
    if (g_inited && p_slFreeResources) p_slFreeResources(sl::kFeatureDLSS, sl::ViewportHandle(0u));
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

// ---- Ray Reconstruction ----------------------------------------------------------------------------------------------------

API int32_t vkb_dlss_rr_is_supported(uint32_t* outSupported) {
    SLDL_TRY
    if (!outSupported) return VKB_DLSS_ERR_INVALID_ARG;
    bool loaded = false;
    if (g_inited && p_slIsFeatureLoaded) p_slIsFeatureLoaded(sl::kFeatureDLSS_RR, loaded);
    bool ok = false;
    if (loaded && g_luidValid && p_slIsFeatureSupported) {
        uint8_t luid[8]; std::memcpy(luid, g_luid, 8);
        sl::AdapterInfo ai{}; ai.deviceLUID = luid; ai.deviceLUIDSizeInBytes = 8;
        ok = p_slIsFeatureSupported(sl::kFeatureDLSS_RR, ai) == sl::Result::eOk;
    }
    *outSupported = ok ? 1 : 0;
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API int32_t vkb_dlss_rr_create_feature(uint64_t, const VkbDlssRrCreateInfo* info, uint64_t* outFeature) {
    SLDL_TRY
    if (!info || !outFeature) return VKB_DLSS_ERR_INVALID_ARG;
    resolve();
    if (!p_rrSetOptions) { t_err = "sl.dlss_d functions not available (sl.dlss_d.dll / nvngx_dlssd.dll missing?)"; return VKB_DLSS_ERR_NOT_INITIALIZED; }
    sl::DLSSDOptions o{};
    o.mode = toSlMode(info->quality_mode);
    o.outputWidth = info->output_width;
    o.outputHeight = info->output_height;
    o.colorBuffersHDR = (info->flags & VKB_DLSS_FLAG_IS_HDR) ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    o.normalRoughnessMode = info->roughness_mode == 1 ? sl::DLSSDNormalRoughnessMode::ePacked : sl::DLSSDNormalRoughnessMode::eUnpacked;
    for (int r = 0; r < 4; ++r) {
        o.worldToCameraView.row[r] = sl::float4(r == 0, r == 1, r == 2, r == 3);
        o.cameraViewToWorld.row[r] = sl::float4(r == 0, r == 1, r == 2, r == 3);
    }
    sl::Result r = p_rrSetOptions(sl::ViewportHandle(0u), o);
    if (r != sl::Result::eOk) { fail("slDLSSDSetOptions", r); return VKB_DLSS_ERR_NGX; }
    g_rr.opts = o; g_rr.valid = true;
    // the depth convention of the create info decides the tag type at evaluate time: remember it in the (unused) preset field? keep a global
    g_rrDepthLinear = info->depth_type == 0 ? 1 : 0;
    *outFeature = 2;
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API int32_t vkb_dlss_rr_evaluate(uint64_t cmd, uint64_t feature, const VkbDlssRrEvalInfo* info) {
    SLDL_TRY
    if (!info || feature != 2 || !g_rr.valid) return VKB_DLSS_ERR_INVALID_ARG;
    if (!g_token) { t_err = "no frame token (frame head hook did not run)"; return VKB_DLSS_ERR_NOT_INITIALIZED; }
    // matrices change every frame (ProgrammingGuideDLSS_RR.md: DLSSDOptions::worldToCameraView / cameraViewToWorld)
    sl::DLSSDOptions o = g_rr.opts;
    if (info->has_matrices) {
        // The Java side hands JOML column-major arrays; as row-major arrays they are the row-vector (transposed) forms SL expects.
        for (int r = 0; r < 4; ++r)
            o.worldToCameraView.row[r] = sl::float4(info->world_to_view_matrix[r * 4 + 0], info->world_to_view_matrix[r * 4 + 1],
                                                    info->world_to_view_matrix[r * 4 + 2], info->world_to_view_matrix[r * 4 + 3]);
        // inverse of a rigid view matrix is cheap but generic inverse is safer: compute via cofactor on the row-major copy
        float m[16];
        for (int k = 0; k < 16; ++k) m[k] = info->world_to_view_matrix[k];
        float inv[16];
        inv[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        inv[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        inv[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        inv[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
        inv[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        inv[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        inv[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        inv[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
        inv[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6];
        inv[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6];
        inv[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5];
        inv[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5];
        inv[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6];
        inv[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6];
        inv[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] - m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5];
        inv[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] + m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5];
        float det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12];
        if (det != 0.0f) {
            float id = 1.0f / det;
            for (int r = 0; r < 4; ++r) o.cameraViewToWorld.row[r] = sl::float4(inv[r*4+0]*id, inv[r*4+1]*id, inv[r*4+2]*id, inv[r*4+3]*id);
        }
    }
    o.preExposure = info->pre_exposure > 0.0f ? info->pre_exposure : 1.0f;
    o.exposureScale = info->exposure_scale > 0.0f ? info->exposure_scale : 1.0f;
    sl::Result r = p_rrSetOptions(sl::ViewportHandle(0u), o);
    if (r != sl::Result::eOk) { fail("slDLSSDSetOptions(per frame)", r); return VKB_DLSS_ERR_NGX; }

    TagBuilder tb;
    std::vector<sl::BufferType> types;
    const uint32_t SRO = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    const uint32_t GENERAL = VK_IMAGE_LAYOUT_GENERAL;
    auto add = [&](const VkbDlssImage& i, sl::BufferType t, uint32_t layout) { size_t n = tb.res.size(); tb.add(i, t, layout); if (tb.res.size() > n) types.push_back(t); };
    add(info->color, sl::kBufferTypeScalingInputColor, SRO);
    add(info->output, sl::kBufferTypeScalingOutputColor, GENERAL);
    add(info->depth, g_rrDepthLinear ? sl::kBufferTypeLinearDepth : sl::kBufferTypeDepth, SRO);
    add(info->motion_vectors, sl::kBufferTypeMotionVectors, SRO);
    add(info->exposure, sl::kBufferTypeExposure, SRO);
    add(info->diffuse_albedo, sl::kBufferTypeAlbedo, SRO);
    add(info->specular_albedo, sl::kBufferTypeSpecularAlbedo, SRO);
    if (g_rr.opts.normalRoughnessMode == sl::DLSSDNormalRoughnessMode::ePacked) {
        add(info->normals, sl::kBufferTypeNormalRoughness, SRO);
    } else {
        add(info->normals, sl::kBufferTypeNormals, SRO);
        add(info->roughness, sl::kBufferTypeRoughness, SRO);
    }
    add(info->specular_hit_distance, sl::kBufferTypeSpecularHitDistance, SRO);
    add(info->diffuse_hit_distance, sl::kBufferTypeDiffuseHitDistance, SRO);
    add(info->ray_tracing_hit_distance, sl::kBufferTypeRaytracingDistance, SRO);
    add(info->disocclusion_mask, sl::kBufferTypeDisocclusionMask, SRO);
    tb.finish(types);
    sl::ViewportHandle vp(0u);
    r = p_slSetTagForFrame(*g_token, vp, tb.tags.data(), static_cast<uint32_t>(tb.tags.size()), reinterpret_cast<sl::CommandBuffer*>(cmd));
    if (r != sl::Result::eOk) { fail("slSetTagForFrame(DLSS-RR)", r); return VKB_DLSS_ERR_NGX; }
    const sl::BaseStructure* inputs[] = {&vp};
    r = p_slEvaluateFeature(sl::kFeatureDLSS_RR, *g_token, inputs, 1, reinterpret_cast<sl::CommandBuffer*>(cmd));
    if (r != sl::Result::eOk) { fail("slEvaluateFeature(DLSS-RR)", r); return VKB_DLSS_ERR_NGX; }
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API int32_t vkb_dlss_rr_release_feature(uint64_t feature) {
    SLDL_TRY
    if (feature != 2) return VKB_DLSS_ERR_INVALID_ARG;
    g_rr.valid = false;
    if (g_inited && p_slFreeResources) p_slFreeResources(sl::kFeatureDLSS_RR, sl::ViewportHandle(0u));
    return VKB_DLSS_OK;
    SLDL_CATCH()
}

API int32_t vkb_dlss_fg_is_supported(uint32_t* outSupported) {
    SLDL_TRY
    if (!outSupported) return VKB_DLSS_ERR_INVALID_ARG;
    bool loaded = false;
    if (g_inited && p_slIsFeatureLoaded) p_slIsFeatureLoaded(sl::kFeatureDLSS_G, loaded);
    *outSupported = loaded ? 1 : 0;
    return VKB_DLSS_OK;
    SLDL_CATCH()
}
