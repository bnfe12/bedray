package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Discovers SDK providers on the active Vulkan device and orders context setup attempts. */
final class FsrProviders {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FsrProviders");
    private static final Map<Path, FfxNative> LIBRARIES = new HashMap<>();
    private static final Map<FfxNative, List<FfxNative.ProviderVersion>> VERSIONS = new HashMap<>();
    private static VulkanDevice queriedDevice;
    private static boolean modernSupported;
    private static boolean legacySupported;
    private static boolean mlDeviceEnabled;

    private static final List<FsrVersion> ANALYTICAL = List.of(FsrVersion.FSR_3_1, FsrVersion.FSR_2);

    private FsrProviders() {}

    private record Sdk(FfxNative library, List<FfxNative.ProviderVersion> eligible, boolean mlEnabled) {}

    record Candidate(FfxNative library, FfxNative.ProviderVersion pinned, boolean allowMl) {
        long versionId() {
            return pinned == null ? 0 : pinned.id();
        }

        boolean accepts(FfxNative.ProviderVersion actual) {
            if (pinned != null) return pinned.id() == actual.id();
            return actual.id() != 0 && (allowMl || analytical(actual));
        }

        @Override
        public String toString() {
            return pinned == null ? "SDK default" : pinned.toString();
        }
    }

    static FfxNative.VulkanHandles handles(VulkanDevice device) {
        var vk = device.vkDevice();
        return new FfxNative.VulkanHandles(vk.address(), vk.getPhysicalDevice().address(), vk.getCapabilities().vkGetDeviceProcAddr);
    }

    static List<FfxNative.ProviderVersion> available(VulkanDevice device) {
        return discover(device, true).stream().flatMap(sdk -> sdk.eligible().stream()).distinct().toList();
    }

    static List<Candidate> candidates(VulkanDevice device, FsrVersion requested) {
        List<Candidate> candidates = new ArrayList<>();
        for (Sdk sdk : discover(device, requested != FsrVersion.FSR_4)) {
            if (requested == FsrVersion.AUTO) {
                // Leave the version unpinned when AMD can choose ML, allowing future driver providers.
                if (sdk.mlEnabled()) {
                    candidates.add(new Candidate(sdk.library(), null, true));
                }
                for (var family : ANALYTICAL) {
                    addPinned(candidates, sdk, family);
                }
            } else {
                addPinned(candidates, sdk, requested);
            }
        }
        return candidates;
    }

    private static void addPinned(List<Candidate> candidates, Sdk sdk, FsrVersion family) {
        family.pick(sdk.eligible()).ifPresent(v -> candidates.add(new Candidate(sdk.library(), v, family == FsrVersion.FSR_4)));
    }

    private static List<Sdk> discover(VulkanDevice device, boolean includeLegacy) {
        if (queriedDevice != device) {
            queriedDevice = device;
            VERSIONS.clear();
            modernSupported = FsrDeviceRequirements.supportsSdk(device, true);
            legacySupported = FsrDeviceRequirements.supportsSdk(device, false);
            mlDeviceEnabled = modernSupported && FsrDeviceRequirements.mlDeviceEnabled(device);
            LOGGER.info("[VKB-FSR] device_support sdk_2_4={} sdk_1_1_4={} ml_features={}",
                modernSupported, legacySupported, mlDeviceEnabled);
        }
        var handles = handles(device);
        if (handles.getDeviceProcAddr() == 0) return List.of();
        List<Sdk> results = new ArrayList<>();
        var modern = FsrLibrary.modernLibrary();
        if (modernSupported && FsrDeviceRequirements.modernRegistered() && modern.usable()) {
            discover(results, modern, handles, mlDeviceEnabled);
        }
        if (includeLegacy && legacySupported) {
            discover(results, FsrLibrary.library(), handles, false);
        }
        return results;
    }

    private static void discover(List<Sdk> results, FsrLibrary.Library library, FfxNative.VulkanHandles handles,
                                 boolean mlDeviceEnabled) {
        if (!library.usable()) return;
        try {
            FfxNative sdk = LIBRARIES.computeIfAbsent(library.dll(), FfxNative::load);
            var reported = VERSIONS.computeIfAbsent(sdk, lib -> enumerate(lib, handles));
            var eligible = reported.stream().filter(v -> v.id() != 0 && (mlDeviceEnabled || analytical(v))).toList();
            boolean mlAvailable = mlDeviceEnabled && eligible.stream().anyMatch(v -> !analytical(v));
            results.add(new Sdk(sdk, eligible, mlAvailable));
        } catch (FsrException failure) {
            LOGGER.warn("[VKB-FSR] sdk_probe_failed dll={} stage={} reason={}",
                library.dll().getFileName(), failure.stage(), LogPaths.show(failure.getMessage()));
        }
    }

    private static boolean analytical(FfxNative.ProviderVersion version) {
        return ANALYTICAL.stream().anyMatch(family -> family.matches(version.name()));
    }

    private static List<FfxNative.ProviderVersion> enumerate(FfxNative lib, FfxNative.VulkanHandles handles) {
        var query = lib.upscalerVersions(handles);
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=versions dll={} result={} available={}",
            lib.libraryPath().getFileName(), query.result(), query.value());
        return query.result() == FfxNative.RETURN_OK ? query.value() : List.of();
    }
}
