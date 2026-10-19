package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic library discovery and loader for super resolution upscalers.
 * <p>
 * Follows the Radiance/OptiScaler discovery model: vendor DLLs are never packaged
 * inside mod jars or committed to git. Users or launcher scripts provide libraries
 * in {@code <gameDirectory>/bedray/natives/}.
 * <p>
 * Blind {@link System#load(String)} is strictly avoided. Files are checked physically first,
 * and link errors are logged at INFO level without crashing the game.
 */
public final class UpscalerLibraryLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/LibraryLoader");

    private static final String NATIVES_SUBDIR = "bedray/natives";

    // Standard DLL file names
    public static final String DLL_DLSS = "nvngx_dlss.dll";
    public static final String DLL_DLSS_FG = "nvngx_dlssg.dll";
    public static final String DLL_DLSS_RR = "nvngx_dlssnr.dll";
    public static final String DLL_DLSS_RR_ALT = "nvngx_dlssd.dll";

    // FSR: only the Vulkan build of FidelityFX counts (e.g. amd_fidelityfx_vk.dll). The DX12 DLLs
    // (amd_fidelityfx_*_dx12*.dll) cannot be used with a Vulkan device (known pitfall), see isFsrVulkanLibraryName().

    public static final String DLL_XESS = "libxess.dll";

    // Scan results cache
    private static volatile boolean dlssAvailable = false;
    private static volatile boolean dlssFrameGenAvailable = false;
    private static volatile boolean dlssRayReconstructionAvailable = false;
    private static volatile boolean fsrAvailable = false;
    private static volatile boolean xessAvailable = false;

    // Backend singletons
    private static final BilinearBackend BILINEAR_BACKEND = new BilinearBackend();
    private static final DlssBackend DLSS_BACKEND = new DlssBackend();
    private static final FsrBackend FSR_BACKEND = new FsrBackend();
    private static final XessBackend XESS_BACKEND = new XessBackend();

    // Testing override
    private static Path customNativesDirForTesting = null;

    static {
        scanAvailableBackends();
    }

    private UpscalerLibraryLoader() {}

    /**
     * Safely determines the Minecraft game directory, falling back to current working directory "."
     * if Minecraft is not yet initialized or during headless testing.
     */
    public static Path resolveGameDirectory() {
        for (String key : new String[] {"net.neoforged.gameDir", "fabric.gameDir", "minecraft.gameDir"}) {
            String dir = System.getProperty(key);
            if (dir != null && !dir.isBlank()) {
                return Path.of(dir).toAbsolutePath().normalize();
            }
        }
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath().toAbsolutePath().normalize();
            }
        } catch (Throwable ignored) {
            // Headless runner or pre-init: use current directory
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    /**
     * Returns the target natives directory: {@code <gameDirectory>/bedray/natives/}.
     * Automatically creates the directory, the streamline/ subfolder, and a guidance README if they do not exist.
     */
    public static Path getNativesDirectory() {
        if (customNativesDirForTesting != null) {
            return customNativesDirForTesting;
        }
        Path gameDir = resolveGameDirectory();
        Path nativesDir = gameDir.resolve("bedray").resolve("natives");
        Path streamlineDir = nativesDir.resolve("streamline");
        try {
            if (!Files.exists(nativesDir)) {
                Files.createDirectories(nativesDir);
            }
            if (!Files.exists(streamlineDir)) {
                Files.createDirectories(streamlineDir);
            }
            ensureReadme(nativesDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create natives directory {}: {}", nativesDir, e.getMessage());
        }
        return nativesDir;
    }

    private static void ensureReadme(Path nativesDir) {
        Path readme = nativesDir.resolve("README.txt");
        if (Files.exists(readme)) {
            return;
        }
        try {
            String content = """
                === Bedray Native Upscaler Libraries ===

                [RUS]
                Сюда можно положить библиотеки (DLL) апскейлеров для максимального качества:
                - amd_fidelityfx_vk.dll — AMD FSR 3.1 (апскейлер и генерация кадров для любых GPU: RTX, GTX, Radeon, Intel Arc)
                - nvngx_dlss.dll — NVIDIA DLSS Super Resolution (для карт GeForce RTX)
                - libxess.dll — Intel XeSS

                Для генерации кадров NVIDIA (DLSS Frame Generation / Streamline):
                - Положите файлы Streamline в подпапку 'streamline/':
                  sl.interposer.dll, sl.common.dll, sl.dlss_g.dll, sl.reflex.dll, sl.pcl.dll, nvngx_dlssg.dll, vkb_sl.dll

                После добавления файлов перезапустите Minecraft.
                Если файлы не добавлены, мод будет использовать встроенный рендерер.

                [ENG]
                Place vendor upscaler libraries here:
                - amd_fidelityfx_vk.dll (AMD FSR 3.1)
                - nvngx_dlss.dll (NVIDIA DLSS)
                - libxess.dll (Intel XeSS)
                Place NVIDIA Streamline files into 'streamline/' subfolder.
                Restart Minecraft after adding files.
                """;
            Files.writeString(readme, content, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Returns the list of directories searched for vendor native libraries.
     * Includes natives/, vendor/ subdirectories, and cache locations.
     */
    public static List<Path> getCandidateDirectories() {
        if (customNativesDirForTesting != null) {
            return List.of(customNativesDirForTesting);
        }
        Path gameDir = resolveGameDirectory();
        List<Path> candidates = new ArrayList<>();
        candidates.add(getNativesDirectory());
        candidates.add(gameDir.resolve("bedray").resolve("vendor"));
        candidates.add(gameDir.resolve("bedray").resolve("vendor").resolve("nvidia"));
        candidates.add(gameDir.resolve("bedray").resolve("vendor").resolve("amd"));
        candidates.add(gameDir.resolve("bedray").resolve("vendor").resolve("intel"));
        candidates.add(gameDir.resolve(".vulkan_rtx_cache").resolve("natives"));
        // pre-release folder names, read only
        candidates.add(gameDir.resolve("vulkan_rtx").resolve("natives"));
        candidates.add(gameDir.resolve("vulkan_rtx").resolve("vendor"));

        Path cwdDir = Path.of(".").toAbsolutePath().normalize();
        if (!cwdDir.equals(gameDir)) {
            candidates.add(cwdDir.resolve("bedray").resolve("natives"));
            candidates.add(cwdDir.resolve("bedray").resolve("vendor"));
            candidates.add(cwdDir.resolve("bedray").resolve("vendor").resolve("nvidia"));
            candidates.add(cwdDir.resolve("bedray").resolve("vendor").resolve("amd"));
            candidates.add(cwdDir.resolve("bedray").resolve("vendor").resolve("intel"));
            candidates.add(cwdDir.resolve(".vulkan_rtx_cache").resolve("natives"));
        }
        return candidates;
    }

    private static boolean findFileInCandidates(String fileName) {
        for (Path dir : getCandidateDirectories()) {
            if (Files.isDirectory(dir) && fileExistsCaseInsensitive(dir, fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the file name looks like the Vulkan build of the AMD FidelityFX SDK
     * ({@code amd_fidelityfx_vk.dll}, {@code ffx_*vk*.dll}). DX12/DX11 builds are rejected.
     */
    public static boolean isFsrVulkanLibraryName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String n = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith(".dll")) {
            return false;
        }
        if (n.contains("dx12") || n.contains("dx11") || n.contains("d3d")) {
            return false;
        }
        boolean fidelityFx = n.startsWith("amd_fidelityfx") || n.startsWith("ffx_");
        boolean vulkan = n.contains("_vk") || n.contains("vk_") || n.contains("vulkan");
        return fidelityFx && vulkan;
    }

    private static boolean directoryHasFsrVulkanLibrary(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && isFsrVulkanLibraryName(entry.getFileName().toString())) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[UpscalerLibraryLoader] Cannot list {}: {}", dir, e.getMessage());
        }
        return false;
    }

    private static boolean fsrVulkanInCandidates() {
        for (Path dir : getCandidateDirectories()) {
            if (Files.isDirectory(dir) && directoryHasFsrVulkanLibrary(dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans candidate natives and vendor folders for physical presence of upscaler DLL files.
     * Logs detected backends at INFO level without throwing exceptions.
     */
    public static synchronized void scanAvailableBackends() {
        Path mainDir = getNativesDirectory();

        // DLSS scan
        boolean dlss = findFileInCandidates(DLL_DLSS);
        boolean dlssFg = dlss && findFileInCandidates(DLL_DLSS_FG);
        boolean dlssRr = dlss && (findFileInCandidates(DLL_DLSS_RR) || findFileInCandidates(DLL_DLSS_RR_ALT));

        // FSR scan: Vulkan build of FidelityFX only (DX12 DLLs are ignored)
        boolean fsr = fsrVulkanInCandidates();

        // XeSS scan
        boolean xess = findFileInCandidates(DLL_XESS);

        dlssAvailable = dlss;
        dlssFrameGenAvailable = dlssFg;
        dlssRayReconstructionAvailable = dlssRr;
        fsrAvailable = fsr;
        xessAvailable = xess;

        LOGGER.info("[UpscalerLibraryLoader] Scanned natives at {}: DLSS={} (FG={}, RR={}), FSR={}, XeSS={}, BILINEAR=true",
                mainDir, dlss, dlssFg, dlssRr, fsr, xess);
    }

    /**
     * Returns true if the native library for the backend is present (and, for DLSS, the GPU is NVIDIA).
     * This is NOT "the backend works": DLSS/FSR/XeSS are STUB, see {@link UpscalerBackend#isImplemented()}.
     * BILINEAR is built in and always available.
     */
    public static boolean isBackendAvailable(UpscalerBackend backend) {
        if (backend == null) {
            return false;
        }
        return switch (backend) {
            case BILINEAR, FSR1, NIS -> true;
            // DLSS: libraries come from Bedray Core's NativeLibraries (nvngx_dlss.dll + vkb_dlss.dll) and the GPU must not be known non-NVIDIA.
            case DLSS -> io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability.isUsable();
            case FSR -> fsrAvailable;
            case XESS -> xessAvailable;
        };
    }

    /**
     * Returns true if DLSS Frame Generation library ({@code nvngx_dlssg.dll}) is found and DLSS is available.
     */
    public static boolean hasDlssFrameGen() {
        return isBackendAvailable(UpscalerBackend.DLSS) && dlssFrameGenAvailable;
    }

    /**
     * Returns true if DLSS Ray Reconstruction library ({@code nvngx_dlssnr.dll} or {@code nvngx_dlssd.dll})
     * is found and DLSS is available.
     */
    public static boolean hasDlssRayReconstruction() {
        return isBackendAvailable(UpscalerBackend.DLSS) && dlssRayReconstructionAvailable;
    }

    /**
     * Returns the backend implementation singleton for the given enum.
     */
    public static IUpscalerBackend getBackend(UpscalerBackend backend) {
        if (backend == null) {
            return BILINEAR_BACKEND;
        }
        return switch (backend) {
            // FSR1 / NIS are dispatched by SpatialUpscaler (WorldTargetManager), not through IUpscalerBackend.
            case BILINEAR, FSR1, NIS -> BILINEAR_BACKEND;
            case DLSS -> DLSS_BACKEND;
            case FSR -> FSR_BACKEND;
            case XESS -> XESS_BACKEND;
        };
    }

    /**
     * Safely attempts to load a native library from the specified path.
     * Never crashes the application: catches {@link UnsatisfiedLinkError} and {@link Throwable},
     * logging the event at INFO level.
     *
     * @param dllPath Path to native library
     * @return true if loaded successfully, false on error
     */
    public static boolean tryLoadLibrary(Path dllPath) {
        if (dllPath == null || !Files.isRegularFile(dllPath)) {
            LOGGER.info("[UpscalerLibraryLoader] Native library not found: {}", dllPath);
            return false;
        }
        try {
            System.load(dllPath.toAbsolutePath().toString());
            LOGGER.info("[UpscalerLibraryLoader] Successfully loaded native library: {}", dllPath.getFileName());
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[UpscalerLibraryLoader] Native library '{}' could not be linked: {}", dllPath.getFileName(), e.getMessage());
            return false;
        } catch (Throwable t) {
            LOGGER.info("[UpscalerLibraryLoader] Failed to load native library '{}': {}", dllPath.getFileName(), t.getMessage());
            return false;
        }
    }

    private static boolean fileExistsCaseInsensitive(Path dir, String fileName) {
        Path direct = dir.resolve(fileName);
        if (Files.isRegularFile(direct)) {
            return true;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && entry.getFileName().toString().equalsIgnoreCase(fileName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[UpscalerLibraryLoader] Cannot list {}: {}", dir, e.getMessage());
        }
        return false;
    }

    /**
     * Configures a custom natives directory for unit tests and triggers a rescan.
     */
    public static synchronized void setCustomNativesDirectoryForTesting(Path path) {
        customNativesDirForTesting = path;
        scanAvailableBackends();
    }

    /**
     * Resets custom natives directory and rescans.
     */
    public static synchronized void resetForTesting() {
        customNativesDirForTesting = null;
        scanAvailableBackends();
    }
}
