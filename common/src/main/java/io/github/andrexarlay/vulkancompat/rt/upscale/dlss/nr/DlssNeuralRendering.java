package io.github.andrexarlay.vulkancompat.rt.upscale.dlss.nr;

import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * STUB (not wired, hidden from the settings page): no NGX feature is created and no DLL is loaded. {@link #init} only checks
 * that the two files exist; {@link #evaluate} is a CPU colour blend that no render pass calls. "nvngx_dlssnr.dll" is not part
 * of the public NVIDIA DLSS SDK (310.9.1 has nvngx_dlss / nvngx_dlssd / nvngx_dlssg only); copies taken from a game install
 * (e.g. STALKER 2 via OptiScaler) must not be redistributed and are never searched for automatically.
 * <p>
 * Original description: Experimental DLSS 5 Neural Rendering (DLSS-NR) module.
 * <p>
 * Evaluates NVIDIA's DLSS-NR neural model ({@code nvngx_dlssnr.dll} + {@code nvngx.dll_dlssnr.dll})
 * after the upscaling stage to synthesize microscopic detail, and reconstructs the image
 * using RenoDX / OptiScaler style color composition:
 * <ul>
 *   <li>Original luminance & exposure preservation</li>
 *   <li>Highlight guard ({@code maxRatio}) against light artifacts</li>
 *   <li>Color strength ({@code colourStrength}) blending between game hue and model hue</li>
 *   <li>Detail strength ({@code transferStrength}) blending with true bypass at 0.0</li>
 * </ul>
 *
 * <h3>Fail-Safe Guard:</h3>
 * If native libraries are missing, the GPU is not an RTX 50 series (Blackwell), or native
 * initialization fails, this module safely falls back to {@link Status#NOT_SUPPORTED_MISSING_LIBS},
 * {@link Status#NOT_SUPPORTED_GPU_ARCH}, or {@link Status#BYPASS}.
 * <b>It never throws unhandled exceptions or crashes the game loop.</b>
 */
public final class DlssNeuralRendering implements Closeable, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssNeuralRendering");

    public static final String MODEL_DLL_NAME = "nvngx_dlssnr.dll";
    public static final String LOADER_DLL_NAME = "nvngx.dll_dlssnr.dll";

    public static final String PROP_MODEL_PATH = "vkrtx.dlssnr.model_path";
    public static final String PROP_DIR = "vkrtx.dlssnr.dir";
    public static final String PROP_FORCE_SUPPORTED = "vkrtx.dlssnr.force_supported";

    // Rec. 709 Luminance coefficients
    private static final float LUM_R = 0.2126f;
    private static final float LUM_G = 0.7152f;
    private static final float LUM_B = 0.0722f;

    public enum Status {
        INITIALIZED("DLSS Neural Rendering active"),
        BYPASS("DLSS Neural Rendering bypassed"),
        NOT_SUPPORTED_MISSING_LIBS("Required DLSS-NR libraries (nvngx_dlssnr.dll / nvngx.dll_dlssnr.dll) not found"),
        NOT_SUPPORTED_GPU_ARCH("GPU architecture not supported (RTX 50 series / Blackwell or newer required)"),
        NOT_SUPPORTED_INIT_FAILED("DLSS-NR initialization failed or driver model rejected"),
        ERROR("Internal DLSS-NR execution error");

        private final String message;

        Status(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }

        public boolean isAvailable() {
            return this == INITIALIZED;
        }

        public boolean isBypass() {
            return this == BYPASS;
        }
    }

    public record LibraryResolution(boolean usable, Path modelDll, Path loaderDll, String reason) {
        public static LibraryResolution unusable(String reason) {
            return new LibraryResolution(false, null, null, reason);
        }
    }

    private final DlssNrConfig config;
    private Status status = Status.BYPASS;
    private Path resolvedModelDll;
    private Path resolvedLoaderDll;
    private boolean initialized = false;

    public DlssNeuralRendering() {
        this(new DlssNrConfig());
    }

    public DlssNeuralRendering(DlssNrConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public DlssNrConfig getConfig() {
        return config;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Path getResolvedModelDll() {
        return resolvedModelDll;
    }

    public Path getResolvedLoaderDll() {
        return resolvedLoaderDll;
    }

    /**
     * Checks if DLSS-NR libraries exist directly in the specified directory.
     */
    public static LibraryResolution checkDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return LibraryResolution.unusable("Directory does not exist: " + dir);
        }
        try {
            Path model = dir.resolve(MODEL_DLL_NAME);
            Path loader = dir.resolve(LOADER_DLL_NAME);
            if (Files.isRegularFile(model) && Files.isRegularFile(loader)) {
                return new LibraryResolution(true, model.toAbsolutePath().normalize(), loader.toAbsolutePath().normalize(), "Libraries found in " + dir);
            }
        } catch (Throwable t) {
            return LibraryResolution.unusable("Error accessing directory " + dir + ": " + t.getMessage());
        }
        return LibraryResolution.unusable("Missing " + MODEL_DLL_NAME + " or " + LOADER_DLL_NAME + " in " + dir);
    }

    /**
     * Resolves and verifies DLSS-NR libraries across candidate locations, or in customDir if specified.
     */
    public static LibraryResolution checkLibraries(Path customDir) {
        if (customDir != null) {
            return checkDirectory(customDir);
        }

        List<Path> candidateDirs = new ArrayList<>();

        // 1. System property directory
        String propDir = System.getProperty(PROP_DIR);
        if (propDir != null && !propDir.isBlank()) {
            candidateDirs.add(Path.of(propDir.trim()));
        }

        // 2. System property direct model path
        String propModel = System.getProperty(PROP_MODEL_PATH);
        if (propModel != null && !propModel.isBlank()) {
            Path p = Path.of(propModel.trim());
            if (Files.isRegularFile(p)) {
                Path parent = p.getParent();
                if (parent != null) candidateDirs.add(parent);
            }
        }

        // Only explicit locations: no working-directory / "." search and no automatic lookup in other games' install
        // folders (third-party binaries are not picked up implicitly).

        for (Path dir : candidateDirs) {
            if (dir == null) continue;
            try {
                Path model = dir.resolve(MODEL_DLL_NAME);
                Path loader = dir.resolve(LOADER_DLL_NAME);
                if (Files.isRegularFile(model) && Files.isRegularFile(loader)) {
                    return new LibraryResolution(true, model.toAbsolutePath().normalize(), loader.toAbsolutePath().normalize(), "Libraries found in " + dir);
                }
            } catch (Throwable ignored) {
            }
        }

        return LibraryResolution.unusable("Missing " + MODEL_DLL_NAME + " or " + LOADER_DLL_NAME + " in candidate search locations.");
    }

    /**
     * Checks if the active GPU architecture meets DLSS 5 Neural Rendering hardware requirements.
     * DLSS-NR requires an NVIDIA RTX 50 series card (Blackwell or newer), or explicit test override.
     */
    public static boolean isGpuSupported(String deviceName) {
        if (Boolean.getBoolean(PROP_FORCE_SUPPORTED)) {
            return true;
        }

        // Must be an NVIDIA GPU
        if (!VulkanCapabilityChecker.isNvidiaGpu()) {
            return false;
        }

        if (deviceName == null || deviceName.isBlank()) {
            // If device name cannot be determined, do not assume RTX 50
            return false;
        }

        String lower = deviceName.toLowerCase(Locale.ROOT);
        // Search for RTX 50xx identifiers (e.g. RTX 5090, RTX 5080, RTX 5070, RTX 5060, Blackwell)
        return lower.contains("rtx 50") || lower.contains("rtx50") || lower.contains("blackwell");
    }

    /**
     * Initializes the DLSS Neural Rendering pass.
     * Guaranteed fail-safe: catches all exceptions and safely reports status without throwing.
     */
    public synchronized Status init(Path customDir, String deviceName) {
        try {
            if (!config.isEnabled()) {
                this.status = Status.BYPASS;
                this.initialized = false;
                return Status.BYPASS;
            }

            // 1. Library check
            LibraryResolution libRes = checkLibraries(customDir);
            if (!libRes.usable()) {
                LOGGER.info("[DLSS-NR] Native libraries not found: {}. DLSS-NR will be safely disabled.", libRes.reason());
                this.status = Status.NOT_SUPPORTED_MISSING_LIBS;
                this.initialized = false;
                return this.status;
            }
            this.resolvedModelDll = libRes.modelDll();
            this.resolvedLoaderDll = libRes.loaderDll();

            // 2. GPU architecture check
            if (!isGpuSupported(deviceName)) {
                LOGGER.info("[DLSS-NR] GPU '{}' is not an RTX 50 series (Blackwell) device. DLSS-NR requires RTX 50. Safe bypass enabled.",
                    deviceName != null ? deviceName : "Unknown");
                this.status = Status.NOT_SUPPORTED_GPU_ARCH;
                this.initialized = false;
                return this.status;
            }

            // 3. Mark initialized
            this.initialized = true;
            this.status = Status.INITIALIZED;
            LOGGER.warn("[DLSS-NR] STUB: files present (model={}, loader={}) but nothing is loaded or evaluated on the GPU",
                resolvedModelDll, resolvedLoaderDll);
            return Status.INITIALIZED;
        } catch (Throwable t) {
            LOGGER.warn("[DLSS-NR] Fail-Safe Guard caught error during initialization: {}", t.getMessage(), t);
            this.status = Status.NOT_SUPPORTED_INIT_FAILED;
            this.initialized = false;
            return this.status;
        }
    }

    /**
     * Evaluates DLSS Neural Rendering over an RGB pixel, applying RenoDX / OptiScaler color composition.
     */
    public synchronized Status evaluate(float[] originalRgb, float[] modelRgb, float[] outputRgb) {
        if (!initialized || config.isBypass()) {
            if (originalRgb != null && outputRgb != null && originalRgb != outputRgb) {
                System.arraycopy(originalRgb, 0, outputRgb, 0, Math.min(originalRgb.length, outputRgb.length));
            }
            return Status.BYPASS;
        }

        try {
            composeColorRgb(originalRgb, modelRgb, outputRgb, config);
            return Status.INITIALIZED;
        } catch (Throwable t) {
            LOGGER.warn("[DLSS-NR] Fail-Safe Guard caught exception in evaluate: {}", t.getMessage());
            if (originalRgb != null && outputRgb != null && originalRgb != outputRgb) {
                System.arraycopy(originalRgb, 0, outputRgb, 0, Math.min(originalRgb.length, outputRgb.length));
            }
            return Status.BYPASS;
        }
    }

    /**
     * Evaluates DLSS Neural Rendering over an array of RGB/RGBA pixels.
     */
    public synchronized Status evaluate(float[] originalPixels, float[] modelPixels, float[] outputPixels, int pixelCount) {
        if (!initialized || config.isBypass()) {
            if (originalPixels != null && outputPixels != null && originalPixels != outputPixels) {
                System.arraycopy(originalPixels, 0, outputPixels, 0, Math.min(originalPixels.length, outputPixels.length));
            }
            return Status.BYPASS;
        }

        try {
            composePixelBuffer(originalPixels, modelPixels, outputPixels, pixelCount, config);
            return Status.INITIALIZED;
        } catch (Throwable t) {
            LOGGER.warn("[DLSS-NR] Fail-Safe Guard caught exception in evaluate buffer: {}", t.getMessage());
            if (originalPixels != null && outputPixels != null && originalPixels != outputPixels) {
                System.arraycopy(originalPixels, 0, outputPixels, 0, Math.min(originalPixels.length, outputPixels.length));
            }
            return Status.BYPASS;
        }
    }

    /**
     * RenoDX / OptiScaler reference color composition for a single RGB color.
     *
     * @param origRgb  Original upscaler output RGB [0..1 or HDR]
     * @param modelRgb DLSS-NR model raw response RGB
     * @param outRgb   Output blended RGB destination
     * @param cfg      DLSS-NR configuration
     */
    public static void composeColorRgb(float[] origRgb, float[] modelRgb, float[] outRgb, DlssNrConfig cfg) {
        if (origRgb == null || outRgb == null) return;
        if (modelRgb == null || cfg == null || cfg.isBypass()) {
            System.arraycopy(origRgb, 0, outRgb, 0, 3);
            return;
        }

        // Debug Views
        int debugView = cfg.getDebugView();
        if (debugView == DlssNrConfig.DEBUG_VIEW_INPUT) {
            System.arraycopy(origRgb, 0, outRgb, 0, 3);
            return;
        }
        if (debugView == DlssNrConfig.DEBUG_VIEW_RAW) {
            System.arraycopy(modelRgb, 0, outRgb, 0, 3);
            return;
        }
        if (debugView == DlssNrConfig.DEBUG_VIEW_DIFFERENCE) {
            // Amplified 20x centered at flat grey (0.5f)
            for (int i = 0; i < 3; i++) {
                outRgb[i] = 0.5f + 20.0f * (modelRgb[i] - origRgb[i]);
            }
            return;
        }

        // 1. Calculate luminance (Rec. 709)
        float origLum = LUM_R * origRgb[0] + LUM_G * origRgb[1] + LUM_B * origRgb[2];
        float modelLum = LUM_R * modelRgb[0] + LUM_G * modelRgb[1] + LUM_B * modelRgb[2];

        float safeOrigLum = Math.max(origLum, 1e-6f);
        float safeModelLum = Math.max(modelLum, 1e-6f);

        // 2. Highlight Guard: cap maximum brightening ratio (darkening is never capped)
        float maxRatio = Math.max(1.0f, cfg.getMaxRatio());
        float ratio = modelLum / safeOrigLum;
        if (ratio > maxRatio) {
            ratio = maxRatio;
        }
        float targetLum = safeOrigLum * ratio;

        // 3. Colour Strength: blend between game-accurate hue and model colour
        // Case A: Luminance only (colourStrength = 0)
        float lumScaleOrig = targetLum / safeOrigLum;
        float rLumOnly = origRgb[0] * lumScaleOrig;
        float gLumOnly = origRgb[1] * lumScaleOrig;
        float bLumOnly = origRgb[2] * lumScaleOrig;

        // Case B: Full model colour (colourStrength = 1)
        float lumScaleModel = targetLum / safeModelLum;
        float rFull = modelRgb[0] * lumScaleModel;
        float gFull = modelRgb[1] * lumScaleModel;
        float bFull = modelRgb[2] * lumScaleModel;

        float cStr = cfg.getColourStrength();
        float rAdapted = rLumOnly + cStr * (rFull - rLumOnly);
        float gAdapted = gLumOnly + cStr * (gFull - gLumOnly);
        float bAdapted = bLumOnly + cStr * (bFull - bLumOnly);

        // 4. Detail / Transfer Strength: blend between original and adapted model
        float tStr = cfg.getTransferStrength();
        outRgb[0] = origRgb[0] + tStr * (rAdapted - origRgb[0]);
        outRgb[1] = origRgb[1] + tStr * (gAdapted - origRgb[1]);
        outRgb[2] = origRgb[2] + tStr * (bAdapted - origRgb[2]);
    }

    /**
     * Convenience method returning newly allocated float[3] array.
     */
    public static float[] composeColor(float[] origRgb, float[] modelRgb, DlssNrConfig cfg) {
        float[] out = new float[3];
        composeColorRgb(origRgb, modelRgb, out, cfg);
        return out;
    }

    /**
     * Composes RGB float buffers containing {@code pixelCount} contiguous pixels (stride = 3 or 4).
     */
    public static void composePixelBuffer(float[] origPixels, float[] modelPixels, float[] outPixels, int pixelCount, DlssNrConfig cfg) {
        if (origPixels == null || outPixels == null) return;
        if (modelPixels == null || cfg == null || cfg.isBypass()) {
            System.arraycopy(origPixels, 0, outPixels, 0, Math.min(origPixels.length, outPixels.length));
            return;
        }

        float[] orig = new float[3];
        float[] model = new float[3];
        float[] out = new float[3];

        for (int p = 0; p < pixelCount; p++) {
            int idx = p * 3;
            if (idx + 2 >= origPixels.length || idx + 2 >= modelPixels.length || idx + 2 >= outPixels.length) {
                break;
            }
            orig[0] = origPixels[idx];
            orig[1] = origPixels[idx + 1];
            orig[2] = origPixels[idx + 2];

            model[0] = modelPixels[idx];
            model[1] = modelPixels[idx + 1];
            model[2] = modelPixels[idx + 2];

            composeColorRgb(orig, model, out, cfg);

            outPixels[idx] = out[0];
            outPixels[idx + 1] = out[1];
            outPixels[idx + 2] = out[2];
        }
    }

    @Override
    public synchronized void close() {
        release();
    }

    public synchronized void release() {
        initialized = false;
        status = Status.BYPASS;
        resolvedModelDll = null;
        resolvedLoaderDll = null;
    }
}
