package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;

/** Negotiates the public Vulkan host requirements for the optional NGX Neural Rendering stage. */
final class DlssNrDeviceRequirements {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssNr");
    private static final String PROPERTY = "bedray.dlss5";
    private static final String MODEL = "nvngx_dlssnr.dll";

    private DlssNrDeviceRequirements() {}

    static void registerInstanceExtensions(DeviceFeatureRegistry registry, DlssAvailability.Library library) {
        if (!Boolean.getBoolean(PROPERTY)) return;
        if (!hasDirectRuntime(library.featureDir())) return;
        try (DlssNative nativeLib = DlssNative.load(library.wrapperDll())) {
            DlssNrNative nr = findExtension(nativeLib);
            if (nr == null) return;
            DlssNative.NameList query = nr.requiredInstanceExtensions(library.featureDir(), DlssRuntime.gameLogDir());
            if (!query.status().ok()) {
                LOGGER.warn("[VKB-DLSS-NR] stage=instance_request result={}", LogPaths.show(query.status().toString()));
                return;
            }
            registry.requestInstanceExtensions("VKB DLSS Neural Rendering instance", Set.copyOf(query.names()));
            LOGGER.info("[VKB-DLSS-NR] stage=instance_request extensions={}", query.names());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("[VKB-DLSS-NR] stage=instance_request result=failed\n{}", LogPaths.stack(failure));
        }
    }

    static DlssNrNative verifyEnabled(DlssNative nativeLib, Path featureDir, Path logDir, VulkanDevice device) {
        if (!Boolean.getBoolean(PROPERTY) || !hasDirectRuntime(featureDir)) return null;
        try {
            DlssNrNative nr = findExtension(nativeLib);
            if (nr == null) return null;
            if (!check("instance_extensions", nr.requiredInstanceExtensions(featureDir, logDir),
                device.instance().getEnabledExtensions()::contains)) return null;
            long instance = device.instance().vkInstance().address();
            long physicalDevice = device.vkDevice().getPhysicalDevice().address();
            Set<String> enabled = device.getDeviceInfo().underlyingExtensions();
            if (!check("device_extensions", nr.requiredDeviceExtensions(instance, physicalDevice, featureDir, logDir),
                name -> enabled.contains(name + " (D)"))) return null;
            LOGGER.info("[VKB-DLSS-NR] stage=preflight result=vulkan_extensions_enabled");
            return nr;
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("[VKB-DLSS-NR] stage=preflight result=failed\n{}", LogPaths.stack(failure));
            return null;
        }
    }

    private static boolean hasDirectRuntime(Path featureDir) {
        if (StreamlineBootstrap.dlssViaStreamline()) {
            LOGGER.info("[VKB-DLSS-NR] stage=preflight result=streamline_owns_ngx");
            return false;
        }
        if (!Files.isRegularFile(featureDir.resolve(MODEL))) {
            LOGGER.info("[VKB-DLSS-NR] stage=preflight result=model_missing file={}", MODEL);
            return false;
        }
        return true;
    }

    private static DlssNrNative findExtension(DlssNative nativeLib) {
        DlssNrNative nr = nativeLib.neuralRendering();
        if (nr == null) LOGGER.info("[VKB-DLSS-NR] stage=preflight result=wrapper_contract_unavailable");
        return nr;
    }

    private static boolean check(String stage, DlssNative.NameList query, Predicate<String> isEnabled) {
        if (!query.status().ok()) {
            LOGGER.warn("[VKB-DLSS-NR] stage={} result={}", stage, LogPaths.show(query.status().toString()));
            return false;
        }
        var missing = query.names().stream().filter(isEnabled.negate()).toList();
        LOGGER.info("[VKB-DLSS-NR] stage={} required={} missing={}", stage, query.names(), missing);
        return missing.isEmpty();
    }
}
