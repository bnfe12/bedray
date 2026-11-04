#include "vkb_dlss.h"
#include "fault_control.h"
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <string>

static int checks, cases;
#define CHECK(test) do { ++checks; if (!(test)) throw std::runtime_error(std::string(__func__) + ":" + std::to_string(__LINE__) + " " #test + " wrapper=" + vkb_dlss_get_last_error_string()); } while (false)
static void clear_faults() { for (int op = OpAllocate; op <= OpCaps; ++op) vendor_test_fail(op, 0, 0); }
struct Fixture {
    Fixture() {
        clear_faults(); CHECK(vkb_dlss_shutdown_checked() == VKB_DLSS_OK);
        CHECK(vendor_test_live_features(KindAny) == 0);
        CHECK(vendor_test_live_parameters() == 0);
        vendor_test_reset();
        VkbDlssCaps caps{}; caps.struct_size = sizeof(caps);
        CHECK(vkb_dlss_init(1, 2, 3, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, &caps) == VKB_DLSS_OK);
        CHECK(vkb_dlss_resource_contract_version() >= 1);
    }
    ~Fixture() { clear_faults(); vkb_dlss_shutdown_checked(); }
};
static int create(int kind, uint64_t& handle) {
    if (kind == KindSr) {
        VkbDlssCreateInfo info{}; info.struct_size = sizeof(info);
        info.render_width = 64; info.render_height = 48; info.output_width = 128; info.output_height = 96;
        info.quality_mode = VKB_DLSS_MODE_MAX_QUALITY; info.flags = VKB_DLSS_FLAG_DEPTH_INVERTED;
        return vkb_dlss_create_feature(42, &info, &handle);
    }
    VkbDlssRrCreateInfo info{}; info.struct_size = sizeof(info);
    info.render_width = 64; info.render_height = 48; info.output_width = 128; info.output_height = 96;
    info.quality_mode = VKB_DLSS_MODE_MAX_QUALITY; info.roughness_mode = 1; info.depth_type = 1;
    return vkb_dlss_rr_create_feature(42, &info, &handle);
}
static VkbDlssImage image(bool output = false) {
    VkbDlssImage value{}; value.image = output ? 11 : 12; value.view = output ? 21 : 22;
    value.width = output ? 128 : 64; value.height = output ? 96 : 48;
    value.format = 97; value.aspect_mask = 1; value.level_count = value.layer_count = 1;
    value.read_write = output ? 1 : 0; return value;
}
static int evaluate(int kind, uint64_t handle) {
    if (kind == KindSr) {
        VkbDlssEvalInfo info{}; info.struct_size = sizeof(info); info.reset = 1;
        info.color = info.depth = info.motion_vectors = image(); info.output = image(true);
        info.mv_scale_x = info.mv_scale_y = info.pre_exposure = info.exposure_scale = 1;
        return vkb_dlss_evaluate(42, handle, &info);
    }
    VkbDlssRrEvalInfo info{}; info.struct_size = sizeof(info); info.reset = 1;
    info.color = info.depth = info.motion_vectors = info.diffuse_albedo = info.specular_albedo = info.normals = image();
    info.output = image(true); info.mv_scale_x = info.mv_scale_y = info.pre_exposure = info.exposure_scale = 1;
    return vkb_dlss_rr_evaluate(42, handle, &info);
}
static int release(int kind, uint64_t handle) {
    return kind == KindSr ? vkb_dlss_release_feature(handle) : vkb_dlss_rr_release_feature(handle);
}
static void successful_lifecycle(int kind) {
    Fixture fixture; uint64_t handle = 0;
    CHECK(create(kind, handle) == VKB_DLSS_OK && handle != 0);
    CHECK(evaluate(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 1);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 0);
    CHECK(release(kind, handle) != VKB_DLSS_OK);
}
static void release_failure_is_retryable_and_release_only(int kind) {
    Fixture fixture; uint64_t handle = 0;
    CHECK(create(kind, handle) == VKB_DLSS_OK); CHECK(evaluate(kind, handle) == VKB_DLSS_OK);
    vendor_test_fail(OpRelease, 1, 0);
    CHECK(release(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 1);
    int calls = vendor_test_count(OpEvaluate, KindAny);
    CHECK(evaluate(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_count(OpEvaluate, KindAny) == calls);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 0);
}
static void sr_release_does_not_release_rr_or_shared_queries() {
    Fixture fixture; uint64_t sr = 0, rr = 0;
    CHECK(create(KindSr, sr) == VKB_DLSS_OK); CHECK(create(KindRr, rr) == VKB_DLSS_OK);
    CHECK(evaluate(KindSr, sr) == VKB_DLSS_OK); CHECK(evaluate(KindRr, rr) == VKB_DLSS_OK);
    CHECK(release(KindSr, sr) == VKB_DLSS_OK);
    CHECK(vendor_test_live_features(KindRr) == 1); CHECK(evaluate(KindRr, rr) == VKB_DLSS_OK);
    VkbDlssOptimalSettings settings{}; settings.struct_size = sizeof(settings);
    CHECK(vkb_dlss_query_optimal_settings(VKB_DLSS_MODE_MAX_QUALITY, 128, 96, &settings) == VKB_DLSS_OK);
    CHECK(settings.render_width > 0 && settings.render_height > 0);
    CHECK(release(KindRr, rr) == VKB_DLSS_OK);
}
static void checked_shutdown_failure_retains_ownership() {
    Fixture fixture; uint64_t handle = 0;
    CHECK(create(KindSr, handle) == VKB_DLSS_OK); CHECK(evaluate(KindSr, handle) == VKB_DLSS_OK);
    vendor_test_fail(OpRelease, 1, 0);
    CHECK(vkb_dlss_shutdown_checked() != VKB_DLSS_OK);
    CHECK(vendor_test_live_features(KindSr) == 1);
    CHECK(vkb_dlss_shutdown_checked() == VKB_DLSS_OK);
    CHECK(vendor_test_live_features(KindAny) == 0); CHECK(vendor_test_live_parameters() == 0);
}
#ifdef VENDOR_TEST_ngx
static void failed_create_keeps_partial_owner(int kind, int operation, bool partial) {
    Fixture fixture; uint64_t handle = 0;
    vendor_test_fail(operation, 1, partial ? 1 : 0);
    CHECK(create(kind, handle) != VKB_DLSS_OK); CHECK(handle != 0);
    int calls = vendor_test_count(OpEvaluate, KindAny);
    CHECK(evaluate(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_count(OpEvaluate, KindAny) == calls);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_live_features(KindAny) == 0);
    CHECK(vendor_test_live_parameters() == 1); // Shared capability parameters survive feature release.
}
static void parameter_destroy_failure_does_not_release_sdk_handle_twice(int kind) {
    Fixture fixture; uint64_t handle = 0;
    CHECK(create(kind, handle) == VKB_DLSS_OK); CHECK(evaluate(kind, handle) == VKB_DLSS_OK);
    int releases = vendor_test_count(OpRelease, KindAny);
    vendor_test_fail(OpDestroyParameters, 1, 0);
    CHECK(release(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 0);
    CHECK(vendor_test_live_parameters() == 2);
    CHECK(evaluate(kind, handle) != VKB_DLSS_OK);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_count(OpRelease, KindAny) == releases + 1);
    CHECK(vendor_test_live_parameters() == 1);
}
static void sdk_shutdown_failure_retains_device_for_retry() {
    Fixture fixture; vendor_test_fail(OpShutdown, 1, 0);
    CHECK(vkb_dlss_shutdown_checked() != VKB_DLSS_OK);
    CHECK(vkb_dlss_shutdown_checked() == VKB_DLSS_OK);
    CHECK(vendor_test_count(OpShutdown, KindAny) == 2);
}
static void capability_refresh_waits_for_destruction_acknowledgement() {
    Fixture fixture; vendor_test_caps_available(0);
    int acquired = vendor_test_count(OpCaps, KindAny);
    vendor_test_fail(OpDestroyParameters, 1, 0);
    VkbDlssCaps caps{}; caps.struct_size = sizeof(caps);
    vkb_dlss_query_caps(&caps);
    CHECK(vendor_test_live_parameters() == 1);
    CHECK(vendor_test_count(OpCaps, KindAny) == acquired);
    CHECK(vkb_dlss_query_caps(&caps) == VKB_DLSS_OK);
    CHECK(vendor_test_count(OpCaps, KindAny) == acquired + 1);
    CHECK(vendor_test_live_parameters() == 1);
}
static void partial_capability_refresh_and_init_remain_owned() {
    Fixture fixture; vendor_test_caps_available(0);
    vendor_test_fail(OpCaps, 1, 1);
    VkbDlssCaps caps{}; caps.struct_size = sizeof(caps);
    vkb_dlss_query_caps(&caps); CHECK(vendor_test_live_parameters() == 1);
    CHECK(vkb_dlss_shutdown_checked() == VKB_DLSS_OK); CHECK(vendor_test_live_parameters() == 0);
    vendor_test_reset(); vendor_test_fail(OpCaps, 1, 1);
    CHECK(vkb_dlss_init(1, 2, 3, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, &caps) != VKB_DLSS_OK);
    CHECK(vendor_test_live_parameters() == 1);
    uint64_t handle = 0; CHECK(create(KindSr, handle) != VKB_DLSS_OK && handle == 0);
    vendor_test_fail(OpDestroyParameters, 1, 0);
    CHECK(vkb_dlss_shutdown_checked() != VKB_DLSS_OK); CHECK(vendor_test_live_parameters() == 1);
    CHECK(vkb_dlss_shutdown_checked() == VKB_DLSS_OK); CHECK(vendor_test_live_parameters() == 0);
}
static void capability_shutdown_failure_keeps_parameters_for_retry() {
    Fixture fixture; vendor_test_fail(OpDestroyParameters, 1, 0);
    CHECK(vkb_dlss_shutdown_checked() != VKB_DLSS_OK);
    CHECK(vendor_test_live_parameters() == 1);
    CHECK(vendor_test_count(OpShutdown, KindAny) == 0);
    CHECK(vkb_dlss_shutdown_checked() == VKB_DLSS_OK);
    CHECK(vendor_test_live_parameters() == 0);
}
#else
static void options_failure_returns_a_release_only_owner(int kind) {
    Fixture fixture; uint64_t handle = 0; vendor_test_fail(OpOptions, 1, 0);
    CHECK(create(kind, handle) != VKB_DLSS_OK); CHECK(handle != 0);
    CHECK(evaluate(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_count(OpEvaluate, KindAny) == 0);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_count(OpRelease, KindAny) == 0);
}
static void options_only_owner_needs_no_sdk_free(int kind) {
    Fixture fixture; uint64_t handle = 0;
    CHECK(create(kind, handle) == VKB_DLSS_OK);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_count(OpRelease, KindAny) == 0);
}
static void tag_failure_before_evaluate_needs_no_sdk_free(int kind) {
    Fixture fixture; uint64_t handle = 0;
    CHECK(create(kind, handle) == VKB_DLSS_OK);
    vendor_test_fail(OpTag, 1, 0); CHECK(evaluate(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_count(OpEvaluate, KindAny) == 0);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_count(OpRelease, KindAny) == 0);
}
static void partial_evaluate_requires_acknowledged_free(int kind) {
    Fixture fixture; uint64_t handle = 0;
    CHECK(create(kind, handle) == VKB_DLSS_OK);
    vendor_test_fail(OpEvaluate, 1, 1); CHECK(evaluate(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 1);
    vendor_test_fail(OpRelease, 1, 0); CHECK(release(kind, handle) != VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 1);
    CHECK(release(kind, handle) == VKB_DLSS_OK);
    CHECK(vendor_test_live_features(kind) == 0);
    CHECK(vendor_test_last_feature() == (kind == KindSr ? 0 : 1001));
    CHECK(vendor_test_last_viewport() == 0);
}
#endif
int main() {
    try {
        for (int kind : {KindSr, KindRr}) {
            successful_lifecycle(kind); ++cases;
            release_failure_is_retryable_and_release_only(kind); ++cases;
#ifdef VENDOR_TEST_ngx
            for (int operation : {OpAllocate, OpCreate}) for (bool partial : {false, true}) {
                failed_create_keeps_partial_owner(kind, operation, partial); ++cases;
            }
            parameter_destroy_failure_does_not_release_sdk_handle_twice(kind); ++cases;
#else
            options_failure_returns_a_release_only_owner(kind); ++cases;
            options_only_owner_needs_no_sdk_free(kind); ++cases;
            tag_failure_before_evaluate_needs_no_sdk_free(kind); ++cases;
            partial_evaluate_requires_acknowledged_free(kind); ++cases;
#endif
        }
        sr_release_does_not_release_rr_or_shared_queries(); ++cases;
        checked_shutdown_failure_retains_ownership(); ++cases;
#ifdef VENDOR_TEST_ngx
        sdk_shutdown_failure_retains_device_for_retry(); ++cases;
        capability_refresh_waits_for_destruction_acknowledgement(); ++cases;
        partial_capability_refresh_and_init_remain_owned(); ++cases;
        capability_shutdown_failure_keeps_parameters_for_retry(); ++cases;
#endif
        clear_faults(); CHECK(vkb_dlss_shutdown_checked() == VKB_DLSS_OK);
        CHECK(vendor_test_live_features(KindAny) == 0); CHECK(vendor_test_live_parameters() == 0);
        std::printf("actual-wrapper resource contracts: %d cases, %d checks passed\n", cases, checks); return 0;
    } catch (const std::exception& error) { std::fprintf(stderr, "FAILED: %s\n", error.what()); return 1; }
}
