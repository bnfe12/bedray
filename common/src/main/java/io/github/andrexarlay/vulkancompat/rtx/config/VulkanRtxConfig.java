package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerSwitch;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Configuration for the Bedray. Reads and writes {@code config/bedray.toml} (UTF-8, no BOM).
 * <p>
 * Options persisted: {@code active_backend} (BILINEAR or XESS; STUB / unavailable values are rejected on load),
 * {@code xess_mode},
 * {@code quality_preset} (3D render target scale), {@code custom_scale} and {@code enable_hardware_ray_tracing}.
 * {@code upscaler_type} is derived from the preset ({@link #syncUpscalerTypeWithPreset()}), not persisted.
 */
public final class VulkanRtxConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Config");

    /** The voxel option uses the separate bounded software renderer with model textures and world light. */
    public enum RendererSelection { HARDWARE_RT, VOXEL_LIVE_DEBUG }
    public static RendererSelection rendererSelection = RendererSelection.HARDWARE_RT;
    public static boolean voxelDistanceAuto = true;
    public static int voxelDistanceChunks = 12;
    public static int voxelMemoryBudgetMiB = 256;
    public static String voxelQualityProfile = "CUSTOM";
    public static String pathQualityProfile = "CUSTOM";
    public static boolean voxelDiffuseBounce = true;
    public static boolean voxelTranslucentOptics = true;
    public static boolean voxelDiffuseBounce() { return voxelDiffuseBounce; }
    public static boolean voxelTranslucentOptics() { return voxelTranslucentOptics; }
    public static void setVoxelDiffuseBounce(boolean value) { voxelDiffuseBounce = value; }
    public static void setVoxelTranslucentOptics(boolean value) { voxelTranslucentOptics = value; }
    public static boolean voxelDistanceAuto() { return voxelDistanceAuto; }
    public static int voxelDistanceChunks() { return Math.clamp(voxelDistanceChunks, 2, 64); }
    public static int voxelMemoryBudgetMiB() { return Math.clamp(voxelMemoryBudgetMiB, 64, 1024); }
    public static void setVoxelDistanceAuto(boolean value) { voxelDistanceAuto = value; }
    public static void setVoxelDistanceChunks(int value) { voxelDistanceChunks = Math.clamp(value, 2, 64); }
    public static void setVoxelMemoryBudgetMiB(int value) { voxelMemoryBudgetMiB = Math.clamp(value, 64, 1024); }
    public static String voxelQualityProfile() { return qualityProfile(voxelQualityProfile); }
    public static String pathQualityProfile() { return qualityProfile(pathQualityProfile); }
    public static void setVoxelQualityProfile(String value) { voxelQualityProfile = qualityProfile(value); }
    public static void setPathQualityProfile(String value) { pathQualityProfile = qualityProfile(value); }
    private static String qualityProfile(String value) {
        if (value == null) return "CUSTOM";
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) { case "FAST", "BEAUTIFUL", "CUSTOM" -> normalized; default -> "CUSTOM"; };
    }
    private static boolean rendererUiOverride;
    private static GraphicsBackendRegistry.SceneMode diagnosticScene = GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;

    /** Startup diagnostic properties remain session-only until the player explicitly selects a renderer. */
    public static GraphicsBackendRegistry.SceneMode selectedComputeScene() {
        if (!rendererUiOverride) {
            if (Boolean.getBoolean("bedray.compute.world")) return GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
            if (Boolean.getBoolean("bedray.compute.demo")) return GraphicsBackendRegistry.SceneMode.SYNTHETIC;
        }
        return rendererSelection == RendererSelection.VOXEL_LIVE_DEBUG
            ? diagnosticScene : GraphicsBackendRegistry.SceneMode.OFF;
    }

    /** Diagnostic preference is independent of the main hardware/voxel selection. */
    public static GraphicsBackendRegistry.SceneMode computeDiagnosticScene() {
        if (!rendererUiOverride) {
            if (Boolean.getBoolean("bedray.compute.world")) return GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
            if (Boolean.getBoolean("bedray.compute.demo")) return GraphicsBackendRegistry.SceneMode.SYNTHETIC;
        }
        return diagnosticScene;
    }

    public static RendererSelection selectedRenderer() {
        return voxelRendererSelected() ? RendererSelection.VOXEL_LIVE_DEBUG : RendererSelection.HARDWARE_RT;
    }

    public static boolean voxelRendererSelected() {
        return selectedComputeScene() != GraphicsBackendRegistry.SceneMode.OFF;
    }

    /** Applies an explicit in-game choice. The independent hardware RT mode remains saved for switching back. */
    public static void setRendererSelection(RendererSelection selection) {
        diagnosticScene = computeDiagnosticScene();
        rendererSelection = java.util.Objects.requireNonNull(selection, "selection");
        rendererUiOverride = true;
        applyRendererSelection();
    }

    /** Changes only the diagnostic preference; the main control remains the sole owner of hardware/voxel selection. */
    public static void setComputeScene(GraphicsBackendRegistry.SceneMode scene) {
        java.util.Objects.requireNonNull(scene, "scene");
        if (scene == GraphicsBackendRegistry.SceneMode.OFF) {
            throw new IllegalArgumentException("Use renderer selection to disable voxel diagnostics");
        }
        rendererSelection = selectedRenderer();
        rendererUiOverride = true;
        diagnosticScene = scene;
        applyRendererSelection();
    }

    /** Called on the render thread after runtime initialization, never from the static config loader. */
    public static void applyRendererSelection() {
        if (voxelRendererSelected()) {
            enforceComputeCompatibility();
        }
        enableHardwareRayTracing = !voxelRendererSelected() && rtMode.isActive();
        GraphicsBackendRegistry.setSceneMode(selectedComputeScene());
    }

    /** Compute diagnostics do not yet produce depth/motion guides for temporal upscalers or frame generation. */
    public static void enforceComputeCompatibility() {
        if (FrameGenSettings.mode() != FrameGenSettings.Mode.OFF) {
            FrameGenSettings.setMode(FrameGenSettings.Mode.OFF);
            LOGGER.info("[Bedray] frame generation disabled for the voxel diagnostic renderer");
        }
        if (activeBackend != null && activeBackend != UpscalerBackend.BILINEAR) {
            applyUpscaler(UpscalerBackend.BILINEAR, null, null, "voxel_renderer");
            LOGGER.info("[Bedray] temporal upscaler replaced with built-in bilinear for the voxel diagnostic renderer");
        }
    }

    // STUB: only used by unreferenced stub layers. No mode switching exists; world rendering is never replaced.
    public enum RtxMode {
        NATIVE_RTX,
        SHADERPACK_ACCELERATION,
        PURE_UPSCALING,
        OFF
    }

    /**
     * OFF: 3D target at full resolution. BILINEAR: 3D target scaled by {@link QualityPreset}, stretched to the
     * swapchain by the (bilinear) blit in MixinVulkanGpuSurface. The remaining values are STUB: not implemented,
     * rejected on load, never shown in the UI.
     */
    public enum UpscalerType {
        OFF,
        BILINEAR,
        // STUB: DLSS not implemented (needs NVIDIA Streamline, Phase 6)
        DLSS_3_7,
        // STUB: FSR not implemented (needs FidelityFX Vulkan backend, Phase 4)
        FSR_3_1,
        // STUB: XeSS not implemented (Phase 4)
        XESS_1_3,
        // STUB: NIS not implemented
        NIS;

        /** True only for values that have a real implementation. */
        public boolean isImplemented() {
            return this == OFF || this == BILINEAR;
        }
    }

    public enum QualityPreset {
        ULTRA_PERFORMANCE(0.333f),
        PERFORMANCE(0.500f),
        BALANCED(0.667f),
        QUALITY(0.770f),
        NATIVE(1.000f),
        CUSTOM(1.000f);

        private final float scaleFactor;

        QualityPreset(float scaleFactor) {
            this.scaleFactor = scaleFactor;
        }

        public float getScaleFactor() {
            if (this == CUSTOM) {
                return VulkanRtxConfig.customScalePercent / 100.0f;
            }
            return scaleFactor;
        }
    }

    // STUB: NRD not integrated.
    public enum DenoiserType {
        NRD_REBLUR,
        NRD_RELAX,
        OFF
    }

    public enum FrameGenerationSetting {
        OFF("Off"),
        AUTO("Auto"),
        ON("On");

        private final String label;
        FrameGenerationSetting(String label) {
            this.label = label;
        }
        public String getLabel() {
            return label;
        }
    }

    public enum FrameGenMultiplier {
        OFF("1x / Off", 1),
        X2("2x (DLSS 3 / FSR 3)", 2),
        X3("3x", 3),
        X4("4x", 4);

        private final String label;
        private final int multiplier;

        FrameGenMultiplier(String label, int multiplier) {
            this.label = label;
            this.multiplier = multiplier;
        }

        public String getLabel() {
            return label;
        }

        public int getMultiplier() {
            return multiplier;
        }

        public boolean isEnabled() {
            return this != OFF;
        }
    }

    public static FrameGenerationSetting frameGenerationSetting = FrameGenerationSetting.OFF;



    // ---- Real, persisted options (safe defaults: no scaling) ----
    public static UpscalerType upscalerType = UpscalerType.OFF;
    /**
     * Implemented backends: BILINEAR, XESS (needs libxess.dll) and DLSS (needs nvngx_dlss.dll + vkb_dlss.dll installed
     * through Bedray Core's NativeLibraries and an NVIDIA RTX GPU). XESS/DLSS switch live when their device requirements were
     * registered at startup (preregistration, see UpscalerPreregistration), otherwise they take effect after a restart.
     * FSR (official AMD FidelityFX Vulkan DLL, FsrLibrary) switches live like XeSS/DLSS. A backend that is not usable is
     * reset to BILINEAR on load with a WARN.
     */
    public static UpscalerBackend activeBackend = UpscalerBackend.BILINEAR;
    /** XeSS quality mode (persisted as {@code xess_mode}); must be a member of {@code UpscalerModes.modesFor(XESS)}. */
    public static UpscalerMode xessMode = UpscalerModes.defaultMode(UpscalerBackend.XESS);
    /** DLSS quality mode (persisted as {@code dlss_mode}); must be a member of {@code UpscalerModes.modesFor(DLSS)}. */
    public static UpscalerMode dlssMode = UpscalerModes.defaultMode(UpscalerBackend.DLSS);
    /** FSR quality mode (persisted as {@code fsr_mode}); must be a member of {@code UpscalerModes.modesFor(FSR)}. */
    public static UpscalerMode fsrMode = UpscalerModes.defaultMode(UpscalerBackend.FSR);
    /** FSR provider version (persisted as {@code fsr_version}): AUTO, FSR_3_1 or FSR_2 (only those present in the DLL apply). */
    public static FsrVersion fsrVersion = FsrVersion.AUTO;
    /** FSR RCAS sharpness 0..1 (persisted as {@code fsr_sharpness}); 0 disables the sharpening pass. Per dispatch, no re-init. */
    public static float fsrSharpness = 0.0f;
    /** FSR 1 (built-in) mode (persisted as {@code fsr1_mode}); member of {@code UpscalerModes.modesFor(FSR1)}. */
    public static UpscalerMode fsr1Mode = UpscalerModes.defaultMode(UpscalerBackend.FSR1);
    /** FSR 1 RCAS sharpening 0..1 (persisted as {@code fsr1_sharpness}); 0 skips the RCAS pass. Default 0.3 (was 0.8: measured sparkle on noisy RT frames, see docs). */
    public static float fsr1Sharpness = 0.3f;
    /** NIS sharpness slider 0..1 of NVScalerUpdateConfig (persisted as {@code nis_sharpness}); default 0.2 (was 0.5: spark_pm 2.0 vs 0.13 at 0.25 on the lava scene). */
    public static float nisSharpness = 0.2f;
    /** User render scale per backend in percent, used when that backend's mode is CUSTOM ({@code <backend>_custom_scale}). */
    private static final java.util.EnumMap<UpscalerBackend, Integer> customPercents = new java.util.EnumMap<>(UpscalerBackend.class);
    /**
     * Test override for the directory searched for native libraries. Unset means "ask the platform" (see {@link #gameDir()}):
     * the static field used to default to the current working directory, which on a launcher-started client is not the game
     * directory, so an installed {@code libxess.dll} looked missing and {@link #enforceBackendAvailability()} reset XeSS to
     * BILINEAR while {@code XessRuntime} (which reads {@code Minecraft.gameDirectory}) would have loaded it.
     */
    private static java.nio.file.Path gameDirOverride;
    public static QualityPreset qualityPreset = QualityPreset.NATIVE;
    public static int customScalePercent = 100;
    /** Auto negative Mipmap LOD bias based on upscaler render scale. */
    public static boolean autoTextureLodBias = true;
    /** Additional LOD bias offset (e.g. -0.25 to -0.5 for extra pixel-art sharpness). */
    public static float textureLodBiasOffset = 0.0f;
    /** Manual LOD bias when autoTextureLodBias is false. */
    public static float manualTextureLodBias = 0.0f;
    /** Use point sampling for magnified textures to preserve the original pixel grid. */
    public static boolean pointSamplingSharpness = false;
    /** Active resource pack texture width in pixels; 16 is the vanilla baseline. */
    public static int resourcePackResolution = 16;
    /**
     * Master switch of the hardware world ray/path tracing renderer: true when the hardware renderer is selected and {@link #rtMode} is not OFF (kept as a field because
     * WorldTargetManager and the PoC engine read it). Written by {@link #setRtMode}; legacy value of the old toml key
     * {@code enable_hardware_ray_tracing}.
     */
    public static boolean enableHardwareRayTracing = false;
    /** Effective mode of this session (the {@code -Dvkrtx.rt.mode} override, else the persisted one). */
    public static RtMode rtMode = RtMode.OFF;
    /** The mode written to the toml file: a session override from {@code -Dvkrtx.rt.mode} is never persisted. */
    private static RtMode rtModePersisted = RtMode.OFF;
    private static boolean rtModeSeenInFile = false;

    /**
     * Hidden switch for the not yet public "Ray Tracing (Fast)" mode ({@code rays}, plus the partial modes ao/direct): toml key
     * {@code experimental_rays}. The code stays in the jar; without this flag (or {@code -Dvkrtx.experimental.rays=true}, or a
     * session override {@code -Dvkrtx.rt.mode=rays|ao|direct}) the settings page offers Off and Path Tracing only and a saved
     * {@code rt_mode = "rays"} is switched to path when the file is loaded.
     */
    public static boolean experimentalRays = false;
    /** Shows the Expert and Debug groups on the Bedray Advanced settings page (toml key ui_expert, or -Dvkrtx.ui.expert=true). */
    public static boolean uiExpert = false;
    /** Set for this session by {@code -Dvkrtx.rt.mode=rays|ao|direct} (autotests): the hidden mode is then usable and shown. */
    private static boolean raysUnlockedBySession = false;

    /** Whether the hidden Ray Tracing (Fast) mode is available in this session. */
    public static boolean raysUnlocked() {
        return experimentalRays || raysUnlockedBySession || Boolean.getBoolean("vkrtx.experimental.rays");
    }

    /**
     * Soft migration (called after the toml was read): a persisted non-public mode without the experimental flag becomes Path Tracing.
     *
     * @return true when the mode was switched
     */
    public static boolean migrateHiddenRtMode() {
        if (raysUnlocked() || rtMode.isPublic()) {
            return false;
        }
        LOGGER.info("[Bedray] rt_mode '{}' is experimental and hidden in this release (set experimental_rays = true to use it); switched to 'path'",
            rtMode.logName());
        setRtMode(RtMode.PATH_TRACED);
        return true;
    }

    /** Sets the mode from the settings page: stored (persisted) and effective. */
    public static void setRtMode(RtMode mode) {
        rtMode = mode;
        rtModePersisted = mode;
        enableHardwareRayTracing = !voxelRendererSelected() && mode.isActive();
    }

    /**
     * Mode used by the renderer: also honours a direct write of {@link #enableHardwareRayTracing} (autotests, tools).
     * Such a caller says "trace" without naming a mode, and path tracing is the fuller frame of the two profiles the
     * Slang pass runs, so an unnamed request gets {@code PATH_TRACED} rather than the ray-effects profile.
     */
    public static RtMode effectiveRtMode() {
        if (voxelRendererSelected()) {
            return RtMode.OFF;
        }
        if (rtMode == RtMode.OFF && enableHardwareRayTracing) {
            return RtMode.PATH_TRACED;
        }
        return rtMode;
    }

    /** Applies {@code -Dvkrtx.rt.mode=off|rays|path} for this session only; an invalid value is logged and ignored (rule 4). */
    public static void applyRtModeOverride(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        RtMode parsed = RtMode.parse(value);
        if (parsed == null) {
            LOGGER.error("[VKB-RT] invalid -Dvkrtx.rt.mode={} (use off|rays|path), keeping {}", value, rtMode);
            return;
        }
        rtMode = parsed;
        enableHardwareRayTracing = !voxelRendererSelected() && parsed.isActive();
        if (!parsed.isPublic()) {
            raysUnlockedBySession = true;
        }
        LOGGER.info("[VKB-RT] mode override -Dvkrtx.rt.mode={} (session only, not saved)", parsed.logName());
    }

    /** Custom render percent of {@code backend} (default 67, clamped to the technology range). */
    public static int customPercent(UpscalerBackend backend) {
        return UpscalerModes.clampCustomPercent(backend, customPercents.getOrDefault(backend, 67));
    }

    public static void setCustomPercent(UpscalerBackend backend, int percent) {
        customPercents.put(backend, UpscalerModes.clampCustomPercent(backend, percent));
    }

    /** Stored mode of {@code backend} (null for BILINEAR). */
    public static UpscalerMode modeOf(UpscalerBackend backend) {
        return switch (backend) {
            case BILINEAR -> null;
            case XESS -> xessMode;
            case DLSS -> dlssMode;
            case FSR -> fsrMode;
            case FSR1 -> fsr1Mode;
            case NIS -> UpscalerMode.CUSTOM;
        };
    }

    /** Re-applies the stored choice of the active backend (mode, custom percent) to the runtimes (after a slider change). */
    public static UpscalerSwitch.Outcome reapplyUpscaler(String reason) {
        return applyUpscalerFull(activeBackend, null, null, null, null, null, reason);
    }

    /**
     * Full form of {@link #applyUpscaler}: also the FSR1 mode ({@code fsr1}, null keeps the stored one). The custom percent of
     * the selected backend comes from {@link #customPercent}. Does not save the file.
     */
    public static UpscalerSwitch.Outcome applyUpscalerFull(UpscalerBackend backend, UpscalerMode xess, UpscalerMode dlss,
                                                           UpscalerMode fsr, FsrVersion fsrVer, UpscalerMode fsr1, String reason) {
        activeBackend = voxelRendererSelected() && backend != null && backend != UpscalerBackend.BILINEAR
            ? UpscalerBackend.BILINEAR : backend;
        enforceBackendAvailability();
        if (xess != null) {
            xessMode = xess;
        }
        if (dlss != null) {
            dlssMode = dlss;
        }
        if (fsr != null) {
            fsrMode = fsr;
        }
        if (fsrVer != null) {
            fsrVersion = fsrVer;
        }
        if (fsr1 != null) {
            fsr1Mode = fsr1;
        }
        syncUpscalerTypeWithPreset();
        UpscalerMode spatial = activeBackend.isSpatial() ? modeOf(activeBackend) : null;
        return UpscalerSwitch.apply(activeBackend, xessMode, dlssMode, fsrMode, fsrVersion, spatial, customPercent(activeBackend), reason);
    }

    // ---- STUB, never persisted, never shown in UI, no reader in the render path ----
    // STUB: DLSS/FSR frame generation not implemented. Always OFF/false.
    public static FrameGenMultiplier dlssFrameGenMultiplier = FrameGenMultiplier.OFF;
    public static FrameGenMultiplier fsrFrameGenMultiplier = FrameGenMultiplier.OFF;
    public static boolean enableDlssFrameGen = false;
    // STUB: DLSS Ray Reconstruction not implemented (Phase 6). Always false.
    public static boolean enableDlssRayReconstruction = false;

    // STUB: read only by unreferenced stub layers (RtxRayTracingPipeline, NrdDenoiserPipeline).
    public static RtxMode mode = RtxMode.OFF;
    public static float sharpness = 0.0f;
    public static DenoiserType denoiserType = DenoiserType.OFF;
    public static boolean enableDirectLight = false;
    public static boolean enableEmissiveBlocks = false;
    public static boolean enablePbrReflections = false;
    public static int rayBounces = 0;
    public static int aoSamples = 0;

    private static File configFile = ConfigFiles.resolve(new File("config/bedray.toml"));

    static {
        load();
        applyRtModeOverride(System.getProperty("vkrtx.rt.mode"));
    }

    /**
     * Crash protection: Until 3D target is separated (Phase 4.1, separate-3d-target.md),
     * scaling the main render target causes scissor exceptions and shrinks the GUI (known pitfall: scissor bounds).
     * Render buffer is forced to 100% (Native) by returning false here.
     */
    public static boolean isScalingEnabled() {
        return false;
    }

    /**
     * Checks whether scaling is configured by the user, regardless of runtime crash protection.
     */
    public static boolean isScalingConfigured() {
        return upscalerType != UpscalerType.OFF && activeBackend != null && qualityPreset.getScaleFactor() < 1.0f;
    }

    public static float getEffectiveScaleFactor() {
        return qualityPreset.getScaleFactor();
    }

    /**
     * The only implemented upscaler is the bilinear stretch of the scaled 3D target, so "scaling is on" is exactly
     * "preset scale below 100%". Keeps {@link #upscalerType} (read by WorldTargetManager) consistent with the preset.
     */
    public static void syncUpscalerTypeWithPreset() {
        upscalerType = qualityPreset.getScaleFactor() < 1.0f ? UpscalerType.BILINEAR : UpscalerType.OFF;
    }

    /**
     * Config API for changing the upscaler while the game runs (settings page, autotest scenarios): stores the choice,
     * keeps {@link #upscalerType} in line with the preset, and hands the request to the runtimes
     * ({@link UpscalerSwitch}: live when the device requirements were registered at startup, otherwise only stored with the
     * outcome DEFERRED_RESTART). A backend that cannot be made active is replaced by BILINEAR with a WARN, as on load.
     * Does not save the file (the settings page saves through its storage handler).
     */
    public static UpscalerSwitch.Outcome applyUpscaler(UpscalerBackend backend, UpscalerMode xess, UpscalerMode dlss, String reason) {
        return applyUpscaler(backend, xess, dlss, null, null, reason);
    }

    /** As {@link #applyUpscaler(UpscalerBackend, UpscalerMode, UpscalerMode, String)} plus FSR mode / version (null keeps the stored one). */
    public static UpscalerSwitch.Outcome applyUpscaler(UpscalerBackend backend, UpscalerMode xess, UpscalerMode dlss,
                                                       UpscalerMode fsr, FsrVersion fsrVer, String reason) {
        return applyUpscalerFull(backend, xess, dlss, fsr, fsrVer, null, reason);
    }

    /** Redirects the directory searched for native libraries (unit tests only). */
    public static void setGameDirForTesting(java.nio.file.Path dir) {
        gameDirOverride = dir;
    }

    /**
     * The directory the native libraries are searched in: the platform game directory (loader system property, then
     * {@code Minecraft.gameDirectory}, then the working directory) unless a test redirected it.
     */
    public static java.nio.file.Path gameDir() {
        return gameDirOverride != null
            ? gameDirOverride
            : io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerLibraryLoader.resolveGameDirectory();
    }

    /**
     * Makes {@link #activeBackend} one that can really run (implemented + library present). Falls back to BILINEAR
     * with a WARN. Called after load and by the settings page before a new backend is stored.
     */
    public static void enforceBackendAvailability() {
        activeBackend = UpscalerSelection.resolve(activeBackend, gameDir());
    }

    /** Redirects the config file (unit tests only). */
    public static void setConfigFileForTesting(File file) {
        configFile = file;
    }

    /**
     * Applies one {@code key = value} pair from the toml file. Public for unit tests.
     * STUB values (DLSS/FSR/XESS backends, frame generation, ray reconstruction) are rejected with a WARN.
     */
    public static void applyKeyValue(String key, String val) {
        try {
            switch (key) {
                case "renderer" -> rendererSelection = RendererSelection.valueOf(val.toUpperCase(java.util.Locale.ROOT));
                case "voxel_distance_auto" -> setVoxelDistanceAuto(Boolean.parseBoolean(val));
                case "voxel_distance_chunks" -> setVoxelDistanceChunks(Integer.parseInt(val));
                case "voxel_memory_budget_mib" -> setVoxelMemoryBudgetMiB(Integer.parseInt(val));
                case "voxel_quality_profile" -> setVoxelQualityProfile(val);
                case "path_quality_profile" -> setPathQualityProfile(val);
                case "voxel_diffuse_bounce" -> setVoxelDiffuseBounce(Boolean.parseBoolean(val));
                case "voxel_translucent_optics" -> setVoxelTranslucentOptics(Boolean.parseBoolean(val));
                case "upscaler_backend", "active_backend" -> {
                    UpscalerBackend parsed;
                    try {
                        parsed = UpscalerBackend.valueOf(val.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Unknown upscaler backend '{}', using BILINEAR", val);
                        parsed = UpscalerBackend.BILINEAR;
                    }
                    if (!parsed.isImplemented()) {
                        LOGGER.warn("Upscaler backend '{}' is not usable ({}), using BILINEAR", val,
                            parsed == UpscalerBackend.DLSS
                                ? io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability.unusableReason()
                                : parsed == UpscalerBackend.FSR
                                    ? io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary.library().reason()
                                    : "not implemented (STUB)");
                        parsed = UpscalerBackend.BILINEAR;
                    }
                    activeBackend = parsed;
                }
                case "xess_mode" -> {
                    UpscalerMode parsed = UpscalerMode.valueOf(val.toUpperCase());
                    if (UpscalerModes.isOffered(UpscalerBackend.XESS, parsed)) {
                        xessMode = parsed;
                    } else {
                        LOGGER.warn("xess_mode '{}' is not offered by XeSS, keeping {}", val, xessMode);
                    }
                }
                case "dlss_mode" -> {
                    UpscalerMode parsed = UpscalerMode.valueOf(val.toUpperCase());
                    if (UpscalerModes.isOffered(UpscalerBackend.DLSS, parsed)) {
                        dlssMode = parsed;
                    } else {
                        LOGGER.warn("dlss_mode '{}' is not offered by DLSS, keeping {}", val, dlssMode);
                    }
                }
                case "fsr_mode" -> {
                    UpscalerMode parsed = UpscalerMode.valueOf(val.toUpperCase());
                    if (UpscalerModes.isOffered(UpscalerBackend.FSR, parsed)) {
                        fsrMode = parsed;
                    } else {
                        LOGGER.warn("fsr_mode '{}' is not offered by FSR 3.1/2 (no ULTRA_QUALITY), keeping {}", val, fsrMode);
                    }
                }
                case "fsr_version" -> fsrVersion = FsrVersion.valueOf(val.toUpperCase());
                case "fsr_sharpness" -> {
                    float s = Float.parseFloat(val);
                    if (s >= 0.0f && s <= 1.0f) {
                        fsrSharpness = s;
                    } else {
                        LOGGER.warn("fsr_sharpness {} is outside 0..1, keeping {}", val, fsrSharpness);
                    }
                }
                case "fsr1_mode" -> {
                    UpscalerMode parsed = UpscalerMode.valueOf(val.toUpperCase());
                    if (UpscalerModes.isOffered(UpscalerBackend.FSR1, parsed)) {
                        fsr1Mode = parsed;
                    } else {
                        LOGGER.warn("fsr1_mode '{}' is not offered by FSR 1, keeping {}", val, fsr1Mode);
                    }
                }
                case "fsr1_sharpness", "nis_sharpness" -> {
                    float s = Float.parseFloat(val);
                    if (s >= 0.0f && s <= 1.0f) {
                        if (key.equals("fsr1_sharpness")) {
                            fsr1Sharpness = s;
                        } else {
                            nisSharpness = s;
                        }
                    } else {
                        LOGGER.warn("{} {} is outside 0..1, ignored", key, val);
                    }
                }
                case "xess_custom_scale", "dlss_custom_scale", "fsr_custom_scale", "fsr1_custom_scale", "nis_custom_scale" -> {
                    UpscalerBackend b = UpscalerBackend.valueOf(key.substring(0, key.indexOf("_custom_scale")).toUpperCase());
                    int p = Integer.parseInt(val);
                    if (p >= UpscalerModes.customMinPercent(b) && p <= UpscalerModes.CUSTOM_MAX_PERCENT) {
                        setCustomPercent(b, p);
                    } else {
                        LOGGER.warn("{} {} is outside {}..{}, keeping {}", key, val, UpscalerModes.customMinPercent(b),
                            UpscalerModes.CUSTOM_MAX_PERCENT, customPercent(b));
                    }
                }
                case "upscaler_type" -> {
                    // Derived from quality_preset (syncUpscalerTypeWithPreset); the stored value is only validated.
                    UpscalerType parsed = UpscalerType.valueOf(val.toUpperCase());
                    if (!parsed.isImplemented()) {
                        LOGGER.warn("upscaler_type '{}' is not implemented (STUB), ignored", val);
                    }
                }
                case "quality_preset" -> {
                    String upper = val.toUpperCase();
                    switch (upper) {
                        case "NATIVE_AA" -> qualityPreset = QualityPreset.NATIVE;
                        case "ULTRA_QUALITY" -> qualityPreset = QualityPreset.QUALITY;
                        default -> {
                            try {
                                qualityPreset = QualityPreset.valueOf(upper);
                            } catch (IllegalArgumentException e) {
                                LOGGER.warn("Unknown quality preset '{}', using NATIVE", val);
                                qualityPreset = QualityPreset.NATIVE;
                            }
                        }
                    }
                }
                case "custom_scale" -> {
                    try {
                        int percent = Integer.parseInt(val);
                        if (percent >= 25 && percent <= 100) {
                            customScalePercent = percent;
                        } else {
                            LOGGER.warn("custom_scale {} is outside 25..100, keeping {}", percent, customScalePercent);
                        }
                    } catch (NumberFormatException e) {
                        LOGGER.warn("Invalid custom_scale '{}', keeping {}", val, customScalePercent);
                    }
                }
                case "auto_texture_lod_bias", "texture_lod_bias_auto" -> {
                    boolean b = Boolean.parseBoolean(val);
                    autoTextureLodBias = b;
                    TextureLodBias.autoTextureLodBias = b;
                }
                case "texture_lod_bias_offset", "lod_bias_offset" -> {
                    try {
                        float f = Float.parseFloat(val);
                        textureLodBiasOffset = f;
                        TextureLodBias.textureLodBiasOffset = f;
                    } catch (NumberFormatException e) {
                        LOGGER.warn("Invalid texture_lod_bias_offset '{}', keeping {}", val, textureLodBiasOffset);
                    }
                }
                case "texture_lod_bias", "manual_texture_lod_bias" -> {
                    try {
                        float f = Float.parseFloat(val);
                        manualTextureLodBias = f;
                        TextureLodBias.manualTextureLodBias = f;
                    } catch (NumberFormatException e) {
                        LOGGER.warn("Invalid texture_lod_bias '{}', keeping {}", val, manualTextureLodBias);
                    }
                }
                case "point_sampling_sharpness", "point_sampling", "pixel_sharpness" -> {
                    boolean enabled = Boolean.parseBoolean(val);
                    pointSamplingSharpness = enabled;
                    TextureLodBias.setPointSamplingSharpness(enabled);
                }
                case "resource_pack_resolution", "pack_resolution" -> {
                    try {
                        int resolution = Integer.parseInt(val);
                        if (resolution > 0) {
                            resourcePackResolution = resolution;
                            TextureLodBias.resourcePackResolution = resolution;
                        } else {
                            LOGGER.warn("resource_pack_resolution {} must be positive; keeping {}", resolution, resourcePackResolution);
                        }
                    } catch (NumberFormatException e) {
                        LOGGER.warn("Invalid resource_pack_resolution '{}', keeping {}", val, resourcePackResolution);
                    }
                }
                case "dlss_frame_gen_multiplier", "fsr_frame_gen_multiplier", "enable_dlss_frame_gen",
                     "enable_frame_generation", "enable_dlss_ray_reconstruction", "enable_ray_reconstruction" -> {
                    if (!val.equalsIgnoreCase("false") && !val.equalsIgnoreCase("off")) {
                        LOGGER.warn("'{}' = '{}' is not implemented (STUB: frame generation / ray reconstruction), ignored", key, val);
                    }
                }
                case "framegen_mode", "reflex_mode", "framegen_frame_limit" ->
                    io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings.applyKeyValue(key, val);
                case "rt_mode" -> {
                    RtMode parsed = RtMode.parse(val);
                    if (parsed == null) {
                        LOGGER.warn("Unknown rt_mode '{}' (use off|rays|path), using off", val);
                        parsed = RtMode.OFF;
                    }
                    rtModeSeenInFile = true;
                    setRtMode(parsed);
                }
                case "experimental_rays" -> experimentalRays = Boolean.parseBoolean(val);
                case "ui_expert" -> uiExpert = Boolean.parseBoolean(val);
                case "enable_hardware_ray_tracing" -> {
                    // Legacy key of the single on/off toggle, only read by a file that has no rt_mode. It said "trace", not
                    // "with which renderer", and path tracing is the fuller of the two profiles the Slang pass runs, so the
                    // file gets the path profile rather than the ray-effects one it never named.
                    if (!rtModeSeenInFile) {
                        setRtMode(Boolean.parseBoolean(val) ? RtMode.PATH_TRACED : RtMode.OFF);
                    }
                }
                // RT mode settings; false = a key of an older config file that no longer exists (ignored on purpose)
                default -> {
                    if (key.startsWith("atm_")) {
                        // light and atmosphere options (light shafts, bloom, glare, haze); a -Dvkrtx.rt.<name> of the session wins
                        String prop = "vkrtx.rt." + key.substring(4).replace('_', '.');
                        // the old default bloom strength (0.8) was far too visible: a file that still holds it takes the new default
                        boolean oldBloomDefault = key.equals("atm_bloom_strength") && Math.abs(Double.parseDouble(val) - 0.8) < 1.0e-6;
                        if (System.getProperty(prop) == null && !oldBloomDefault) {
                            io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings.set(key.substring(4), Double.parseDouble(val));
                        }
                    } else {
                        RtSettings.applyKeyValue(key, val);
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid config value for '{}': {}", key, val);
        }
    }

    /**
     * The runtime request starts from the pre-device reads (UpscalerRequest.fromEarly), which know neither the built-in
     * spatial upscalers nor custom percents. Once the file is loaded, a built-in backend or a CUSTOM mode is handed to the
     * request so the first world frame already uses it. Temporal backends in preset modes are left to the early request.
     */
    private static void syncRequestWithLoadedConfig() {
        UpscalerMode mode = modeOf(activeBackend);
        if (!activeBackend.isSpatial() && mode != UpscalerMode.CUSTOM) {
            return;
        }
        try {
            UpscalerSwitch.Outcome o = reapplyUpscaler("config_load");
            LOGGER.info("[VKB-AUTOTEST] upscaler_config_load backend={} mode={} custom={} outcome={}", activeBackend, mode,
                customPercent(activeBackend), o);
        } catch (IllegalArgumentException e) {
            LOGGER.error("[VKB-AUTOTEST] upscaler_config_load FAILED backend={} mode={}: {}", activeBackend, mode, e.getMessage(), e);
        }
    }

    public static void load() {
        rtModeSeenInFile = false;
        RtSettings.presetSeen = false;
        RtSettings.pathPresetSeen = false;
        RtSettings.skipPresetValues = false;
        if (!configFile.exists()) {
            syncUpscalerTypeWithPreset();
            save();
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("﻿")) line = line.substring(1).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;

                int eqIdx = line.indexOf('=');
                if (eqIdx < 0) continue;

                String key = line.substring(0, eqIdx).trim();
                String val = line.substring(eqIdx + 1).trim().replace("\"", "");
                applyKeyValue(key, val);
            }
            if (!RtSettings.presetSeen) {
                RtSettings.preset = RtSettings.Preset.CUSTOM;   // older file: its individual values / rt_mode stay as they are
            }
            if (!RtSettings.pathPresetSeen) {
                RtSettings.pathPreset = RtSettings.PathPreset.CUSTOM;   // older file: its path values stay as they are
            }
            migrateHiddenRtMode();
            io.github.andrexarlay.vulkancompat.sodium.SettingsPolicy.enforce();
            enforceBackendAvailability();
            syncUpscalerTypeWithPreset();
            syncRequestWithLoadedConfig();
            enableHardwareRayTracing = !voxelRendererSelected() && rtMode.isActive();
            TextureLodBias.autoTextureLodBias = autoTextureLodBias;
            TextureLodBias.textureLodBiasOffset = textureLodBiasOffset;
            TextureLodBias.manualTextureLodBias = manualTextureLodBias;
            TextureLodBias.setPointSamplingSharpness(pointSamplingSharpness);
            TextureLodBias.resourcePackResolution = resourcePackResolution;
            LOGGER.info("Loaded Vulkan config: backend={}, xessMode={}, upscaler={}, quality={}, customScale={}%, rtMode={}, hardwareRayTracing={}",
                activeBackend, xessMode, upscalerType, qualityPreset, customScalePercent, rtMode.logName(), enableHardwareRayTracing);
        } catch (IOException e) {
            LOGGER.error("Failed to load bedray.toml config: {}", e.getMessage());
        }
    }

    public static void save() {
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (BufferedWriter writer = Files.newBufferedWriter(configFile.toPath(), StandardCharsets.UTF_8)) {
            writeAll(writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save bedray.toml config: {}", e.getMessage());
        }
    }

    /** The whole bedray.toml text (also the source of the user presets, sodium.PresetStore). */
    public static void writeAll(java.io.Writer writer) throws IOException {
        {
            writer.write("# Bedray configuration\n\n");
            writer.write("[super_resolution]\n");
            writer.write("# BILINEAR, FSR1, NIS (built-in, any GPU), XESS (needs libxess.dll), DLSS (needs nvngx_dlss.dll + vkb_dlss.dll (see docs/INSTALL.md) and an NVIDIA RTX GPU)\n");
            writer.write("# or FSR (needs the official AMD amd_fidelityfx_vk.dll of FidelityFX SDK v1.1.4 (see docs/INSTALL.md)).\n");
            writer.write("# XESS/DLSS/FSR switch live in the settings page; only when their Vulkan device requirements were not registered at startup (library missing then, or -Dvkrtx.upscaler.preregister=false) the choice applies after a restart.\n");
            writer.write("active_backend = \"" + activeBackend.name() + "\"\n");
            writer.write("# XeSS mode: ULTRA_QUALITY_PLUS, ULTRA_QUALITY, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE\n");
            writer.write("xess_mode = \"" + xessMode.name() + "\"\n");
            writer.write("# DLSS mode: DLAA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE (the render size is asked from NGX)\n");
            writer.write("dlss_mode = \"" + dlssMode.name() + "\"\n");
            writer.write("# FSR mode: NATIVE_AA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE (the render size is asked from the FidelityFX DLL)\n");
            writer.write("fsr_mode = \"" + fsrMode.name() + "\"\n");
            writer.write("# FSR version: AUTO (newest provider in the DLL), FSR_3_1 or FSR_2. FSR 4 has no Vulkan build from AMD.\n");
            writer.write("fsr_version = \"" + fsrVersion.name() + "\"\n");
            writer.write("# FSR RCAS sharpening 0.0 (off) .. 1.0\n");
            writer.write("fsr_sharpness = " + fsrSharpness + "\n");
            writer.write("# Built-in spatial upscalers (no DLL, any GPU): FSR1 (AMD FSR 1 EASU+RCAS) and NIS (NVIDIA Image Scaling).\n");
            writer.write("# FSR 1 mode: ULTRA_QUALITY (1.3x), QUALITY (1.5x), BALANCED (1.7x), PERFORMANCE (2.0x), CUSTOM. NIS is always CUSTOM (50..100%).\n");
            writer.write("fsr1_mode = \"" + fsr1Mode.name() + "\"\n");
            writer.write("# FSR 1 RCAS sharpening 0.0 (pass off) .. 1.0; NIS sharpness 0.0 .. 1.0 (NIS SDK slider)\n");
            writer.write("fsr1_sharpness = " + fsr1Sharpness + "\n");
            writer.write("nis_sharpness = " + nisSharpness + "\n");
            writer.write("# Render scale in percent used when the mode of that upscaler is CUSTOM (25..100; NIS 50..100).\n");
            writer.write("# DLSS/XeSS clamp it into the dynamic range their library reports (logged).\n");
            for (UpscalerBackend b : new UpscalerBackend[] {UpscalerBackend.XESS, UpscalerBackend.DLSS, UpscalerBackend.FSR,
                UpscalerBackend.FSR1, UpscalerBackend.NIS}) {
                writer.write(b.name().toLowerCase(java.util.Locale.ROOT) + "_custom_scale = " + customPercent(b) + "\n");
            }
            writer.write("# Presets: ULTRA_PERFORMANCE (33.3%), PERFORMANCE (50%), BALANCED (66.7%), QUALITY (77%), NATIVE (100%), CUSTOM\n");
            writer.write("# Below 100% the 3D world is rendered smaller and stretched with a bilinear blit\n");
            writer.write("quality_preset = \"" + qualityPreset.name() + "\"\n");
            writer.write("custom_scale = " + customScalePercent + "\n");
            writer.write("# Auto texture mipmap LOD bias: negative bias based on upscaler scale eliminates DLSS/FSR/XeSS blurriness\n");
            writer.write("auto_texture_lod_bias = " + autoTextureLodBias + "\n");
            writer.write("texture_lod_bias_offset = " + textureLodBiasOffset + "\n");
            writer.write("manual_texture_lod_bias = " + manualTextureLodBias + "\n");
            writer.write("point_sampling_sharpness = " + pointSamplingSharpness + "\n");
            writer.write("resource_pack_resolution = " + resourcePackResolution + "\n");
            writer.write("\n[ray_tracing]\n");
            writer.write("# HARDWARE_RT keeps the rt_mode preference. VOXEL_LIVE_DEBUG requires the matching separate Bedray Compute add-on: bounded textured models and world light without RT cores, with raster preservation. Physical GI, reflections and temporal guides remain pending.\n");
            writer.write("renderer = \"" + rendererSelection.name() + "\"\n");
            writer.write("# Voxel coverage follows loaded Minecraft chunks by default; custom distance is bounded by Minecraft.\n");
            writer.write("voxel_distance_auto = " + voxelDistanceAuto() + "\n");
            writer.write("voxel_distance_chunks = " + voxelDistanceChunks() + "\n");
            writer.write("voxel_memory_budget_mib = " + voxelMemoryBudgetMiB() + "\n");
            writer.write("voxel_quality_profile = \"" + voxelQualityProfile() + "\"\n");
            writer.write("path_quality_profile = \"" + pathQualityProfile() + "\"\n");
            writer.write("voxel_diffuse_bounce = " + voxelDiffuseBounce() + "\n");
            writer.write("voxel_translucent_optics = " + voxelTranslucentOptics() + "\n");
            writer.write("# [EXPERIMENTAL] Hardware ray tracing (requires VK_KHR_ray_query). rt_mode: off, rays (Ray Tracing: shadows, reflections, AO, emissive lights) or path (Path Tracing: full light transport)\n");
            writer.write("rt_mode = \"" + rtModePersisted.logName() + "\"\n");
            writer.write("# Hidden in this release: Ray Tracing (Fast) is in development. true unlocks rt_mode = \"rays\" and its settings.\n");
            writer.write("experimental_rays = " + experimentalRays + "\n");
            writer.write("# Legacy key kept for older versions (ignored when rt_mode is present)\n");
            writer.write("enable_hardware_ray_tracing = " + rtModePersisted.isActive() + "\n");
            writer.write("# Light and atmosphere: light shafts (atm_godrays 0=off 1=post 2=inline), bloom, sun glare, haze\n");
            for (var o : io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings.options()) {
                writer.write("atm_" + o.key() + " = " + io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings.get(o.key()) + "\n");
            }
            writer.write("ui_expert = " + uiExpert + "\n");
            RtSettings.write(writer);
            io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings.write(writer);
        }
    }
}
