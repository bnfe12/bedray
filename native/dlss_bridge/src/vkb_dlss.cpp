/*
 * vkb_dlss.cpp - implementation of the flat C DLSS wrapper on top of the NVIDIA NGX SDK (Vulkan).
 * Build: native/dlss_bridge/build.bat. Links nvsdk_ngx_s.lib (static CRT, /MT).
 *
 * NGX API usage verified against the NVIDIA DLSS SDK headers
 *   nvsdk_ngx_vk.h (Init_with_ProjectID, GetFeatureInstance/DeviceExtensionRequirements,
 *   GetCapabilityParameters, AllocateParameters, ReleaseFeature, Shutdown1),
 *   nvsdk_ngx_helpers.h (NGX_DLSS_GET_OPTIMAL_SETTINGS),
 *   nvsdk_ngx_helpers_vk.h (NGX_VULKAN_CREATE_DLSS_EXT1, NGX_VULKAN_EVALUATE_DLSS_EXT).
 */
#define VKB_DLSS_BUILD
#define VK_NO_PROTOTYPES
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan.h>

#include "nvsdk_ngx.h"
#include "nvsdk_ngx_defs.h"
#include "nvsdk_ngx_params.h"
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_defs_dlssd.h"
#include "nvsdk_ngx_params_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

#include "vkb_dlss.h"

#include <cstdio>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

static_assert(sizeof(VkbDlssImage) == 56, "VkbDlssImage layout changed: update Java FFM layout");
static_assert(sizeof(VkbDlssEvalInfo) == 344, "VkbDlssEvalInfo layout changed: update Java FFM layout");
static_assert(sizeof(VkbDlssCreateInfo) == 32, "VkbDlssCreateInfo layout changed: update Java FFM layout");
static_assert(sizeof(VkbDlssCaps) == 32, "VkbDlssCaps layout changed: update Java FFM layout");
static_assert(sizeof(VkbDlssOptimalSettings) == 36, "VkbDlssOptimalSettings layout changed: update Java FFM layout");
static_assert(sizeof(VkbDlssRrCreateInfo) == 48, "VkbDlssRrCreateInfo layout changed: update Java FFM layout");
static_assert(sizeof(VkbDlssRrEvalInfo) == 968, "VkbDlssRrEvalInfo layout changed: update Java FFM layout");

namespace {

// Default project id for this mod (custom engine, no NVIDIA-issued application id).
const char* const kDefaultProjectId = "6f1d2c3e-8a4b-4c5d-9e7f-5b0a1c2d3e4f";
const char* const kDefaultEngineVersion = "26.3";
const unsigned long long kDiscoveryApplicationId = 0x564B42ULL;  // "VKB": custom id, not issued by NVIDIA

struct Feature {
    NVSDK_NGX_Handle* handle = nullptr;
    NVSDK_NGX_Parameter* params = nullptr;
    bool is_rr = false;
    uint32_t roughness_mode = 0;
};

struct State {
    std::recursive_mutex mutex;
    bool initialised = false;
    VkDevice device = VK_NULL_HANDLE;
    NVSDK_NGX_Parameter* capParams = nullptr;
    // NGX may keep pointers into these after Init, so they live as long as the state.
    std::wstring featurePath;
    std::wstring dataPath;
    const wchar_t* pathList[4] = {nullptr};
    NVSDK_NGX_FeatureCommonInfo commonInfo{};
    std::string projectId;
    std::string engineVersion;
    std::set<Feature*> features;
    VkbDlssLogCallback logCb = nullptr;
    std::vector<std::string> instExtStore, devExtStore;
    std::vector<const char*> instExtPtrs, devExtPtrs;
};

State& st() {
    static State s;
    return s;
}

thread_local std::string g_lastError;
thread_local uint32_t g_lastNgx = 0;

std::string wideToUtf8(const wchar_t* w) {
    if (!w) return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return std::string();
    std::string out(static_cast<size_t>(n - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, out.data(), n, nullptr, nullptr);
    return out;
}

std::string ngxResultString(NVSDK_NGX_Result r) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "0x%08X", static_cast<unsigned>(r));
    std::string s = buf;
    const wchar_t* w = GetNGXResultAsString(r);
    if (w) {
        s += " (";
        s += wideToUtf8(w);
        s += ")";
    }
    return s;
}

int32_t fail(int32_t code, const std::string& msg) {
    g_lastError = msg;
    g_lastNgx = 0;
    return code;
}

int32_t failNgx(const char* what, NVSDK_NGX_Result r) {
    g_lastNgx = static_cast<uint32_t>(r);
    g_lastError = std::string(what) + " failed: " + ngxResultString(r);
    return VKB_DLSS_ERR_NGX;
}

void NVSDK_CONV ngxLogCallback(const char* message, NVSDK_NGX_Logging_Level level, NVSDK_NGX_Feature /*src*/) {
    VkbDlssLogCallback cb = st().logCb;
    if (cb && message) cb(message, static_cast<int32_t>(level));
}

bool toPerfQuality(int32_t mode, NVSDK_NGX_PerfQuality_Value& out) {
    switch (mode) {
        case VKB_DLSS_MODE_ULTRA_PERFORMANCE: out = NVSDK_NGX_PerfQuality_Value_UltraPerformance; return true;
        case VKB_DLSS_MODE_MAX_PERFORMANCE:   out = NVSDK_NGX_PerfQuality_Value_MaxPerf; return true;
        case VKB_DLSS_MODE_BALANCED:          out = NVSDK_NGX_PerfQuality_Value_Balanced; return true;
        case VKB_DLSS_MODE_MAX_QUALITY:       out = NVSDK_NGX_PerfQuality_Value_MaxQuality; return true;
        case VKB_DLSS_MODE_ULTRA_QUALITY:     out = NVSDK_NGX_PerfQuality_Value_UltraQuality; return true;
        case VKB_DLSS_MODE_DLAA:              out = NVSDK_NGX_PerfQuality_Value_DLAA; return true;
        default: return false;
    }
}

// Fills the discovery info. featureInfo/pathList storage is owned by the caller's locals.
void makeDiscovery(NVSDK_NGX_FeatureDiscoveryInfo& d, NVSDK_NGX_FeatureCommonInfo& ci,
                   const wchar_t** pathEntries, uint32_t& pathCount, const wchar_t* featurePath, const wchar_t* dataPath,
                   const std::string& projectId, const std::string& engineVersion) {
    std::memset(&ci, 0, sizeof(ci));
    ci.LoggingInfo.LoggingCallback = ngxLogCallback;
    ci.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_VERBOSE;
    pathCount = 0;
    if (featurePath && featurePath[0]) {
        pathEntries[pathCount++] = featurePath;
    }
    pathEntries[pathCount++] = L"C:\\ProgramData\\NVIDIA\\NGX\\models";
    pathEntries[pathCount++] = L"C:\\ProgramData\\NVIDIA\\NGX\\models\\dlss";
    ci.PathListInfo.Path = pathEntries;
    ci.PathListInfo.Length = pathCount;
    std::memset(&d, 0, sizeof(d));
    d.SDKVersion = NVSDK_NGX_Version_API;
    d.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    // TRAP: GetFeatureDeviceExtensionRequirements returns NVSDK_NGX_Result_FAIL_InvalidParameter (0xBAD00005)
    // when the discovery info uses Identifier_Type_Project_Id (verified with DLSS 310.9.0 / driver 617.14).
    // With Identifier_Type_Application_Id it succeeds. This is only a requirements query, so a custom
    // application id is fine; NVSDK_NGX_VULKAN_Init_with_ProjectID (real init) keeps using the project id.
    (void)projectId;
    (void)engineVersion;
    d.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Application_Id;
    d.Identifier.v.ApplicationId = kDiscoveryApplicationId;
    d.ApplicationDataPath = dataPath ? dataPath : (featurePath ? featurePath : L".");
    d.FeatureInfo = &ci;
}

int32_t copyExtensions(uint32_t count, VkExtensionProperties* props, std::vector<std::string>& store,
                       std::vector<const char*>& ptrs, uint32_t* outCount, const char* const** outNames) {
    store.clear();
    ptrs.clear();
    for (uint32_t i = 0; i < count; ++i) store.emplace_back(props[i].extensionName);
    for (auto& s : store) ptrs.push_back(s.c_str());
    *outCount = count;
    *outNames = ptrs.empty() ? nullptr : ptrs.data();
    return VKB_DLSS_OK;
}

VkImageSubresourceRange toRange(const VkbDlssImage& i) {
    VkImageSubresourceRange r{};
    r.aspectMask = i.aspect_mask ? i.aspect_mask : VK_IMAGE_ASPECT_COLOR_BIT;
    r.baseMipLevel = i.base_mip_level;
    r.levelCount = i.level_count ? i.level_count : 1;
    r.baseArrayLayer = i.base_array_layer;
    r.layerCount = i.layer_count ? i.layer_count : 1;
    return r;
}

NVSDK_NGX_Resource_VK toResource(const VkbDlssImage& i) {
    return NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(static_cast<uintptr_t>(i.view)),
        reinterpret_cast<VkImage>(static_cast<uintptr_t>(i.image)),
        toRange(i), static_cast<VkFormat>(i.format), i.width, i.height, i.read_write != 0);
}

}  // namespace

// Exception containment (ADR-0005 rule 4). These exports are reached through a java.lang.foreign (FFM)
// downcall: a C++ exception escaping an extern "C" function surfaces in the JVM as an unhandled native
// exception and terminates the Minecraft process. Every non-trivial export wraps its body with these.
// fail() already resets g_lastNgx and stores the message in the thread-local g_lastError; the fallback
// code is VKB_DLSS_ERR_UNSUPPORTED (this project has no dedicated internal/exception code and the
// numbering must not change), matching the vkb_nrd.cpp reference pattern.
#define API_TRY try {
#define API_CATCH()                                                                                     \
    }                                                                                                   \
    catch (const std::exception& e_) {                                                                  \
        return fail(VKB_DLSS_ERR_UNSUPPORTED, std::string("unexpected C++ exception: ") + e_.what());   \
    }                                                                                                   \
    catch (...) {                                                                                       \
        return fail(VKB_DLSS_ERR_UNSUPPORTED, "Unknown unhandled C++ exception");                       \
    }

#define API_CATCH_VOID()                                                                                \
    }                                                                                                   \
    catch (const std::exception& e_) {                                                                  \
        fail(VKB_DLSS_ERR_UNSUPPORTED, std::string("unexpected C++ exception: ") + e_.what());          \
    }                                                                                                   \
    catch (...) {                                                                                       \
        fail(VKB_DLSS_ERR_UNSUPPORTED, "Unknown unhandled C++ exception");                              \
    }

// ============================================================================================
// generic
// ============================================================================================
extern "C" {

VKB_DLSS_API uint32_t vkb_dlss_get_api_version(void) { return VKB_DLSS_API_VERSION; }
VKB_DLSS_API uint32_t vkb_dlss_get_ngx_api_version(void) { return static_cast<uint32_t>(NVSDK_NGX_Version_API); }
VKB_DLSS_API const char* vkb_dlss_get_last_error_string(void) { return g_lastError.c_str(); }
VKB_DLSS_API uint32_t vkb_dlss_get_last_ngx_result(void) { return g_lastNgx; }

VKB_DLSS_API const char* vkb_dlss_result_to_string(int32_t r) {
    switch (r) {
        case VKB_DLSS_OK: return "OK";
        case VKB_DLSS_ERR_INVALID_ARG: return "ERR_INVALID_ARG";
        case VKB_DLSS_ERR_NOT_INITIALIZED: return "ERR_NOT_INITIALIZED";
        case VKB_DLSS_ERR_ALREADY_INIT: return "ERR_ALREADY_INIT";
        case VKB_DLSS_ERR_NGX: return "ERR_NGX";
        case VKB_DLSS_ERR_UNSUPPORTED: return "ERR_UNSUPPORTED";
        case VKB_DLSS_ERR_BUFFER_TOO_SMALL: return "ERR_BUFFER_TOO_SMALL";
        case VKB_DLSS_NOT_IMPLEMENTED: return "NOT_IMPLEMENTED";
        default: return "UNKNOWN";
    }
}

VKB_DLSS_API void vkb_dlss_set_log_callback(VkbDlssLogCallback cb) {
    API_TRY
    std::lock_guard<std::recursive_mutex> lk(st().mutex);
    st().logCb = cb;
    API_CATCH_VOID()
}

// ============================================================================================
// required extensions
// ============================================================================================
VKB_DLSS_API int32_t vkb_dlss_get_required_instance_extensions(
    const wchar_t* featurePath, const wchar_t* dataPath, uint32_t* outCount, const char* const** outNames) {
    API_TRY
    if (!outCount || !outNames) return fail(VKB_DLSS_ERR_INVALID_ARG, "outCount/outNames is null");
    std::lock_guard<std::recursive_mutex> lk(st().mutex);
    NVSDK_NGX_FeatureDiscoveryInfo d;
    NVSDK_NGX_FeatureCommonInfo ci;
    const wchar_t* pathEntries[4] = {nullptr};
    uint32_t pathCount = 0;
    makeDiscovery(d, ci, pathEntries, pathCount, featurePath, dataPath, kDefaultProjectId, kDefaultEngineVersion);
    uint32_t count = 0;
    VkExtensionProperties* props = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&d, &count, &props);
    if (NVSDK_NGX_FAILED(r)) return failNgx("NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements", r);
    return copyExtensions(count, props, st().instExtStore, st().instExtPtrs, outCount, outNames);
    API_CATCH()
}

VKB_DLSS_API int32_t vkb_dlss_get_required_device_extensions(
    uint64_t instance, uint64_t physicalDevice, const wchar_t* featurePath, const wchar_t* dataPath,
    uint32_t* outCount, const char* const** outNames) {
    API_TRY
    if (!outCount || !outNames || !instance || !physicalDevice)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "null instance/physicalDevice/out pointer");
    std::lock_guard<std::recursive_mutex> lk(st().mutex);
    NVSDK_NGX_FeatureDiscoveryInfo d;
    NVSDK_NGX_FeatureCommonInfo ci;
    const wchar_t* pathEntries[4] = {nullptr};
    uint32_t pathCount = 0;
    makeDiscovery(d, ci, pathEntries, pathCount, featurePath, dataPath, kDefaultProjectId, kDefaultEngineVersion);
    uint32_t count = 0;
    VkExtensionProperties* props = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
        reinterpret_cast<VkInstance>(static_cast<uintptr_t>(instance)),
        reinterpret_cast<VkPhysicalDevice>(static_cast<uintptr_t>(physicalDevice)), &d, &count, &props);
    if (NVSDK_NGX_FAILED(r)) return failNgx("NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements", r);
    return copyExtensions(count, props, st().devExtStore, st().devExtPtrs, outCount, outNames);
    API_CATCH()
}

bool refreshCapabilityParameters(State& s) {
    NVSDK_NGX_Parameter* fresh = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&fresh);
    if (NVSDK_NGX_SUCCEED(r) && fresh) {
        if (s.capParams) {
            NVSDK_NGX_VULKAN_DestroyParameters(s.capParams);
        }
        s.capParams = fresh;
        return true;
    }
    return false;
}

// ============================================================================================
// init / capabilities / shutdown
// ============================================================================================
VKB_DLSS_API int32_t vkb_dlss_query_caps(VkbDlssCaps* outCaps) {
    API_TRY
    if (!outCaps || outCaps->struct_size != sizeof(VkbDlssCaps))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "null outCaps or wrong struct_size");
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised || !s.capParams) return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "vkb_dlss_init not done");

    int avail = 0, needsDrv = 0;
    unsigned minMaj = 0, minMin = 0, initRes = 0;
    NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDrv);
    NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, &minMaj);
    NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, &minMin);
    NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initRes);
    NVSDK_NGX_Result ra = NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_Available, &avail);
    if (NVSDK_NGX_FAILED(ra)) avail = 0;

    // If still not available, refresh capability parameters in case NGX service finished model loading
    if (!avail) {
        if (refreshCapabilityParameters(s)) {
            NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDrv);
            NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, &minMaj);
            NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, &minMin);
            NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initRes);
            ra = NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_Available, &avail);
            if (NVSDK_NGX_FAILED(ra)) avail = 0;
        }
    }

    outCaps->super_sampling_available = avail ? 1 : 0;
    outCaps->needs_updated_driver = needsDrv ? 1 : 0;
    outCaps->min_driver_major = minMaj;
    outCaps->min_driver_minor = minMin;
    outCaps->feature_init_result = initRes;
    outCaps->ngx_result_init = 1;

    if (needsDrv) {
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "DLSS SR not available: needs updated driver (MinDriver=%u.%u, FeatureInitResult=0x%08X)",
                      minMaj, minMin, initRes);
        g_lastNgx = initRes ? initRes : 0;
        g_lastError = buf;
        return VKB_DLSS_ERR_UNSUPPORTED;
    }

    g_lastError.clear();
    g_lastNgx = 0;
    return VKB_DLSS_OK;
    API_CATCH()
}

VKB_DLSS_API int32_t vkb_dlss_init(
    uint64_t instance, uint64_t physicalDevice, uint64_t device, void* gipa, void* gdpa,
    const wchar_t* featurePath, const wchar_t* logPath, const char* projectId, const char* engineVersion,
    VkbDlssCaps* outCaps) {
    API_TRY
    if (!instance || !physicalDevice || !device || !outCaps || outCaps->struct_size != sizeof(VkbDlssCaps))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "null handle/outCaps or wrong VkbDlssCaps.struct_size");
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (s.initialised) {
        VkDevice vkDev = reinterpret_cast<VkDevice>(static_cast<uintptr_t>(device));
        if (s.device == vkDev) {
            return vkb_dlss_query_caps(outCaps);
        }
        return fail(VKB_DLSS_ERR_ALREADY_INIT, "vkb_dlss_init called twice with different devices");
    }

    uint32_t sz = outCaps->struct_size;
    std::memset(outCaps, 0, sizeof(*outCaps));
    outCaps->struct_size = sz;

    s.featurePath = featurePath ? featurePath : L"";
    s.dataPath = logPath ? logPath : (featurePath ? featurePath : L".");
    s.projectId = (projectId && projectId[0]) ? projectId : kDefaultProjectId;
    s.engineVersion = (engineVersion && engineVersion[0]) ? engineVersion : kDefaultEngineVersion;
    std::memset(&s.commonInfo, 0, sizeof(s.commonInfo));
    uint32_t pathCount = 0;
    if (!s.featurePath.empty()) {
        s.pathList[pathCount++] = s.featurePath.c_str();
    }
    s.pathList[pathCount++] = L"C:\\ProgramData\\NVIDIA\\NGX\\models";
    s.pathList[pathCount++] = L"C:\\ProgramData\\NVIDIA\\NGX\\models\\dlss";
    s.commonInfo.PathListInfo.Path = s.pathList;
    s.commonInfo.PathListInfo.Length = pathCount;
    s.commonInfo.LoggingInfo.LoggingCallback = ngxLogCallback;
    s.commonInfo.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_ON;
    s.commonInfo.LoggingInfo.DisableOtherLoggingSinks = false;

    VkInstance vkInst = reinterpret_cast<VkInstance>(static_cast<uintptr_t>(instance));
    VkPhysicalDevice vkPd = reinterpret_cast<VkPhysicalDevice>(static_cast<uintptr_t>(physicalDevice));
    VkDevice vkDev = reinterpret_cast<VkDevice>(static_cast<uintptr_t>(device));

    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(
        s.projectId.c_str(), NVSDK_NGX_ENGINE_TYPE_CUSTOM, s.engineVersion.c_str(), s.dataPath.c_str(),
        vkInst, vkPd, vkDev, reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa),
        reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa), &s.commonInfo, NVSDK_NGX_Version_API);
    outCaps->ngx_result_init = static_cast<uint32_t>(r);
    if (NVSDK_NGX_FAILED(r)) return failNgx("NVSDK_NGX_VULKAN_Init_with_ProjectID", r);

    s.device = vkDev;
    s.initialised = true;

    r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&s.capParams);
    if (NVSDK_NGX_FAILED(r)) {
        int32_t rc = failNgx("NVSDK_NGX_VULKAN_GetCapabilityParameters", r);
        NVSDK_NGX_VULKAN_Shutdown1(s.device);
        s.initialised = false;
        s.device = VK_NULL_HANDLE;
        return rc;
    }

    int avail = 0, needsDrv = 0;
    unsigned minMaj = 0, minMin = 0, initRes = 0;
    NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDrv);
    NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, &minMaj);
    NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, &minMin);
    NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initRes);
    NVSDK_NGX_Result ra = NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSampling_Available, &avail);
    if (NVSDK_NGX_FAILED(ra)) avail = 0;  // parameter missing == not available yet
    outCaps->super_sampling_available = avail ? 1 : 0;
    outCaps->needs_updated_driver = needsDrv ? 1 : 0;
    outCaps->min_driver_major = minMaj;
    outCaps->min_driver_minor = minMin;
    outCaps->feature_init_result = initRes;

    if (needsDrv) {
        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "DLSS SR not available: needs updated driver (NeedsUpdatedDriver=%d, MinDriver=%u.%u, FeatureInitResult=0x%08X)",
                      needsDrv, minMaj, minMin, initRes);
        g_lastNgx = initRes ? initRes : static_cast<uint32_t>(ra);
        g_lastError = buf;
        return VKB_DLSS_ERR_UNSUPPORTED;
    }

    g_lastError.clear();
    g_lastNgx = 0;
    return VKB_DLSS_OK;
    API_CATCH()
}

VKB_DLSS_API void vkb_dlss_shutdown(void) {
    API_TRY
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised) return;
    for (Feature* f : s.features) {
        if (f->handle) NVSDK_NGX_VULKAN_ReleaseFeature(f->handle);
        if (f->params) NVSDK_NGX_VULKAN_DestroyParameters(f->params);
        delete f;
    }
    s.features.clear();
    if (s.capParams) {
        NVSDK_NGX_VULKAN_DestroyParameters(s.capParams);
        s.capParams = nullptr;
    }
    NVSDK_NGX_VULKAN_Shutdown1(s.device);
    s.device = VK_NULL_HANDLE;
    s.initialised = false;
    API_CATCH_VOID()
}

// ============================================================================================
// optimal settings
// ============================================================================================
VKB_DLSS_API int32_t vkb_dlss_query_optimal_settings(int32_t mode, uint32_t w, uint32_t h,
                                                     VkbDlssOptimalSettings* out) {
    API_TRY
    if (!out || out->struct_size != sizeof(VkbDlssOptimalSettings) || w == 0 || h == 0)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "bad out/struct_size/size");
    NVSDK_NGX_PerfQuality_Value pq;
    if (!toPerfQuality(mode, pq)) return fail(VKB_DLSS_ERR_INVALID_ARG, "bad quality mode");
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised || !s.capParams) return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "vkb_dlss_init not done");
    unsigned rw = 0, rh = 0, maxw = 0, maxh = 0, minw = 0, minh = 0;
    float sharp = 0.0f;
    NVSDK_NGX_Result r = NGX_DLSS_GET_OPTIMAL_SETTINGS(s.capParams, w, h, pq, &rw, &rh, &maxw, &maxh, &minw, &minh, &sharp);
    if (NVSDK_NGX_FAILED(r) || rw == 0 || rh == 0) {
        if (refreshCapabilityParameters(s)) {
            r = NGX_DLSS_GET_OPTIMAL_SETTINGS(s.capParams, w, h, pq, &rw, &rh, &maxw, &maxh, &minw, &minh, &sharp);
        }
    }
    if (NVSDK_NGX_FAILED(r)) return failNgx("NGX_DLSS_GET_OPTIMAL_SETTINGS", r);
    if (rw == 0 || rh == 0) {
        // NGX reports success with 0x0 for presets it does not provide (e.g. UltraQuality on DLSS 310.9.0).
        g_lastNgx = 0;
        g_lastError = "DLSS quality mode not supported by this DLSS version (NGX returned 0x0 render size)";
        return VKB_DLSS_ERR_UNSUPPORTED;
    }
    out->render_width = rw;
    out->render_height = rh;
    out->max_width = maxw;
    out->max_height = maxh;
    out->min_width = minw;
    out->min_height = minh;
    out->sharpness = sharp;
    return VKB_DLSS_OK;
    API_CATCH()
}

// ============================================================================================
// feature
// ============================================================================================
VKB_DLSS_API int32_t vkb_dlss_create_feature(uint64_t cmd, const VkbDlssCreateInfo* info, uint64_t* outFeature) {
    API_TRY
    if (!cmd || !info || !outFeature || info->struct_size != sizeof(VkbDlssCreateInfo))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "null cmd/info/outFeature or wrong struct_size");
    NVSDK_NGX_PerfQuality_Value pq;
    if (!toPerfQuality(info->quality_mode, pq)) return fail(VKB_DLSS_ERR_INVALID_ARG, "bad quality mode");
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised) return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "vkb_dlss_init not done");

    int flags = 0;
    if (info->flags & VKB_DLSS_FLAG_MV_LOW_RES) flags |= NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;
    if (info->flags & VKB_DLSS_FLAG_IS_HDR) flags |= NVSDK_NGX_DLSS_Feature_Flags_IsHDR;
    if (info->flags & VKB_DLSS_FLAG_DEPTH_INVERTED) flags |= NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
    if (info->flags & VKB_DLSS_FLAG_AUTO_EXPOSURE) flags |= NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    if (info->flags & VKB_DLSS_FLAG_MV_JITTERED) flags |= NVSDK_NGX_DLSS_Feature_Flags_MVJittered;
    if (info->flags & VKB_DLSS_FLAG_SHARPEN) flags |= NVSDK_NGX_DLSS_Feature_Flags_DoSharpening;
    if (info->flags & VKB_DLSS_FLAG_ALPHA_UPSCALING) flags |= NVSDK_NGX_DLSS_Feature_Flags_AlphaUpscaling;

    NVSDK_NGX_DLSS_Create_Params cp{};
    cp.Feature.InWidth = info->render_width;
    cp.Feature.InHeight = info->render_height;
    cp.Feature.InTargetWidth = info->output_width;
    cp.Feature.InTargetHeight = info->output_height;
    cp.Feature.InPerfQualityValue = pq;
    cp.InFeatureCreateFlags = flags;
    cp.InEnableOutputSubrects = info->enable_output_subrects != 0;

    std::unique_ptr<Feature> f(new Feature());
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_AllocateParameters(&f->params);
    if (NVSDK_NGX_FAILED(r)) return failNgx("NVSDK_NGX_VULKAN_AllocateParameters", r);
    r = NGX_VULKAN_CREATE_DLSS_EXT1(s.device, reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(cmd)), 1, 1,
                                    &f->handle, f->params, &cp);
    if (NVSDK_NGX_FAILED(r)) {
        NVSDK_NGX_VULKAN_DestroyParameters(f->params);
        return failNgx("NGX_VULKAN_CREATE_DLSS_EXT1", r);
    }
    Feature* raw = f.release();
    s.features.insert(raw);
    *outFeature = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(raw));
    return VKB_DLSS_OK;
    API_CATCH()
}

VKB_DLSS_API int32_t vkb_dlss_evaluate(uint64_t cmd, uint64_t feature, const VkbDlssEvalInfo* info) {
    API_TRY
    if (!cmd || !feature || !info || info->struct_size != sizeof(VkbDlssEvalInfo))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "null cmd/feature/info or wrong struct_size");
    if (!info->color.view || !info->output.view || !info->depth.view || !info->motion_vectors.view)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "color/output/depth/motion_vectors view must be non-null");
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised) return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "vkb_dlss_init not done");
    Feature* f = reinterpret_cast<Feature*>(static_cast<uintptr_t>(feature));
    if (s.features.find(f) == s.features.end()) return fail(VKB_DLSS_ERR_INVALID_ARG, "unknown feature handle");
    if (f->is_rr) return fail(VKB_DLSS_ERR_INVALID_ARG, "feature handle belongs to DLSS-RR, use vkb_dlss_rr_evaluate");

    NVSDK_NGX_Resource_VK color = toResource(info->color);
    NVSDK_NGX_Resource_VK output = toResource(info->output);
    NVSDK_NGX_Resource_VK depth = toResource(info->depth);
    NVSDK_NGX_Resource_VK mv = toResource(info->motion_vectors);
    NVSDK_NGX_Resource_VK exposure{};
    bool hasExposure = info->exposure.view != 0;
    if (hasExposure) exposure = toResource(info->exposure);

    NVSDK_NGX_VK_DLSS_Eval_Params ep{};
    ep.Feature.pInColor = &color;
    ep.Feature.pInOutput = &output;
    ep.Feature.InSharpness = info->sharpness;
    ep.pInDepth = &depth;
    ep.pInMotionVectors = &mv;
    ep.pInExposureTexture = hasExposure ? &exposure : nullptr;
    ep.InJitterOffsetX = info->jitter_x;
    ep.InJitterOffsetY = info->jitter_y;
    ep.InReset = info->reset ? 1 : 0;
    ep.InMVScaleX = info->mv_scale_x;
    ep.InMVScaleY = info->mv_scale_y;
    ep.InPreExposure = info->pre_exposure;
    ep.InExposureScale = info->exposure_scale;
    ep.InRenderSubrectDimensions.Width = info->render_subrect_width ? info->render_subrect_width : info->color.width;
    ep.InRenderSubrectDimensions.Height = info->render_subrect_height ? info->render_subrect_height : info->color.height;

    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(cmd)),
                                                      f->handle, f->params, &ep);
    if (NVSDK_NGX_FAILED(r)) return failNgx("NGX_VULKAN_EVALUATE_DLSS_EXT", r);
    return VKB_DLSS_OK;
    API_CATCH()
}

VKB_DLSS_API int32_t vkb_dlss_release_feature(uint64_t feature) {
    API_TRY
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    Feature* f = reinterpret_cast<Feature*>(static_cast<uintptr_t>(feature));
    auto it = s.features.find(f);
    if (!feature || it == s.features.end()) return fail(VKB_DLSS_ERR_INVALID_ARG, "unknown feature handle");
    s.features.erase(it);
    NVSDK_NGX_Result r = f->handle ? NVSDK_NGX_VULKAN_ReleaseFeature(f->handle) : NVSDK_NGX_Result_Success;
    if (f->params) NVSDK_NGX_VULKAN_DestroyParameters(f->params);
    delete f;
    if (NVSDK_NGX_FAILED(r)) return failNgx("NVSDK_NGX_VULKAN_ReleaseFeature", r);
    return VKB_DLSS_OK;
    API_CATCH()
}

// ============================================================================================
// DLSS Ray Reconstruction (DLSS-D / DLSS 3.5+) [DLSS-RR]
// ============================================================================================
VKB_DLSS_API int32_t vkb_dlss_rr_is_supported(uint32_t* outSupported) {
    API_TRY
    if (!outSupported) return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] outSupported is null");
    *outSupported = 0;
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised || !s.capParams) return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "[DLSS-RR] vkb_dlss_init not done");
    int avail = 0;
    NVSDK_NGX_Result r = NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &avail);
    if (NVSDK_NGX_FAILED(r)) avail = 0;
    *outSupported = avail ? 1 : 0;
    if (!avail) {
        int needsDriver = 0;
        NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSamplingDenoising_NeedsUpdatedDriver, &needsDriver);
        int initResult = 0;
        NVSDK_NGX_Parameter_GetI(s.capParams, NVSDK_NGX_Parameter_SuperSamplingDenoising_FeatureInitResult, &initResult);
        if (needsDriver) {
            unsigned maj = 0, min = 0;
            NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSamplingDenoising_MinDriverVersionMajor, &maj);
            NVSDK_NGX_Parameter_GetUI(s.capParams, NVSDK_NGX_Parameter_SuperSamplingDenoising_MinDriverVersionMinor, &min);
            char buf[128];
            std::snprintf(buf, sizeof(buf), "[DLSS-RR] GPU driver too old for Ray Reconstruction (minimum driver %u.%u)", maj, min);
            g_lastError = buf;
        } else if (NVSDK_NGX_FAILED(initResult)) {
            g_lastError = std::string("[DLSS-RR] Ray Reconstruction feature init failed: ") + ngxResultString(static_cast<NVSDK_NGX_Result>(initResult));
        } else {
            g_lastError = "[DLSS-RR] Ray Reconstruction is not supported on this device/driver configuration";
        }
    }
    return VKB_DLSS_OK;
    API_CATCH()
}

VKB_DLSS_API int32_t vkb_dlss_rr_create_feature(uint64_t cmd, const VkbDlssRrCreateInfo* info, uint64_t* outFeature) {
    API_TRY
    if (!cmd || !info || !outFeature || info->struct_size != sizeof(VkbDlssRrCreateInfo))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] null cmd/info/outFeature or wrong struct_size");
    if (info->render_width == 0 || info->render_height == 0 || info->output_width == 0 || info->output_height == 0)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] render/output width and height must be positive");
    NVSDK_NGX_PerfQuality_Value pq;
    if (!toPerfQuality(info->quality_mode, pq)) return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] bad quality mode");
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised) return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "[DLSS-RR] vkb_dlss_init not done");

    int flags = 0;
    if (info->flags & VKB_DLSS_FLAG_MV_LOW_RES) flags |= NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;
    if (info->flags & VKB_DLSS_FLAG_IS_HDR) flags |= NVSDK_NGX_DLSS_Feature_Flags_IsHDR;
    if (info->flags & VKB_DLSS_FLAG_DEPTH_INVERTED) flags |= NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
    if (info->flags & VKB_DLSS_FLAG_AUTO_EXPOSURE) flags |= NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    if (info->flags & VKB_DLSS_FLAG_MV_JITTERED) flags |= NVSDK_NGX_DLSS_Feature_Flags_MVJittered;
    if (info->flags & VKB_DLSS_FLAG_SHARPEN) flags |= NVSDK_NGX_DLSS_Feature_Flags_DoSharpening;
    if (info->flags & VKB_DLSS_FLAG_ALPHA_UPSCALING) flags |= NVSDK_NGX_DLSS_Feature_Flags_AlphaUpscaling;

    NVSDK_NGX_DLSSD_Create_Params cp{};
    cp.InDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    cp.InRoughnessMode = info->roughness_mode ? NVSDK_NGX_DLSS_Roughness_Mode_Packed : NVSDK_NGX_DLSS_Roughness_Mode_Unpacked;
    cp.InUseHWDepth = info->depth_type ? NVSDK_NGX_DLSS_Depth_Type_HW : NVSDK_NGX_DLSS_Depth_Type_Linear;
    cp.InWidth = info->render_width;
    cp.InHeight = info->render_height;
    cp.InTargetWidth = info->output_width;
    cp.InTargetHeight = info->output_height;
    cp.InPerfQualityValue = pq;
    cp.InFeatureCreateFlags = flags;
    cp.InEnableOutputSubrects = info->enable_output_subrects != 0;

    std::unique_ptr<Feature> f(new Feature());
    f->is_rr = true;
    f->roughness_mode = info->roughness_mode;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_AllocateParameters(&f->params);
    if (NVSDK_NGX_FAILED(r)) return failNgx("[DLSS-RR] NVSDK_NGX_VULKAN_AllocateParameters", r);
    r = NGX_VULKAN_CREATE_DLSSD_EXT1(s.device, reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(cmd)), 1, 1,
                                     &f->handle, f->params, &cp);
    if (NVSDK_NGX_FAILED(r)) {
        NVSDK_NGX_VULKAN_DestroyParameters(f->params);
        return failNgx("[DLSS-RR] NGX_VULKAN_CREATE_DLSSD_EXT1", r);
    }
    Feature* raw = f.release();
    s.features.insert(raw);
    *outFeature = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(raw));
    return VKB_DLSS_OK;
    API_CATCH()
}

VKB_DLSS_API int32_t vkb_dlss_rr_evaluate(uint64_t cmd, uint64_t feature, const VkbDlssRrEvalInfo* info) {
    API_TRY
    if (!cmd || !feature || !info || info->struct_size != sizeof(VkbDlssRrEvalInfo))
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] null cmd/feature/info or wrong struct_size");
    if (!info->color.view || !info->output.view || !info->depth.view || !info->motion_vectors.view)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] color/output/depth/motion_vectors view must be non-null");
    if (!info->output.read_write)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] output image must have read_write=1 (storage usage required)");
    if (!info->diffuse_albedo.view || !info->specular_albedo.view || !info->normals.view)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] diffuse_albedo/specular_albedo/normals view must be non-null");
    State& s = st();
    std::lock_guard<std::recursive_mutex> lk(s.mutex);
    if (!s.initialised) return fail(VKB_DLSS_ERR_NOT_INITIALIZED, "[DLSS-RR] vkb_dlss_init not done");
    Feature* f = reinterpret_cast<Feature*>(static_cast<uintptr_t>(feature));
    if (s.features.find(f) == s.features.end()) return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] unknown feature handle");
    if (!f->is_rr) return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] feature handle belongs to DLSS-SR, use vkb_dlss_evaluate");
    if (f->roughness_mode == 0 && !info->roughness.view)
        return fail(VKB_DLSS_ERR_INVALID_ARG, "[DLSS-RR] roughness view must be non-null when created with roughness_mode=0 (unpacked)");

    NVSDK_NGX_Resource_VK color = toResource(info->color);
    NVSDK_NGX_Resource_VK output = toResource(info->output);
    NVSDK_NGX_Resource_VK depth = toResource(info->depth);
    NVSDK_NGX_Resource_VK mv = toResource(info->motion_vectors);
    NVSDK_NGX_Resource_VK diffAlb = toResource(info->diffuse_albedo);
    NVSDK_NGX_Resource_VK specAlb = toResource(info->specular_albedo);
    NVSDK_NGX_Resource_VK normals = toResource(info->normals);

    NVSDK_NGX_Resource_VK exposure{};
    bool hasExposure = info->exposure.view != 0;
    if (hasExposure) exposure = toResource(info->exposure);

    NVSDK_NGX_Resource_VK roughness{};
    bool hasRoughness = info->roughness.view != 0;
    if (hasRoughness) roughness = toResource(info->roughness);

    NVSDK_NGX_Resource_VK diffHitDist{};
    bool hasDiffHit = info->diffuse_hit_distance.view != 0;
    if (hasDiffHit) diffHitDist = toResource(info->diffuse_hit_distance);

    NVSDK_NGX_Resource_VK specHitDist{};
    bool hasSpecHit = info->specular_hit_distance.view != 0;
    if (hasSpecHit) specHitDist = toResource(info->specular_hit_distance);

    NVSDK_NGX_Resource_VK rtHitDist{};
    bool hasRtHit = info->ray_tracing_hit_distance.view != 0;
    if (hasRtHit) rtHitDist = toResource(info->ray_tracing_hit_distance);

    NVSDK_NGX_Resource_VK transpMask{};
    bool hasTransp = info->transparency_mask.view != 0;
    if (hasTransp) transpMask = toResource(info->transparency_mask);

    NVSDK_NGX_Resource_VK disoccMask{};
    bool hasDisocc = info->disocclusion_mask.view != 0;
    if (hasDisocc) disoccMask = toResource(info->disocclusion_mask);

    NVSDK_NGX_VK_DLSSD_Eval_Params ep{};
    ep.pInColor = &color;
    ep.pInOutput = &output;
    ep.pInDepth = &depth;
    ep.pInMotionVectors = &mv;
    ep.pInDiffuseAlbedo = &diffAlb;
    ep.pInSpecularAlbedo = &specAlb;
    ep.pInNormals = &normals;
    ep.pInRoughness = hasRoughness ? &roughness : nullptr;
    ep.pInExposureTexture = hasExposure ? &exposure : nullptr;
    ep.pInDiffuseHitDistance = hasDiffHit ? &diffHitDist : nullptr;
    ep.pInSpecularHitDistance = hasSpecHit ? &specHitDist : nullptr;
    ep.pInRayTracingHitDistance = hasRtHit ? &rtHitDist : nullptr;
    ep.pInTransparencyMask = hasTransp ? &transpMask : nullptr;
    ep.pInDisocclusionMask = hasDisocc ? &disoccMask : nullptr;

    // Ensure GBufferSurface entries are aligned as well
    ep.GBufferSurface.pInAttrib[NVSDK_NGX_GBUFFER_ALBEDO] = &diffAlb;
    ep.GBufferSurface.pInAttrib[NVSDK_NGX_GBUFFER_SPECULAR] = &specAlb;
    ep.GBufferSurface.pInAttrib[NVSDK_NGX_GBUFFER_NORMALS] = &normals;
    if (hasRoughness) {
        ep.GBufferSurface.pInAttrib[NVSDK_NGX_GBUFFER_ROUGHNESS] = &roughness;
    }

    ep.InJitterOffsetX = info->jitter_x;
    ep.InJitterOffsetY = info->jitter_y;
    ep.InReset = info->reset ? 1 : 0;
    ep.InMVScaleX = info->mv_scale_x == 0.0f ? 1.0f : info->mv_scale_x;
    ep.InMVScaleY = info->mv_scale_y == 0.0f ? 1.0f : info->mv_scale_y;
    ep.InPreExposure = info->pre_exposure == 0.0f ? 1.0f : info->pre_exposure;
    ep.InExposureScale = info->exposure_scale == 0.0f ? 1.0f : info->exposure_scale;
    ep.InFrameTimeDeltaInMsec = info->frame_time_delta_ms;
    ep.InRenderSubrectDimensions.Width = info->render_subrect_width ? info->render_subrect_width : info->color.width;
    ep.InRenderSubrectDimensions.Height = info->render_subrect_height ? info->render_subrect_height : info->color.height;

    if ((info->has_matrices & 1) != 0 || info->has_matrices == 1) {
        ep.pInWorldToViewMatrix = const_cast<float*>(info->world_to_view_matrix);
    }
    if ((info->has_matrices & 2) != 0 || info->has_matrices == 1) {
        ep.pInViewToClipMatrix = const_cast<float*>(info->view_to_clip_matrix);
    }

    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSSD_EXT(reinterpret_cast<VkCommandBuffer>(static_cast<uintptr_t>(cmd)),
                                                      f->handle, f->params, &ep);
    if (NVSDK_NGX_FAILED(r)) return failNgx("[DLSS-RR] NGX_VULKAN_EVALUATE_DLSSD_EXT", r);
    return VKB_DLSS_OK;
    API_CATCH()
}

VKB_DLSS_API int32_t vkb_dlss_rr_release_feature(uint64_t feature) {
    API_TRY
    return vkb_dlss_release_feature(feature);
    API_CATCH()
}

// ============================================================================================
// STUBS (explicit, not silent)
// ============================================================================================
VKB_DLSS_API int32_t vkb_dlss_fg_is_supported(uint32_t* outSupported) {
    API_TRY
    // STUB: Frame Generation requires Streamline integration, not part of this DLL yet.
    if (outSupported) *outSupported = 0;
    return fail(VKB_DLSS_NOT_IMPLEMENTED, "STUB: DLSS Frame Generation (Streamline) is not implemented");
    API_CATCH()
}

}  // extern "C"
