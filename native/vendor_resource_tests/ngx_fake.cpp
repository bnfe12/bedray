#define VK_NO_PROTOTYPES
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan.h>
#include <nvsdk_ngx.h>
#include <nvsdk_ngx_vk.h>
#include <nvsdk_ngx_params.h>
#include <nvsdk_ngx_defs_dlssd.h>
#include "fault_state.h"
#include <map>
#include <set>
#include <string>
#include <cstdint>

namespace {
constexpr auto ok = NVSDK_NGX_Result_Success;
constexpr auto bad = NVSDK_NGX_Result_FAIL_PlatformError;
NVSDK_NGX_Result NVSDK_CONV optimal(NVSDK_NGX_Parameter* parameters);
struct Parameters final : NVSDK_NGX_Parameter {
    bool capabilities;
    std::map<std::string, long double> numbers;
    std::map<std::string, void*> pointers;
    explicit Parameters(bool caps) : capabilities(caps) {
        if (caps) {
            numbers[NVSDK_NGX_Parameter_SuperSampling_Available] = 1;
            numbers[NVSDK_NGX_Parameter_SuperSamplingDenoising_Available] = 1;
            pointers[NVSDK_NGX_Parameter_DLSSOptimalSettingsCallback] = reinterpret_cast<void*>(&optimal);
        }
    }
#define NUMBER(type) \
    void Set(const char* key, type value) override { numbers[key] = value; } \
    NVSDK_NGX_Result Get(const char* key, type* value) const override { \
        auto it = numbers.find(key); *value = it == numbers.end() ? static_cast<type>(0) : static_cast<type>(it->second); return ok; }
    NUMBER(unsigned long long) NUMBER(float) NUMBER(double) NUMBER(unsigned int) NUMBER(int)
#undef NUMBER
#define POINTER(type) \
    void Set(const char* key, type* value) override { pointers[key] = value; } \
    NVSDK_NGX_Result Get(const char* key, type** value) const override { \
        auto it = pointers.find(key); *value = it == pointers.end() ? nullptr : static_cast<type*>(it->second); return ok; }
    POINTER(ID3D11Resource) POINTER(ID3D12Resource) POINTER(void)
#undef POINTER
    void Reset() override { numbers.clear(); pointers.clear(); }
};
std::set<NVSDK_NGX_Parameter*> parameters;
std::map<const NVSDK_NGX_Handle*, int> handles;
std::uintptr_t next_handle = 0x1000;
bool caps_available = true;
NVSDK_NGX_Result allocate(NVSDK_NGX_Parameter** out, bool caps) {
    int operation = caps ? OpCaps : OpAllocate;
    bool failed = fault::hit(operation);
    *out = nullptr;
    if (!failed || fault::partial[operation]) {
        auto value = new Parameters(caps);
        if (caps) value->numbers[NVSDK_NGX_Parameter_SuperSampling_Available] = caps_available ? 1 : 0;
        *out = value; parameters.insert(*out);
    }
    return failed ? bad : ok;
}
NVSDK_NGX_Result NVSDK_CONV optimal(NVSDK_NGX_Parameter* p) {
    unsigned width = 0, height = 0; p->Get(NVSDK_NGX_Parameter_Width, &width); p->Get(NVSDK_NGX_Parameter_Height, &height);
    p->Set(NVSDK_NGX_Parameter_OutWidth, width / 2); p->Set(NVSDK_NGX_Parameter_OutHeight, height / 2);
    p->Set(NVSDK_NGX_Parameter_DLSS_Get_Dynamic_Min_Render_Width, width / 3);
    p->Set(NVSDK_NGX_Parameter_DLSS_Get_Dynamic_Min_Render_Height, height / 3);
    p->Set(NVSDK_NGX_Parameter_DLSS_Get_Dynamic_Max_Render_Width, width);
    p->Set(NVSDK_NGX_Parameter_DLSS_Get_Dynamic_Max_Render_Height, height);
    p->Set(NVSDK_NGX_Parameter_Sharpness, 0.0f); return ok;
}
}
TEST_API void vendor_test_reset() { if (!parameters.empty() || !handles.empty()) fault::violation("reset with live SDK ownership"); fault::reset(); caps_available = true; }
TEST_API void vendor_test_fail(int operation, int count, int partial) { fault::remaining.at(operation) = count; fault::partial.at(operation) = partial; }
TEST_API int vendor_test_count(int operation, int kind) { auto& row = fault::calls.at(operation); return kind == KindAny ? row[0] + row[1] + row[2] : row.at(kind + 1); }
TEST_API int vendor_test_live_features(int kind) { int result = 0; for (auto& handle : handles) if (kind == KindAny || handle.second == kind) ++result; return result; }
TEST_API int vendor_test_live_parameters() { return static_cast<int>(parameters.size()); }
TEST_API int vendor_test_last_feature() { return fault::last_feature; }
TEST_API int vendor_test_last_viewport() { return fault::last_viewport; }
TEST_API void vendor_test_caps_available(int available) {
    caps_available = available != 0;
    for (auto value : parameters) { auto p = static_cast<Parameters*>(value);
        if (p->capabilities) p->numbers[NVSDK_NGX_Parameter_SuperSampling_Available] = available ? 1 : 0;
    }
}

NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_Init_with_ProjectID(const char*, NVSDK_NGX_EngineType, const char*, const wchar_t*, VkInstance, VkPhysicalDevice, VkDevice, PFN_vkGetInstanceProcAddr, PFN_vkGetDeviceProcAddr, const NVSDK_NGX_FeatureCommonInfo*, NVSDK_NGX_Version) { return ok; }
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_AllocateParameters(NVSDK_NGX_Parameter** out) { return allocate(out, false); }
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_GetCapabilityParameters(NVSDK_NGX_Parameter** out) { return allocate(out, true); }
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_DestroyParameters(NVSDK_NGX_Parameter* value) {
    if (parameters.count(value) == 0) fault::violation("double/unknown parameter destruction");
    if (fault::hit(OpDestroyParameters)) return bad;
    parameters.erase(value); delete static_cast<Parameters*>(value); return ok;
}
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_CreateFeature1(VkDevice, VkCommandBuffer, NVSDK_NGX_Feature feature, NVSDK_NGX_Parameter*, NVSDK_NGX_Handle** out) {
    int kind = feature == NVSDK_NGX_Feature_SuperSampling ? KindSr : KindRr;
    bool failed = fault::hit(OpCreate, kind); *out = nullptr;
    if (!failed || fault::partial[OpCreate]) { *out = reinterpret_cast<NVSDK_NGX_Handle*>(++next_handle); handles[*out] = kind; }
    return failed ? bad : ok;
}
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_CreateFeature(VkCommandBuffer command, NVSDK_NGX_Feature feature, NVSDK_NGX_Parameter* params, NVSDK_NGX_Handle** out) { return NVSDK_NGX_VULKAN_CreateFeature1(nullptr, command, feature, params, out); }
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_EvaluateFeature(VkCommandBuffer, const NVSDK_NGX_Handle* handle, const NVSDK_NGX_Parameter*, PFN_NVSDK_NGX_ProgressCallback) {
    auto it = handles.find(handle); if (it == handles.end()) fault::violation("evaluate after SDK release");
    return fault::hit(OpEvaluate, it->second) ? bad : ok;
}
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_EvaluateFeature_C(VkCommandBuffer command, const NVSDK_NGX_Handle* handle, const NVSDK_NGX_Parameter* params, PFN_NVSDK_NGX_ProgressCallback_C) {
    return NVSDK_NGX_VULKAN_EvaluateFeature(command, handle, params, nullptr);
}
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_ReleaseFeature(NVSDK_NGX_Handle* handle) {
    auto it = handles.find(handle); if (it == handles.end()) fault::violation("double/unknown SDK feature release");
    if (fault::hit(OpRelease, it->second)) return bad;
    handles.erase(it); return ok;
}
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_Shutdown1(VkDevice) {
    if (fault::hit(OpShutdown)) return bad;
    if (!handles.empty() || !parameters.empty()) fault::violation("SDK shutdown with live resources"); return ok;
}
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(const NVSDK_NGX_FeatureDiscoveryInfo*, uint32_t* count, VkExtensionProperties** properties) { *count = 0; *properties = nullptr; return ok; }
NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(VkInstance, VkPhysicalDevice, const NVSDK_NGX_FeatureDiscoveryInfo*, uint32_t* count, VkExtensionProperties** properties) { *count = 0; *properties = nullptr; return ok; }
extern "C" const wchar_t* NVSDK_CONV GetNGXResultAsString(NVSDK_NGX_Result) { return L"injected SDK result"; }
#define PARAMETER(type, suffix) \
    NVSDK_NGX_API void NVSDK_CONV NVSDK_NGX_Parameter_Set##suffix(NVSDK_NGX_Parameter* p, const char* key, type value) { p->Set(key, value); } \
    NVSDK_NGX_API NVSDK_NGX_Result NVSDK_CONV NVSDK_NGX_Parameter_Get##suffix(NVSDK_NGX_Parameter* p, const char* key, type* value) { return p->Get(key, value); }
PARAMETER(unsigned long long, ULL) PARAMETER(float, F) PARAMETER(double, D) PARAMETER(unsigned int, UI) PARAMETER(int, I)
PARAMETER(ID3D11Resource*, D3d11Resource) PARAMETER(ID3D12Resource*, D3d12Resource) PARAMETER(void*, VoidPointer)
#undef PARAMETER
