package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The settings-mutation actions of {@link VulkanSodiumConfig}: the reset/apply/mode bindings, the screen-config apply, the
 * value-refresh flag and the engine mode-change hook. The page owns the UI construction (the option/button builders, the
 * {@code custom}/{@code customPath} value wrappers, the debug-view enum) and delegates every action here, so the mutation
 * logic and the refresh state it reads live in one owner instead of being interleaved with the page layout.
 */
final class BedraySettingsActions {

    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/SodiumPage");

    private BedraySettingsActions() {
    }

    static void resetOptions(List<Identifier> ids, String why) {
        int n = 0;
        for (Identifier id : ids) {
            Option o = ConfigManager.CONFIG.getOption(id);
            if (o != null) {
                o.resetToDefault();
                n++;
            }
        }
        LOGGER.info("[VKB-AUTOTEST] ui_reset_list why={} options={}", why, n);
    }

    /** After a user preset was applied: engine hooks for the (possibly changed) tracing mode. */
    static void afterPresetApplied() {
        VulkanRtxConfig.applyRendererSelection();
        RtMode now = VulkanRtxConfig.effectiveRtMode();
        afterModeChange(now, now, "preset");
    }

    /** "PT Quality" binding (also used by the settings_ui autotest through the Config API). */
    static void applyPathPresetFromUi(RtSettings.PathPreset val) {
        RtSettings.PathPreset before = RtSettings.pathPreset;
        RtSettings.applyPathPreset(val);
        requestValueRefresh();
        io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.resetHistory();
        LOGGER.info("[VKB-AUTOTEST] ui_pt_preset from={} to={} mode={} applied: {}", before, val,
            VulkanRtxConfig.effectiveRtMode().logName(), RtSettings.pathSummary());
    }

    private static volatile boolean valueRefreshRequested;
    private static boolean applyingScreenConfig;

    static void applyScreenConfig(net.caffeinemc.mods.sodium.client.config.structure.Config config, Map<String, String> imported) {
        applyingScreenConfig = true;
        try {
            imported.forEach(VulkanRtxConfig::applyKeyValue);
            config.applyAllOptions();
            SettingsPolicy.enforce();
            VulkanRtxConfig.enforceBackendAvailability();
            afterPresetApplied();
            VulkanRtxManager.applyResolutionScalingNow();
            VulkanRtxConfig.save();
        } finally {
            applyingScreenConfig = false;
        }
    }

    /**
     * A preset changed many values behind Sodium's back: the controls keep the values they loaded when the screen was built.
     * The option list mixin calls {@code ConfigManager.CONFIG.resetAllOptionsFromBindings()} (public, Sodium 0.9.2 Config)
     * on its next frame, after Sodium's apply loop has finished.
     */
    static void requestValueRefresh() {
        if (!applyingScreenConfig) valueRefreshRequested = true;
    }

    static boolean consumeValueRefreshRequest() {
        boolean r = valueRefreshRequested;
        valueRefreshRequested = false;
        return r;
    }

    static void resetAllToDefaultsAndReopen(net.minecraft.client.gui.screens.Screen current) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        // 1. Reset Upscaling & Resolution
        VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;
        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
        VulkanRtxConfig.customScalePercent = 100;
        VulkanRtxConfig.syncUpscalerTypeWithPreset();
        VulkanRtxConfig.xessMode = UpscalerModes.defaultMode(UpscalerBackend.XESS);
        VulkanRtxConfig.dlssMode = UpscalerModes.defaultMode(UpscalerBackend.DLSS);
        VulkanRtxConfig.dlssSrPreset = io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssSrPreset.AUTO;
        VulkanRtxConfig.fsrMode = UpscalerModes.defaultMode(UpscalerBackend.FSR);
        VulkanRtxConfig.fsrVersion = FsrVersion.AUTO;
        VulkanRtxConfig.fsrSharpness = 0.0f;
        VulkanRtxConfig.autoTextureLodBias = true;
        VulkanRtxConfig.textureLodBiasOffset = 0.0f;
        VulkanRtxConfig.manualTextureLodBias = 0.0f;
        io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBias.autoTextureLodBias = true;
        io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBias.textureLodBiasOffset = 0.0f;

        // 2. Reset Ray Tracing
        VulkanRtxConfig.setRendererSelection(VulkanRtxConfig.RendererSelection.HARDWARE_RT);
        GraphicsBackendRegistry.setDebugView(GraphicsBackendRegistry.DebugView.SHADED);
        VulkanRtxConfig.setRtMode(RtMode.OFF);
        RtSettings.applyPreset(RtSettings.Preset.MEDIUM);
        RtSettings.writeAlphaPathDefaults();
        RtSettings.pathPreset = RtSettings.PathPreset.CUSTOM;
        RtSettings.showAdvanced = false;
        RtSettings.showExpert = false;

        // 3. Reset Atmosphere & Advanced / Experimental
        RtSettings.volumetricFogEnabled = true;
        RtSettings.rtFog = true;
        RtSettings.fogDensity = 1.0f;
        RtSettings.rtFogDensity = 100;
        RtSettings.fogInScattering = 1.0f;
        RtSettings.rtFogInScattering = 100;
        RtSettings.fogDistanceScale = 1.0f;
        RtConfig.setDebugView("");
        for (RtAtmosphereSettings.Option o : RtAtmosphereSettings.options()) {
            RtAtmosphereSettings.set(o.key(), o.def());
        }
        RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
        RtSettings.autoNormals = true;
        RtSettings.autoNormalStrength = 100;
        RtSettings.writeAlphaPathDefaults();
        RtSettings.pathPreset = RtSettings.PathPreset.CUSTOM;
        RtSettings.rtDistanceAuto = true;
        VulkanRtxConfig.pointSamplingSharpness = false;
        SettingsPolicy.enforce();

        VulkanRtxConfig.save();
        if (before != VulkanRtxConfig.effectiveRtMode()) {
            afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "reset_all_defaults");
        }
        VulkanRtxManager.applyResolutionScalingNow();
        LOGGER.info("[VKB-AUTOTEST] ui_reset_all_defaults applied rt_preset={} {}", RtSettings.preset, RtSettings.pathSummary());
        if (current instanceof VideoSettingsScreenAccess access) {
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(
                net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(access.vkb$prevScreen()));
        }
    }

    /**
     * The "Tracing" option of the page (also used by the rt_modes autotest through the Config API): sets the mode, starts or
     * stops the experiment engine, reloads Sodium's meshes when switching on (the geometry capture needs every section).
     */
    static void applyRtModeFromUi(RtMode val) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        if (val == VulkanRtxConfig.rtMode) {
            return;
        }
        VulkanRtxConfig.setRtMode(val);
        afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "mode");
    }

    static void applyRendererFromUi(VulkanRtxConfig.RendererSelection renderer) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        VulkanRtxConfig.setRendererSelection(renderer);
        afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "renderer");
        requestValueRefresh();
        VulkanRtxManager.applyResolutionScalingNow();
    }

    static void applyComputeSceneFromUi(GraphicsBackendRegistry.SceneMode scene) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        VulkanRtxConfig.setComputeScene(scene);
        afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "compute_scene");
        requestValueRefresh();
        VulkanRtxManager.applyResolutionScalingNow();
    }

    /** Applies a reset (also used by the settings autotest) and reopens the Sodium screen on the same parent. */
    static void resetAndReopen(RtSettings.Level level, net.minecraft.client.gui.screens.Screen current) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        String what = RtSettings.resetLevel(level);
        VulkanRtxConfig.save();
        if (before != VulkanRtxConfig.effectiveRtMode()) {
            afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "reset");
        }
        LOGGER.info("[VKB-AUTOTEST] ui_reset level={} {} mode={} rays: shadows={} refl={} ao={} gi={} | {}",
            level.name().toLowerCase(Locale.ROOT), what, VulkanRtxConfig.effectiveRtMode().logName(), RtSettings.raysShadows,
            RtSettings.raysReflections, RtSettings.raysAo, RtSettings.raysGi,
            RtSettings.pathSummary());
        if (current instanceof VideoSettingsScreenAccess access) {
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(
                net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(access.vkb$prevScreen()));
        }
    }

    /** Engine hooks after the RT mode changed through the page (preset or mode option). */
    private static void afterModeChange(RtMode before, RtMode after, String reason) {
        if (!after.isActive()) {
            // explicit user action: a session failure is forgotten, switching RT on again retries (RtGeometryGate)
            RtGeometryGate.clearFailure();
        }
        // Switching on: RtCaptureControl.onFrameBegin (head of the next frame) sees the gate go off -> on and schedules a
        // rebuild of every built Sodium section (scheduleRebuildForChunks, no fence wait), so the capture covers the view.
        // This replaces the full SodiumWorldRenderer.reload() of 532310f (one re-mesh path, not two).
        LOGGER.info("[VKB-AUTOTEST] ui_rt_mode from={} to={} reason={} preset={}", before.logName(), after.logName(), reason,
            RtSettings.preset);
    }
}