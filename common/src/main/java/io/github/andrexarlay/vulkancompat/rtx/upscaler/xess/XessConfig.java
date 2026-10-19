package io.github.andrexarlay.vulkancompat.rtx.upscaler.xess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Early (pre-device) XeSS request. The device is created before any mod config is available, so the request comes
 * from the JVM property {@code -Dvkbridge.xess=<mode>} (see {@link XessQuality}) or, when absent, from a read-only
 * early parse of {@code config/bedray.toml} ({@code active_backend=XESS}, {@code xess_mode}), see {@link UpscalerSelection}.
 * <p>
 * This class must not touch Minecraft classes: it is unit-tested and used from the device-creation mixin.
 */
public final class XessConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/XessConfig");

    public static final String PROPERTY = "vkbridge.xess";
    public static final String DLL_NAME = "libxess.dll";
    /** Same relative path as VulkanRtxConfig (the launcher starts the game in the instance directory). */
    private static final Path CONFIG_FILE = io.github.andrexarlay.vulkancompat.rtx.config.ConfigFiles.resolve(Path.of("config", "bedray.toml"));

    private static boolean resolved;
    private static XessQuality requested;

    private XessConfig() {}

    /** Requested mode, or {@code null} when the property is not set (XeSS fully off) or invalid (logged as FAILED). */
    public static synchronized XessQuality requestedQuality() {
        if (!resolved) {
            resolved = true;
            String raw = System.getProperty(PROPERTY);
            if (raw != null && !raw.isBlank()) {
                try {
                    requested = XessQuality.parse(raw);
                    LOGGER.info("[VKB-AUTOTEST] xess=requested mode={} source=property", requested);
                } catch (IllegalArgumentException e) {
                    LOGGER.error("[VKB-AUTOTEST] xess=FAILED stage=config result=invalid_mode value={} message={}", raw, e.getMessage());
                }
            } else {
                // Settings page choice: config/bedray.toml active_backend=XESS + xess_mode (read-only, pre-device).
                Path gameDir = Path.of("").toAbsolutePath().normalize();
                requested = UpscalerSelection.earlyXessQuality(CONFIG_FILE, gameDir);
                if (requested != null) {
                    LOGGER.info("[VKB-AUTOTEST] xess=requested mode={} source=config", requested);
                }
            }
        }
        return requested;
    }

    /** Test hook: forget the cached property value. */
    static synchronized void resetForTests() {
        resolved = false;
        requested = null;
    }

    /**
     * {@code <gameDir>/bedray/natives/libxess.dll}: explicit path, never searched on PATH or in other games. Pre-release folders
     * (see NativeLibrariesImpl.LEGACY_ROOTS) are used only when the file exists there and not in the new folder.
     */
    public static Path libraryPath(Path gameDir) {
        Path current = gameDir.resolve("bedray").resolve("natives").resolve(DLL_NAME);
        if (!Files.isRegularFile(current)) {
            for (String root : io.github.andrexarlay.vulkanbridge.core.NativeLibrariesImpl.LEGACY_ROOTS) {
                Path legacy = gameDir.resolve(root).resolve("natives").resolve(DLL_NAME);
                if (Files.isRegularFile(legacy)) {
                    return legacy;
                }
            }
        }
        return current;
    }

    public static boolean libraryExists(Path gameDir) {
        return Files.isRegularFile(libraryPath(gameDir));
    }
}
