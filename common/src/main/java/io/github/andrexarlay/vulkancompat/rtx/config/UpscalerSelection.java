package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessQuality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Which upscaler backends can really be made active, and the pre-device read of the choice from the config file.
 * Must not touch Minecraft classes (used from the device-creation path and from unit tests).
 */
public final class UpscalerSelection {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/UpscalerSelection");

    public enum Status { AVAILABLE, NOT_IMPLEMENTED, LIBRARY_MISSING, UNSUPPORTED_GPU }

    private UpscalerSelection() {}

    /** Whether the backend can be the active one: code exists (isImplemented) and its native library is present. */
    public static Status status(UpscalerBackend backend, Path gameDir) {
        if (backend.isBuiltIn()) {
            return Status.AVAILABLE; // BILINEAR, FSR1, NIS: compute/blit in this mod, nothing to install
        }
        if (backend == UpscalerBackend.DLSS) {
            // The code exists; usability depends on the libraries resolved by Bedray Core's NativeLibraries and on the GPU
            // (vendor id once the device exists, NGX's own answer after the first world frame).
            if (!DlssAvailability.library().usable()) {
                return Status.LIBRARY_MISSING;
            }
            if (DlssAvailability.gpuRulesOutDlss() || DlssRuntime.isUnsupportedOnThisSystem()) {
                return Status.UNSUPPORTED_GPU;
            }
            return Status.AVAILABLE;
        }
        if (backend == UpscalerBackend.FSR) {
            // Official AMD FidelityFX Vulkan DLL resolved by Bedray Core (FsrLibrary). The GPU vendor does not matter (FSR runs
            // on any Vulkan GPU with the features of FsrDeviceRequirements).
            return FsrLibrary.isUsable() ? Status.AVAILABLE : Status.LIBRARY_MISSING;
        }
        if (!backend.isImplemented()) {
            return Status.NOT_IMPLEMENTED;
        }
        if (backend == UpscalerBackend.XESS && !XessConfig.libraryExists(gameDir)) {
            return Status.LIBRARY_MISSING;
        }
        return Status.AVAILABLE;
    }

    /**
     * The backend that may really be stored as active: the requested one when {@link Status#AVAILABLE}, otherwise
     * BILINEAR with a WARN in the log (never a silent substitution).
     */
    public static UpscalerBackend resolve(UpscalerBackend requested, Path gameDir) {
        if (requested == null) {
            return UpscalerBackend.BILINEAR;
        }
        Status st = status(requested, gameDir);
        if (st == Status.AVAILABLE) {
            return requested;
        }
        LOGGER.warn("[VKB-AUTOTEST] upscaler=rejected requested={} status={} using=BILINEAR", requested, st);
        return UpscalerBackend.BILINEAR;
    }

    /**
     * Pre-device read of {@code active_backend}/{@code xess_mode} from the toml (the Vulkan device is created before
     * {@code VulkanRtxConfig} exists, and XeSS needs device extensions). Read-only: never creates or rewrites the file.
     *
     * @return the XeSS mode requested by the file, or {@code null} when the file does not ask for a usable XeSS
     *         (no file, other backend, unknown mode, library missing: the reason is logged)
     */
    public static XessQuality earlyXessQuality(Path configFile, Path gameDir) {
        String[] raw = readEarly(configFile, "xess");
        if (raw == null) {
            return null;
        }
        String backend = raw[0];
        String mode = raw[1];
        if (backend == null || !backend.equalsIgnoreCase("XESS")) {
            return null;
        }
        UpscalerMode m;
        try {
            m = mode == null ? UpscalerModes.defaultMode(UpscalerBackend.XESS)
                : UpscalerMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.error("[VKB-AUTOTEST] xess=FAILED stage=config result=invalid_mode value={} file={}", mode, LogPaths.show(configFile));
            return null;
        }
        if (!UpscalerModes.isOffered(UpscalerBackend.XESS, m)) {
            LOGGER.error("[VKB-AUTOTEST] xess=FAILED stage=config result=invalid_mode value={} file={}", mode, LogPaths.show(configFile));
            return null;
        }
        if (!XessConfig.libraryExists(gameDir)) {
            LOGGER.warn("[VKB-AUTOTEST] xess=FAILED stage=config result=dll_missing path={} (active_backend=XESS in {} ignored)",
                LogPaths.show(XessConfig.libraryPath(gameDir)), LogPaths.show(configFile));
            return null;
        }
        // CUSTOM: the device requirements are the same; the percent is applied by UpscalerRequest after the config is loaded.
        return m == UpscalerMode.CUSTOM ? XessQuality.ULTRA_PERFORMANCE : UpscalerModes.toXessQuality(m);
    }

    /**
     * Pre-device read of {@code active_backend}/{@code dlss_mode}, same rules as {@link #earlyXessQuality}. The library check
     * is made later by {@code DlssDeviceRequirements} / {@code DlssRuntime} (through Bedray Core's NativeLibraries).
     *
     * @return the DLSS mode requested by the file, or {@code null} (no file, other backend, unknown or not offered mode)
     */
    public static DlssQuality earlyDlssQuality(Path configFile) {
        String[] raw = readEarly(configFile, "dlss");
        if (raw == null) {
            return null;
        }
        String backend = raw[0];
        String mode = raw[1];
        if (backend == null || !backend.equalsIgnoreCase("DLSS")) {
            return null;
        }
        UpscalerMode m;
        try {
            m = mode == null ? UpscalerModes.defaultMode(UpscalerBackend.DLSS)
                : UpscalerMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=config result=invalid_mode value={} file={}", mode, LogPaths.show(configFile));
            return null;
        }
        if (!UpscalerModes.isOffered(UpscalerBackend.DLSS, m)) {
            LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=config result=invalid_mode value={} file={}", mode, LogPaths.show(configFile));
            return null;
        }
        return DlssQuality.fromMode(m == UpscalerMode.CUSTOM ? UpscalerMode.QUALITY : m); // CUSTOM: see UpscalerRequest.build
    }

    /**
     * Pre-device read of {@code active_backend}/{@code fsr_mode}/{@code fsr_version}, same rules as {@link #earlyXessQuality}.
     * The library check is made later by {@code FsrDeviceRequirements} / {@code FsrRuntime} (Core NativeLibraries).
     *
     * @return the FSR request of the file, or {@code null} (no file, other backend, unknown or not offered mode/version)
     */
    public static FsrRequest earlyFsrRequest(Path configFile) {
        String[] raw = readEarly(configFile, "fsr");
        if (raw == null) {
            return null;
        }
        if (raw[0] == null || !raw[0].equalsIgnoreCase("FSR")) {
            return null;
        }
        try {
            UpscalerMode m = raw[1] == null ? UpscalerModes.defaultMode(UpscalerBackend.FSR)
                : UpscalerMode.valueOf(raw[1].toUpperCase(Locale.ROOT));
            FsrVersion v = raw[2] == null ? FsrVersion.AUTO : FsrVersion.valueOf(raw[2].toUpperCase(Locale.ROOT));
            return new FsrRequest(FsrQuality.fromMode(m == UpscalerMode.CUSTOM ? UpscalerMode.QUALITY : m), v); // CUSTOM: see UpscalerRequest.build
        } catch (IllegalArgumentException e) {
            LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage=config result=invalid_value mode={} version={} file={} message={}",
                raw[1], raw[2], LogPaths.show(configFile), e.getMessage());
            return null;
        }
    }

    /** Reads {@code active_backend}, {@code <tech>_mode} and {@code <tech>_version} (read-only); null when there is no file or it cannot be read (logged). */
    private static String[] readEarly(Path configFile, String tech) {
        if (!Files.isRegularFile(configFile)) {
            return null;
        }
        String backend = null;
        String mode = null;
        String version = null;
        try {
            List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("﻿")) line = line.substring(1).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim().replace("\"", "");
                if (key.equals("active_backend") || key.equals("upscaler_backend")) backend = val;
                if (key.equals(tech + "_mode")) mode = val;
                if (key.equals(tech + "_version")) version = val;
            }
        } catch (IOException e) {
            LOGGER.error("[VKB-AUTOTEST] {}=FAILED stage=config result=read_error file={} message={}", tech, LogPaths.show(configFile),
                LogPaths.show(e.toString()));
            return null;
        }
        return new String[] {backend, mode, version};
    }
}
