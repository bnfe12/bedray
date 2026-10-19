package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.LibraryStatus;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java-level checks (L2) of the DLSS backend: modes, struct layouts, library/GPU verdicts, backend selection, config.
 * No GPU, no game. {@link #wrapperBindingIfPresent()} additionally loads the real {@code vkb_dlss.dll} when a path is given
 * ({@code -Dvkbridge.test.dlssWrapper}) and checks that every export of the FFM binding resolves and the API versions match;
 * it does NOT call NGX and is not evidence that DLSS works in game.
 */
public final class DlssChecks {
    private DlssChecks() {}

    public static void runAll() throws Exception {
        qualityModes();
        layouts();
        availability();
        backendSelection();
        deviceRequirement();
        configAndEarlyRead();
        jitterSigns();
        runtimeInitializingLifecycle();
        wrapperBindingIfPresent();
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void qualityModes() {
        // numbers of vkb_dlss.h (VkbDlssQualityMode)
        check(DlssQuality.ULTRA_PERFORMANCE.nativeValue() == 0, "UP=0");
        check(DlssQuality.PERFORMANCE.nativeValue() == 1, "Performance=1 (MaxPerf)");
        check(DlssQuality.BALANCED.nativeValue() == 2, "Balanced=2");
        check(DlssQuality.QUALITY.nativeValue() == 3, "Quality=3 (MaxQuality)");
        check(DlssQuality.DLAA.nativeValue() == 5, "DLAA=5");
        check(DlssQuality.values().length == 5, "Ultra Quality is not offered (NGX returns 0x0 for it on 310.9.0)");
        // exactly the modes the UI lists
        for (UpscalerMode m : UpscalerModes.modesFor(UpscalerBackend.DLSS)) {
            if (m == UpscalerMode.CUSTOM) {
                continue; // mapped by UpscalerRequest.build, never a PerfQuality value
            }
            check(DlssQuality.fromMode(m).mode() == m, "mode round trip " + m);
        }
        check(UpscalerModes.modesFor(UpscalerBackend.DLSS).size() == DlssQuality.values().length + 1, "UI list == enum + CUSTOM");
        check(DlssQuality.parse(" ultra-performance ") == DlssQuality.ULTRA_PERFORMANCE, "parse normalises");
        check(DlssQuality.parse("dlaa") == DlssQuality.DLAA, "parse dlaa");
        check(throwsIae(() -> DlssQuality.parse("ULTRA_QUALITY")), "unknown mode throws, no default");
        check(throwsIae(() -> DlssQuality.parse(null)), "null throws");
        check(throwsIae(() -> DlssQuality.fromMode(UpscalerMode.NATIVE_AA)), "FSR-only mode throws");
        // no invented scale table
        check(throwsIae(() -> UpscalerModes.ratio(UpscalerBackend.DLSS, UpscalerMode.QUALITY)), "DLSS ratio is not hard-coded");
        check(UpscalerModes.source(UpscalerBackend.DLSS, UpscalerMode.QUALITY) == UpscalerModes.Source.RUNTIME_QUERY, "DLSS source is the NGX query");
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void layouts() {
        DlssLayouts.verify();
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "color") == 8, "color @8");
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "output") == 64, "output @64");
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "depth") == 120, "depth @120");
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "motion_vectors") == 176, "mv @176");
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "exposure") == 232, "exposure @232");
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "jitter_x") == 288, "jitter_x @288");
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "render_subrect_height") == 320, "subrect_h @320");
        check(DlssLayouts.offset(DlssLayouts.EVAL_INFO, "reserved") == 324, "reserved @324");
        check(DlssLayouts.offset(DlssLayouts.IMAGE, "view") == 8 && DlssLayouts.offset(DlssLayouts.IMAGE, "read_write") == 48, "image offsets");
        check(DlssLayouts.offset(DlssLayouts.CAPS, "ngx_result_init") == 24, "caps offsets (read by DlssNative.init)");
        check(DlssLayouts.offset(DlssLayouts.OPTIMAL_SETTINGS, "sharpness") == 28, "optimal settings offsets (read by DlssNative)");
        check(DlssLayouts.offset(DlssLayouts.CREATE_INFO, "flags") == 24, "create info offsets (written by DlssNative)");
        // feature flags of vkb_dlss.h
        check(DlssRuntime.FEATURE_FLAGS == (DlssLayouts.FLAG_MV_LOW_RES | DlssLayouts.FLAG_DEPTH_INVERTED | DlssLayouts.FLAG_AUTO_EXPOSURE),
            "flags: low-res MV, reverse-Z, auto exposure, LDR (no IS_HDR), jitter-free MV (no MV_JITTERED)");
        check((DlssRuntime.FEATURE_FLAGS & (DlssLayouts.FLAG_IS_HDR | DlssLayouts.FLAG_MV_JITTERED)) == 0, "no HDR / jittered-MV flags");
    }

    private static final LibraryDescriptor DESCRIPTOR = new LibraryDescriptor("dlss", "NVIDIA DLSS", "NVIDIA",
        List.of(DlssAvailability.NGX_DLL), List.of(DlssAvailability.WRAPPER_DLL), "NVIDIA RTX SDK License", "https://example.invalid/",
        Map.of(), null, LibraryDescriptor.DEFAULT_MAX_FILE_BYTES);

    private static LibraryResolution missing() {
        return new LibraryResolution(DESCRIPTOR, LibraryStatus.missing("nvngx_dlss.dll not found"), Map.of(), Map.of(), false);
    }

    private static LibraryResolution present(boolean withWrapper, boolean legacy) {
        Path dir = Path.of("natives-test");
        Map<String, Path> files = withWrapper
            ? Map.of(DlssAvailability.NGX_DLL, dir.resolve(DlssAvailability.NGX_DLL), DlssAvailability.WRAPPER_DLL, dir.resolve(DlssAvailability.WRAPPER_DLL))
            : Map.of(DlssAvailability.NGX_DLL, dir.resolve(DlssAvailability.NGX_DLL));
        return new LibraryResolution(DESCRIPTOR, LibraryStatus.presentUnverified("ok"), files, Map.of(), legacy);
    }

    private static void availability() {
        try {
            DlssAvailability.setResolverForTesting(DlssChecks::missing);
            DlssAvailability.Library lib = DlssAvailability.library();
            check(!lib.usable() && lib.reason().contains("MISSING"), "missing nvngx_dlss.dll: unusable with the state in the reason: " + lib.reason());
            check(lib.wrapperDll() == null && lib.featureDir() == null, "no paths for an unusable library");

            DlssAvailability.setResolverForTesting(() -> present(false, false));
            lib = DlssAvailability.library();
            check(!lib.usable() && lib.reason().contains(DlssAvailability.WRAPPER_DLL), "nvngx without vkb_dlss.dll: unusable, names the wrapper: " + lib.reason());

            DlssAvailability.setResolverForTesting(() -> present(true, false));
            lib = DlssAvailability.library();
            check(lib.usable(), "both libraries: usable");
            check(lib.wrapperDll().equals(Path.of("natives-test").resolve(DlssAvailability.WRAPPER_DLL)), "wrapper path comes from resolve()");
            check(lib.featureDir().equals(Path.of("natives-test")), "feature dir is the folder of nvngx_dlss.dll");

            DlssAvailability.setResolverForTesting(() -> present(true, true));
            check(DlssAvailability.library().reason().contains("legacy"), "legacy folder is reported");

            // the default resolver outside the game: reported as unusable, not thrown and not "present"
            DlssAvailability.setResolverForTesting(null);
            lib = DlssAvailability.library();
            check(!lib.usable() && lib.reason().contains("unavailable"), "outside the game Bedray Core manager is unavailable: " + lib.reason());

            // GPU verdicts
            DlssAvailability.setResolverForTesting(() -> present(true, false));
            DlssAvailability.setNvidiaForTesting(() -> null);
            check(DlssAvailability.isUsable(), "device unknown is not a veto");
            DlssAvailability.setNvidiaForTesting(() -> Boolean.TRUE);
            check(DlssAvailability.isUsable(), "NVIDIA GPU");
            DlssAvailability.setNvidiaForTesting(() -> Boolean.FALSE);
            check(!DlssAvailability.isUsable() && DlssAvailability.gpuRulesOutDlss(), "non-NVIDIA GPU rules DLSS out");
        } finally {
            DlssAvailability.setResolverForTesting(null);
            DlssAvailability.setNvidiaForTesting(null);
        }
    }

    private static void backendSelection() throws Exception {
        Path dir = Files.createTempDirectory("vkb_dlss_sel");
        try {
            DlssAvailability.setResolverForTesting(DlssChecks::missing);
            check(!UpscalerBackend.DLSS.isImplemented(), "DLSS without libraries is not implemented-for-use");
            check(UpscalerSelection.status(UpscalerBackend.DLSS, dir) == UpscalerSelection.Status.LIBRARY_MISSING, "status LIBRARY_MISSING");
            check(UpscalerSelection.resolve(UpscalerBackend.DLSS, dir) == UpscalerBackend.BILINEAR, "falls back to BILINEAR");
            VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;
            VulkanRtxConfig.applyKeyValue("active_backend", "DLSS");
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.BILINEAR, "config DLSS without libraries -> BILINEAR");

            DlssAvailability.setResolverForTesting(() -> present(true, false));
            DlssAvailability.setNvidiaForTesting(() -> Boolean.TRUE);
            check(UpscalerBackend.DLSS.isImplemented(), "DLSS with libraries on NVIDIA");
            check(UpscalerSelection.status(UpscalerBackend.DLSS, dir) == UpscalerSelection.Status.AVAILABLE, "status AVAILABLE");
            check(UpscalerSelection.resolve(UpscalerBackend.DLSS, dir) == UpscalerBackend.DLSS, "resolve keeps DLSS");
            VulkanRtxConfig.applyKeyValue("active_backend", "DLSS");
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.DLSS, "config DLSS with libraries is accepted");

            DlssAvailability.setNvidiaForTesting(() -> Boolean.FALSE);
            check(!UpscalerBackend.DLSS.isImplemented(), "non-NVIDIA: not implemented-for-use");
            check(UpscalerSelection.status(UpscalerBackend.DLSS, dir) == UpscalerSelection.Status.UNSUPPORTED_GPU, "status UNSUPPORTED_GPU");
            check(UpscalerSelection.resolve(UpscalerBackend.DLSS, dir) == UpscalerBackend.BILINEAR, "non-NVIDIA falls back");
        } finally {
            VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;
            DlssAvailability.setResolverForTesting(null);
            DlssAvailability.setNvidiaForTesting(null);
            Files.deleteIfExists(dir);
        }
    }

    private static void deviceRequirement() {
        DeviceRequirement r = DlssDeviceRequirements.requirement();
        check(r.id().equals("VKB DLSS"), "id");
        check(r.deviceExtensions().equals(Set.of("VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_buffer_device_address", "VK_KHR_push_descriptor")),
            "extensions of the NGX probe (RTX 5060 Ti, DLSS 310.9.1)");
        check(r.optional(), "optional: an unsupported device is created without it");
        check(r.features().size() == 1 && r.features().get(0).field().equals("bufferDeviceAddress"), "bufferDeviceAddress feature");
        check(r.minInstanceApiVersion() == 0x401000, "Vulkan 1.1 instance");
        check(DlssDeviceRequirements.INSTANCE_EXTENSIONS.equals(Set.of("VK_KHR_get_physical_device_properties2")),
            "instance extension NGX asks for is requested explicitly, not assumed from core promotion");
        io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl reg = new io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl();
        reg.requestInstanceExtensions(DlssDeviceRequirements.INSTANCE_REQUEST_ID, DlssDeviceRequirements.INSTANCE_EXTENSIONS);
        check(reg.requestedInstanceExtensions().equals(DlssDeviceRequirements.INSTANCE_EXTENSIONS), "registry holds the instance request");
        providerDecisions();
    }

    /** Preregistration (docs/experiments/runtime-switch.md): usable libraries register DLSS even when it is not requested. */
    private static void providerDecisions() {
        io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.resetForTests();
        try {
            DlssAvailability.setResolverForTesting(() -> present(true, false));
            io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl reg = new io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl();
            DlssDeviceRequirements.register(reg, false, true);
            check(reg.snapshot().size() == 1 && reg.snapshot().get(0).id().equals(DlssDeviceRequirements.ID), "usable library, not requested, prereg: registered");
            check(reg.requestedInstanceExtensions().equals(DlssDeviceRequirements.INSTANCE_EXTENSIONS), "instance extension requested with it");
            check(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.isRegistered(UpscalerBackend.DLSS), "recorded as registered");

            io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.resetForTests();
            reg = new io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl();
            DlssDeviceRequirements.register(reg, false, false);
            check(reg.snapshot().isEmpty() && reg.requestedInstanceExtensions().isEmpty(), "emergency switch: nothing registered, instance untouched");
            check(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.requiresRestart(UpscalerBackend.DLSS), "then DLSS needs a restart");

            io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.resetForTests();
            DlssAvailability.setResolverForTesting(DlssChecks::missing);
            reg = new io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl();
            DlssDeviceRequirements.register(reg, false, true);
            check(reg.snapshot().isEmpty() && reg.requestedInstanceExtensions().isEmpty(), "library missing: nothing registered");
            check(!io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.isRegistered(UpscalerBackend.DLSS), "recorded as not registered");
        } finally {
            DlssAvailability.setResolverForTesting(null);
            io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.resetForTests();
        }
    }

    private static void configAndEarlyRead() throws Exception {
        VulkanRtxConfig.applyKeyValue("dlss_mode", "PERFORMANCE");
        check(VulkanRtxConfig.dlssMode == UpscalerMode.PERFORMANCE, "dlss_mode parsed");
        VulkanRtxConfig.applyKeyValue("dlss_mode", "ULTRA_QUALITY_PLUS");
        check(VulkanRtxConfig.dlssMode == UpscalerMode.PERFORMANCE, "a mode DLSS does not offer is rejected, previous value kept");
        VulkanRtxConfig.dlssMode = UpscalerModes.defaultMode(UpscalerBackend.DLSS);

        Path dir = Files.createTempDirectory("vkb_dlss_cfg");
        Path toml = dir.resolve("bedray.toml");
        try {
            Files.writeString(toml, "[super_resolution]\nactive_backend = \"DLSS\"\ndlss_mode = \"BALANCED\"\n", StandardCharsets.UTF_8);
            check(UpscalerSelection.earlyDlssQuality(toml) == DlssQuality.BALANCED, "early read: DLSS BALANCED");
            Files.writeString(toml, "active_backend = \"DLSS\"\n", StandardCharsets.UTF_8);
            check(UpscalerSelection.earlyDlssQuality(toml) == DlssQuality.QUALITY, "early read: default mode");
            Files.writeString(toml, "active_backend = \"DLSS\"\ndlss_mode = \"NATIVE_AA\"\n", StandardCharsets.UTF_8);
            check(UpscalerSelection.earlyDlssQuality(toml) == null, "early read: not offered mode -> no request");
            Files.writeString(toml, "active_backend = \"XESS\"\ndlss_mode = \"BALANCED\"\n", StandardCharsets.UTF_8);
            check(UpscalerSelection.earlyDlssQuality(toml) == null, "early read: other backend -> no request");
            check(UpscalerSelection.earlyDlssQuality(dir.resolve("missing.toml")) == null, "early read: no file");

            // property request
            DlssConfig.resetForTests();
            System.setProperty(DlssConfig.PROPERTY, "dlaa");
            check(DlssConfig.requestedQuality() == DlssQuality.DLAA, "-Dvkbridge.dlss=dlaa");
            DlssConfig.resetForTests();
            System.setProperty(DlssConfig.PROPERTY, "bogus");
            check(DlssConfig.requestedQuality() == null, "invalid property -> no request (logged as FAILED)");
        } finally {
            System.clearProperty(DlssConfig.PROPERTY);
            DlssConfig.resetForTests();
            Files.deleteIfExists(toml);
            Files.deleteIfExists(dir);
        }
    }

    private static void jitterSigns() {
        check(DlssConfig.jitterSignX() == 1.0f && DlssConfig.jitterSignY() == 1.0f, "default signs");
        System.setProperty(DlssConfig.JITTER_SIGN_X_PROPERTY, "-1");
        try {
            check(DlssConfig.jitterSignX() == -1.0f, "sign property");
            System.setProperty(DlssConfig.JITTER_SIGN_X_PROPERTY, "2");
            boolean threw = false;
            try {
                DlssConfig.jitterSignX();
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "sign other than +-1 throws");
        } finally {
            System.clearProperty(DlssConfig.JITTER_SIGN_X_PROPERTY);
        }
    }

    private static void runtimeInitializingLifecycle() {
        DlssRuntime.resetForTests();
        check(DlssRuntime.state() == DlssRuntime.State.IDLE, "reset leaves state IDLE");
        check(DlssRuntime.MODEL_LOAD_TIMEOUT_NANOS == 25_000_000_000L, "timeout is 25s");
        check(DlssRuntime.State.valueOf("INITIALIZING") == DlssRuntime.State.INITIALIZING, "State.INITIALIZING exists");
        check(!DlssRuntime.isReady(), "INITIALIZING is not ready");
        check(!DlssRuntime.isActive(), "INITIALIZING is not active");
        check(!DlssRuntime.isUnsupportedOnThisSystem(), "not unsupported yet");
        DlssRuntime.cleanup();
        check(DlssRuntime.state() == DlssRuntime.State.IDLE, "cleanup returns to IDLE");
    }

    /** Loads the real wrapper when {@code -Dvkbridge.test.dlssWrapper=<path to vkb_dlss.dll>} is set; otherwise says so and returns. */
    private static void wrapperBindingIfPresent() {
        String path = System.getProperty("vkbridge.test.dlssWrapper");
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            System.out.println("    (DLSS wrapper binding check SKIPPED: no vkb_dlss.dll given via -Dvkbridge.test.dlssWrapper)");
            return;
        }
        try (DlssNative lib = DlssNative.load(Path.of(path))) {
            check((lib.apiVersion() >>> 16) == DlssNative.EXPECTED_API_MAJOR, "wrapper API major");
            check(lib.ngxApiVersion() != 0, "NGX API version is reported by the DLL");
            System.out.println("    (DLSS wrapper binding OK: api=0x" + Integer.toHexString(lib.apiVersion())
                + " ngx_api=0x" + Integer.toHexString(lib.ngxApiVersion()) + " " + path + ")");
        }
    }
}
