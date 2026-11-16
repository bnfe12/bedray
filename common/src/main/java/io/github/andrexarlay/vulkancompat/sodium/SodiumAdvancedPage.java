package io.github.andrexarlay.vulkancompat.sodium;


import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.RtDebugView;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.curPageIds;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.boolOpt;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.intOpt;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.curGroupIds;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.NO_DEPS;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.id;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.tr;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.nameOf;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.tipOf;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.track;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.hideRowWhenDisabled;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.mark;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.unit;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.percent;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.group;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.rtSupported;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.hardwareRenderer;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.rtDependencies;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.resetListButton;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.presetsButton;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.nativesManagerButton;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.plainBool;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.custom;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.customPath;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.pathBool;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.pathInt;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.bool;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.integer;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.allowedRtModes;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.godRaysOption;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.resetAllDefaultsButton;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.resetButton;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.addUpscalerBackend;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.addQualityModes;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.addDlssModel;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.collapsible;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.endGroup;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.Mark;
import io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import net.minecraft.client.Minecraft;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;
import io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBias;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostConfig;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerSwitch;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssSrPreset;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr.DlssRrParams;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.andrexarlay.vulkancompat.rtx.debug.VkbDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "Bedray Advanced" Sodium page: collapsible groups of path tracing, light and atmosphere, image and upscaling,
 * frame generation and diagnostics options, plus the Expert group. Split out of {@link VulkanSodiumConfig}, which keeps the
 * shared option builders and the page registration; this class only lays the groups out.
 */
final class SodiumAdvancedPage {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Bedray/SodiumPage");

    private SodiumAdvancedPage() {
    }

    static void build(ConfigBuilder builder, OptionPageBuilder page, Identifier backendId, Identifier modeId,
                      Path gameDir, StorageEventHandler st) {
        Function<ConfigState, Boolean> anyRt = s -> hardwareRenderer(s) && s.readEnumOption(modeId, RtMode.class).isActive();
        Function<ConfigState, Boolean> pathOn = s -> hardwareRenderer(s) && s.readEnumOption(modeId, RtMode.class) == RtMode.PATH_TRACED;
        Function<ConfigState, Boolean> raysOn = s -> hardwareRenderer(s) && s.readEnumOption(modeId, RtMode.class) == RtMode.RAY_TRACED;
        Identifier[] dMode = rtDependencies(modeId);
        boolean expert = SettingsCatalog.expertUi();

        // ---- Path tracing details
        OptionGroupBuilder pt = collapsible(builder, CollapsibleGroups.RT_ADVANCED, "Path Tracing Details", "adv_pt");
        Identifier ptPresetId = id("pt_preset");
        EnumOptionBuilder<RtSettings.PathPreset> ptPreset = builder.createEnumOption(ptPresetId, RtSettings.PathPreset.class);
        ptPreset.setName(nameOf("pt_preset")).setTooltip(v -> tipOf("pt_preset")).setDefaultValue(RtSettings.PathPreset.FAST)
            .setAllowedValues(EnumSet.allOf(RtSettings.PathPreset.class))
            .setElementNameProvider(val -> switch (val) {
                case FAST -> tr("bedray.pt_preset.fast", "Fast");
                case QUALITY -> tr("bedray.pt_preset.quality", "Quality");
                case CUSTOM -> tr("bedray.pt_preset.custom", "Custom");
            })
            .setEnabledProvider(pathOn, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(VulkanSodiumConfig::applyPathPresetFromUi, () -> RtSettings.pathPreset)
            .setImpact(OptionImpact.HIGH).setStorageHandler(st);
        pt.addOption(track("pt_preset", ptPreset));
        pt.addOption(intOpt(builder, "path_spp", 2, 1, 8, 1, unit(" rays"),
            v -> RtSettings.pathSpp = v, () -> RtSettings.pathSpp, pathOn, dMode, Mark.PATH, OptionImpact.HIGH, false, st));
        pt.addOption(intOpt(builder, "path_distance", 384, RtSettings.DISTANCE_MIN, RtSettings.DISTANCE_MAX, 16,
            unit(" blocks"), v -> RtSettings.pathDistance = v, () -> RtSettings.pathDistance, pathOn, dMode, Mark.PATH, OptionImpact.MEDIUM, false, st));
        EnumOptionBuilder<RtSettings.PathDenoiser> den = builder.createEnumOption(id("path_denoiser"), RtSettings.PathDenoiser.class);
        den.setName(nameOf("path_denoiser")).setTooltip(v -> tipOf("path_denoiser")).setDefaultValue(RtSettings.PathDenoiser.NRD)
            .setAllowedValues(EnumSet.of(RtSettings.PathDenoiser.OFF, RtSettings.PathDenoiser.NRD, RtSettings.PathDenoiser.DLSS_RR))
            .setElementNameProvider(val -> switch (val) {
                case OFF -> tr("bedray.path_denoiser.off", "Off");
                case NRD -> tr("bedray.path_denoiser.nrd", "NRD");
                case DLSS_RR -> tr("bedray.path_denoiser.dlss_rr", "DLSS Ray Reconstruction");
            })
            .setEnabledProvider(pathOn, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(customPath(v -> RtSettings.pathDenoiser = v, () -> RtSettings.pathDenoiser), () -> RtSettings.pathDenoiser)
            .setImpact(OptionImpact.HIGH).setStorageHandler(st);
        pt.addOption(track("path_denoiser", den));
        // ReSTIR for the direct light of the path tracer: a pixel keeps its torch sample across frames instead of redrawing
        // it every frame. This is what makes an interior converge while the camera moves, so it is worth a row of its own.
        pt.addOption(boolOpt(builder, "path_restir_di", true, v -> RtSettings.pathRestirDi = v, () -> RtSettings.pathRestirDi,
            pathOn, dMode, Mark.PATH, OptionImpact.HIGH, false, st));
        // The second stage pools the records the first writes, so the row is only live while reuse itself is on - an
        // independent switch would be a control that changes nothing.
        Identifier pathDiId = id("path_restir_di");
        pt.addOption(boolOpt(builder, "path_restir_spatial", true, v -> RtSettings.pathRestirSpatial = v,
            () -> RtSettings.pathRestirSpatial,
            s -> pathOn.apply(s) && s.readBooleanOption(pathDiId), rtDependencies(modeId, pathDiId),
            Mark.PATH, OptionImpact.MEDIUM, false, st));
        // Path reuse of the indirect light. Off by default for its video memory, and a child of direct-light reuse: the
        // reservoir owns the bounced light while DI owns the direct light, and the page offers them in that order.
        pt.addOption(boolOpt(builder, "path_restir_pt", false, v -> RtSettings.pathRestirPt = v,
            () -> RtSettings.pathRestirPt,
            s -> pathOn.apply(s) && s.readBooleanOption(pathDiId), rtDependencies(modeId, pathDiId),
            Mark.PATH, OptionImpact.HIGH, false, st));
        // The spatial stage pools the path records the temporal stage writes, so it is live only under that row.
        Identifier pathPtId = id("path_restir_pt");
        pt.addOption(boolOpt(builder, "path_restir_pt_spatial", true, v -> RtSettings.pathRestirPtSpatial = v,
            () -> RtSettings.pathRestirPtSpatial,
            s -> pathOn.apply(s) && s.readBooleanOption(pathDiId) && s.readBooleanOption(pathPtId),
            rtDependencies(modeId, pathDiId, pathPtId), Mark.PATH, OptionImpact.MEDIUM, false, st));
        // Shader execution reordering: the device feature is requested while the Vulkan device is created and the trace
        // program is picked when the pass is built, so the row asks for a game restart.
        BooleanOptionBuilder serOption = boolOpt(builder, "path_ser", false, v -> RtSettings.pathSer = v,
            () -> RtSettings.pathSer, pathOn, rtDependencies(modeId), Mark.PATH, OptionImpact.HIGH, false, st);
        serOption.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
        pt.addOption(serOption);
        EnumOptionBuilder<RtSettings.AdaptiveQuality> aq = builder.createEnumOption(id("adaptive_quality"), RtSettings.AdaptiveQuality.class);
        aq.setName(nameOf("adaptive_quality")).setTooltip(v -> tipOf("adaptive_quality")).setDefaultValue(RtSettings.AdaptiveQuality.OFF)
            .setAllowedValues(EnumSet.allOf(RtSettings.AdaptiveQuality.class))
            .setElementNameProvider(val -> switch (val) {
                case OFF -> tr("bedray.adaptive_quality.off", "Off");
                case FPS30 -> tr("bedray.adaptive_quality.fps30", "30 FPS");
                case FPS45 -> tr("bedray.adaptive_quality.fps45", "45 FPS");
                case FPS60 -> tr("bedray.adaptive_quality.fps60", "60 FPS");
                case FPS90 -> tr("bedray.adaptive_quality.fps90", "90 FPS");
                case FPS120 -> tr("bedray.adaptive_quality.fps120", "120 FPS");
            })
            .setEnabledProvider(pathOn, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(v -> RtSettings.setAdaptiveQuality(v), () -> RtSettings.adaptiveQuality)
            .setImpact(OptionImpact.LOW).setStorageHandler(st);
        pt.addOption(track("adaptive_quality", aq));
        pt.addOption(boolOpt(builder, "path_caustics", true, v -> RtSettings.caustics = v, () -> RtSettings.caustics,
            pathOn, dMode, Mark.PATH, OptionImpact.MEDIUM, false, st));
        Identifier denId = id("path_denoiser");
        // Additive DLSS-RR render preset hint (D/E/F). DEFAULT leaves the NGX parameter untouched; opt-in until measured.
        EnumOptionBuilder<DlssRrParams.Preset> rrPreset = builder.createEnumOption(id("dlss_rr_preset"), DlssRrParams.Preset.class);
        rrPreset.setName(nameOf("dlss_rr_preset")).setTooltip(v -> tipOf("dlss_rr_preset")).setDefaultValue(DlssRrParams.Preset.DEFAULT)
            .setAllowedValues(EnumSet.allOf(DlssRrParams.Preset.class))
            .setElementNameProvider(val -> switch (val) {
                case DEFAULT -> tr("bedray.dlss_rr_preset.default", "Default");
                case D -> tr("bedray.dlss_rr_preset.d", "Preset D (transformer)");
                case E -> tr("bedray.dlss_rr_preset.e", "Preset E (transformer, DoF)");
                case F -> tr("bedray.dlss_rr_preset.f", "Preset F (RR2)");
            })
            .setEnabledProvider(s -> anyRt.apply(s) && s.readEnumOption(denId, RtSettings.PathDenoiser.class) == RtSettings.PathDenoiser.DLSS_RR,
                rtDependencies(modeId, denId))
            .setControlHiddenWhenDisabled(false)
            .setBinding(customPath(v -> RtSettings.dlssRrPreset = v, () -> RtSettings.dlssRrPreset), () -> RtSettings.dlssRrPreset)
            .setImpact(OptionImpact.LOW).setStorageHandler(st);
        pt.addOption(track("dlss_rr_preset", rrPreset));
        endGroup(builder, page, pt, "adv_pt");

        // ---- Light and atmosphere
        OptionGroupBuilder look = collapsible(builder, CollapsibleGroups.ADV_LOOK, "Light & Atmosphere", "adv_look");
        look.addOption(intOpt(builder, "path_exposure_ev", 0, -40, 40, 1,
            v -> Component.literal(String.format(Locale.ROOT, "%+.1f EV", v / 10.0)),
            v -> RtSettings.pathExposureEvTenths = v, () -> RtSettings.pathExposureEvTenths,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        EnumOptionBuilder<RtSettings.Tonemapper> tm = builder.createEnumOption(id("tonemapper"), RtSettings.Tonemapper.class);
        tm.setName(nameOf("tonemapper")).setTooltip(v -> tipOf("tonemapper")).setDefaultValue(RtSettings.Tonemapper.PEAK)
            .setAllowedValues(EnumSet.allOf(RtSettings.Tonemapper.class))
            .setElementNameProvider(val -> switch (val) {
                case PEAK -> tr("bedray.tonemapper.peak", "Peak (BedRay)");
                case ACES -> tr("bedray.tonemapper.aces", "ACES");
                case PBR_NEUTRAL -> tr("bedray.tonemapper.pbr_neutral", "PBR Neutral");
                case GT -> tr("bedray.tonemapper.gt", "Gran Turismo");
                case AGX -> tr("bedray.tonemapper.agx", "AgX");
            })
            .setEnabledProvider(anyRt, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(v -> RtSettings.tonemapper = v, () -> RtSettings.tonemapper)
            .setImpact(OptionImpact.LOW).setStorageHandler(st);
        look.addOption(track("tonemapper", tm));
        Function<ConfigState, Boolean> localToneOn = state -> pathOn.apply(state)
            && state.readEnumOption(denId, RtSettings.PathDenoiser.class) == RtSettings.PathDenoiser.DLSS_RR;
        Identifier[] dLocalTone = rtDependencies(modeId, denId);
        look.addOption(atmSlider(builder, "atm_local_highlights", "local_highlights", 100, 0, 200, 5, localToneOn, dLocalTone, true, st));
        look.addOption(atmSlider(builder, "atm_local_shadows", "local_shadows", 100, 0, 200, 5, localToneOn, dLocalTone, true, st));
        look.addOption(boolOpt(builder, "vibrant_visuals", false, v -> RtSettings.vibrantVisuals = v,
            () -> RtSettings.vibrantVisuals, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        look.addOption(boolOpt(builder, "vignette", false, v -> RtSettings.vignette = v,
            () -> RtSettings.vignette, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        look.addOption(boolOpt(builder, "film_grain", false, v -> RtSettings.filmGrain = v,
            () -> RtSettings.filmGrain, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        Identifier particleId = id("rt_particles");
        look.addOption(boolOpt(builder, "rt_particles", true, v -> RtSettings.rtParticles = v, () -> RtSettings.rtParticles,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        // weather rides on the particle layer, so it is only ever offered while particles are on (RtSettings.weather() enforces it)
        look.addOption(boolOpt(builder, "rt_weather", true, v -> RtSettings.rtWeather = v, () -> RtSettings.rtWeather,
            s -> anyRt.apply(s) && s.readBooleanOption(particleId), rtDependencies(modeId, particleId),
            Mark.NONE, OptionImpact.LOW, false, st));
        Identifier godId = id("atm_godrays");
        Function<ConfigState, Boolean> shaftsOn = s -> s.readEnumOption(godId, RtAtmosphereSettings.GodRays.class) != RtAtmosphereSettings.GodRays.OFF
            && anyRt.apply(s);
        Identifier[] dShafts = rtDependencies(modeId, godId);
        look.addOption(atmSlider(builder, "atm_godrays_strength", "godrays_strength", 100, 0, 200, 5, shaftsOn, dShafts, true, st));
        look.addOption(intOpt(builder, "atm_godrays_quality", 2, 0, 3, 1,
            value -> Component.literal(Integer.toString(RtAtmosphereSettings.stepsFor(value))),
            value -> RtAtmosphereSettings.set("godrays_quality", value), () -> (int) RtAtmosphereSettings.get("godrays_quality"),
            state -> anyRt.apply(state) && state.readEnumOption(godId, RtAtmosphereSettings.GodRays.class) == RtAtmosphereSettings.GodRays.POST,
            dShafts, Mark.NONE, OptionImpact.MEDIUM, true, st));
        look.addOption(intOpt(builder, "atm_godrays_range", 160, 32, 256, 16, unit(" m"),
            value -> RtAtmosphereSettings.set("godrays_range", value), () -> RtAtmosphereSettings.godRayRange(),
            shaftsOn, dShafts, Mark.NONE, OptionImpact.LOW, true, st));
        Identifier bloomId = id("atm_bloom");
        look.addOption(boolOpt(builder, "atm_bloom", true, v -> RtAtmosphereSettings.set("bloom", v ? 1 : 0),
            () -> RtAtmosphereSettings.get("bloom") >= 0.5, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        Function<ConfigState, Boolean> bloomOn = s -> anyRt.apply(s) && s.readBooleanOption(bloomId);
        Identifier[] dBloom = rtDependencies(modeId, bloomId);
        look.addOption(atmSlider(builder, "atm_bloom_strength", "bloom_strength", 25, 0, 200, 5, bloomOn, dBloom, true, st));
        look.addOption(atmSlider(builder, "atm_bloom_threshold", "bloom_threshold", 600, 50, 1000, 5, bloomOn, dBloom, true, st));
        look.addOption(atmSlider(builder, "atm_bloom_scatter", "bloom_scatter", 62, 30, 95, 1, bloomOn, dBloom, true, st));
        look.addOption(bloomShapeOption(builder, bloomOn, dBloom, st));
        look.addOption(boolOpt(builder, "atm_bloom_sky", false, value -> RtAtmosphereSettings.set("bloom_sky", value ? 1 : 0),
            RtAtmosphereSettings::bloomSky, bloomOn, dBloom, Mark.NONE, OptionImpact.LOW, true, st));
        look.addOption(boolOpt(builder, "atm_sunglare", true, v -> RtAtmosphereSettings.set("sunglare", v ? 1 : 0),
            () -> RtAtmosphereSettings.get("sunglare") >= 0.5, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        Identifier glareId = id("atm_sunglare");
        look.addOption(atmSlider(builder, "atm_sunglare_strength", "sunglare_strength", 100, 0, 200, 5,
            s -> anyRt.apply(s) && s.readBooleanOption(glareId), rtDependencies(modeId, glareId), true, st));
        look.addOption(atmSlider(builder, "atm_sunglare_size", "sunglare_size", 100, 50, 200, 5,
            s -> anyRt.apply(s) && s.readBooleanOption(glareId), rtDependencies(modeId, glareId), true, st));
        look.addOption(intOpt(builder, "sun_size_tenths", 5, 1, 50, 1,
            value -> Component.literal(String.format(Locale.ROOT, "%.1f°", value / 10.0)),
            value -> RtSettings.sunSizeTenths = value, () -> RtSettings.sunSizeTenths,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, true, st));
        look.addOption(intOpt(builder, "sun_tilt_deg", 24, 0, 45, 1, unit("°"),
            value -> RtSettings.sunTiltDeg = value, () -> RtSettings.sunTiltDeg,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, true, st));
        Identifier fogId = id("rt_fog");
        look.addOption(boolOpt(builder, "rt_fog", true, v -> RtSettings.volumetricFogEnabled = RtSettings.rtFog = v, () -> RtSettings.rtFog,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        look.addOption(intOpt(builder, "rt_fog_density", 100, 20, 200, 5, percent(),
            v -> {
                RtSettings.rtFogDensity = v;
                RtSettings.fogDensity = RtSettings.clampFloat(v / 100f, 0.0f, 2.0f);
            }, () -> RtSettings.rtFogDensity, s -> s.readBooleanOption(fogId) && anyRt.apply(s),
            rtDependencies(modeId, fogId), Mark.NONE, OptionImpact.MEDIUM, true, st));
        Function<ConfigState, Boolean> fogOn = s -> anyRt.apply(s) && s.readBooleanOption(fogId);
        Identifier[] dFog = rtDependencies(modeId, fogId);
        look.addOption(atmSlider(builder, "atm_fog_aerial", "fog_aerial", 100, 0, 200, 5, fogOn, dFog, true, st));
        look.addOption(atmSlider(builder, "atm_fog_vanilla", "fog_vanilla", 100, 0, 100, 5, fogOn, dFog, true, st));
        look.addOption(intOpt(builder, "rt_fog_in_scattering", 100, 0, 200, 5, percent(), value -> {
            RtSettings.rtFogInScattering = value;
            RtSettings.fogInScattering = value / 100f;
        }, () -> RtSettings.rtFogInScattering, fogOn, dFog, Mark.NONE, OptionImpact.MEDIUM, true, st));
        look.addOption(intOpt(builder, "rt_fog_distance_scale", 100, 20, 200, 5, percent(),
            value -> RtSettings.fogDistanceScale = value / 100f, () -> Math.round(RtSettings.fogDistanceScale * 100f),
            fogOn, dFog, Mark.NONE, OptionImpact.LOW, true, st));
        Identifier waveId = id("waving_foliage");
        Identifier pixelShadowsId = id("rays_pixel_shadows");
        look.addOption(boolOpt(builder, "rays_pixel_shadows", false, v -> RtSettings.raysPixelShadows = v, () -> RtSettings.raysPixelShadows,
            anyRt, dMode, Mark.RT, OptionImpact.LOW, false, st));
        look.addOption(intOpt(builder, "rays_pixel_shadow_size", 1, 1, 16, 1, unit(" texels"), v -> RtSettings.raysPixelShadowSize = v,
            () -> RtSettings.raysPixelShadowSize,
            s -> anyRt.apply(s) && s.readBooleanOption(pixelShadowsId), rtDependencies(modeId, pixelShadowsId), Mark.RT, OptionImpact.LOW, false, st));
        look.addOption(intOpt(builder, "atm_chunk_fade", 150, 0, 400, 10,
            value -> tr("bedray.unit.seconds", "%s s", String.format(Locale.ROOT, "%.1f", value / 100.0)),
            value -> RtAtmosphereSettings.set("chunk_fade", value / 100.0),
            () -> (int) Math.round(RtAtmosphereSettings.get("chunk_fade") * 100.0),
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, true, st));
        look.addOption(boolOpt(builder, "waving_foliage", true, v -> RtSettings.wavingFoliage = v, () -> RtSettings.wavingFoliage,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        look.addOption(intOpt(builder, "waving_foliage_strength", 100, 0, 200, 5, percent(),
            v -> RtSettings.wavingFoliageStrength = v, () -> RtSettings.wavingFoliageStrength,
            s -> s.readBooleanOption(waveId) && anyRt.apply(s),
            rtDependencies(modeId, waveId), Mark.NONE, OptionImpact.LOW, true, st));
        endGroup(builder, page, look, "adv_look");

        // ---- Image
        OptionGroupBuilder img = collapsible(builder, CollapsibleGroups.ADV_IMAGE, "Image & Upscaling Details", "adv_image");
        img.addOption(boolOpt(builder, "auto_normals", true, v -> RtSettings.autoNormals = v, () -> RtSettings.autoNormals,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        Identifier reliefId = id("auto_normals");
        img.addOption(intOpt(builder, "auto_normal_strength", 100, 0, 400, 10, percent(),
            value -> RtSettings.autoNormalStrength = value, () -> RtSettings.autoNormalStrength,
            state -> anyRt.apply(state) && state.readBooleanOption(reliefId), rtDependencies(modeId, reliefId),
            Mark.NONE, OptionImpact.LOW, true, st));
        img.addOption(boolOpt(builder, "point_sampling_sharpness", false, v -> {
            VulkanRtxConfig.pointSamplingSharpness = v;
            TextureLodBias.setPointSamplingSharpness(v);
        }, () -> VulkanRtxConfig.pointSamplingSharpness, null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        Identifier autoLodId = id("auto_texture_lod_bias");
        img.addOption(boolOpt(builder, "auto_texture_lod_bias", true, value -> {
            VulkanRtxConfig.autoTextureLodBias = value;
            TextureLodBias.autoTextureLodBias = value;
        }, () -> VulkanRtxConfig.autoTextureLodBias, null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        img.addOption(intOpt(builder, "manual_texture_lod_bias", 0, -40, 20, 1,
            value -> Component.literal(String.format(Locale.ROOT, "%.1f", value / 10.0)),
            value -> {
                VulkanRtxConfig.manualTextureLodBias = value / 10f;
                TextureLodBias.manualTextureLodBias = value / 10f;
            }, () -> Math.round(TextureLodBias.clamp(VulkanRtxConfig.manualTextureLodBias) * 10f),
            state -> !state.readBooleanOption(autoLodId), new Identifier[] {autoLodId}, Mark.NONE, OptionImpact.LOW, true, st));
        // Always registered because Texture Style presets stage this option.
        img.addOption(intOpt(builder, "texture_lod_bias_offset", 0, -20, 10, 1,
            v -> Component.literal(String.format(Locale.ROOT, "%.1f", v / 10.0f)),
            v -> {
                float offset = v / 10.0f;
                VulkanRtxConfig.textureLodBiasOffset = offset;
                TextureLodBias.textureLodBiasOffset = offset;
            }, () -> Math.round(VulkanRtxConfig.textureLodBiasOffset * 10.0f),
            state -> state.readBooleanOption(autoLodId), new Identifier[] {autoLodId}, Mark.NONE, OptionImpact.LOW, true, st));
        img.addOption(intOpt(builder, "fsr_sharpness", 0, 0, 100, 5,
            v -> v == 0 ? tr("bedray.onoff.off", "Off") : Component.literal(v + "%"),
            v -> {
                VulkanRtxConfig.fsrSharpness = v / 100.0f;
                LOGGER.info("[VKB-AUTOTEST] ui_upscaler fsr_sharpness={}", VulkanRtxConfig.fsrSharpness);
            }, () -> Math.round(VulkanRtxConfig.fsrSharpness * 100.0f),
            s -> s.readEnumOption(backendId, UpscalerChoice.class).backend() == UpscalerBackend.FSR, new Identifier[] {backendId}, Mark.NONE,
            OptionImpact.LOW, true, st));
        img.addOption(intOpt(builder, "fsr1_sharpness", 30, 0, 100, 1, percent(),
            value -> VulkanRtxConfig.fsr1Sharpness = value / 100f, () -> Math.round(VulkanRtxConfig.fsr1Sharpness * 100f),
            state -> state.readEnumOption(backendId, UpscalerChoice.class).backend() == UpscalerBackend.FSR1,
            new Identifier[] {backendId}, Mark.NONE, OptionImpact.LOW, true, st));
        img.addOption(intOpt(builder, "nis_sharpness", 20, 0, 100, 1, percent(),
            value -> VulkanRtxConfig.nisSharpness = value / 100f, () -> Math.round(VulkanRtxConfig.nisSharpness * 100f),
            state -> state.readEnumOption(backendId, UpscalerChoice.class).backend() == UpscalerBackend.NIS,
            new Identifier[] {backendId}, Mark.NONE, OptionImpact.LOW, true, st));
        endGroup(builder, page, img, "adv_image");

        // ---- Frame generation details
        OptionGroupBuilder fg = collapsible(builder, CollapsibleGroups.ADV_FG, "Frame Generation Details", "adv_fg");
        for (OptionBuilder o : FrameGenOptions.details(builder, st)) {
            fg.addOption(o);
        }
        curPageIds().add(id("frame_generation_reflex"));
        curPageIds().add(id("frame_generation_limit"));
        curGroupIds().add(id("frame_generation_reflex"));
        curGroupIds().add(id("frame_generation_limit"));
        endGroup(builder, page, fg, "adv_fg");

        // ---- Hidden Ray Tracing (Fast) mode, only when unlocked
        if (VulkanRtxConfig.raysUnlocked() || expert) {
            OptionGroupBuilder rays = collapsible(builder, CollapsibleGroups.ADV_RAYS, "Ray Tracing (Fast, experimental)", "adv_rays");
            addRaysOptions(builder, rays, raysOn, dMode, modeId, st);
            endGroup(builder, page, rays, "adv_rays");
        }

        // ---- Fine-tuning & expert numbers: dev knobs, only with expert UI (-Dvkrtx.ui.expert=true / ui_expert in bedray.toml)
        if (expert) {
            OptionGroupBuilder ex = collapsible(builder, CollapsibleGroups.RT_EXPERT, "Fine-Tuning & Budgets", "expert");
            addExpertOptions(builder, ex, raysOn, anyRt, dMode, modeId, st);
            endGroup(builder, page, ex, "expert");
        }

        // ---- Diagnostics
        // ---- Experimental features: official switches for what used to be JVM flags only. Each row says in its tooltip
        // whether it needs a restart; the saved value feeds the same decision as the flag, which still wins for one session.
        OptionGroupBuilder experimental = collapsible(builder, CollapsibleGroups.ADV_EXPERIMENTAL, "Experimental Features", "adv_experimental");
        BooleanOptionBuilder rcOption = boolOpt(builder, "path_radiance_cascades", false, v -> RtSettings.pathRadianceCascades = v,
            () -> RtSettings.pathRadianceCascades, pathOn, dMode, Mark.PATH, OptionImpact.HIGH, false, st);
        rcOption.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
        experimental.addOption(rcOption);
        BooleanOptionBuilder nativeOption = boolOpt(builder, "path_native_recording", false, v -> RtSettings.pathNativeRecording = v,
            () -> RtSettings.pathNativeRecording, pathOn, dMode, Mark.PATH, OptionImpact.MEDIUM, false, st);
        nativeOption.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
        experimental.addOption(nativeOption);
        BooleanOptionBuilder dlss5Option = boolOpt(builder, "dlss_neural_rendering", false, v -> RtSettings.dlssNeuralRendering = v,
            () -> RtSettings.dlssNeuralRendering, s -> true, NO_DEPS, Mark.NONE, OptionImpact.HIGH, false, st);
        dlss5Option.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
        experimental.addOption(dlss5Option);
        experimental.addOption(boolOpt(builder, "hand_geometry", false, v -> RtSettings.handGeometry = v,
            () -> RtSettings.handGeometry, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        experimental.addOption(boolOpt(builder, "icon_light_sky", false, v -> RtSettings.iconLightSky = v,
            () -> RtSettings.iconLightSky, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        experimental.addOption(boolOpt(builder, "addon_lights", true, v -> RtSettings.addonLights = v,
            () -> RtSettings.addonLights, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        endGroup(builder, page, experimental, "adv_experimental");

        // Bedrock Edition ray-tracing material packs (Vanilla RTX and similar): a drop folder and an import button.
        OptionGroupBuilder bedrock = collapsible(builder, "bedray.group.bedrock_rtx", "Bedrock RTX packs", "adv_bedrock_rtx");
        var openBedrock = builder.createExternalButtonOption(id("bedrock_rtx_open"));
        openBedrock.setName(tr("bedray.option.bedrock_rtx_open", "Open Bedrock RTX pack folder"))
            .setTooltip(tr("bedray.option.bedrock_rtx_open.tooltip",
                "Opens bedray/bedrock_rtx_packs/ in the game folder. Put Bedrock ray-tracing resource packs there (a folder, .mcpack or .zip, e.g. Vanilla RTX), then press Import. Bedrock shader packs (BetterRTX .rtpack) are compiled for the Bedrock renderer and cannot be used."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> {
                try {
                    com.mojang.blaze3d.Blaze3D.openPath(
                        io.github.andrexarlay.vulkancompat.rt.material.bedrock.BedrockRtxPackImporter.sourceDir(Minecraft.getInstance()));
                } catch (java.io.IOException e) {
                    LOGGER.warn("[BEDROCK-RTX] cannot open the pack folder: {}", e.toString());
                }
            });
        bedrock.addOption(openBedrock);
        var importBedrock = builder.createExternalButtonOption(id("bedrock_rtx_import"));
        importBedrock.setName(tr("bedray.option.bedrock_rtx_import", "Import Bedrock RTX packs"))
            .setTooltip(tr("bedray.option.bedrock_rtx_import.tooltip",
                "Converts the metal / emission / roughness / subsurface and height maps of every pack in bedray/bedrock_rtx_packs/ into a Java LabPBR resource pack (resourcepacks/Bedray RTX - <name>), enables it and reloads resources. Java keeps its own colour textures; water keeps Bedray's own water. The converted pack stays on this computer under the source pack's licence."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> io.github.andrexarlay.vulkancompat.rt.material.bedrock.BedrockRtxPackImporter.importAllAsync());
        bedrock.addOption(importBedrock);
        page.addOptionGroup(bedrock);

        OptionGroupBuilder diag = collapsible(builder, CollapsibleGroups.ADV_DIAG, "Diagnostics & Reset", "adv_diag");
        if (expert) {
            EnumOptionBuilder<GraphicsBackendRegistry.SceneMode> scene = builder.createEnumOption(id("compute_scene"), GraphicsBackendRegistry.SceneMode.class);
            scene.setName(nameOf("compute_scene")).setTooltip(v -> tipOf("compute_scene"))
                .setDefaultValue(GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE)
                .setAllowedValues(EnumSet.of(GraphicsBackendRegistry.SceneMode.SYNTHETIC, GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE))
                .setElementNameProvider(VulkanSodiumConfig::computeSceneName)
                .setEnabledProvider(s -> !hardwareRenderer(s), id("renderer")).setControlHiddenWhenDisabled(false)
                .setBinding(VulkanSodiumConfig::applyComputeSceneFromUi, VulkanRtxConfig::computeDiagnosticScene)
                .setImpact(OptionImpact.HIGH).setStorageHandler(st);
            diag.addOption(track("compute_scene", scene));
            EnumOptionBuilder<GraphicsBackendRegistry.DebugView> computeView = builder.createEnumOption(id("compute_debug_view"), GraphicsBackendRegistry.DebugView.class);
            computeView.setName(nameOf("compute_debug_view")).setTooltip(v -> tipOf("compute_debug_view"))
                .setDefaultValue(GraphicsBackendRegistry.DebugView.SHADED)
                .setAllowedValues(EnumSet.allOf(GraphicsBackendRegistry.DebugView.class))
                .setElementNameProvider(v -> tr("bedray.compute.view." + v.name().toLowerCase(Locale.ROOT), v.name()))
                .setEnabledProvider(s -> GraphicsBackendRegistry.backend() != null && GraphicsBackendRegistry.backend().supportsDebugViews())
                .setControlHiddenWhenDisabled(false)
                .setBinding(GraphicsBackendRegistry::setDebugView, GraphicsBackendRegistry::debugView)
                .setImpact(OptionImpact.LOW).setStorageHandler(st);
            diag.addOption(track("compute_debug_view", computeView));
        }
        diag.addOption(boolOpt(builder, "debug_overlay", true, SodiumAdvancedPage::setDebugOverlay, SodiumAdvancedPage::debugOverlayOn,
            null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        diag.addOption(boolOpt(builder, "rays_transparency_stats", false, v -> RtSettings.transparencyStats = v,
            () -> RtSettings.transparencyStats, null, NO_DEPS, Mark.NONE, OptionImpact.MEDIUM, false, st));
        diag.addOption(intOpt(builder, "ui_menu_transparency", VulkanRtxConfig.DEFAULT_MENU_TRANSPARENCY,
            0, VulkanRtxConfig.MAX_MENU_TRANSPARENCY, 1, percent(), v -> VulkanRtxConfig.menuTransparency = v,
            () -> VulkanRtxConfig.menuTransparency, null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        diag.addOption(boolOpt(builder, "ui_expert", false, v -> VulkanRtxConfig.uiExpert = v, () -> VulkanRtxConfig.uiExpert,
            null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        if (expert) {
            Identifier viewId = id("rt_debug_view");
            EnumOptionBuilder<RtDebugView> dv = builder.createEnumOption(viewId, RtDebugView.class);
            dv.setName(nameOf("rt_debug_view")).setTooltip(v -> tipOf("rt_debug_view")).setDefaultValue(RtDebugView.OFF)
                .setAllowedValues(EnumSet.allOf(RtDebugView.class))
                .setElementNameProvider(val -> tr("bedray.debug_view." + val.name().toLowerCase(Locale.ROOT), val.name()))
                .setBinding(val -> RtConfig.setDebugView(val.viewName()), () -> RtDebugView.fromViewName(RtConfig.currentDebugView()))
                .setImpact(OptionImpact.LOW).setStorageHandler(st);
            diag.addOption(track("rt_debug_view", dv));
        }
        var diagBtn = builder.createExternalButtonOption(id("copy_diagnostics"));
        diagBtn.setName(tr("bedray.option.copy_diagnostics", "Copy diagnostics report"))
            .setTooltip(tr("bedray.option.copy_diagnostics.tooltip",
                "Writes a technical report (versions, GPU, driver, Bedray settings and status, recent warnings) to bedray/reports/ and copies it to the clipboard. Paths, names, UUIDs, e-mails and IP addresses are removed. Nothing is sent anywhere; attach it to your bug report."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> io.github.andrexarlay.vulkancompat.rtx.debug.Diagnostics.generateAndNotify());
        diag.addOption(diagBtn);
        diag.addOption(resetAllDefaultsButton(builder, "reset_all_defaults", "Reset ALL Bedray settings",
            "Resets every Bedray setting (both pages, including values that are not shown) to factory defaults at once and reopens this screen."));
        endGroup(builder, page, diag, "adv_diag");
    }

    private static OptionBuilder bloomShapeOption(ConfigBuilder builder, Function<ConfigState, Boolean> enabled,
                                                  Identifier[] deps, StorageEventHandler st) {
        String path = "atm_bloom_shape";
        var shape = builder.createEnumOption(id(path), RtAtmosphereSettings.BloomShape.class);
        shape.setName(nameOf(path)).setTooltip(v -> tipOf(path)).setDefaultValue(RtAtmosphereSettings.BloomShape.ROUND)
            .setAllowedValues(EnumSet.allOf(RtAtmosphereSettings.BloomShape.class))
            .setElementNameProvider(v -> switch (v) {
                case ROUND -> tr("bedray.bloom_shape.round", "Round");
                case HORIZONTAL -> tr("bedray.bloom_shape.horizontal", "Horizontal");
                case VERTICAL -> tr("bedray.bloom_shape.vertical", "Vertical");
            })
            .setEnabledProvider(enabled, deps).setControlHiddenWhenDisabled(true)
            .setBinding(v -> RtAtmosphereSettings.set("bloom_shape", v.ordinal()), RtAtmosphereSettings::bloomShape)
            .setImpact(OptionImpact.LOW).setStorageHandler(st);
        return track(path, shape);
    }

    private static IntegerOptionBuilder atmSlider(ConfigBuilder builder, String path, String atmKey, int defPercent, int min, int max, int step,
                                                  Function<ConfigState, Boolean> enabled, Identifier[] deps, boolean hideRow, StorageEventHandler st) {
        return intOpt(builder, path, defPercent, min, max, step, percent(),
            v -> RtAtmosphereSettings.set(atmKey, v / 100.0), () -> (int) Math.round(RtAtmosphereSettings.get(atmKey) * 100.0),
            enabled, deps, Mark.NONE, OptionImpact.LOW, hideRow, st);
    }

    private static void setDebugOverlay(boolean val) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.debugEntries != null) {
            mc.debugEntries.setStatus(VkbDebugEntry.ID, val ? DebugScreenEntryStatus.IN_OVERLAY : DebugScreenEntryStatus.NEVER);
            if (val && !mc.debugEntries.isOverlayVisible()) {
                mc.debugEntries.setOverlayVisible(true);
            }
            mc.debugEntries.save();
        } else {
            LOGGER.warn("[VKB-UI] debug_overlay={} not applied: debug entries not created yet", val);
        }
    }

    private static boolean debugOverlayOn() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.debugEntries == null || mc.debugEntries.isCurrentlyEnabled(VkbDebugEntry.ID);
    }

    private static void addRaysOptions(ConfigBuilder builder, OptionGroupBuilder g, Function<ConfigState, Boolean> raysOn, Identifier[] dMode,
                                       Identifier modeId, StorageEventHandler st) {
        Identifier presetId = id("rt_preset");
        EnumOptionBuilder<RtSettings.Preset> presetOpt = builder.createEnumOption(presetId, RtSettings.Preset.class);
        presetOpt.setName(tr("bedray.option.rt_preset", "RT Quality")).setTooltip(v -> tr("bedray.option.rt_preset.tooltip",
                "Sets all Ray Tracing (Fast) values at once. Changing one of them switches to Custom."))
            .setDefaultValue(RtSettings.Preset.MEDIUM).setAllowedValues(EnumSet.copyOf(RtSettings.RAY_PRESETS))
            .setElementNameProvider(val -> tr("bedray.rt_preset." + val.name().toLowerCase(Locale.ROOT), val.name()))
            .setEnabledProvider(raysOn, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(val -> {
                RtSettings.applyPreset(val);
                VulkanSodiumConfig.requestValueRefresh();
                io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.resetHistory();
            }, () -> RtSettings.RAY_PRESETS.contains(RtSettings.preset) ? RtSettings.preset : RtSettings.Preset.CUSTOM)
            .setImpact(OptionImpact.HIGH).setStorageHandler(st);
        g.addOption(track("rt_preset", presetOpt));
        String[] names = {"rays_shadows", "rays_soft_shadows", "rays_reflections", "rays_ao", "rays_gi", "rays_emissive", "rays_accumulate"};
        boolean[] defs = {true, true, true, true, false, true, true};
        List<Supplier<Boolean>> gets = List.of(() -> RtSettings.raysShadows, () -> RtSettings.raysSoftShadows,
            () -> RtSettings.raysReflections, () -> RtSettings.raysAo, () -> RtSettings.raysGi, () -> RtSettings.raysEmissive,
            () -> RtSettings.raysAccumulate);
        List<Consumer<Boolean>> sets = List.of(v -> RtSettings.raysShadows = v, v -> RtSettings.raysSoftShadows = v,
            v -> RtSettings.raysReflections = v, v -> RtSettings.raysAo = v, v -> RtSettings.raysGi = v,
            v -> RtSettings.raysEmissive = v, v -> RtSettings.raysAccumulate = v);
        for (int i = 0; i < names.length; i++) {
            g.addOption(boolOpt(builder, names[i], defs[i], sets.get(i), gets.get(i), raysOn, dMode, Mark.RT, OptionImpact.HIGH, false, st));
        }
        // The two ReSTIR stages of the ray pass, offered separately because each one is a different kind of reuse: temporal
        // keeps a pixel's own light sample across frames, spatial pools the samples of neighbouring pixels. Spatial merges
        // the records the temporal stage writes, so it is only ever useful with temporal on.
        Identifier temporalId = id("rays_restir_temporal");
        g.addOption(boolOpt(builder, "rays_restir_temporal", true, v -> RtSettings.raysRestirTemporal = v,
            () -> RtSettings.raysRestirTemporal, raysOn, dMode, Mark.RT, OptionImpact.HIGH, false, st));
        g.addOption(boolOpt(builder, "rays_restir_spatial", true, v -> RtSettings.raysRestirSpatial = v,
            () -> RtSettings.raysRestirSpatial,
            s -> raysOn.apply(s) && s.readBooleanOption(temporalId), rtDependencies(modeId, temporalId),
            Mark.RT, OptionImpact.MEDIUM, false, st));
    }

    private static void addExpertOptions(ConfigBuilder builder, OptionGroupBuilder ex, Function<ConfigState, Boolean> raysOn,
                                  Function<ConfigState, Boolean> anyRt, Identifier[] dMode,
                                  Identifier modeId, StorageEventHandler st) {
        String[] names = {"rays_reflection_distance", "rays_ao_radius", "rays_emissive_quality", "rays_emissive_distance",
            "rays_gi_distance"};
        int[][] r = {{128, RtSettings.DISTANCE_MIN, RtSettings.DISTANCE_MAX, 16}, {3, 1, 8, 1}, {1, 1, 8, 1},
            {32, 8, 64, 8}, {64, RtSettings.DISTANCE_MIN, 256, 16}};
        String[] units = {" blocks", " blocks", "", " blocks", " blocks"};
        List<Supplier<Integer>> gets = List.of(() -> RtSettings.raysReflectionDistance,
            () -> RtSettings.raysAoRadius, () -> RtSettings.raysEmissiveQuality, () -> RtSettings.raysEmissiveDistance,
            () -> RtSettings.raysGiDistance);
        List<Consumer<Integer>> sets = List.of(v -> RtSettings.raysReflectionDistance = v,
            v -> RtSettings.raysAoRadius = v, v -> RtSettings.raysEmissiveQuality = v, v -> RtSettings.raysEmissiveDistance = v,
            v -> RtSettings.raysGiDistance = v);
        for (int i = 0; i < names.length; i++) {
            ex.addOption(intOpt(builder, names[i], r[i][0], r[i][1], r[i][2], r[i][3], unit(units[i]), sets.get(i), gets.get(i),
                raysOn, dMode, Mark.RT, OptionImpact.HIGH, false, st));
        }
        Identifier distAutoId = id("rt_distance_auto");
        ex.addOption(boolOpt(builder, "rt_distance_auto", true, v -> RtSettings.rtDistanceAuto = v, () -> RtSettings.rtDistanceAuto,
            anyRt, dMode, Mark.RT, OptionImpact.MEDIUM, false, st));
        ex.addOption(intOpt(builder, "rt_distance_chunks", 8, 2, 32, 1, unit(" chunks"), v -> RtSettings.rtDistanceChunks = v,
            () -> RtSettings.rtDistanceChunks, s -> anyRt.apply(s) && !s.readBooleanOption(distAutoId), rtDependencies(modeId, distAutoId),
            Mark.RT, OptionImpact.MEDIUM, true, st));
        ex.addOption(intOpt(builder, "rt_light_budget", 2048, 16, 8192, 16, unit(""), v -> RtSettings.lightBudget = v,
            () -> RtSettings.lightBudget, anyRt, dMode, Mark.RT, OptionImpact.MEDIUM, false, st));
    }

}
