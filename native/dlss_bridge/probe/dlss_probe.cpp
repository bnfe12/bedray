/*
 * dlss_probe.cpp - console probe for vkb_dlss.dll. No game involved.
 *   dlss_probe.exe [featurePath]
 * Creates a minimal Vulkan instance/device on the NVIDIA GPU with the extensions requested by
 * the wrapper, initialises NGX and prints optimal render resolutions for all DLSS modes.
 * Output lines start with "DLSS_PROBE". Exit code 0 only after "DLSS_PROBE OK".
 */
#define VK_NO_PROTOTYPES
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan.h>

#include "vkb_dlss.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static const wchar_t* kDefaultFeaturePath = L".";

static int failStage(const char* stage, long long result) {
    std::printf("DLSS_PROBE FAILED stage=%s result=0x%llX\n", stage, static_cast<unsigned long long>(result));
    const char* err = vkb_dlss_get_last_error_string();
    if (err && err[0]) std::printf("DLSS_PROBE detail=%s ngx_result=0x%08X\n", err, vkb_dlss_get_last_ngx_result());
    std::fflush(stdout);
    return 1;
}

static std::string join(uint32_t n, const char* const* names) {
    std::string s;
    for (uint32_t i = 0; i < n; ++i) {
        if (i) s += ",";
        s += names[i];
    }
    return s;
}

static void ngxLog(const char* msg, int32_t level) { std::printf("DLSS_PROBE ngxlog[%d] %s", level, msg); }

#define LOADG(name) auto name = reinterpret_cast<PFN_##name>(gipa(VK_NULL_HANDLE, #name))
#define LOADI(name) auto name = reinterpret_cast<PFN_##name>(gipa(instance, #name))

int wmain(int argc, wchar_t** argv) {
    const wchar_t* featurePath = argc > 1 ? argv[1] : kDefaultFeaturePath;
    // NGX writes several log files; keep them out of the DLL folder.
    wchar_t exeDir[MAX_PATH];
    GetModuleFileNameW(nullptr, exeDir, MAX_PATH);
    std::wstring logDir = exeDir;
    logDir = logDir.substr(0, logDir.find_last_of(L'\\')) + L"\\ngx_logs";
    CreateDirectoryW(logDir.c_str(), nullptr);
    const wchar_t* logPath = logDir.c_str();
    std::printf("DLSS_PROBE api_version=0x%06X ngx_api=0x%X\n", vkb_dlss_get_api_version(), vkb_dlss_get_ngx_api_version());
    vkb_dlss_set_log_callback(ngxLog);

    HMODULE vk = LoadLibraryW(L"vulkan-1.dll");
    if (!vk) return failStage("load_vulkan-1.dll", GetLastError());
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(vk, "vkGetInstanceProcAddr"));
    if (!gipa) return failStage("vkGetInstanceProcAddr", 0);

    // 1. instance extensions required by NGX
    uint32_t instCount = 0;
    const char* const* instNames = nullptr;
    int32_t rc = vkb_dlss_get_required_instance_extensions(featurePath, logPath, &instCount, &instNames);
    if (rc != VKB_DLSS_OK) return failStage("get_required_instance_extensions", rc);
    std::vector<std::string> instExtCopy;
    for (uint32_t i = 0; i < instCount; ++i) instExtCopy.emplace_back(instNames[i]);
    std::vector<const char*> instExt;
    for (auto& s : instExtCopy) instExt.push_back(s.c_str());

    std::printf("DLSS_PROBE instance_ext_count=%u [%s]\n", instCount, join(instCount, instNames).c_str());

    // 2. instance
    LOADG(vkCreateInstance);
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "dlss_probe";
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = static_cast<uint32_t>(instExt.size());
    ici.ppEnabledExtensionNames = instExt.data();
    VkInstance instance = VK_NULL_HANDLE;
    VkResult vr = vkCreateInstance(&ici, nullptr, &instance);
    if (vr != VK_SUCCESS) return failStage("vkCreateInstance", vr);

    LOADI(vkEnumeratePhysicalDevices);
    LOADI(vkGetPhysicalDeviceProperties);
    LOADI(vkGetPhysicalDeviceQueueFamilyProperties);
    LOADI(vkEnumerateDeviceExtensionProperties);
    LOADI(vkCreateDevice);
    LOADI(vkDestroyInstance);
    LOADI(vkGetDeviceProcAddr);

    uint32_t pdCount = 0;
    vkEnumeratePhysicalDevices(instance, &pdCount, nullptr);
    std::vector<VkPhysicalDevice> pds(pdCount);
    vkEnumeratePhysicalDevices(instance, &pdCount, pds.data());
    VkPhysicalDevice pd = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties props{};
    for (auto cand : pds) {
        VkPhysicalDeviceProperties p{};
        vkGetPhysicalDeviceProperties(cand, &p);
        std::printf("DLSS_PROBE gpu=\"%s\" vendor=0x%04X\n", p.deviceName, p.vendorID);
        if (p.vendorID == 0x10DE && pd == VK_NULL_HANDLE) {
            pd = cand;
            props = p;
        }
    }
    if (!pd) return failStage("no_nvidia_physical_device", pdCount);
    uint32_t dv = props.driverVersion;
    char drvStr[64];
    std::snprintf(drvStr, sizeof(drvStr), "%u.%u.%u", (dv >> 22) & 0x3FF, (dv >> 14) & 0xFF, (dv >> 6) & 0xFF);

    // 3. device extensions required by NGX
    uint32_t devCount = 0;
    const char* const* devNames = nullptr;
    rc = vkb_dlss_get_required_device_extensions(reinterpret_cast<uint64_t>(instance), reinterpret_cast<uint64_t>(pd),
                                                 featurePath, logPath, &devCount, &devNames);
    if (rc != VKB_DLSS_OK) return failStage("get_required_device_extensions", rc);
    std::vector<std::string> devExtCopy;
    for (uint32_t i = 0; i < devCount; ++i) devExtCopy.emplace_back(devNames[i]);
    std::vector<const char*> devExt;
    for (auto& s : devExtCopy) devExt.push_back(s.c_str());
    std::printf("DLSS_PROBE required_instance_ext=[%s] required_device_ext=[%s]\n", join(instCount, instNames).c_str(),
                join(devCount, devNames).c_str());

    // verify the device supports them (fail loudly, do not silently drop)
    uint32_t supCount = 0;
    vkEnumerateDeviceExtensionProperties(pd, nullptr, &supCount, nullptr);
    std::vector<VkExtensionProperties> sup(supCount);
    vkEnumerateDeviceExtensionProperties(pd, nullptr, &supCount, sup.data());
    for (auto& need : devExt) {
        bool found = false;
        for (auto& e : sup) found = found || std::strcmp(e.extensionName, need) == 0;
        if (!found) {
            std::printf("DLSS_PROBE missing_device_extension=%s\n", need);
            return failStage("device_extension_unsupported", 0);
        }
    }

    // 4. device with one graphics+compute queue
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfs(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, qfs.data());
    uint32_t qf = UINT32_MAX;
    for (uint32_t i = 0; i < qfCount; ++i) {
        if ((qfs[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            qf = i;
            break;
        }
    }
    if (qf == UINT32_MAX) return failStage("no_graphics_compute_queue", 0);
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = qf;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = static_cast<uint32_t>(devExt.size());
    dci.ppEnabledExtensionNames = devExt.data();
    VkDevice device = VK_NULL_HANDLE;
    vr = vkCreateDevice(pd, &dci, nullptr, &device);
    if (vr != VK_SUCCESS) return failStage("vkCreateDevice", vr);

    // 5. NGX init
    VkbDlssCaps caps{};
    caps.struct_size = sizeof(caps);
    rc = vkb_dlss_init(reinterpret_cast<uint64_t>(instance), reinterpret_cast<uint64_t>(pd), reinterpret_cast<uint64_t>(device),
                       reinterpret_cast<void*>(gipa), reinterpret_cast<void*>(vkGetDeviceProcAddr), featurePath, logPath,
                       nullptr, nullptr, &caps);
    std::printf("DLSS_PROBE init result=0x%X available=%u driver=%s ngx_init=0x%08X needs_updated_driver=%u min_driver=%u.%u feature_init=0x%08X wrapper=%s\n",
                static_cast<unsigned>(rc), caps.super_sampling_available, drvStr, caps.ngx_result_init, caps.needs_updated_driver,
                caps.min_driver_major, caps.min_driver_minor, caps.feature_init_result, vkb_dlss_result_to_string(rc));
    if (rc != VKB_DLSS_OK) {
        vkb_dlss_shutdown();
        return failStage("init", rc);
    }

    // 6. optimal settings
    static const char* kModeNames[VKB_DLSS_MODE_COUNT] = {"UltraPerf", "MaxPerf", "Balanced", "MaxQuality", "UltraQuality", "DLAA"};
    static const uint32_t kOut[2][2] = {{1280, 720}, {2560, 1440}};
    for (auto& o : kOut) {
        for (int m = 0; m < VKB_DLSS_MODE_COUNT; ++m) {
            VkbDlssOptimalSettings s{};
            s.struct_size = sizeof(s);
            rc = vkb_dlss_query_optimal_settings(m, o[0], o[1], &s);
            if (rc == VKB_DLSS_ERR_UNSUPPORTED) {
                std::printf("DLSS_PROBE mode=%s out=%ux%u unsupported (%s)\n", kModeNames[m], o[0], o[1],
                            vkb_dlss_get_last_error_string());
                continue;
            }
            if (rc != VKB_DLSS_OK) {
                std::printf("DLSS_PROBE mode=%s out=%ux%u FAILED rc=%d\n", kModeNames[m], o[0], o[1], rc);
                vkb_dlss_shutdown();
                return failStage("query_optimal_settings", rc);
            }
            std::printf("DLSS_PROBE mode=%s out=%ux%u render=%ux%u [%ux%u..%ux%u] sharpness=%.2f\n", kModeNames[m], o[0], o[1],
                        s.render_width, s.render_height, s.min_width, s.min_height, s.max_width, s.max_height, s.sharpness);
        }
    }

    // 7. Ray Reconstruction & Frame Generation support check
    uint32_t rrSupported = 0;
    int32_t rrSupRc = vkb_dlss_rr_is_supported(&rrSupported);
    int32_t rrCreateErr = vkb_dlss_rr_create_feature(0, nullptr, nullptr);
    int32_t rrEvalErr = vkb_dlss_rr_evaluate(0, 0, nullptr);
    int32_t rrRelErr = vkb_dlss_rr_release_feature(0);
    std::printf("DLSS_PROBE [DLSS-RR] supported=%u sup_rc=%s create_check=%s eval_check=%s rel_check=%s fg_check=%s\n",
                rrSupported, vkb_dlss_result_to_string(rrSupRc),
                vkb_dlss_result_to_string(rrCreateErr),
                vkb_dlss_result_to_string(rrEvalErr),
                vkb_dlss_result_to_string(rrRelErr),
                vkb_dlss_result_to_string(vkb_dlss_fg_is_supported(nullptr)));

    vkb_dlss_shutdown();
    std::printf("DLSS_PROBE OK\n");
    std::fflush(stdout);
    return 0;
}
