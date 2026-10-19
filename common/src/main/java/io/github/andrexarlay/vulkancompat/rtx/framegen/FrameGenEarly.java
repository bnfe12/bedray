package io.github.andrexarlay.vulkancompat.rtx.framegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Startup preparation of Streamline, called from the mixin plugin {@code onLoad} (before the engine loads LWJGL's Vulkan library).
 * Streamline only sees the engine's Vulkan calls when {@code sl.interposer.dll} is THE Vulkan library of LWJGL and SDL, so
 * {@code org.lwjgl.vulkan.libname} has to be set before {@code VK.create()} (NativeLibrariesBootstrap.tryLoadingVulkan). When
 * frame generation is requested (property {@code -Dvkrtx.framegen} or {@code framegen_mode} in config/bedray.toml) and the
 * Streamline binaries are found, the property is set here; otherwise nothing changes and the engine uses vulkan-1.dll as before.
 * <p>
 * Streamline directory: {@code -Dvkrtx.sl.dir}, default {@code <game dir>/bedray/natives/streamline}; it must hold
 * {@code sl.interposer.dll} (+ the SL plugins) and our {@code vkb_sl.dll} (or {@code -Dvkrtx.sl.shim}). Binaries are never committed.
 */
public final class FrameGenEarly {
    public static final String DEFAULT_DIR = "bedray/natives/streamline";
    /** Folder of the pre-release name, searched after the new one. */
    public static final String LEGACY_DIR = "vulkan_rtx/natives/streamline";
    private static String note = "not_prepared";

    private FrameGenEarly() {}

    /** Why Streamline was (not) hooked at startup; shown in the status. */
    public static String note() {
        return note;
    }

    /** Fallback of last resort for developer machines: the unpacked NVIDIA SDK (never used when the game dir has the files). */
    /** Files that must exist (next to each other, except vkb_sl.dll which may live in the game dir). */
    public static final String[] REQUIRED = {"sl.interposer.dll", "sl.common.dll", "sl.dlss_g.dll", "sl.reflex.dll", "sl.pcl.dll", "nvngx_dlssg.dll"};
    public static final String SHIM = "vkb_sl.dll";

    private static Path resolvedDir;

    /**
     * Directory with the Streamline binaries: {@code -Dvkrtx.sl.dir}, else {@code <game dir>/bedray/natives/streamline}, else env
     * {@code VKRTX_SL_DIR}, else the SDK fallback; the first one that contains {@code sl.interposer.dll} wins. When none does, the game dir
     * path is returned (it is the one the message tells the user to fill).
     */
    public static synchronized Path slDir() {
        if (resolvedDir != null) {
            return resolvedDir;
        }
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        String p = System.getProperty(StreamlineBootstrap.PROP_DIR);
        if (p != null && !p.isBlank()) {
            candidates.add(Paths.get(p).toAbsolutePath().normalize());
        }
        for (String key : new String[] {"net.neoforged.gameDir", "fabric.gameDir", "minecraft.gameDir"}) {
            String dir = System.getProperty(key);
            if (dir != null && !dir.isBlank()) {
                candidates.add(Paths.get(dir, DEFAULT_DIR).toAbsolutePath().normalize());
                candidates.add(Paths.get(dir, LEGACY_DIR).toAbsolutePath().normalize());
            }
        }
        candidates.add(Paths.get(DEFAULT_DIR).toAbsolutePath().normalize());
        String env = System.getenv("VKRTX_SL_DIR");
        if (env != null && !env.isBlank()) {
            candidates.add(Paths.get(env).toAbsolutePath().normalize());
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("sl.interposer.dll"))) {
                resolvedDir = c;
                return c;
            }
        }
        return candidates.get(0);
    }

    /** vkb_sl.dll: {@code -Dvkrtx.sl.shim}, else in the Streamline dir, else in the game dir folder. */
    public static Path shimPath() {
        String p = System.getProperty(StreamlineBootstrap.PROP_SHIM);
        if (p != null && !p.isBlank()) {
            return Paths.get(p).toAbsolutePath().normalize();
        }
        Path inDir = slDir().resolve(SHIM);
        if (Files.isRegularFile(inDir)) {
            return inDir;
        }
        return Paths.get(DEFAULT_DIR).toAbsolutePath().normalize().resolve(SHIM);
    }

    /** Names of required files that are missing (empty = complete). */
    public static java.util.List<String> missingFiles() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        Path dir = slDir();
        for (String f : REQUIRED) {
            if (!Files.isRegularFile(dir.resolve(f))) {
                missing.add(f);
            }
        }
        if (!Files.isRegularFile(shimPath())) {
            missing.add(SHIM);
        }
        return missing;
    }

    public static Path findConfigFile() {
        String[] propKeys = {
            "net.neoforged.gameDir",
            "fabric.gameDir",
            "minecraft.gameDir"
        };
        for (String key : propKeys) {
            String dir = System.getProperty(key);
            if (dir != null && !dir.isBlank()) {
                Path candidate = io.github.andrexarlay.vulkancompat.rtx.config.ConfigFiles.resolve(Paths.get(dir, "config", "bedray.toml"));
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
        }
        Path fallback = io.github.andrexarlay.vulkancompat.rtx.config.ConfigFiles.resolve(Paths.get("config", "bedray.toml"));
        return fallback.toAbsolutePath().normalize();
    }

    public static void prepare() {
        FrameGenSettings.Mode m = FrameGenSettings.startupMode();
        if (System.getProperty(FrameGenSettings.PROPERTY) == null) {
            FrameGenSettings.Mode fromFile = FrameGenSettings.peekModeFromFile(findConfigFile());
            if (fromFile != null) {
                FrameGenSettings.applyKeyValue("framegen_mode", fromFile.name());
                m = fromFile;
            }
        }
        if (System.getProperty(FrameGenSettings.PROVIDER_PROPERTY) == null) {
            FrameGenSettings.Provider pf = FrameGenSettings.peekProviderFromFile(findConfigFile());
            if (pf != null) {
                FrameGenSettings.setProvider(pf);
            }
        }
        FrameGenProvider.resolveAtStartup(m == FrameGenSettings.Mode.OFF, missingFiles().isEmpty());
        if (m == FrameGenSettings.Mode.OFF) {
            note = "mode_off_at_startup";
            return;
        }
        if (FrameGenProvider.amd()) {
            // AMD FSR 3 frame generation: the normal vulkan-1.dll stays the Vulkan library, Streamline is never loaded.
            note = "provider_amd";
            return;
        }
        Path dir = slDir();
        Path interposer = dir.resolve("sl.interposer.dll");
        java.util.List<String> missing = missingFiles();
        if (!missing.isEmpty()) {
            note = "missing_files: " + missing + " in " + dir;
            System.err.println("[VKB-FG] frame generation requested (" + m + ") but these files are missing in " + dir + ": " + missing
                + " (shim: native/sl_bridge/build.bat); staying on vulkan-1.dll");
            return;
        }
        String current = System.getProperty("org.lwjgl.vulkan.libname");
        if (current == null || current.isBlank()) {
            System.setProperty("org.lwjgl.vulkan.libname", interposer.toString());
            note = "libname_set_by_mod";
        } else {
            note = "libname_preset";
        }
        System.setProperty(StreamlineBootstrap.PROP_DIR, dir.toString());
        System.setProperty(StreamlineBootstrap.PROP_SHIM, shimPath().toString());
    }
}
