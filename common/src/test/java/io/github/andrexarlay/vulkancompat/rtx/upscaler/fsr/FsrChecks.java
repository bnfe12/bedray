package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.LibraryStatus;
import io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import org.joml.Matrix4f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Java-level checks (L2) of the FSR integration: ffx-api struct layouts, quality modes, version selection, the official-DLL
 * check, device requirements, camera derivation and the early config parse. No DLL is loaded and no GPU is used: this is NOT
 * evidence that FSR works in game (that is {@code [VKB-FSR] fsr=dispatch ok} in latest.log plus a screenshot).
 */
public final class FsrChecks {
    private FsrChecks() {}

    public static void runAll() throws Exception {
        layouts();
        qualityModes();
        versions();
        officialLibraryCheck();
        deviceRequirements();
        camera();
        earlyConfig();
        properties();
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void layouts() {
        FfxLayouts.verify(); // throws on any offset/size different from the C layout
        check(FfxLayouts.DISPATCH_UPSCALE.byteSize() == 432, "sizeof(ffxDispatchDescUpscale) == 432");
        check(FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "depth") == 72, "dispatch.depth at 72");
        check(FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "motionVectors") == 120, "dispatch.motionVectors at 120");
        check(FfxLayouts.offset(FfxLayouts.DISPATCH_UPSCALE, "renderSize") == 376, "dispatch.renderSize at 376");
        check(FfxLayouts.offset(FfxLayouts.CREATE_UPSCALE, "maxRenderSize") == 20, "create.maxRenderSize at 20");
        check(FfxLayouts.offset(FfxLayouts.QUERY_GET_VERSIONS, "versionNames") == 48, "getVersions.versionNames at 48");
        // FfxApiSurfaceFormat order (ffx_api_types.h l.29-60)
        check(FfxNative.FORMAT_R8G8B8A8_UNORM == 10 && FfxNative.FORMAT_R16G16_FLOAT == 18 && FfxNative.FORMAT_R32_FLOAT == 28,
            "surface format numbers");
        check(FfxNative.DESC_CREATE_UPSCALE == 0x10000L && FfxNative.DESC_DISPATCH_UPSCALE == 0x10001L
            && FfxNative.DESC_CREATE_BACKEND_VK == 3L && FfxNative.DESC_OVERRIDE_VERSION == 5L, "descriptor type ids");
    }

    private static void qualityModes() {
        check(FsrQuality.values().length == 5, "five FSR 3.1 quality modes");
        check(FsrQuality.NATIVE_AA.nativeValue() == 0 && FsrQuality.ULTRA_PERFORMANCE.nativeValue() == 4, "enum values 0..4");
        double[] ratios = {1.0, 1.5, 1.7, 2.0, 3.0};
        for (int i = 0; i < ratios.length; i++) {
            check(Math.abs(FsrQuality.values()[i].ratio() - ratios[i]) < 1e-9, FsrQuality.values()[i] + " ratio");
            check(Math.abs(UpscalerModes.ratio(UpscalerBackend.FSR, FsrQuality.values()[i].mode()) - ratios[i]) < 1e-9, "UI ratio matches");
        }
        check(FsrQuality.fromMode(UpscalerMode.BALANCED) == FsrQuality.BALANCED, "fromMode");
        check(throwsIae(() -> FsrQuality.fromMode(UpscalerMode.ULTRA_QUALITY)), "no Ultra Quality in FSR 3.1");
        check(throwsIae(() -> FsrQuality.fromMode(UpscalerMode.DLAA)), "DLAA is DLSS only");
        check(FsrQuality.parse("ultra-performance") == FsrQuality.ULTRA_PERFORMANCE, "parse tolerant");
        check(throwsIae(() -> FsrQuality.parse("ultra_quality")), "parse rejects unknown");
        check(UpscalerModes.defaultMode(UpscalerBackend.FSR) == UpscalerMode.QUALITY, "default FSR mode QUALITY");
    }

    private static void versions() {
        // ids as the providers build them (ffx_provider_fsr3upscale.cpp GetId: 0xF5A5CA1E << 32 | version)
        FfxNative.ProviderVersion v31 = new FfxNative.ProviderVersion(0xF5A5CA1E00000000L | 0x00C01004L, "3.1.4");
        FfxNative.ProviderVersion v2 = new FfxNative.ProviderVersion(0xF5A5CA1E00000000L | 0x00803002L, "2.3.2");
        List<FfxNative.ProviderVersion> both = List.of(v31, v2);
        check(FsrVersion.AUTO.pick(both).isEmpty(), "AUTO: no override");
        check(FsrVersion.FSR_3_1.pick(both).orElseThrow() == v31, "3.1 picks 3.1.4");
        check(FsrVersion.FSR_2.pick(both).orElseThrow() == v2, "2 picks 2.3.2");
        check(FsrVersion.FSR_2.pick(List.of(v31)).isEmpty() && !FsrVersion.FSR_2.isAvailableIn(List.of(v31)), "FSR 2 missing in a 3.1-only DLL");
        check(FsrVersion.AUTO.isAvailableIn(List.of()), "AUTO always available");
        check(!FsrVersion.FSR_3_1.matches("3.0.1") && !FsrVersion.FSR_3_1.matches("4.0.2") && !FsrVersion.FSR_2.matches("20.1"),
            "prefix match is exact (3.0 / 4.x are not 3.1)");
        check(FsrVersion.parse("3.1") == FsrVersion.FSR_3_1 && FsrVersion.parse("2") == FsrVersion.FSR_2
            && FsrVersion.parse("auto") == FsrVersion.AUTO, "parse");
        check(throwsIae(() -> FsrVersion.parse("4")), "FSR 4 is not a Vulkan option");
        check(new FsrRequest(FsrQuality.QUALITY, FsrVersion.AUTO).equals(new FsrRequest(FsrQuality.QUALITY, FsrVersion.AUTO)), "request equality");
        check(!new FsrRequest(FsrQuality.QUALITY, FsrVersion.AUTO).equals(new FsrRequest(FsrQuality.QUALITY, FsrVersion.FSR_2)), "version is part of it");
    }

    private static LibraryResolution resolution(Path dll) {
        if (dll == null) {
            return new LibraryResolution(LibraryDescriptor.FSR, LibraryStatus.missing("amd_fidelityfx_vk.dll not found"), Map.of(), Map.of(), false);
        }
        return new LibraryResolution(LibraryDescriptor.FSR, LibraryStatus.presentUnverified("ok"),
            Map.of(FsrLibrary.DLL, dll), Map.of(FsrLibrary.DLL, "1.0.1.41314"), true);
    }

    private static void officialLibraryCheck() throws Exception {
        Path dir = Files.createTempDirectory("vkb_fsr_lib");
        Path fake = dir.resolve(FsrLibrary.DLL);
        Files.writeString(fake, "not the AMD dll", StandardCharsets.UTF_8);
        try {
            FsrLibrary.setResolverForTesting(() -> resolution(null));
            check(!FsrLibrary.isUsable(), "missing file: unusable");

            FsrLibrary.setResolverForTesting(() -> resolution(fake));
            FsrLibrary.Library lib = FsrLibrary.library();
            check(!lib.usable() && lib.sha256() != null && lib.reason().contains("not a known official"),
                "a file with an unknown SHA-256 is refused: " + lib.reason());

            System.setProperty(FsrLibrary.ALLOW_UNKNOWN_PROPERTY, "true");
            FsrLibrary.invalidate();
            lib = FsrLibrary.library();
            check(lib.usable() && lib.release() == null && lib.reason().contains("UNKNOWN"), "explicit override accepts it, marked UNKNOWN");
            System.clearProperty(FsrLibrary.ALLOW_UNKNOWN_PROPERTY);

            // The real official DLL, when it exists on this machine (SDK v1.1.4 PrebuiltSignedDLL): hash must be in the list.
            String officialProp = System.getProperty("vkrtx.test.fsrOfficialDll");
            Path official = officialProp == null ? Path.of("official-dll-not-configured") : Path.of(officialProp);
            if (Files.isRegularFile(official)) {
                FsrLibrary.setResolverForTesting(() -> resolution(official));
                lib = FsrLibrary.library();
                check(lib.usable() && lib.release() != null, "official SDK v1.1.4 DLL is accepted: " + lib.reason());
            } else {
                System.out.println("  (FsrChecks: official DLL not on this machine, positive hash check skipped)");
            }
            check(FsrLibrary.sha256(fake).length() == 64, "sha256 hex length");
        } finally {
            System.clearProperty(FsrLibrary.ALLOW_UNKNOWN_PROPERTY);
            FsrLibrary.setResolverForTesting(null);
            Files.deleteIfExists(fake);
            Files.deleteIfExists(dir);
        }
    }

    private static void deviceRequirements() {
        FsrLibrary.Library usable = new FsrLibrary.Library(true, "ok", Path.of("x.dll"), "00", "test", "1");
        FsrLibrary.Library unusable = new FsrLibrary.Library(false, "missing", null, null, null, null);
        try {
            UpscalerPreregistration.resetForTests();
            DeviceFeatureRegistryImpl reg = new DeviceFeatureRegistryImpl();
            FsrDeviceRequirements.register(reg, false, true, usable);
            check(reg.snapshot().size() == 4, "four optional groups registered");
            check(reg.snapshot().get(0).id().equals(FsrDeviceRequirements.ID) && reg.snapshot().get(1).id().equals(FsrDeviceRequirements.ID_FP16), "group ids");
            check(reg.snapshot().stream().allMatch(r -> r.optional() && r.minInstanceApiVersion() == 0), "optional, no instance API demand");
            check(reg.snapshot().get(0).deviceExtensions().equals(FsrDeviceRequirements.BASE_EXTENSIONS),
                "base group enables VK_KHR_get_memory_requirements2 + VK_KHR_dedicated_allocation (ffxCreateContext needs the KHR alias)");
            check(reg.snapshot().get(1).deviceExtensions().isEmpty(), "FP16 group: features only");
            check(reg.snapshot().get(1).features().stream().anyMatch(f -> f.field().equals("shaderFloat16")), "FP16 group has shaderFloat16");
            check(reg.snapshot().get(1).features().stream().anyMatch(f -> f.field().equals("shaderInt16")), "FP16 group has shaderInt16");
            check(reg.requestedInstanceExtensions().isEmpty(), "no instance extensions");
            check(UpscalerPreregistration.isRegistered(UpscalerBackend.FSR) && !UpscalerPreregistration.requiresRestart(UpscalerBackend.FSR), "recorded");

            reg = new DeviceFeatureRegistryImpl();
            FsrDeviceRequirements.register(reg, false, false, usable);
            check(reg.snapshot().isEmpty() && UpscalerPreregistration.requiresRestart(UpscalerBackend.FSR), "emergency switch: nothing, restart needed");

            reg = new DeviceFeatureRegistryImpl();
            FsrDeviceRequirements.register(reg, true, true, unusable);
            check(reg.snapshot().isEmpty() && !UpscalerPreregistration.isRegistered(UpscalerBackend.FSR), "unusable library: nothing registered");
        } finally {
            UpscalerPreregistration.resetForTests();
        }
    }

    private static void camera() {
        float fov = (float) Math.toRadians(70.0);
        // standard [0,1] depth: near 0.05 at depth 0, far 1000 at depth 1
        FsrCamera std = FsrCamera.fromProjection(new Matrix4f().perspective(fov, 16f / 9f, 0.05f, 1000f, true));
        check(std.derived() && Math.abs(std.near() - 0.05f) < 1e-4 && Math.abs(std.far() - 1000f) < 5f, "standard Z (float m22 precision limits far to ~0.5%): " + std);
        check(Math.abs(std.fovY() - fov) < 1e-4, "fov from m11");
        // reverse-Z (depth 1 at near): JOML with near/far swapped
        FsrCamera rev = FsrCamera.fromProjection(new Matrix4f().perspective(fov, 16f / 9f, 1000f, 0.05f, true));
        check(rev.derived() && Math.abs(rev.near() - 0.05f) < 1e-4 && Math.abs(rev.far() - 1000f) < 5f && !rev.infiniteFar(), "reverse Z: " + rev);
        // reverse-Z with infinite far plane
        FsrCamera inf = FsrCamera.fromProjection(new Matrix4f().perspective(fov, 16f / 9f, Float.POSITIVE_INFINITY, 0.05f, true));
        check(inf.derived() && inf.infiniteFar() && Math.abs(inf.near() - 0.05f) < 1e-4, "reverse Z infinite: " + inf);
        // y-flipped projection keeps the fov
        FsrCamera flipped = FsrCamera.fromProjection(new Matrix4f().scale(1f, -1f, 1f).perspective(fov, 16f / 9f, 1000f, 0.05f, true));
        check(Math.abs(flipped.fovY() - fov) < 1e-4, "y flip: " + flipped);
        check(FsrCamera.fromProjection(null) == FsrCamera.FALLBACK && !FsrCamera.FALLBACK.derived(), "no projection: fallback, marked");
        check(FsrCamera.fromProjection(new Matrix4f().ortho(-1, 1, -1, 1, 0.1f, 10f)) == FsrCamera.FALLBACK, "orthographic: fallback");
    }

    private static void earlyConfig() throws Exception {
        Path dir = Files.createTempDirectory("vkb_fsr_cfg");
        Path cfg = dir.resolve("bedray.toml");
        try {
            Files.writeString(cfg, "active_backend = \"FSR\"\nfsr_mode = \"PERFORMANCE\"\nfsr_version = \"FSR_2\"\n", StandardCharsets.UTF_8);
            FsrRequest r = UpscalerSelection.earlyFsrRequest(cfg);
            check(r != null && r.quality() == FsrQuality.PERFORMANCE && r.version() == FsrVersion.FSR_2, "early FSR request: " + r);
            Files.writeString(cfg, "active_backend = \"FSR\"\n", StandardCharsets.UTF_8);
            r = UpscalerSelection.earlyFsrRequest(cfg);
            check(r != null && r.quality() == FsrQuality.QUALITY && r.version() == FsrVersion.AUTO, "defaults");
            Files.writeString(cfg, "active_backend = \"FSR\"\nfsr_mode = \"ULTRA_QUALITY\"\n", StandardCharsets.UTF_8);
            check(UpscalerSelection.earlyFsrRequest(cfg) == null, "not offered mode: no request (logged FAILED)");
            Files.writeString(cfg, "active_backend = \"XESS\"\nfsr_mode = \"QUALITY\"\n", StandardCharsets.UTF_8);
            check(UpscalerSelection.earlyFsrRequest(cfg) == null, "other backend: no FSR request");
            check(UpscalerSelection.earlyFsrRequest(dir.resolve("missing.toml")) == null, "no file");
        } finally {
            Files.deleteIfExists(cfg);
            Files.deleteIfExists(dir);
        }
    }

    private static void properties() {
        try {
            System.setProperty(FsrConfig.SHARPNESS_PROPERTY, "0.4");
            check(Math.abs(FsrConfig.sharpness(0f) - 0.4f) < 1e-6, "sharpness property");
            System.setProperty(FsrConfig.SHARPNESS_PROPERTY, "1.5");
            check(throwsIae(() -> FsrConfig.sharpness(0f)), "sharpness out of range throws");
            System.clearProperty(FsrConfig.SHARPNESS_PROPERTY);
            check(FsrConfig.sharpness(0.25f) == 0.25f, "sharpness fallback");
            check(FsrConfig.jitterSignX() == -1f && FsrConfig.jitterSignY() == -1f, "default jitter signs");
            System.setProperty(FsrConfig.JITTER_SIGN_X_PROPERTY, "2");
            check(throwsIae(FsrConfig::jitterSignX), "invalid jitter sign throws");
            System.setProperty(FsrConfig.VERSION_PROPERTY, "2");
            check(FsrConfig.propertyVersion(FsrVersion.AUTO) == FsrVersion.FSR_2, "version property");
        } finally {
            System.clearProperty(FsrConfig.SHARPNESS_PROPERTY);
            System.clearProperty(FsrConfig.JITTER_SIGN_X_PROPERTY);
            System.clearProperty(FsrConfig.VERSION_PROPERTY);
        }
    }
}
