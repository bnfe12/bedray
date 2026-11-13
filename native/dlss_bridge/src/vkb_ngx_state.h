#ifndef VKB_NGX_STATE_H
#define VKB_NGX_STATE_H

#include <vulkan/vulkan.h>

#include "nvsdk_ngx_vk.h"
#include "vkb_dlss.h"

#include <mutex>
#include <set>
#include <string>
#include <vector>

namespace vkb::ngx {

enum class FeatureKind { super_resolution, ray_reconstruction, neural_rendering };

struct Feature {
    NVSDK_NGX_Handle* handle = nullptr;
    NVSDK_NGX_Parameter* params = nullptr;
    bool usable = false;  // Failed creation and retirement are release-only.
    FeatureKind kind = FeatureKind::super_resolution;
    uint32_t roughness_mode = 0;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct ExtensionList {
    std::vector<std::string> storage;
    std::vector<const char*> names;
};

struct State {
    std::recursive_mutex mutex;
    bool initialised = false;
    bool shuttingDown = false;
    VkDevice device = VK_NULL_HANDLE;
    NVSDK_NGX_Parameter* capParams = nullptr;
    bool capabilitiesReady = false;
    // NGX may keep pointers into these after Init, so they live as long as the state.
    std::wstring featurePath;
    std::wstring dataPath;
    const wchar_t* pathList[4] = {nullptr};
    NVSDK_NGX_FeatureCommonInfo commonInfo{};
    std::string projectId;
    std::string engineVersion;
    std::set<Feature*> features;
    VkbDlssLogCallback logCb = nullptr;
    ExtensionList instanceExtensions, deviceExtensions;
};

State& st();
Feature* registerFeature(State& state, uint64_t* outFeature);
Feature* findFeature(State& state, uint64_t handle);
bool refreshCapabilityParameters(State& state);
int32_t fail(int32_t code, const std::string& message);
int32_t failNgx(const char* operation, NVSDK_NGX_Result result);
void clearError();
NVSDK_NGX_Resource_VK toResource(const VkbDlssImage& image);

// All returned names are copied into the supplied store before returning to the host.
int32_t queryExtensions(NVSDK_NGX_Feature feature, uint64_t instance, uint64_t physicalDevice,
    const wchar_t* featurePath, const wchar_t* dataPath, ExtensionList& extensions,
    uint32_t* outCount, const char* const** outNames);

} // namespace vkb::ngx

#endif
