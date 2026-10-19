/*
 * sl_probe.cpp - console probe for NVIDIA Streamline (no game, no Vulkan device needed).
 *   sl_probe.exe <dir_with_sl.interposer.dll_and_plugins> <log_dir> [manual]
 * Loads sl.interposer.dll by explicit path, calls slInit (renderAPI = Vulkan; OTA/downloaded plugins disabled),
 * then slGetFeatureRequirements for DLSS, DLSS_G, Reflex, PCL, DLSS_RR and prints what each feature needs
 * (instance/device extensions, queues, Vulkan 1.2/1.3 features, required buffer tags, flags).
 * Output lines start with "SL_PROBE". Signatures: Streamline 2.14.1 include/sl_core_api.h, sl_core_types.h.
 */
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdio>
#include <string>
#include <vector>

#include "sl.h"
#include "sl_consts.h"
#include "sl_dlss.h"
#include "sl_dlss_g.h"
#include "sl_dlss_d.h"
#include "sl_reflex.h"
#include "sl_pcl.h"

static void onLog(sl::LogType type, const char* msg) { std::printf("SL_PROBE sllog[%u] %s", static_cast<unsigned>(type), msg); }

template <class F> static F load(HMODULE m, const char* name) {
    F f = reinterpret_cast<F>(GetProcAddress(m, name));
    if (!f) std::printf("SL_PROBE FAILED stage=export name=%s\n", name);
    return f;
}

int wmain(int argc, wchar_t** argv) {
    if (argc < 3) { std::printf("usage: sl_probe <sl_dir> <log_dir> [manual]\n"); return 2; }
    std::wstring dir = argv[1];
    std::wstring logDir = argv[2];
    bool manual = argc > 3 && std::wstring(argv[3]) == L"manual";

    std::wstring dll = dir + L"\\sl.interposer.dll";
    HMODULE mod = LoadLibraryExW(dll.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!mod) { std::printf("SL_PROBE FAILED stage=load_interposer error=%lu\n", GetLastError()); return 1; }
    std::printf("SL_PROBE interposer_loaded manual_hooking=%d\n", manual ? 1 : 0);

    auto pInit = load<PFun_slInit*>(mod, "slInit");
    auto pShutdown = load<PFun_slShutdown*>(mod, "slShutdown");
    auto pReq = load<PFun_slGetFeatureRequirements*>(mod, "slGetFeatureRequirements");
    auto pVer = load<PFun_slGetFeatureVersion*>(mod, "slGetFeatureVersion");
    if (!pInit || !pShutdown || !pReq) return 1;

    const wchar_t* paths[1] = {dir.c_str()};
    sl::Feature features[] = {sl::kFeatureDLSS, sl::kFeatureDLSS_G, sl::kFeatureReflex, sl::kFeaturePCL, sl::kFeatureDLSS_RR};
    sl::Preferences pref{};
    pref.showConsole = false;
    pref.logLevel = sl::LogLevel::eDefault;
    pref.pathsToPlugins = paths;
    pref.numPathsToPlugins = 1;
    pref.pathToLogsAndData = logDir.c_str();
    pref.logMessageCallback = onLog;
    pref.flags = sl::PreferenceFlags::eDisableCLStateTracking;  // no OTA, no downloaded plugins
    if (manual) pref.flags |= sl::PreferenceFlags::eUseManualHooking;
    pref.featuresToLoad = features;
    pref.numFeaturesToLoad = sizeof(features) / sizeof(features[0]);
    pref.engine = sl::EngineType::eCustom;
    pref.engineVersion = "26.3";
    pref.projectId = "6f1d2c3e-8a4b-4c5d-9e7f-5b0a1c2d3e4f";
    pref.renderAPI = sl::RenderAPI::eVulkan;

    sl::Result r = pInit(pref, sl::kSDKVersion);
    std::printf("SL_PROBE slInit result=%d (0=eOk)\n", static_cast<int>(r));
    if (r != sl::Result::eOk) return 1;

    struct { const char* name; sl::Feature f; } list[] = {
        {"DLSS", sl::kFeatureDLSS}, {"DLSS_G", sl::kFeatureDLSS_G}, {"Reflex", sl::kFeatureReflex},
        {"PCL", sl::kFeaturePCL}, {"DLSS_RR", sl::kFeatureDLSS_RR}};
    for (auto& e : list) {
        sl::FeatureRequirements q{};
        sl::Result rr = pReq(e.f, q);
        if (rr != sl::Result::eOk) { std::printf("SL_PROBE feature=%s requirements result=%d\n", e.name, static_cast<int>(rr)); continue; }
        std::printf("SL_PROBE feature=%s flags=0x%X vulkan_supported=%d vsync_off_required=%d hw_sched_required=%d max_viewports=%u threads=%u\n",
            e.name, static_cast<unsigned>(q.flags),
            (static_cast<uint32_t>(q.flags) & static_cast<uint32_t>(sl::FeatureRequirementFlags::eVulkanSupported)) ? 1 : 0,
            (static_cast<uint32_t>(q.flags) & static_cast<uint32_t>(sl::FeatureRequirementFlags::eVSyncOffRequired)) ? 1 : 0,
            (static_cast<uint32_t>(q.flags) & static_cast<uint32_t>(sl::FeatureRequirementFlags::eHardwareSchedulingRequired)) ? 1 : 0,
            q.maxNumViewports, q.maxNumCPUThreads);
        std::printf("SL_PROBE feature=%s vk_queues graphics=%u compute=%u\n", e.name, q.vkNumGraphicsQueuesRequired, q.vkNumComputeQueuesRequired);
        std::string s;
        for (uint32_t i = 0; i < q.vkNumInstanceExtensions; ++i) { if (i) s += ","; s += q.vkInstanceExtensions[i]; }
        std::printf("SL_PROBE feature=%s vk_instance_ext=[%s]\n", e.name, s.c_str());
        s.clear();
        for (uint32_t i = 0; i < q.vkNumDeviceExtensions; ++i) { if (i) s += ","; s += q.vkDeviceExtensions[i]; }
        std::printf("SL_PROBE feature=%s vk_device_ext=[%s]\n", e.name, s.c_str());
        s.clear();
        for (uint32_t i = 0; i < q.vkNumFeatures12; ++i) { if (i) s += ","; s += q.vkFeatures12[i]; }
        std::printf("SL_PROBE feature=%s vk_features12=[%s]\n", e.name, s.c_str());
        s.clear();
        for (uint32_t i = 0; i < q.vkNumFeatures13; ++i) { if (i) s += ","; s += q.vkFeatures13[i]; }
        std::printf("SL_PROBE feature=%s vk_features13=[%s]\n", e.name, s.c_str());
        s.clear();
        for (uint32_t i = 0; i < q.numRequiredTags; ++i) { if (i) s += ","; s += std::to_string(q.requiredTags[i]); }
        std::printf("SL_PROBE feature=%s required_tags=[%s]\n", e.name, s.c_str());
        std::printf("SL_PROBE feature=%s os=%u.%u.%u/%u.%u.%u driver_detected=%u.%u.%u driver_required=%u.%u.%u\n", e.name,
            q.osVersionDetected.major, q.osVersionDetected.minor, q.osVersionDetected.build,
            q.osVersionRequired.major, q.osVersionRequired.minor, q.osVersionRequired.build,
            q.driverVersionDetected.major, q.driverVersionDetected.minor, q.driverVersionDetected.build,
            q.driverVersionRequired.major, q.driverVersionRequired.minor, q.driverVersionRequired.build);
        if (pVer) {
            sl::FeatureVersion v{};
            if (pVer(e.f, v) == sl::Result::eOk)
                std::printf("SL_PROBE feature=%s version sl=%u.%u.%u ngx=%u.%u.%u\n", e.name, v.versionSL.major, v.versionSL.minor, v.versionSL.build,
                    v.versionNGX.major, v.versionNGX.minor, v.versionNGX.build);
        }
    }
    sl::Result rs = pShutdown();
    std::printf("SL_PROBE slShutdown result=%d\nSL_PROBE OK\n", static_cast<int>(rs));
    return 0;
}
