#include <sl.h>
#include <sl_dlss.h>
#include <sl_dlss_d.h>
#include "fault_state.h"
#include <set>
#include <cstring>

namespace {
struct Token final : sl::FrameToken { operator uint32_t() const override { return 1; } } token;
std::set<int> resources;
int kind_of(sl::Feature feature) {
    if (feature == sl::kFeatureDLSS) return KindSr;
    if (feature == sl::kFeatureDLSS_RR) return KindRr;
    fault::violation("wrapper touched another feature, including FG");
}
sl::Result sr_options(const sl::ViewportHandle& viewport, const sl::DLSSOptions&) {
    if (static_cast<uint32_t>(viewport) != 0) fault::violation("unexpected SR viewport");
    return fault::hit(OpOptions, KindSr) ? sl::Result::eErrorNGXFailed : sl::Result::eOk;
}
sl::Result rr_options(const sl::ViewportHandle& viewport, const sl::DLSSDOptions&) {
    if (static_cast<uint32_t>(viewport) != 0) fault::violation("unexpected RR viewport");
    return fault::hit(OpOptions, KindRr) ? sl::Result::eErrorNGXFailed : sl::Result::eOk;
}
sl::Result optimal(const sl::DLSSOptions& options, sl::DLSSOptimalSettings& settings) {
    settings.optimalRenderWidth = options.outputWidth / 2; settings.optimalRenderHeight = options.outputHeight / 2;
    settings.renderWidthMin = options.outputWidth / 3; settings.renderHeightMin = options.outputHeight / 3;
    settings.renderWidthMax = options.outputWidth; settings.renderHeightMax = options.outputHeight;
    return sl::Result::eOk;
}
sl::Result resolve(sl::Feature feature, const char* name, void*& output) {
    kind_of(feature);
    if (std::strcmp(name, "slDLSSGetOptimalSettings") == 0) output = reinterpret_cast<void*>(&optimal);
    else if (std::strcmp(name, "slDLSSSetOptions") == 0) output = reinterpret_cast<void*>(&sr_options);
    else if (std::strcmp(name, "slDLSSDSetOptions") == 0) output = reinterpret_cast<void*>(&rr_options);
    else { output = nullptr; return sl::Result::eErrorMissingOrInvalidAPI; }
    return sl::Result::eOk;
}
sl::Result loaded(sl::Feature, bool& result) { result = true; return sl::Result::eOk; }
sl::Result supported(sl::Feature, const sl::AdapterInfo&) { return sl::Result::eOk; }
sl::Result tag(const sl::FrameToken&, const sl::ViewportHandle& viewport, const sl::ResourceTag*, uint32_t, sl::CommandBuffer*) {
    if (static_cast<uint32_t>(viewport) != 0) fault::violation("unexpected tag viewport");
    return fault::hit(OpTag) ? sl::Result::eErrorNGXFailed : sl::Result::eOk;
}
sl::Result evaluate(sl::Feature feature, const sl::FrameToken&, const sl::BaseStructure**, uint32_t, sl::CommandBuffer*) {
    int kind = kind_of(feature); bool failed = fault::hit(OpEvaluate, kind);
    if (!failed || fault::partial[OpEvaluate]) resources.insert(kind);
    return failed ? sl::Result::eErrorNGXFailed : sl::Result::eOk;
}
sl::Result free_resources(sl::Feature feature, const sl::ViewportHandle& viewport) {
    int kind = kind_of(feature); fault::last_feature = static_cast<int>(feature);
    fault::last_viewport = static_cast<int>(static_cast<uint32_t>(viewport));
    if (fault::last_viewport != 0) fault::violation("unexpected free viewport");
    if (fault::hit(OpRelease, kind)) return sl::Result::eErrorNGXFailed;
    if (resources.erase(kind) == 0) return sl::Result::eErrorInvalidParameter;
    return sl::Result::eOk;
}
}
// The same imported SL boundary that vkb_sl.cpp owns in the real DLL.
sl::FrameToken* g_token = &token;
bool g_inited = true;
PFun_slSetTagForFrame* p_slSetTagForFrame = &tag;
PFun_slGetFeatureFunction* p_slGetFeatureFunction = &resolve;
PFun_slIsFeatureSupported* p_slIsFeatureSupported = &supported;
PFun_slIsFeatureLoaded* p_slIsFeatureLoaded = &loaded;
PFun_slEvaluateFeature* p_slEvaluateFeature = &evaluate;
PFun_slFreeResources* p_slFreeResources = &free_resources;
TEST_API void vendor_test_reset() { if (!resources.empty()) fault::violation("reset with live SL resources"); fault::reset(); g_inited = true; }
TEST_API void vendor_test_fail(int operation, int count, int partial) { fault::remaining.at(operation) = count; fault::partial.at(operation) = partial; }
TEST_API int vendor_test_count(int operation, int kind) { auto& row = fault::calls.at(operation); return kind == KindAny ? row[0] + row[1] + row[2] : row.at(kind + 1); }
TEST_API int vendor_test_live_features(int kind) { return kind == KindAny ? static_cast<int>(resources.size()) : static_cast<int>(resources.count(kind)); }
TEST_API int vendor_test_live_parameters() { return 0; }
TEST_API int vendor_test_last_feature() { return fault::last_feature; }
TEST_API int vendor_test_last_viewport() { return fault::last_viewport; }
TEST_API void vendor_test_caps_available(int) {}
