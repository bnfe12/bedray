/*
 * vkb_sl.cpp - thin C ABI over NVIDIA Streamline 2.14.1 for the Java side (FFM). Our code, NVIDIA headers only (no NVIDIA binary
 * is linked: sl.interposer.dll is loaded by explicit path after its Authenticode signature was verified, sl_security.h).
 *
 * Mode: "global" hooking. sl.interposer.dll is handed to LWJGL/SDL as the Vulkan library (-Dorg.lwjgl.vulkan.libname), so every
 * Vulkan entry point of the engine goes through the SL proxies (vkCreateInstance/vkCreateDevice get the SL requirements, the
 * swapchain/present are SL's). Source: ProgrammingGuideManualHooking.md section 4.2/5.2 (proxy path) and ProgrammingGuide.md.
 * Every function returns sl::Result as int (0 = eOk) unless noted. Render thread only (SL: slDLSSGSetOptions is not thread safe).
 */
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <deque>

#include "sl.h"
#include "sl_consts.h"
#include "sl_dlss_g.h"
#include "sl_reflex.h"
#include "sl_pcl.h"
#include "sl_security.h"

#define VKB_API extern "C" __declspec(dllexport)

// shared with vkb_sl_dlss.cpp (the vkb_dlss.h API on the Streamline DLSS plugins)
sl::FrameToken* g_token = nullptr;
bool g_inited = false;
PFun_slSetTagForFrame* p_slSetTagForFrame = nullptr;
PFun_slGetFeatureFunction* p_slGetFeatureFunction = nullptr;
PFun_slIsFeatureSupported* p_slIsFeatureSupported = nullptr;
PFun_slIsFeatureLoaded* p_slIsFeatureLoaded = nullptr;
PFun_slEvaluateFeature* p_slEvaluateFeature = nullptr;
PFun_slFreeResources* p_slFreeResources = nullptr;

namespace {
HMODULE g_mod = nullptr;
PFun_slInit* p_slInit = nullptr;
PFun_slShutdown* p_slShutdown = nullptr;
PFun_slGetNewFrameToken* p_slGetNewFrameToken = nullptr;
PFun_slSetConstants* p_slSetConstants = nullptr;
PFun_slGetFeatureRequirements* p_slGetFeatureRequirements = nullptr;
PFun_slGetFeatureVersion* p_slGetFeatureVersion = nullptr;

PFun_slDLSSGSetOptions* p_slDLSSGSetOptions = nullptr;
PFun_slDLSSGGetState* p_slDLSSGGetState = nullptr;
PFun_slReflexSleep* p_slReflexSleep = nullptr;
PFun_slReflexSetOptions* p_slReflexSetOptions = nullptr;
PFun_slReflexGetState* p_slReflexGetState = nullptr;
static sl::ReflexState g_reflexState;
PFun_slPCLSetMarker* p_slPCLSetMarker = nullptr;

bool g_funcsResolved = false;

std::mutex g_logMutex;
std::deque<std::string> g_log;

void onLog(sl::LogType type, const char* msg) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    std::string s = "[";
    s += (type == sl::LogType::eError ? "E" : type == sl::LogType::eWarn ? "W" : "I");
    s += "] ";
    s += msg ? msg : "";
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    if (g_log.size() > 4000) g_log.pop_front();
    g_log.push_back(std::move(s));
}

template <class F> F sym(const char* name) { return reinterpret_cast<F>(GetProcAddress(g_mod, name)); }

bool resolveFeatureFuncs() {
    if (g_funcsResolved) return true;
    if (!p_slGetFeatureFunction) return false;
    bool ok = true;
    // slGetFeatureFunction is valid only after the device is known to SL (the proxy vkCreateDevice calls slSetVulkanInfo).
    auto get = [&](sl::Feature f, const char* n, void*& out) {
        sl::Result r = p_slGetFeatureFunction(f, n, out);
        if (r != sl::Result::eOk) { out = nullptr; ok = false; }
    };
    void* p = nullptr;
    get(sl::kFeatureDLSS_G, "slDLSSGSetOptions", p); p_slDLSSGSetOptions = reinterpret_cast<PFun_slDLSSGSetOptions*>(p);
    get(sl::kFeatureDLSS_G, "slDLSSGGetState", p); p_slDLSSGGetState = reinterpret_cast<PFun_slDLSSGGetState*>(p);
    get(sl::kFeatureReflex, "slReflexSleep", p); p_slReflexSleep = reinterpret_cast<PFun_slReflexSleep*>(p);
    get(sl::kFeatureReflex, "slReflexSetOptions", p); p_slReflexSetOptions = reinterpret_cast<PFun_slReflexSetOptions*>(p);
    get(sl::kFeatureReflex, "slReflexGetState", p); p_slReflexGetState = reinterpret_cast<PFun_slReflexGetState*>(p);
    get(sl::kFeaturePCL, "slPCLSetMarker", p); p_slPCLSetMarker = reinterpret_cast<PFun_slPCLSetMarker*>(p);
    g_funcsResolved = ok;
    return ok;
}
} // namespace

// Exception containment (ADR-0005 rule 4): these exports are reached from Java through a
// java.lang.foreign (FFM) downcall, so a C++ exception escaping one of them becomes an unhandled
// native exception in the JVM and terminates the process. Each wrapped body returns the value the
// function already uses on its documented "failed / nothing available" error path. This translation
// unit has no thread-local last-error string of its own (the SL log ring is separate), so containment
// is by return code only.
#define SL_TRY try {
#define SL_CATCH(retval)            \
    }                               \
    catch (const std::exception&) { \
        return (retval);            \
    }                               \
    catch (...) {                   \
        return (retval);            \
    }

/* Loads <slDir>\sl.interposer.dll (signature checked) and calls slInit with DLSS_G + Reflex + PCL for Vulkan.
 * Must run before the first Vulkan call of the process. Returns sl::Result, or negative: -1 bad args, -2 signature, -3 load, -4 exports. */
VKB_API int vkb_sl_init(const wchar_t* slDir, const wchar_t* logDir, int verbose) {
    SL_TRY
    if (g_inited) return 0;
    if (!slDir) return -1;
    std::wstring dir = slDir;
    std::wstring dll = dir + L"\\sl.interposer.dll";
    if (!sl::security::verifyEmbeddedSignature(dll.c_str())) return -2;
    g_mod = LoadLibraryExW(dll.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!g_mod) return -3;
    p_slInit = sym<PFun_slInit*>("slInit");
    p_slShutdown = sym<PFun_slShutdown*>("slShutdown");
    p_slGetNewFrameToken = sym<PFun_slGetNewFrameToken*>("slGetNewFrameToken");
    p_slSetConstants = sym<PFun_slSetConstants*>("slSetConstants");
    p_slSetTagForFrame = sym<PFun_slSetTagForFrame*>("slSetTagForFrame");
    p_slGetFeatureFunction = sym<PFun_slGetFeatureFunction*>("slGetFeatureFunction");
    p_slIsFeatureLoaded = sym<PFun_slIsFeatureLoaded*>("slIsFeatureLoaded");
    p_slGetFeatureRequirements = sym<PFun_slGetFeatureRequirements*>("slGetFeatureRequirements");
    p_slIsFeatureSupported = sym<PFun_slIsFeatureSupported*>("slIsFeatureSupported");
    p_slEvaluateFeature = sym<PFun_slEvaluateFeature*>("slEvaluateFeature");
    p_slFreeResources = sym<PFun_slFreeResources*>("slFreeResources");
    p_slGetFeatureVersion = sym<PFun_slGetFeatureVersion*>("slGetFeatureVersion");
    if (!p_slInit || !p_slShutdown || !p_slGetNewFrameToken || !p_slSetConstants || !p_slSetTagForFrame || !p_slGetFeatureFunction) return -4;

    static const wchar_t* paths[1];
    static std::wstring pathStore;
    pathStore = dir;
    paths[0] = pathStore.c_str();
    static std::wstring logStore;
    static sl::Feature features[5] = {sl::kFeatureDLSS_G, sl::kFeatureReflex, sl::kFeaturePCL};
    static uint32_t numFeatures = 3;
    auto exists = [&](const wchar_t* f) { return GetFileAttributesW((dir + L"\\" + f).c_str()) != INVALID_FILE_ATTRIBUTES; };
    // Super Resolution and Ray Reconstruction through the Streamline plugins (see vkb_sl_dlss.cpp for why raw NGX cannot be used here)
    if (exists(L"sl.dlss.dll") && exists(L"nvngx_dlss.dll")) features[numFeatures++] = sl::kFeatureDLSS;
    if (exists(L"sl.dlss_d.dll") && exists(L"nvngx_dlssd.dll")) features[numFeatures++] = sl::kFeatureDLSS_RR;
    sl::Preferences pref{};
    pref.showConsole = false;
    pref.logLevel = verbose ? sl::LogLevel::eVerbose : sl::LogLevel::eDefault;
    pref.pathsToPlugins = paths;
    pref.numPathsToPlugins = 1;
    if (logDir) { logStore = logDir; pref.pathToLogsAndData = logStore.c_str(); }
    pref.logMessageCallback = onLog;
    // No OTA, no downloaded plugins: only the signed binaries next to the interposer. Frame-based tagging = slSetTagForFrame.
    pref.flags = sl::PreferenceFlags::eDisableCLStateTracking | sl::PreferenceFlags::eUseFrameBasedResourceTagging |
                 sl::PreferenceFlags::eDisableDebugText;
    pref.featuresToLoad = features;
    pref.numFeaturesToLoad = numFeatures;
    pref.engine = sl::EngineType::eCustom;
    pref.engineVersion = "26.3";
    pref.projectId = "6f1d2c3e-8a4b-4c5d-9e7f-5b0a1c2d3e4f"; // project id of this mod (not issued by NVIDIA; see docs/framegen/FINDINGS.md)
    pref.renderAPI = sl::RenderAPI::eVulkan;
    sl::Result r = p_slInit(pref, sl::kSDKVersion);
    if (r == sl::Result::eOk) g_inited = true;
    return static_cast<int>(r);
    SL_CATCH(-1)
}

/* Copies queued SL log lines (newline separated) into buf; returns bytes written. */
VKB_API int vkb_sl_drain_log(char* buf, int cap) {
    SL_TRY
    std::lock_guard<std::mutex> lock(g_logMutex);
    int n = 0;
    while (!g_log.empty()) {
        const std::string& s = g_log.front();
        if (n + (int)s.size() + 2 >= cap) break;
        memcpy(buf + n, s.data(), s.size());
        n += (int)s.size();
        buf[n++] = '\n';
        g_log.pop_front();
    }
    if (n < cap) buf[n] = 0;
    return n;
    SL_CATCH(0)
}

/* Feature state for logs: bit0 DLSS_G loaded, bit1 Reflex loaded, bit2 PCL loaded; -1 if not initialised. */
VKB_API int vkb_sl_loaded_mask() {
    SL_TRY
    if (!g_inited || !p_slIsFeatureLoaded) return -1;
    int m = 0;
    bool l = false;
    if (p_slIsFeatureLoaded(sl::kFeatureDLSS_G, l) == sl::Result::eOk && l) m |= 1;
    l = false;
    if (p_slIsFeatureLoaded(sl::kFeatureReflex, l) == sl::Result::eOk && l) m |= 2;
    l = false;
    if (p_slIsFeatureLoaded(sl::kFeaturePCL, l) == sl::Result::eOk && l) m |= 4;
    l = false;
    if (p_slIsFeatureLoaded(sl::kFeatureDLSS, l) == sl::Result::eOk && l) m |= 8;
    l = false;
    if (p_slIsFeatureLoaded(sl::kFeatureDLSS_RR, l) == sl::Result::eOk && l) m |= 16;
    return m;
    SL_CATCH(-1)
}

/* DLSS_G requirements as seen by SL after init. out[0]=flags, out[1]=vk graphics queues, out[2]=vk compute queues,
 * out[3..5]=detected driver major/minor/build. */
VKB_API int vkb_sl_dlssg_requirements(uint32_t* out) {
    SL_TRY
    if (!g_inited || !p_slGetFeatureRequirements) return -1;
    sl::FeatureRequirements q{};
    sl::Result r = p_slGetFeatureRequirements(sl::kFeatureDLSS_G, q);
    if (r != sl::Result::eOk) return static_cast<int>(r);
    out[0] = static_cast<uint32_t>(q.flags);
    out[1] = q.vkNumGraphicsQueuesRequired;
    out[2] = q.vkNumComputeQueuesRequired;
    out[3] = q.driverVersionDetected.major;
    out[4] = q.driverVersionDetected.minor;
    out[5] = q.driverVersionDetected.build;
    return 0;
    SL_CATCH(-1)
}


/* slIsFeatureSupported(DLSS_G, adapter) for the adapter with the given 8 byte LUID (VkPhysicalDeviceIDProperties.deviceLUID).
 * Returns sl::Result (0 = supported; eErrorOSOutOfDate, eErrorDriverOutOfDate, eErrorNoSupportedAdapterFound, ...). */
VKB_API int vkb_sl_dlssg_supported(const uint8_t* luid8) {
    SL_TRY
    if (!g_inited || !p_slIsFeatureSupported) return -1;
    uint8_t luid[8];
    memcpy(luid, luid8, 8);
    sl::AdapterInfo info{};
    info.deviceLUID = luid;
    info.deviceLUIDSizeInBytes = 8;
    return static_cast<int>(p_slIsFeatureSupported(sl::kFeatureDLSS_G, info));
    SL_CATCH(-1)
}

/* Versions of the DLSS_G plugin: out[0..2] = SL major/minor/build, out[3..5] = NGX major/minor/build. */
VKB_API int vkb_sl_dlssg_version(uint32_t* out) {
    SL_TRY
    if (!g_inited || !p_slGetFeatureVersion) return -1;
    sl::FeatureVersion v{};
    sl::Result r = p_slGetFeatureVersion(sl::kFeatureDLSS_G, v);
    if (r != sl::Result::eOk) return static_cast<int>(r);
    out[0] = v.versionSL.major; out[1] = v.versionSL.minor; out[2] = v.versionSL.build;
    out[3] = v.versionNGX.major; out[4] = v.versionNGX.minor; out[5] = v.versionNGX.build;
    return 0;
    SL_CATCH(-1)
}

/* Test helper (autotests only, -Dvkrtx.autotest.keepFocus): brings the game window of this process to the foreground. Streamline turns DLSS-G off
 * while the window is not focused ("DLSS-G disabled: window not focused"), so an unattended test run must keep the focus. Returns 1 when focused. */
static BOOL CALLBACK vkbFindGameWindow(HWND h, LPARAM lp) {
    DWORD pid = 0;
    GetWindowThreadProcessId(h, &pid);
    if (pid != GetCurrentProcessId() || !IsWindowVisible(h)) return TRUE;
    wchar_t title[64] = {};
    GetWindowTextW(h, title, 63);
    if (wcsncmp(title, L"Minecraft", 9) == 0) { *reinterpret_cast<HWND*>(lp) = h; return FALSE; }
    return TRUE;
}

VKB_API int vkb_focus_game_window() {
    HWND h = nullptr;
    EnumWindows(vkbFindGameWindow, reinterpret_cast<LPARAM>(&h));
    if (!h) return -1;
    HWND fg = GetForegroundWindow();
    if (fg == h) return 1;
    DWORD fgThread = fg ? GetWindowThreadProcessId(fg, nullptr) : 0;
    DWORD cur = GetCurrentThreadId();
    if (fgThread && fgThread != cur) AttachThreadInput(cur, fgThread, TRUE);
    if (IsIconic(h)) ShowWindow(h, SW_RESTORE);
    BringWindowToTop(h);
    SetForegroundWindow(h);
    if (fgThread && fgThread != cur) AttachThreadInput(cur, fgThread, FALSE);
    return GetForegroundWindow() == h ? 1 : 0;
}

/* New frame token (one per frame, before slReflexSleep). frameIndex is the engine frame counter (monotonic). */
VKB_API int vkb_sl_frame_begin(uint32_t frameIndex) {
    SL_TRY
    if (!g_inited) return -1;
    return static_cast<int>(p_slGetNewFrameToken(g_token, &frameIndex));
    SL_CATCH(-1)
}

VKB_API int vkb_sl_reflex_sleep() {
    SL_TRY
    if (!g_inited || !g_token || !resolveFeatureFuncs() || !p_slReflexSleep) return -1;
    return static_cast<int>(p_slReflexSleep(*g_token));
    SL_CATCH(-1)
}

/* mode: 0 off, 1 low latency, 2 low latency + boost */
VKB_API int vkb_sl_reflex_options(int mode, uint32_t frameLimitUs) {
    SL_TRY
    if (!g_inited || !resolveFeatureFuncs() || !p_slReflexSetOptions) return -1;
    sl::ReflexOptions o{};
    o.mode = static_cast<sl::ReflexMode>(mode);
    o.frameLimitUs = frameLimitUs;
    return static_cast<int>(p_slReflexSetOptions(o));
    SL_CATCH(-1)
}


/* Latest complete Reflex frame report: out[0] = frameID (low 32 bit), out[1] = end-to-end latency in microseconds
 * (gpuRenderEndTime - simStartTime), out[2] = gpuFrameTimeUs, out[3] = lowLatencyAvailable. Returns 0 when a report was found. */
VKB_API int vkb_sl_reflex_latency(uint32_t* out) {
    SL_TRY
    if (!g_inited || !resolveFeatureFuncs() || !p_slReflexGetState) return -1;
    sl::Result r = p_slReflexGetState(g_reflexState);
    if (r != sl::Result::eOk) return static_cast<int>(r);
    out[3] = g_reflexState.lowLatencyAvailable ? 1 : 0;
    if (!g_reflexState.latencyReportAvailable) return 1;
    const sl::ReflexReport* best = nullptr;
    for (int i = 0; i < sl::kReflexFrameReportCount; ++i) {
        const sl::ReflexReport& rep = g_reflexState.frameReport[i];
        if (rep.simStartTime == 0 || rep.gpuRenderEndTime == 0 || rep.gpuRenderEndTime < rep.simStartTime) continue;
        if (!best || rep.frameID > best->frameID) best = &rep;
    }
    if (!best) return 2;
    out[0] = static_cast<uint32_t>(best->frameID);
    out[1] = static_cast<uint32_t>(best->gpuRenderEndTime - best->simStartTime);
    out[2] = best->gpuFrameTimeUs;
    return 0;
    SL_CATCH(-1)
}

/* sl::PCLMarker value (sl_pcl.h): 0 sim start, 1 sim end, 2 render submit start, 3 render submit end, 4 present start, 5 present end */
VKB_API int vkb_sl_marker(int marker) {
    SL_TRY
    if (!g_inited || !g_token || !resolveFeatureFuncs() || !p_slPCLSetMarker) return -1;
    return static_cast<int>(p_slPCLSetMarker(static_cast<sl::PCLMarker>(marker), *g_token));
    SL_CATCH(-1)
}

/* Common constants. m: 4 row-major matrices (cameraViewToClip, clipToCameraView, clipToPrevClip, prevClipToClip) = 64 floats;
 * v: jitter xy, mvecScale xy, camPos xyz, up xyz, right xyz, fwd xyz, near, far, fovY, aspect, mvecInvalidValue = 21 floats;
 * i: depthInverted, cameraMotionIncluded, motionVectors3D, reset, motionVectorsJittered, motionVectorsDilated (0/1). */
VKB_API int vkb_sl_set_constants(const float* m, const float* v, const int* i, uint32_t viewport) {
    SL_TRY
    if (!g_inited || !g_token) return -1;
    sl::Constants c{};
    auto mat = [&](sl::float4x4& dst, const float* src) {
        for (int r = 0; r < 4; ++r) dst.row[r] = sl::float4(src[r * 4 + 0], src[r * 4 + 1], src[r * 4 + 2], src[r * 4 + 3]);
    };
    mat(c.cameraViewToClip, m);
    mat(c.clipToCameraView, m + 16);
    mat(c.clipToPrevClip, m + 32);
    mat(c.prevClipToClip, m + 48);
    for (int r = 0; r < 4; ++r) c.clipToLensClip.row[r] = sl::float4(r == 0 ? 1.0f : 0.0f, r == 1 ? 1.0f : 0.0f, r == 2 ? 1.0f : 0.0f, r == 3 ? 1.0f : 0.0f);
    c.jitterOffset = sl::float2(v[0], v[1]);
    c.mvecScale = sl::float2(v[2], v[3]);
    c.cameraPinholeOffset = sl::float2(0.0f, 0.0f);
    c.cameraPos = sl::float3(v[4], v[5], v[6]);
    c.cameraUp = sl::float3(v[7], v[8], v[9]);
    c.cameraRight = sl::float3(v[10], v[11], v[12]);
    c.cameraFwd = sl::float3(v[13], v[14], v[15]);
    c.cameraNear = v[16];
    c.cameraFar = v[17];
    c.cameraFOV = v[18];
    c.cameraAspectRatio = v[19];
    c.motionVectorsInvalidValue = v[20];
    c.depthInverted = i[0] ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    c.cameraMotionIncluded = i[1] ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    c.motionVectors3D = i[2] ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    c.reset = i[3] ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    c.motionVectorsJittered = i[4] ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    c.motionVectorsDilated = i[5] ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    return static_cast<int>(p_slSetConstants(c, *g_token, sl::ViewportHandle(viewport)));
    SL_CATCH(-1)
}

/* Tags one buffer for the current frame token. bufferType: sl_core_types.h kBufferType*. image == 0 clears the tag.
 * lifecycle: 0 only valid now, 1 valid until present, 2 valid until evaluate. layout/format/usage are the Vulkan values
 * (VkImageLayout at use time, VkFormat, VkImageUsageFlags). Extent = whole image. */
VKB_API int vkb_sl_tag(uint32_t bufferType, uint64_t image, uint64_t view, uint32_t layout, uint32_t w, uint32_t h,
                       uint32_t vkFormat, uint32_t usage, int lifecycle, uint32_t viewport) {
    SL_TRY
    if (!g_inited || !g_token) return -1;
    if (image == 0) {
        sl::ResourceTag t(nullptr, bufferType, static_cast<sl::ResourceLifecycle>(lifecycle), nullptr);
        return static_cast<int>(p_slSetTagForFrame(*g_token, sl::ViewportHandle(viewport), &t, 1, nullptr));
    }
    sl::Resource res(sl::ResourceType::eTex2d, reinterpret_cast<void*>(image), nullptr, reinterpret_cast<void*>(view), layout);
    res.width = w;
    res.height = h;
    res.nativeFormat = vkFormat;
    res.mipLevels = 1;
    res.arrayLayers = 1;
    res.flags = 0;
    res.usage = usage;
    sl::Extent ext{0, 0, w, h};
    sl::ResourceTag t(&res, bufferType, static_cast<sl::ResourceLifecycle>(lifecycle), &ext);
    return static_cast<int>(p_slSetTagForFrame(*g_token, sl::ViewportHandle(viewport), &t, 1, nullptr));
    SL_CATCH(-1)
}

/* mode: 0 off, 1 on, 2 auto, 3 dynamic (not on Vulkan). numFrames = frames to generate (1 = 2x). flags: sl::DLSSGFlags bits.
 * w/h: back buffer size; backBuffers: swapchain image count; recompose: 1 = user interface recomposition. */
VKB_API int vkb_sl_dlssg_options(int mode, uint32_t numFrames, uint32_t flags, uint32_t w, uint32_t h, uint32_t backBuffers,
                                 int recompose, uint32_t viewport, uint32_t dynW, uint32_t dynH) {
    SL_TRY
    if (!g_inited || !resolveFeatureFuncs() || !p_slDLSSGSetOptions) return -1;
    sl::DLSSGOptions o{};
    o.mode = static_cast<sl::DLSSGMode>(mode);
    o.numFramesToGenerate = numFrames;
    o.flags = static_cast<sl::DLSSGFlags>(flags);
    o.colorWidth = w;
    o.colorHeight = h;
    o.numBackBuffers = backBuffers;
    o.dynamicResWidth = dynW;
    o.dynamicResHeight = dynH;
    o.enableUserInterfaceRecomposition = recompose ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    return static_cast<int>(p_slDLSSGSetOptions(sl::ViewportHandle(viewport), o));
    SL_CATCH(-1)
}

/* out: [0]=status bits, [1]=minWidthOrHeight, [2]=numFramesActuallyPresented (since last call), [3]=numFramesToGenerateMax,
 * [4]=vsyncSupportAvailable (sl::Boolean). vram = estimatedVRAMUsageInBytes. */
VKB_API int vkb_sl_dlssg_state(uint32_t* out, uint64_t* vram, uint32_t viewport) {
    SL_TRY
    if (!g_inited || !resolveFeatureFuncs() || !p_slDLSSGGetState) return -1;
    sl::DLSSGState s{};
    sl::Result r = p_slDLSSGGetState(sl::ViewportHandle(viewport), s, nullptr);
    if (r != sl::Result::eOk) return static_cast<int>(r);
    out[0] = static_cast<uint32_t>(s.status);
    out[1] = s.minWidthOrHeight;
    out[2] = s.numFramesActuallyPresented;
    out[3] = s.numFramesToGenerateMax;
    out[4] = static_cast<uint32_t>(s.bIsVsyncSupportAvailable);
    *vram = s.estimatedVRAMUsageInBytes;
    return 0;
    SL_CATCH(-1)
}

VKB_API int vkb_sl_shutdown() {
    SL_TRY
    if (!g_inited) return 0;
    g_inited = false;
    return static_cast<int>(p_slShutdown());
    SL_CATCH(-1)
}
