package io.github.andrexarlay.vulkancompat.sodium;

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
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.DefaultRtExperimentEngine;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerSwitch;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FfxNative;
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
 * Sodium Config API entry point ("sodium:config_api_user"). Two pages, built from {@link SettingsCatalog}
 * (decisions per option: docs/SETTINGS_AUDIT.md):
 * <ul>
 * <li><b>Bedray</b> - the essentials (8 settings: Ray Tracing, Light Quality, Auto Exposure, Water Style, Light Shafts,
 *     Upscaler, Upscaler Quality, Frame Generation), then "My presets" and "Reset this page";</li>
 * <li><b>Bedray Advanced</b> - collapsible groups ({@link CollapsibleGroups}), each ending with "Reset this group". The
 *     Expert and Debug options exist only with {@code -Dvkrtx.ui.expert=true} (or {@code ui_expert = true} in bedray.toml).</li>
 * </ul>
 * A row whose option does not apply (an upscaler mode of another backend) is hidden, not greyed
 * ({@code mixin.sodium.MixinOptionListWidget}, {@link #hiddenRowSignature()}). Per-option "Reset" is on the right-hand panel
 * ({@link PreviewPanel}); Sodium's own {@code Option.resetToDefault()} does the work, so factory defaults live in the builders here.
 * Sodium API signatures: javap / decompiled source of Sodium 0.9.2 (libs/sodium-core.jar, scratch/decomp/sodium092).
 */
public final class VulkanSodiumConfig implements ConfigEntryPoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/SodiumPage");

    public enum WaterStyle { VANILLA, REALISTIC }
    public enum RendererStatus { STATUS }
    public enum QualityProfile { FAST, BEAUTIFUL, CUSTOM }

    private enum Mark { NONE, RT, PATH }

    /** Ids of every option per page / per collapsible group (for the reset buttons), refilled by registerConfigLate. */
    private static final List<Identifier> PAGE_ESSENTIAL = new ArrayList<>();
    private static final List<Identifier> PAGE_ADVANCED = new ArrayList<>();
    private static final Map<String, List<Identifier>> GROUP_IDS = new LinkedHashMap<>();
    /** Options whose whole row disappears while they are disabled. */
    private static final Set<Identifier> ROW_HIDDEN = new HashSet<>();
    private static List<Identifier> curPage = PAGE_ESSENTIAL;
    private static List<Identifier> curGroup = null;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("bedray", path);
    }

    private static Component tr(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback);
    }

    private static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    // ---------------------------------------------------------------- catalog-backed texts

    private static Component nameOf(String path) {
        SettingsCatalog.Info i = SettingsCatalog.get(path);
        return tr("bedray.opt." + path, i == null ? path : i.name());
    }

    /** Sodium tooltip (used when the right panel is hidden): description, cost, requirement. */
    private static Component tipOf(String path) {
        SettingsCatalog.Info i = SettingsCatalog.get(path);
        if (i == null) {
            return Component.empty();
        }
        net.minecraft.network.chat.MutableComponent c = Component.empty().append(tr("bedray.opt." + path + ".desc", i.desc()));
        if (!i.cost().isEmpty()) {
            c.append("\n").append(tr("bedray.opt." + path + ".cost", i.cost()));
        }
        if (!i.requires().isEmpty()) {
            c.append("\n").append(tr("bedray.opt." + path + ".req", i.requires()));
        }
        return c;
    }

    private static <T extends OptionBuilder> T track(String path, T opt) {
        Identifier id = id(path);
        curPage.add(id);
        if (curGroup != null) {
            curGroup.add(id);
        }
        return opt;
    }

    /** Hides the whole row while the option is disabled. */
    private static void hideRowWhenDisabled(String path) {
        ROW_HIDDEN.add(id(path));
    }

    /** Changes of this string mean the option list must be rebuilt (a row appeared or disappeared). */
    public static String hiddenRowSignature() {
        StringBuilder sb = new StringBuilder();
        try {
            for (Identifier id : ROW_HIDDEN) {
                Option o = ConfigManager.CONFIG.getOption(id);
                if (o != null && !o.isEnabled()) {
                    sb.append(id.getPath()).append(',');
                }
            }
        } catch (RuntimeException ignored) {
            // config not built yet
        }
        return sb.toString();
    }

    public static boolean isRowHidden(Option o, Identifier id) {
        return id != null && ROW_HIDDEN.contains(id) && !o.isEnabled();
    }

    // ---------------------------------------------------------------- builders

    private static <T> Consumer<T> mark(Mark m, Consumer<T> set, Supplier<T> get) {
        return switch (m) {
            case RT -> custom(set, get);
            case PATH -> customPath(set, get);
            case NONE -> set;
        };
    }

    private static BooleanOptionBuilder boolOpt(ConfigBuilder b, String path, boolean def, Consumer<Boolean> set, Supplier<Boolean> get,
                                                Function<ConfigState, Boolean> enabled, Identifier[] deps, Mark m, OptionImpact impact,
                                                boolean hideRow, StorageEventHandler st) {
        BooleanOptionBuilder o = b.createBooleanOption(id(path));
        o.setName(nameOf(path)).setTooltip(tipOf(path)).setDefaultValue(def).setBinding(mark(m, set, get), get)
            .setImpact(impact).setStorageHandler(st);
        if (enabled != null) {
            o.setEnabledProvider(enabled, deps);
        }
        o.setControlHiddenWhenDisabled(false);
        if (hideRow) {
            hideRowWhenDisabled(path);
        }
        return track(path, o);
    }

    private static IntegerOptionBuilder intOpt(ConfigBuilder b, String path, int def, int min, int max, int step,
                                               Function<Integer, Component> fmt, Consumer<Integer> set, Supplier<Integer> get,
                                               Function<ConfigState, Boolean> enabled, Identifier[] deps, Mark m, OptionImpact impact,
                                               boolean hideRow, StorageEventHandler st) {
        IntegerOptionBuilder o = b.createIntegerOption(id(path));
        o.setName(nameOf(path)).setTooltip(tipOf(path)).setDefaultValue(def).setRange(min, max, step).setValueFormatter(fmt::apply)
            .setBinding(mark(m, set, get), get).setImpact(impact).setStorageHandler(st);
        if (enabled != null) {
            o.setEnabledProvider(enabled, deps);
        }
        o.setControlHiddenWhenDisabled(false);
        if (hideRow) {
            hideRowWhenDisabled(path);
        }
        return track(path, o);
    }

    private static Function<Integer, Component> unit(String u) {
        return v -> Component.literal(v + u);
    }

    private static final Identifier[] NO_DEPS = {};

    private static Function<Integer, Component> percent() {
        return v -> Component.literal(v + "%");
    }

    // ---------------------------------------------------------------- registration

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions().setName("Bedray").addPage(builder.createExternalPage()
            .setName(tr("bedray.settings.open", "Bedray Settings..."))
            .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new BedrayConfigScreen(parent))));
    }

    /** Fresh option objects keep this screen's pending changes independent of a parent Sodium screen. */
    static net.caffeinemc.mods.sodium.client.config.structure.Config createScreenConfig() {
        var builder = new net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl(
            configId -> {
                // Copy metadata, never the parent's mutable Option objects or pending values.
                if (ConfigManager.CONFIG != null) {
                    for (var mod : ConfigManager.CONFIG.getModOptions())
                        if (mod.configId().equals(configId)) return new ConfigManager.ModMetadata(mod.name(), mod.version());
                }
                String version = VulkanSodiumConfig.class.getPackage().getImplementationVersion();
                return new ConfigManager.ModMetadata("Bedray", version == null || version.isBlank() ? "unknown" : version);
            }, "bedray");
        new VulkanSodiumConfig().registerSettings(builder);
        return new net.caffeinemc.mods.sodium.client.config.structure.Config(new ArrayList<>(builder.build()));
    }

    /** Package-visible so the id/dependency tests can drive the real registration against a recording builder. */
    void registerSettings(ConfigBuilder builder) {
        PAGE_ESSENTIAL.clear();
        PAGE_ADVANCED.clear();
        GROUP_IDS.clear();
        ROW_HIDDEN.clear();
        ModOptionsBuilder modOptions = builder.registerOwnModOptions().setName("Bedray");
        StorageEventHandler storage = VulkanRtxConfig::save;
        Path gameDir = XessRuntime.resolveGameDir();
        Identifier backendId = id("upscaler_backend");
        Identifier modeId = id("rt_mode");

        OptionPageBuilder essential = builder.createOptionPage().setName(tr("bedray.page.main", "Bedray"));
        curPage = PAGE_ESSENTIAL;
        curGroup = null;
        buildEssentialPage(builder, essential, backendId, modeId, gameDir, storage);
        modOptions.addPage(essential);

        OptionPageBuilder advanced = builder.createOptionPage().setName(tr("bedray.page.advanced_main", "Bedray Advanced"));
        curPage = PAGE_ADVANCED;
        curGroup = null;
        buildAdvancedPage(builder, advanced, backendId, modeId, gameDir, storage);
        modOptions.addPage(advanced);

        LOGGER.info("[Bedray/SodiumPage] registered 2 pages: Bedray ({} options), Bedray Advanced ({} options, expert={})",
            PAGE_ESSENTIAL.size(), PAGE_ADVANCED.size(), SettingsCatalog.expertUi());
        for (UpscalerBackend b : UpscalerBackend.values()) {
            LOGGER.info("[VKB-AUTOTEST] ui_upscaler backend={} status={}", b, UpscalerSelection.status(b, gameDir));
        }
    }

    private static OptionGroupBuilder group(ConfigBuilder builder, String key, String fallback, List<Identifier> idsHolder) {
        curGroup = idsHolder;
        return builder.createOptionGroup().setName(tr(key, fallback));
    }

    private static boolean rtSupported() {
        return VulkanCapabilityChecker.isHardwareRayTracingSupported();
    }

    private static boolean hardwareRenderer(ConfigState state) {
        return state.readEnumOption(id("renderer"), VulkanRtxConfig.RendererSelection.class)
            == VulkanRtxConfig.RendererSelection.HARDWARE_RT;
    }

    /**
     * RT predicates call hardwareRenderer before reading the mode, including when composed with another toggle.
     * Sodium guards every read on the provider's ConfigState; a helper call does not declare dependencies for its caller.
     */
    private static Identifier[] rtDependencies(Identifier modeId, Identifier... additional) {
        Set<Identifier> dependencies = new LinkedHashSet<>();
        dependencies.add(id("renderer"));
        dependencies.add(modeId);
        for (Identifier dependency : additional) dependencies.add(dependency);
        return dependencies.toArray(Identifier[]::new);
    }

    public static Component rendererName(VulkanRtxConfig.RendererSelection renderer) {
        if (renderer == VulkanRtxConfig.RendererSelection.HARDWARE_RT) {
            return tr("bedray.renderer.hardware", "Hardware RT / Raster");
        }
        return GraphicsBackendRegistry.backend() == null
            ? tr("bedray.renderer.voxel_missing", "Voxel (included provider unavailable)")
            : tr("bedray.renderer.voxel", "Voxel (Experimental)");
    }

    public static Component computeSceneName(GraphicsBackendRegistry.SceneMode scene) {
        return switch (scene) {
            case OFF -> tr("bedray.compute.scene.off", "Hardware / Raster");
            case SYNTHETIC -> tr("bedray.compute.scene.synthetic", "Synthetic diagnostic");
            case LIVE_OPAQUE -> tr("bedray.compute.scene.live", "Live opaque debug");
        };
    }

    public static String rendererStatus() {
        if (!VulkanRtxConfig.voxelRendererSelected()) {
            return tr("bedray.renderer.status.hardware", "Hardware / raster renderer selected").getString();
        }
        if (GraphicsBackendRegistry.backend() == null) {
            return tr("bedray.renderer.status.missing", "Unavailable: the included Compute provider is missing or incompatible").getString();
        }
        return GraphicsBackendRegistry.status();
    }

    // ---------------------------------------------------------------- page 1: essentials

    private void buildEssentialPage(ConfigBuilder builder, OptionPageBuilder page, Identifier backendId, Identifier modeId,
                                    Path gameDir, StorageEventHandler st) {
        Function<ConfigState, Boolean> anyRt = s -> hardwareRenderer(s) && s.readEnumOption(modeId, RtMode.class).isActive();
        Function<ConfigState, Boolean> pathOn = s -> hardwareRenderer(s) && s.readEnumOption(modeId, RtMode.class) == RtMode.PATH_TRACED;
        Identifier[] dMode = rtDependencies(modeId);

        // ---- Ray tracing and light
        OptionGroupBuilder rt = group(builder, "bedray.group.main_rt", "Ray Tracing & Light", new ArrayList<>());
        GROUP_IDS.put("main_rt", curGroup);
        EnumOptionBuilder<VulkanRtxConfig.RendererSelection> renderer = builder.createEnumOption(id("renderer"), VulkanRtxConfig.RendererSelection.class);
        renderer.setName(nameOf("renderer")).setTooltip(v -> tipOf("renderer"))
            .setDefaultValue(VulkanRtxConfig.RendererSelection.HARDWARE_RT)
            .setAllowedValues(EnumSet.allOf(VulkanRtxConfig.RendererSelection.class))
            .setElementNameProvider(VulkanSodiumConfig::rendererName)
            .setBinding(VulkanSodiumConfig::applyRendererFromUi, VulkanRtxConfig::selectedRenderer)
            .setImpact(OptionImpact.HIGH).setStorageHandler(st);
        rt.addOption(track("renderer", renderer));
        EnumOptionBuilder<RendererStatus> rendererStatus = builder.createEnumOption(id("renderer_status"), RendererStatus.class);
        rendererStatus.setName(nameOf("renderer_status")).setTooltip(v -> Component.empty()
            .append(tipOf("renderer_status")).append("\n").append(rendererStatus()))
            .setDefaultValue(RendererStatus.STATUS).setElementNameProvider(v -> Component.literal(rendererStatus()))
            .setEnabledProvider(s -> false).setControlHiddenWhenDisabled(false)
            .setBinding(v -> {}, () -> RendererStatus.STATUS).setStorageHandler(st);
        rt.addOption(track("renderer_status", rendererStatus));
        for (boolean voxel : new boolean[] {false, true}) {
            String path = voxel ? "voxel_quality_profile" : "path_quality_profile";
            EnumOptionBuilder<QualityProfile> profile = builder.createEnumOption(id(path), QualityProfile.class);
            profile.setName(nameOf(path)).setTooltip(v -> tipOf(path)).setDefaultValue(QualityProfile.CUSTOM)
                .setAllowedValues(EnumSet.allOf(QualityProfile.class))
                .setElementNameProvider(v -> tr("bedray.settings.profile." + v.name().toLowerCase(Locale.ROOT), v.name()))
                .setBinding(v -> { if (voxel) VulkanRtxConfig.setVoxelQualityProfile(v.name()); else VulkanRtxConfig.setPathQualityProfile(v.name()); },
                    () -> QualityProfile.valueOf(voxel ? VulkanRtxConfig.voxelQualityProfile() : VulkanRtxConfig.pathQualityProfile()))
                .setStorageHandler(st);
            rt.addOption(track(path, profile));
        }
        rt.addOption(boolOpt(builder, "voxel_distance_auto", true, VulkanRtxConfig::setVoxelDistanceAuto,
            VulkanRtxConfig::voxelDistanceAuto, s -> !hardwareRenderer(s), new Identifier[] {id("renderer")},
            Mark.NONE, OptionImpact.MEDIUM, false, st));
        rt.addOption(intOpt(builder, "voxel_distance_chunks", 12, 2, 64, 1, unit(" chunks"),
            VulkanRtxConfig::setVoxelDistanceChunks, VulkanRtxConfig::voxelDistanceChunks,
            s -> !hardwareRenderer(s) && !s.readBooleanOption(id("voxel_distance_auto")),
            new Identifier[] {id("renderer"), id("voxel_distance_auto")}, Mark.NONE, OptionImpact.HIGH, true, st));
        rt.addOption(intOpt(builder, "voxel_memory_budget_mib", 256, 64, 1024, 64, unit(" MiB"),
            VulkanRtxConfig::setVoxelMemoryBudgetMiB, VulkanRtxConfig::voxelMemoryBudgetMiB,
            s -> !hardwareRenderer(s), new Identifier[] {id("renderer")}, Mark.NONE, OptionImpact.HIGH, false, st));
        rt.addOption(boolOpt(builder, "voxel_diffuse_bounce", true, VulkanRtxConfig::setVoxelDiffuseBounce,
            VulkanRtxConfig::voxelDiffuseBounce, s -> !hardwareRenderer(s), new Identifier[] {id("renderer")}, Mark.NONE, OptionImpact.HIGH, false, st));
        rt.addOption(boolOpt(builder, "voxel_translucent_optics", true, VulkanRtxConfig::setVoxelTranslucentOptics,
            VulkanRtxConfig::voxelTranslucentOptics, s -> !hardwareRenderer(s), new Identifier[] {id("renderer")}, Mark.NONE, OptionImpact.HIGH, false, st));
        EnumOptionBuilder<RtMode> modeOpt = builder.createEnumOption(modeId, RtMode.class);
        modeOpt.setName(nameOf("rt_mode")).setTooltip(v -> tipOf("rt_mode"))
            .setDefaultValue(RtMode.OFF)
            .setAllowedValues(allowedRtModes())
            .setElementNameProvider(val -> switch (val) {
                case OFF -> tr("bedray.rt_mode.off", "Off");
                case RAY_TRACED -> tr("bedray.rt_mode.rays", "Ray Tracing (Fast, experimental)");
                case PATH_TRACED -> tr("bedray.rt_mode.path", "Path Tracing (recommended)");
                case AO -> tr("bedray.rt_mode.ao", "AO only (experimental)");
                case DIRECT -> tr("bedray.rt_mode.direct", "Direct light only (experimental)");
            })
            .setEnabledProvider(s -> hardwareRenderer(s) && rtSupported(), id("renderer"))
            .setControlHiddenWhenDisabled(false)
            .setBinding(VulkanSodiumConfig::applyRtModeFromUi, () -> VulkanRtxConfig.rtMode)
            .setImpact(OptionImpact.HIGH).setStorageHandler(st);
        rt.addOption(track("rt_mode", modeOpt));
        rt.addOption(intOpt(builder, "path_bounces", 4, 1, 8, 1, unit(" bounces"),
            v -> RtSettings.pathBounces = v, () -> RtSettings.pathBounces, pathOn, dMode, Mark.PATH, OptionImpact.HIGH, false, st));
        rt.addOption(boolOpt(builder, "path_auto_exposure", false,
            v -> RtSettings.pathAutoExposure = v, () -> RtSettings.pathAutoExposure, pathOn, dMode, Mark.PATH, OptionImpact.LOW, false, st));
        EnumOptionBuilder<WaterStyle> water = builder.createEnumOption(id("water_style"), WaterStyle.class);
        water.setName(nameOf("water_style")).setTooltip(v -> tipOf("water_style")).setDefaultValue(WaterStyle.REALISTIC)
            .setAllowedValues(EnumSet.allOf(WaterStyle.class))
            .setElementNameProvider(v -> v == WaterStyle.VANILLA ? tr("bedray.water.vanilla", "Vanilla") : tr("bedray.water.realistic", "Realistic"))
            .setEnabledProvider(anyRt, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(v -> RtSettings.waterStyle = v == WaterStyle.VANILLA ? RtSettings.WATER_STYLE_VANILLA : RtSettings.WATER_STYLE_REALISTIC,
                () -> RtSettings.waterStyle == RtSettings.WATER_STYLE_VANILLA ? WaterStyle.VANILLA : WaterStyle.REALISTIC)
            .setImpact(OptionImpact.LOW).setStorageHandler(st);
        rt.addOption(track("water_style", water));
        rt.addOption(boolOpt(builder, "rays_optical_materials", true, v -> RtSettings.opticalMaterials = v,
            () -> RtSettings.opticalMaterials, anyRt, dMode, Mark.NONE, OptionImpact.MEDIUM, false, st));
        rt.addOption(boolOpt(builder, "rays_direct_translucent_shadows", true, v -> RtSettings.directTranslucentShadows = v,
            () -> RtSettings.directTranslucentShadows, anyRt, dMode, Mark.NONE, OptionImpact.MEDIUM, false, st));
        rt.addOption(boolOpt(builder, "rays_indirect_translucent_shadows", true, v -> RtSettings.indirectTranslucentShadows = v,
            () -> RtSettings.indirectTranslucentShadows, anyRt, dMode, Mark.NONE, OptionImpact.MEDIUM, false, st));
        rt.addOption(boolOpt(builder, "rays_cull_shared_glass", true, v -> RtSettings.cullSharedGlass = v,
            () -> RtSettings.cullSharedGlass, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        rt.addOption(godRaysOption(builder, anyRt, dMode, st));
        page.addOptionGroup(rt);

        // ---- Upscaling
        OptionGroupBuilder up = group(builder, "bedray.group.main_upscaling", "Upscaling & Frame Rate", new ArrayList<>());
        GROUP_IDS.put("main_upscaling", curGroup);
        addUpscalerBackend(builder, up, backendId, gameDir, st);
        addQualityModes(builder, up, backendId, gameDir, st);
        up.addOption(track("frame_generation_provider", FrameGenOptions.provider(builder, st)));
        up.addOption(track("frame_generation_mode", FrameGenOptions.mode(builder, st)));
        page.addOptionGroup(up);

        // ---- Presets, reset
        OptionGroupBuilder tools = group(builder, "bedray.group.main_tools", "Presets & Reset", new ArrayList<>());
        GROUP_IDS.put("main_tools", curGroup);
        tools.addOption(nativesManagerButton(builder));
        tools.addOption(presetsButton(builder));
        tools.addOption(resetListButton(builder, "reset_page_main", "Reset this page", PAGE_ESSENTIAL));
        page.addOptionGroup(tools);
        curGroup = null;
    }

    private static Set<RtMode> allowedRtModes() {
        EnumSet<RtMode> set = VulkanRtxConfig.raysUnlocked() ? EnumSet.allOf(RtMode.class) : EnumSet.of(RtMode.OFF, RtMode.PATH_TRACED);
        if (VulkanRtxConfig.rtMode != null) {
            set.add(VulkanRtxConfig.rtMode);   // the stored value always stays selectable
        }
        return set;
    }

    private static OptionBuilder godRaysOption(ConfigBuilder builder, Function<ConfigState, Boolean> anyRt, Identifier[] dMode, StorageEventHandler st) {
        EnumOptionBuilder<RtAtmosphereSettings.GodRays> g = builder.createEnumOption(id("atm_godrays"), RtAtmosphereSettings.GodRays.class);
        g.setName(nameOf("atm_godrays")).setTooltip(v -> tipOf("atm_godrays")).setDefaultValue(RtAtmosphereSettings.GodRays.POST)
            .setAllowedValues(EnumSet.of(RtAtmosphereSettings.GodRays.OFF, RtAtmosphereSettings.GodRays.POST, RtAtmosphereSettings.godRays()))
            .setElementNameProvider(v -> switch (v) {
                case OFF -> tr("bedray.onoff.off", "Off");
                case POST -> tr("bedray.onoff.on", "On");
                case INLINE -> tr("bedray.godrays.inline", "Simple (older)");
            })
            .setEnabledProvider(anyRt, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(v -> RtAtmosphereSettings.set("godrays", v.ordinal()), RtAtmosphereSettings::godRays)
            .setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
        return track("atm_godrays", g);
    }

    private void addUpscalerBackend(ConfigBuilder builder, OptionGroupBuilder group, Identifier backendId, Path gameDir, StorageEventHandler st) {
        EnumOptionBuilder<UpscalerBackend> backendOpt = builder.createEnumOption(backendId, UpscalerBackend.class);
        EnumSet<UpscalerBackend> allowed = EnumSet.of(UpscalerBackend.BILINEAR, UpscalerBackend.FSR, UpscalerBackend.DLSS, UpscalerBackend.XESS);
        if (SettingsCatalog.expertUi()) {
            allowed = EnumSet.allOf(UpscalerBackend.class);
        }
        allowed.add(VulkanRtxConfig.activeBackend);   // a stored built-in spatial upscaler stays selectable
        Set<UpscalerBackend> hardwareBackends = Set.copyOf(allowed);
        backendOpt.setName(nameOf("upscaler_backend"))
            .setTooltip(val -> Component.empty().append(backendTooltip(val, UpscalerSelection.status(val, gameDir))).append("\n").append(tipOf("upscaler_backend")))
            .setDefaultValue(UpscalerBackend.BILINEAR)
            .setAllowedValuesProvider(state -> hardwareRenderer(state) ? hardwareBackends : EnumSet.of(UpscalerBackend.BILINEAR), id("renderer"))
            .setElementNameProvider(val -> backendName(val, UpscalerSelection.status(val, gameDir), UpscalerPreregistration.requiresRestart(val)))
            .setBinding(val -> {
                UpscalerBackend before = VulkanRtxConfig.activeBackend;
                UpscalerSwitch.Outcome outcome = VulkanRtxConfig.applyUpscaler(val, null, null, "ui_backend");
                LOGGER.info("[VKB-AUTOTEST] ui_upscaler selected={} active={} status={} changed={} outcome={}",
                    val, VulkanRtxConfig.activeBackend, UpscalerSelection.status(val, gameDir), before != VulkanRtxConfig.activeBackend, outcome);
                VulkanRtxManager.applyResolutionScalingNow();
            }, () -> VulkanRtxConfig.activeBackend)
            .setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
        if (restartNeeded(gameDir, UpscalerBackend.XESS) || restartNeeded(gameDir, UpscalerBackend.DLSS) || restartNeeded(gameDir, UpscalerBackend.FSR)) {
            backendOpt.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
        }
        group.addOption(track("upscaler_backend", backendOpt));
    }

    /** One "Upscaler Quality" row per backend, only the one of the selected backend is shown; Built-in shows "3D Scale". */
    private void addQualityModes(ConfigBuilder builder, OptionGroupBuilder group, Identifier backendId, Path gameDir, StorageEventHandler st) {
        for (UpscalerBackend ub : new UpscalerBackend[] {UpscalerBackend.DLSS, UpscalerBackend.XESS, UpscalerBackend.FSR}) {
            String path = ub.name().toLowerCase(Locale.ROOT) + "_mode";
            EnumOptionBuilder<UpscalerMode> o = builder.createEnumOption(id(path), UpscalerMode.class);
            o.setName(nameOf(path)).setTooltip(val -> Component.empty().append(modeTooltip(ub)).append("\n").append(tipOf(path)))
                .setDefaultValue(UpscalerModes.defaultMode(ub))
                .setAllowedValues(EnumSet.copyOf(UpscalerModes.modesFor(ub)))
                .setElementNameProvider(val -> modeName(ub, val))
                .setEnabledProvider(s -> s.readEnumOption(backendId, UpscalerBackend.class) == ub, backendId)
                .setControlHiddenWhenDisabled(true)
                .setBinding(val -> {
                    UpscalerSwitch.Outcome outcome = switch (ub) {
                        case XESS -> VulkanRtxConfig.applyUpscaler(VulkanRtxConfig.activeBackend, val, null, "ui_xess_mode");
                        case DLSS -> VulkanRtxConfig.applyUpscaler(VulkanRtxConfig.activeBackend, null, val, "ui_dlss_mode");
                        default -> VulkanRtxConfig.applyUpscaler(VulkanRtxConfig.activeBackend, null, null, val, null, "ui_fsr_mode");
                    };
                    LOGGER.info("[VKB-AUTOTEST] ui_upscaler {}={} ratio={} outcome={}", path, val, qualityRatioForLog(ub, val), outcome);
                }, () -> switch (ub) {
                    case XESS -> VulkanRtxConfig.xessMode;
                    case DLSS -> VulkanRtxConfig.dlssMode;
                    default -> VulkanRtxConfig.fsrMode;
                })
                .setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
            if (restartNeeded(gameDir, ub)) {
                o.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
            }
            hideRowWhenDisabled(path);
            group.addOption(track(path, o));
        }
        Identifier presetId = id("quality_preset");
        EnumOptionBuilder<VulkanRtxConfig.QualityPreset> q = builder.createEnumOption(presetId, VulkanRtxConfig.QualityPreset.class);
        q.setName(nameOf("quality_preset")).setTooltip(v -> tipOf("quality_preset")).setDefaultValue(VulkanRtxConfig.QualityPreset.NATIVE)
            .setElementNameProvider(val -> switch (val) {
                case ULTRA_PERFORMANCE -> tr("bedray.preset.ultra_performance", "Ultra Performance (33%)");
                case PERFORMANCE -> tr("bedray.preset.performance", "Performance (50%)");
                case BALANCED -> tr("bedray.preset.balanced", "Balanced (67%)");
                case QUALITY -> tr("bedray.preset.quality", "Quality (77%)");
                case NATIVE -> tr("bedray.preset.native", "Native (100%)");
                case CUSTOM -> tr("bedray.preset.custom", "Custom");
            })
            .setBinding(val -> {
                VulkanRtxConfig.qualityPreset = val;
                VulkanRtxConfig.syncUpscalerTypeWithPreset();
                VulkanRtxManager.applyResolutionScalingNow();
            }, () -> VulkanRtxConfig.qualityPreset)
            .setEnabledProvider(s -> s.readEnumOption(backendId, UpscalerBackend.class) == UpscalerBackend.BILINEAR, backendId)
            .setControlHiddenWhenDisabled(true).setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
        hideRowWhenDisabled("quality_preset");
        group.addOption(track("quality_preset", q));
    }

    private static OptionBuilder presetsButton(ConfigBuilder builder) {
        var b = builder.createExternalButtonOption(id("presets_open"));
        b.setName(tr("bedray.opt.presets_open", "My presets..."))
            .setTooltip(tr("bedray.opt.presets_open.tip", "Save the current Bedray settings under a name, load or delete your presets, copy them as text or import them. Presets contain setting values only."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> Minecraft.getInstance().gui.setScreen(new PresetsScreen(screen)));
        return b;
    }

    private static OptionBuilder nativesManagerButton(ConfigBuilder builder) {
        var b = builder.createExternalButtonOption(id("natives_manager_open"));
        b.setName(tr("bedray.opt.natives_manager_open", "Upscaler & Frame Gen DLLs..."))
            .setTooltip(tr("bedray.opt.natives_manager_open.tip", "Open the library manager to view installed upscalers (FSR, DLSS, XeSS, Streamline), open the folder, or drag-and-drop ZIP archives."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> Minecraft.getInstance().gui.setScreen(new NativesManagerScreen(screen)));
        return b;
    }

    /** "Reset ..." row: sets every listed option to its factory default (like Sodium's own reset); press Apply to keep it. */
    private static OptionBuilder resetListButton(ConfigBuilder builder, String key, String name, List<Identifier> ids) {
        var b = builder.createExternalButtonOption(id(key));
        b.setName(tr("bedray.opt." + key, name))
            .setTooltip(tr("bedray.opt." + key + ".tip", "Sets every setting listed here back to its default. Press Apply to keep the change, Undo to cancel it."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> resetOptions(ids, key));
        return b;
    }

    public static void resetOptions(List<Identifier> ids, String why) {
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

    public static List<Identifier> pageIds(boolean advanced) {
        return advanced ? PAGE_ADVANCED : PAGE_ESSENTIAL;
    }

    public static List<Identifier> groupIds(String key) {
        return GROUP_IDS.getOrDefault(key, List.of());
    }

    // ---------------------------------------------------------------- page 2: advanced

    private OptionGroupBuilder collapsible(ConfigBuilder builder, String key, String fallback, String shortKey) {
        List<Identifier> ids = new ArrayList<>();
        GROUP_IDS.put(shortKey, ids);
        curGroup = ids;
        return builder.createOptionGroup().setName(tr(key, fallback));
    }

    private void endGroup(ConfigBuilder builder, OptionPageBuilder page, OptionGroupBuilder g, String shortKey) {
        List<Identifier> ids = new ArrayList<>(GROUP_IDS.get(shortKey));
        g.addOption(resetListButton(builder, "reset_group_" + shortKey, "Reset this group", ids));
        page.addOptionGroup(g);
        curGroup = null;
    }

    private void buildAdvancedPage(ConfigBuilder builder, OptionPageBuilder page, Identifier backendId, Identifier modeId,
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
        den.setName(nameOf("path_denoiser")).setTooltip(v -> tipOf("path_denoiser")).setDefaultValue(RtSettings.PathDenoiser.DLSS_RR)
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
        pt.addOption(boolOpt(builder, "dlss_rr_geometric_normals", true, v -> RtSettings.dlssRrGeometricNormals = v,
            () -> RtSettings.dlssRrGeometricNormals,
            s -> anyRt.apply(s) && s.readEnumOption(denId, RtSettings.PathDenoiser.class) == RtSettings.PathDenoiser.DLSS_RR,
            rtDependencies(modeId, denId), Mark.NONE, OptionImpact.LOW, true, st));
        endGroup(builder, page, pt, "adv_pt");

        // ---- Light and atmosphere
        OptionGroupBuilder look = collapsible(builder, CollapsibleGroups.ADV_LOOK, "Light & Atmosphere", "adv_look");
        look.addOption(intOpt(builder, "path_exposure_ev", 0, -40, 40, 1,
            v -> Component.literal(String.format(Locale.ROOT, "%+.1f EV", v / 10.0)),
            v -> RtSettings.pathExposureEvTenths = v, () -> RtSettings.pathExposureEvTenths,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
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
        Identifier bloomId = id("atm_bloom");
        look.addOption(boolOpt(builder, "atm_bloom", true, v -> RtAtmosphereSettings.set("bloom", v ? 1 : 0),
            () -> RtAtmosphereSettings.get("bloom") >= 0.5, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        look.addOption(atmSlider(builder, "atm_bloom_strength", "bloom_strength", 25, 0, 200, 5,
            s -> s.readBooleanOption(bloomId) && anyRt.apply(s), rtDependencies(modeId, bloomId), true, st));
        look.addOption(boolOpt(builder, "atm_sunglare", true, v -> RtAtmosphereSettings.set("sunglare", v ? 1 : 0),
            () -> RtAtmosphereSettings.get("sunglare") >= 0.5, anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        Identifier fogId = id("rt_fog");
        look.addOption(boolOpt(builder, "rt_fog", true, v -> RtSettings.volumetricFogEnabled = RtSettings.rtFog = v, () -> RtSettings.rtFog,
            anyRt, dMode, Mark.NONE, OptionImpact.LOW, false, st));
        look.addOption(intOpt(builder, "rt_fog_density", 100, 20, 200, 5, percent(),
            v -> {
                RtSettings.rtFogDensity = v;
                RtSettings.fogDensity = RtSettings.clampFloat(v / 100f, 0.0f, 2.0f);
            }, () -> RtSettings.rtFogDensity, s -> s.readBooleanOption(fogId) && anyRt.apply(s),
            rtDependencies(modeId, fogId), Mark.NONE, OptionImpact.MEDIUM, true, st));
        look.addOption(atmSlider(builder, "atm_fog_aerial", "fog_aerial", 100, 0, 200, 5,
            s -> s.readBooleanOption(fogId) && anyRt.apply(s), rtDependencies(modeId, fogId), true, st));
        Identifier waveId = id("waving_foliage");
        Identifier pixelShadowsId = id("rays_pixel_shadows");
        look.addOption(boolOpt(builder, "rays_pixel_shadows", false, v -> RtSettings.raysPixelShadows = v, () -> RtSettings.raysPixelShadows,
            anyRt, dMode, Mark.RT, OptionImpact.LOW, false, st));
        look.addOption(intOpt(builder, "rays_pixel_shadow_size", 1, 1, 16, 1, unit(" texels"), v -> RtSettings.raysPixelShadowSize = v,
            () -> RtSettings.raysPixelShadowSize,
            s -> anyRt.apply(s) && s.readBooleanOption(pixelShadowsId), rtDependencies(modeId, pixelShadowsId), Mark.RT, OptionImpact.LOW, false, st));
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
        img.addOption(boolOpt(builder, "point_sampling_sharpness", false, v -> {
            VulkanRtxConfig.pointSamplingSharpness = v;
            TextureLodBias.setPointSamplingSharpness(v);
        }, () -> VulkanRtxConfig.pointSamplingSharpness, null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        img.addOption(intOpt(builder, "custom_scale", 100, 25, 100, 5, percent(),
            v -> {
                VulkanRtxConfig.customScalePercent = v;
                VulkanRtxConfig.syncUpscalerTypeWithPreset();
                VulkanRtxManager.applyResolutionScalingNow();
            }, () -> VulkanRtxConfig.customScalePercent,
            s -> s.readEnumOption(id("quality_preset"), VulkanRtxConfig.QualityPreset.class) == VulkanRtxConfig.QualityPreset.CUSTOM
                && s.readEnumOption(backendId, UpscalerBackend.class) == UpscalerBackend.BILINEAR,
            new Identifier[] {id("quality_preset"), backendId}, Mark.NONE, OptionImpact.MEDIUM, true, st));
        img.addOption(intOpt(builder, "fsr_sharpness", 0, 0, 100, 5,
            v -> v == 0 ? tr("bedray.onoff.off", "Off") : Component.literal(v + "%"),
            v -> {
                VulkanRtxConfig.fsrSharpness = v / 100.0f;
                LOGGER.info("[VKB-AUTOTEST] ui_upscaler fsr_sharpness={}", VulkanRtxConfig.fsrSharpness);
            }, () -> Math.round(VulkanRtxConfig.fsrSharpness * 100.0f),
            s -> s.readEnumOption(backendId, UpscalerBackend.class) == UpscalerBackend.FSR, new Identifier[] {backendId}, Mark.NONE,
            OptionImpact.LOW, true, st));
        endGroup(builder, page, img, "adv_image");

        // ---- Frame generation details
        OptionGroupBuilder fg = collapsible(builder, CollapsibleGroups.ADV_FG, "Frame Generation Details", "adv_fg");
        for (OptionBuilder o : FrameGenOptions.details(builder, st)) {
            fg.addOption(o);
        }
        curPage.add(id("frame_generation_reflex"));
        curPage.add(id("frame_generation_limit"));
        curGroup.add(id("frame_generation_reflex"));
        curGroup.add(id("frame_generation_limit"));
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
            addExpertOptions(builder, ex, raysOn, anyRt, pathOn, dMode, modeId, gameDir, st);
            endGroup(builder, page, ex, "expert");
        }

        // ---- Diagnostics
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
        diag.addOption(boolOpt(builder, "debug_overlay", true, VulkanSodiumConfig::setDebugOverlay, VulkanSodiumConfig::debugOverlayOn,
            null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        diag.addOption(boolOpt(builder, "rays_transparency_stats", false, v -> RtSettings.transparencyStats = v,
            () -> RtSettings.transparencyStats, null, NO_DEPS, Mark.NONE, OptionImpact.MEDIUM, false, st));
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
                requestValueRefresh();
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
    }

    private void addExpertOptions(ConfigBuilder builder, OptionGroupBuilder ex, Function<ConfigState, Boolean> raysOn,
                                  Function<ConfigState, Boolean> anyRt, Function<ConfigState, Boolean> pathOn, Identifier[] dMode,
                                  Identifier modeId, Path gameDir, StorageEventHandler st) {
        String[] names = {"rays_shadow_quality", "rays_reflection_quality", "rays_reflection_distance", "rays_ao_quality",
            "rays_ao_radius", "rays_emissive_quality", "rays_emissive_distance", "rays_gi_quality", "rays_gi_distance"};
        int[][] r = {{2, 1, 8, 1}, {1, 1, 4, 1}, {128, RtSettings.DISTANCE_MIN, RtSettings.DISTANCE_MAX, 16}, {2, 1, 8, 1},
            {3, 1, 8, 1}, {1, 1, 8, 1}, {32, 8, 64, 8}, {1, 1, 8, 1}, {64, RtSettings.DISTANCE_MIN, 256, 16}};
        String[] units = {"", "", " blocks", "", " blocks", "", " blocks", "", " blocks"};
        List<Supplier<Integer>> gets = List.of(() -> RtSettings.raysShadowQuality,
            () -> RtSettings.raysReflectionQuality, () -> RtSettings.raysReflectionDistance, () -> RtSettings.raysAoQuality,
            () -> RtSettings.raysAoRadius, () -> RtSettings.raysEmissiveQuality, () -> RtSettings.raysEmissiveDistance,
            () -> RtSettings.raysGiQuality, () -> RtSettings.raysGiDistance);
        List<Consumer<Integer>> sets = List.of(v -> RtSettings.raysShadowQuality = v,
            v -> RtSettings.raysReflectionQuality = v, v -> RtSettings.raysReflectionDistance = v, v -> RtSettings.raysAoQuality = v,
            v -> RtSettings.raysAoRadius = v, v -> RtSettings.raysEmissiveQuality = v, v -> RtSettings.raysEmissiveDistance = v,
            v -> RtSettings.raysGiQuality = v, v -> RtSettings.raysGiDistance = v);
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
        ex.addOption(intOpt(builder, "rt_vram_budget_mb", 2048, 256, 16384, 256, unit(" MB"), v -> RtSettings.vramBudgetMb = v,
            () -> RtSettings.vramBudgetMb, anyRt, dMode, Mark.RT, OptionImpact.MEDIUM, false, st));
        ex.addOption(intOpt(builder, "rt_light_budget", 512, 16, 8192, 16, unit(""), v -> RtSettings.lightBudget = v,
            () -> RtSettings.lightBudget, anyRt, dMode, Mark.RT, OptionImpact.MEDIUM, false, st));
        ex.addOption(intOpt(builder, "sun_size_tenths", 5, 1, 50, 1, unit(""), v -> RtSettings.sunSizeTenths = v,
            () -> RtSettings.sunSizeTenths, anyRt, dMode, Mark.RT, OptionImpact.LOW, false, st));
        ex.addOption(intOpt(builder, "auto_normal_strength", 100, 0, 400, 10, percent(), v -> RtSettings.autoNormalStrength = v,
            () -> RtSettings.autoNormalStrength, anyRt, dMode, Mark.RT, OptionImpact.LOW, false, st));
        ex.addOption(intOpt(builder, "texture_lod_bias_offset", 0, -20, 10, 1,
            v -> Component.literal(String.format(Locale.ROOT, "%.1f", v / 10.0f)),
            v -> {
                float offset = v / 10.0f;
                VulkanRtxConfig.textureLodBiasOffset = offset;
                TextureLodBias.textureLodBiasOffset = offset;
            }, () -> Math.round(VulkanRtxConfig.textureLodBiasOffset * 10.0f), null, NO_DEPS, Mark.NONE, OptionImpact.LOW, false, st));
        ex.addOption(atmSlider(builder, "atm_godrays_range", "godrays_range", 128, 32, 256, 16, anyRt, dMode, false, st));
        ex.addOption(atmSlider(builder, "atm_bloom_threshold", "bloom_threshold", 400, 50, 800, 10, anyRt, dMode, false, st));
        ex.addOption(atmSlider(builder, "atm_bloom_scatter", "bloom_scatter", 70, 30, 95, 5, anyRt, dMode, false, st));
        ex.addOption(atmSlider(builder, "atm_sunglare_strength", "sunglare_strength", 100, 0, 200, 5, anyRt, dMode, false, st));
        ex.addOption(atmSlider(builder, "atm_fog_vanilla", "fog_vanilla", 100, 0, 100, 5, anyRt, dMode, false, st));
        ex.addOption(intOpt(builder, "rt_fog_in_scattering", 100, 0, 200, 5, percent(), v -> {
            RtSettings.rtFogInScattering = v;
            RtSettings.fogInScattering = RtSettings.clampFloat(v / 100f, 0.0f, 2.0f);
        }, () -> RtSettings.rtFogInScattering, anyRt, dMode, Mark.NONE, OptionImpact.MEDIUM, false, st));
        addFsrVersion(builder, ex, id("upscaler_backend"), gameDir, st);
    }

    private void addFsrVersion(ConfigBuilder builder, OptionGroupBuilder group, Identifier backendId, Path gameDir, StorageEventHandler st) {
        List<FfxNative.ProviderVersion> providers = UpscalerSelection.status(UpscalerBackend.FSR, gameDir) == UpscalerSelection.Status.AVAILABLE
            ? FsrRuntime.availableVersions() : List.of();
        EnumSet<FsrVersion> versions = EnumSet.of(FsrVersion.AUTO);
        for (FsrVersion v : FsrVersion.values()) {
            if (v.isAvailableIn(providers)) {
                versions.add(v);
            }
        }
        EnumOptionBuilder<FsrVersion> o = builder.createEnumOption(id("fsr_version"), FsrVersion.class);
        o.setName(nameOf("fsr_version")).setTooltip(v -> tipOf("fsr_version")).setDefaultValue(FsrVersion.AUTO).setAllowedValues(versions)
            .setElementNameProvider(val -> {
                String suffix = val.pick(providers).map(p -> " (" + p.name() + ")").orElse("");
                return Component.empty().append(tr("bedray.fsr_version." + val.name().toLowerCase(Locale.ROOT), val.displayName())).append(suffix);
            })
            .setEnabledProvider(s -> s.readEnumOption(backendId, UpscalerBackend.class) == UpscalerBackend.FSR, backendId)
            .setControlHiddenWhenDisabled(false)
            .setBinding(val -> VulkanRtxConfig.applyUpscaler(VulkanRtxConfig.activeBackend, null, null, null, val, "ui_fsr_version"),
                () -> versions.contains(VulkanRtxConfig.fsrVersion) ? VulkanRtxConfig.fsrVersion : FsrVersion.AUTO)
            .setImpact(OptionImpact.LOW).setStorageHandler(st);
        hideRowWhenDisabled("fsr_version");
        group.addOption(track("fsr_version", o));
    }

    /** After a user preset was applied: engine hooks for the (possibly changed) tracing mode. */
    public static void afterPresetApplied() {
        VulkanRtxConfig.applyRendererSelection();
        RtMode now = VulkanRtxConfig.effectiveRtMode();
        afterModeChange(now, now, "preset");
    }

    /** "PT Quality" binding (also used by the settings_ui autotest through the Config API). */
    public static void applyPathPresetFromUi(RtSettings.PathPreset val) {
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

    public static boolean consumeValueRefreshRequest() {
        boolean r = valueRefreshRequested;
        valueRefreshRequested = false;
        return r;
    }

    public enum RtDebugView {
        OFF(""),
        NORMALS("normals"),
        DEPTH("depth"),
        ALBEDO("albedo"),
        MATERIAL("material"),
        REFLECTION("reflection"),
        AO("ao"),
        SHADOW("shadow"),
        GI("gi"),
        EMISSION("emission"),
        BOUNCES("bounces");

        private final String viewName;

        RtDebugView(String viewName) {
            this.viewName = viewName;
        }

        public String viewName() {
            return viewName;
        }

        public static RtDebugView fromViewName(String name) {
            if (name == null || name.isEmpty()) {
                return OFF;
            }
            for (RtDebugView v : values()) {
                if (v.viewName.equalsIgnoreCase(name)) {
                    return v;
                }
            }
            return OFF;
        }
    }

    private static net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder resetAllDefaultsButton(
            ConfigBuilder builder, String key, String name, String tooltip) {
        var b = builder.createExternalButtonOption(id(key));
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setEnabledProvider(st -> true)
            .setScreenConsumer(VulkanSodiumConfig::resetAllToDefaultsAndReopen);
        return b;
    }

    public static void resetAllToDefaultsAndReopen(net.minecraft.client.gui.screens.Screen current) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        // 1. Reset Upscaling & Resolution
        VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;
        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
        VulkanRtxConfig.customScalePercent = 100;
        VulkanRtxConfig.syncUpscalerTypeWithPreset();
        VulkanRtxConfig.xessMode = UpscalerModes.defaultMode(UpscalerBackend.XESS);
        VulkanRtxConfig.dlssMode = UpscalerModes.defaultMode(UpscalerBackend.DLSS);
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
        RtSettings.dlssRrGeometricNormals = true;
        RtSettings.dlssNrEnabled = false;
        RtSettings.dlssNrTransferStrength = 1.0f;
        RtSettings.dlssNrColourStrength = 1.0f;
        RtSettings.dlssNrMaxRatio = 2.0f;
        RtSettings.dlssNrWorkingScale = 1.0f;
        RtSettings.dlssNrDebugView = 0;
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
    public static void applyRtModeFromUi(RtMode val) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        if (val == VulkanRtxConfig.rtMode) {
            return;
        }
        VulkanRtxConfig.setRtMode(val);
        afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "mode");
    }

    public static void applyRendererFromUi(VulkanRtxConfig.RendererSelection renderer) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        VulkanRtxConfig.setRendererSelection(renderer);
        afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "renderer");
        requestValueRefresh();
        VulkanRtxManager.applyResolutionScalingNow();
    }

    public static void applyComputeSceneFromUi(GraphicsBackendRegistry.SceneMode scene) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        VulkanRtxConfig.setComputeScene(scene);
        afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "compute_scene");
        requestValueRefresh();
        VulkanRtxManager.applyResolutionScalingNow();
    }

    /**
     * Button (Sodium ExternalButtonOption, javap: setScreenConsumer(Consumer of the current Screen)) that resets one level,
     * saves the toml and reopens the settings screen so that every control shows the new values.
     */
    private static net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder resetButton(ConfigBuilder builder,
            String key, String name, String tooltip, RtSettings.Level level, Function<ConfigState, Boolean> enabled, Identifier... deps) {
        var b = builder.createExternalButtonOption(id(key));
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setEnabledProvider(enabled, deps)
            .setScreenConsumer(screen -> resetAndReopen(level, screen));
        return b;
    }

    /** Applies a reset (also used by the settings autotest) and reopens the Sodium screen on the same parent. */
    public static void resetAndReopen(RtSettings.Level level, net.minecraft.client.gui.screens.Screen current) {
        RtMode before = VulkanRtxConfig.effectiveRtMode();
        String what = RtSettings.resetLevel(level);
        VulkanRtxConfig.save();
        if (before != VulkanRtxConfig.effectiveRtMode()) {
            afterModeChange(before, VulkanRtxConfig.effectiveRtMode(), "reset");
        }
        LOGGER.info("[VKB-AUTOTEST] ui_reset level={} {} mode={} rays: shadows={} refl={} ao={} gi={} shadow_rays={} ao_rays={} | {}",
            level.name().toLowerCase(Locale.ROOT), what, VulkanRtxConfig.effectiveRtMode().logName(), RtSettings.raysShadows,
            RtSettings.raysReflections, RtSettings.raysAo, RtSettings.raysGi, RtSettings.raysShadowQuality, RtSettings.raysAoQuality,
            RtSettings.pathSummary());
        if (current instanceof VideoSettingsScreenAccess access) {
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(
                net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(access.vkb$prevScreen()));
        }
    }

    /** Boolean option that does not mark the preset Custom (page layout toggles). */
    private static BooleanOptionBuilder plainBool(ConfigBuilder builder, Identifier optId, String key, String name, String tooltip,
                                                  boolean def, Consumer<Boolean> set, Supplier<Boolean> get,
                                                  Function<ConfigState, Boolean> enabled, StorageEventHandler storage, Identifier... deps) {
        BooleanOptionBuilder b = builder.createBooleanOption(optId);
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setDefaultValue(def)
            .setEnabledProvider(enabled, deps)
            .setControlHiddenWhenDisabled(true)
            .setBinding(set, get)
            .setImpact(OptionImpact.LOW)
            .setStorageHandler(storage);
        return b;
    }

    /** Engine hooks after the RT mode changed through the page (preset or mode option). */
    private static void afterModeChange(RtMode before, RtMode after, String reason) {
        if (after.isActive()) {
            DefaultRtExperimentEngine.getInstance().init();
        } else {
            DefaultRtExperimentEngine.getInstance().cleanup();
        }
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

    /** A page value: written only when it differs (Sodium saves changed options only), then the preset becomes Custom. */
    private static <T> Consumer<T> custom(Consumer<T> set, Supplier<T> get) {
        return v -> {
            if (!v.equals(get.get())) {
                set.accept(v);
                RtSettings.markCustom();
                requestValueRefresh();
            }
        };
    }

    /** A Path Tracing value: written only when it differs, then the PT preset becomes Custom (the RT preset is untouched). */
    private static <T> Consumer<T> customPath(Consumer<T> set, Supplier<T> get) {
        return v -> {
            if (!v.equals(get.get())) {
                set.accept(v);
                RtSettings.markPathCustom();
                requestValueRefresh();
                io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.resetHistory();
            }
        };
    }

    private static BooleanOptionBuilder pathBool(ConfigBuilder builder, String key, String name, String tooltip, boolean def,
                                                 Consumer<Boolean> set, Supplier<Boolean> get,
                                                 Function<ConfigState, Boolean> enabled, StorageEventHandler storage, Identifier... deps) {
        BooleanOptionBuilder b = builder.createBooleanOption(id(key));
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setDefaultValue(def)
            .setEnabledProvider(enabled, deps)
            .setControlHiddenWhenDisabled(true)
            .setBinding(customPath(set, get), get)
            .setImpact(OptionImpact.HIGH)
            .setStorageHandler(storage);
        return b;
    }

    private static IntegerOptionBuilder pathInt(ConfigBuilder builder, String key, String name, String tooltip,
                                                int def, int min, int max, int step, String unit,
                                                Consumer<Integer> set, Supplier<Integer> get,
                                                Function<ConfigState, Boolean> enabled, StorageEventHandler storage, Identifier... deps) {
        IntegerOptionBuilder b = builder.createIntegerOption(id(key));
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setDefaultValue(def)
            .setRange(min, max, step)
            .setValueFormatter(v -> Component.literal(v + unit))
            .setEnabledProvider(enabled, deps)
            .setControlHiddenWhenDisabled(true)
            .setBinding(customPath(set, get), get)
            .setImpact(OptionImpact.HIGH)
            .setStorageHandler(storage);
        return b;
    }

    private static BooleanOptionBuilder bool(ConfigBuilder builder, Identifier optId, String key, String name, String tooltip,
                                             boolean def, Consumer<Boolean> set, Supplier<Boolean> get,
                                             Function<ConfigState, Boolean> enabled, StorageEventHandler storage, Identifier... deps) {
        BooleanOptionBuilder b = builder.createBooleanOption(optId);
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setDefaultValue(def)
            .setEnabledProvider(enabled, deps)
            .setControlHiddenWhenDisabled(true)
            .setBinding(custom(set, get), get)
            .setImpact(OptionImpact.HIGH)
            .setStorageHandler(storage);
        return b;
    }

    private static IntegerOptionBuilder integer(ConfigBuilder builder, Identifier optId, String key, String name, String tooltip,
                                                int def, int min, int max, int step, String unit,
                                                Consumer<Integer> set, Supplier<Integer> get,
                                                Function<ConfigState, Boolean> enabled, StorageEventHandler storage, Identifier... deps) {
        IntegerOptionBuilder b = builder.createIntegerOption(optId);
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setDefaultValue(def)
            .setRange(min, max, step)
            .setValueFormatter(v -> Component.literal(v + unit))
            .setEnabledProvider(enabled, deps)
            .setControlHiddenWhenDisabled(true)
            .setBinding(custom(set, get), get)
            .setImpact(OptionImpact.HIGH)
            .setStorageHandler(storage);
        return b;
    }

    /**
     * True when this page is built after both device-requirement providers ran and a backend that could be selected has no
     * requirements registered in the device (so selecting it only takes effect after a restart).
     */
    private static boolean restartNeeded(Path gameDir, UpscalerBackend backend) {
        return UpscalerPreregistration.providersDecided()
            && UpscalerSelection.status(backend, gameDir) == UpscalerSelection.Status.AVAILABLE
            && UpscalerPreregistration.requiresRestart(backend);
    }

    private static Component modeTooltip(UpscalerBackend backend) {
        boolean restart = UpscalerPreregistration.requiresRestart(backend);
        String tech = backend.name().toLowerCase(Locale.ROOT);
        String base = "bedray.option." + tech + "_mode.tooltip";
        if (backend == UpscalerBackend.FSR) {
            return restart
                ? Component.translatableWithFallback(base + ".restart", "AMD FSR: the world is rendered at the size the FidelityFX library returns for this mode (Native AA 1.0x, Quality 1.5x, Balanced 1.7x, Performance 2.0x, Ultra Performance 3.0x) and reconstructed to the window size. The Vulkan device was created without the FSR requirements, so the mode takes effect after a game restart.")
                : Component.translatableWithFallback(base, "AMD FSR: the world is rendered at the size the FidelityFX library returns for this mode (Native AA 1.0x, Quality 1.5x, Balanced 1.7x, Performance 2.0x, Ultra Performance 3.0x) and reconstructed to the window size. Applied immediately: the FSR context is re-created on the next frame.");
        }
        if (backend == UpscalerBackend.XESS) {
            return restart
                ? Component.translatableWithFallback(base + ".restart", "XeSS render scale: the world is rendered smaller by the ratio of the mode and reconstructed to the window size by XeSS. The Vulkan device was created without the XeSS requirements, so the mode takes effect after a game restart. Ratios from the XeSS SDK 3.0.2 developer guide; the exact input size is returned by the XeSS library.")
                : Component.translatableWithFallback(base, "XeSS render scale: the world is rendered smaller by the ratio of the mode and reconstructed to the window size by XeSS. Applied immediately: the XeSS context is re-created on the next frame. Ratios from the XeSS SDK 3.0.2 developer guide; the exact input size is returned by the XeSS library.");
        }
        return restart
            ? Component.translatableWithFallback(base + ".restart", "DLSS Super Resolution: the world is rendered at the size NVIDIA DLSS asks for this mode and reconstructed to the window size. DLAA renders at full size. The Vulkan device was created without the DLSS requirements, so the mode takes effect after a game restart.")
            : Component.translatableWithFallback(base, "DLSS Super Resolution: the world is rendered at the size NVIDIA DLSS asks for this mode and reconstructed to the window size. DLAA renders at full size. Applied immediately: the DLSS feature is re-created on the next frame.");
    }

    /**
     * "Bilinear (built-in)", "XeSS (Intel)", or the technology name plus " - not implemented" / " - library missing" /
     * " - restart required" (available, but the device was created without its requirements).
     */
    static Component backendName(UpscalerBackend backend, UpscalerSelection.Status status, boolean requiresRestart) {
        Component base = Component.translatableWithFallback(backend.getTranslationKey(), backend.getDisplayName());
        return switch (status) {
            case AVAILABLE -> requiresRestart
                ? Component.empty().append(base).append(" - ").append(
                    Component.translatableWithFallback("bedray.status.restart_required", "restart required"))
                : base;
            case NOT_IMPLEMENTED -> Component.empty().append(base).append(" - ").append(
                Component.translatableWithFallback("bedray.status.not_implemented", "not implemented"));
            case LIBRARY_MISSING -> Component.empty().append(base).append(" - ").append(
                Component.translatableWithFallback("bedray.status.library_missing", "library missing"));
            case UNSUPPORTED_GPU -> Component.empty().append(base).append(" - ").append(
                Component.translatableWithFallback("bedray.status.unsupported_gpu", "GPU not supported"));
        };
    }

    private static Component backendTooltip(UpscalerBackend backend, UpscalerSelection.Status status) {
        boolean restart = status == UpscalerSelection.Status.AVAILABLE && UpscalerPreregistration.requiresRestart(backend);
        String key = "bedray.upscaler.tooltip." + backend.name().toLowerCase(Locale.ROOT) + "."
            + (restart ? "restart_required" : status.name().toLowerCase(Locale.ROOT));
        String fallback = switch (status) {
            case AVAILABLE -> switch (backend) {
                case BILINEAR -> "Built-in bilinear stretch of the scaled 3D target. No temporal reconstruction.";
                case FSR -> restart
                    ? "Temporal super resolution (AMD FSR 3.1/2, official FidelityFX Vulkan library). The Vulkan device was created without the FSR features (library appeared after startup, or -Dvkrtx.upscaler.preregister=false): applied after a game restart."
                    : "Temporal super resolution (AMD FSR 3.1/2, official FidelityFX Vulkan library). Applied immediately. Frame Generation is not available.";
                case DLSS -> restart
                    ? "Temporal super resolution (NVIDIA DLSS). The Vulkan device was created without the DLSS extensions (library appeared after startup, or -Dvkrtx.upscaler.preregister=false): applied after a game restart."
                    : "Temporal super resolution (NVIDIA DLSS). Applied immediately: the Vulkan device was created with the DLSS extensions at startup.";
                default -> restart
                    ? "Temporal super resolution (XeSS). The Vulkan device was created without the XeSS extensions (library appeared after startup, or -Dvkrtx.upscaler.preregister=false): applied after a game restart."
                    : "Temporal super resolution (XeSS). Applied immediately: the Vulkan device was created with the XeSS extensions at startup.";
            };
            case NOT_IMPLEMENTED -> "Not implemented yet. Selecting it has no effect: Bilinear stays active.";
            case LIBRARY_MISSING -> switch (backend) {
                case DLSS -> "nvngx_dlss.dll and vkb_dlss.dll are not both installed (see docs/INSTALL.md). Selecting it has no effect: Bilinear stays active.";
                case FSR -> "The official AMD amd_fidelityfx_vk.dll (FidelityFX SDK v1.1.4, PrebuiltSignedDLL) is not installed (see docs/INSTALL.md), or the file is not an official build. Selecting it has no effect: Bilinear stays active.";
                default -> "libxess.dll is not found in bedray/natives. Selecting it has no effect: Bilinear stays active.";
            };
            case UNSUPPORTED_GPU -> "DLSS needs an NVIDIA RTX GPU with a current driver. Selecting it has no effect: Bilinear stays active.";
        };
        return Component.translatableWithFallback(key, fallback);
    }

    /** The UI records a request; DLSS input dimensions are queried later on the render thread. */
    static String qualityRatioForLog(UpscalerBackend backend, UpscalerMode mode) {
        if (backend == UpscalerBackend.DLSS) {
            return "runtime-ngx";
        }
        double ratio = mode == UpscalerMode.CUSTOM
            ? UpscalerModes.customRatio(VulkanRtxConfig.customPercent(backend))
            : UpscalerModes.ratio(backend, mode);
        return String.format(Locale.ROOT, "%.4fx%s", ratio, mode == UpscalerMode.CUSTOM ? "-requested-custom" : "");
    }

    /** "Quality (1.7x, 58.8%)": mode name plus its table or configured custom scale. */
    static Component modeName(UpscalerBackend backend, UpscalerMode mode) {
        if (backend == UpscalerBackend.DLSS) {
            // no table: the render size comes from NGX at runtime
            return Component.translatableWithFallback(mode.translationKey(), mode.englishName());
        }
        return Component.empty().append(Component.translatableWithFallback(mode.translationKey(), mode.englishName()))
            .append(ratioSuffix(backend, mode));
    }

    /** " (1.5x, 67.0%)": the table ratio of the mode and the render scale it implies; CUSTOM is the user's percent. */
    static String ratioSuffix(UpscalerBackend backend, UpscalerMode mode) {
        double ratio = mode == UpscalerMode.CUSTOM
            ? UpscalerModes.customRatio(VulkanRtxConfig.customPercent(backend))
            : UpscalerModes.ratio(backend, mode);
        return String.format(Locale.ROOT, " (%.1fx, %.1f%%)", ratio, 100.0 / ratio);
    }

}
