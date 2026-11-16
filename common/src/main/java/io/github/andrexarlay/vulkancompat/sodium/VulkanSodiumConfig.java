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
    public enum SelfShadow {
        FULL(RtSettings.SELF_SHADOW_FULL), OFF(RtSettings.SELF_SHADOW_OFF);

        private final int storedValue;

        SelfShadow(int storedValue) {
            this.storedValue = storedValue;
        }

        int storedValue() {
            return storedValue;
        }

        static SelfShadow fromStored(int value) {
            return value == RtSettings.SELF_SHADOW_OFF ? OFF : FULL;
        }

        static SelfShadow parsePreset(String value) {
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "0", "1", "FULL", "NO_FOOTPRINT" -> FULL;
                case "2", "OFF" -> OFF;
                default -> throw new IllegalArgumentException("Unknown player shadow mode: " + value);
            };
        }
    }
    public enum RendererStatus { STATUS }
    public enum GiExecutionStatusRow { STATUS }
    public enum TechnologyStatusRow { STATUS }
    public enum QualityProfile { FAST, BEAUTIFUL, CUSTOM }

    enum Mark { NONE, RT, PATH }

    /** Ids of every option per page / per collapsible group (for the reset buttons), refilled by registerConfigLate. */
    static final List<Identifier> PAGE_ESSENTIAL = new ArrayList<>();
    private static final List<Identifier> PAGE_ADVANCED = new ArrayList<>();
    static final Map<String, List<Identifier>> GROUP_IDS = new LinkedHashMap<>();
    /** Options whose whole row disappears while they are disabled. */
    private static final Set<Identifier> ROW_HIDDEN = new HashSet<>();
    private static List<Identifier> curPage = PAGE_ESSENTIAL;

    /** The id list of the page being built. */
    static List<Identifier> curPageIds() {
        return curPage;
    }
    private static List<Identifier> curGroup = null;

    /** The id list of the group being built; the page classes record each option they add into it. */
    static void clearCurGroup() {
        curGroup = null;
    }

    static List<Identifier> curGroupIds() {
        return curGroup;
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("bedray", path);
    }

    static Component tr(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback);
    }

    static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    // ---------------------------------------------------------------- catalog-backed texts

    static Component nameOf(String path) {
        SettingsCatalog.Info i = SettingsCatalog.get(path);
        return tr("bedray.opt." + path, i == null ? path : i.name());
    }

    /** Sodium tooltip (used when the right panel is hidden): description, cost, requirement. */
    static Component tipOf(String path) {
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

    static <T extends OptionBuilder> T track(String path, T opt) {
        Identifier id = id(path);
        curPage.add(id);
        if (curGroup != null) {
            curGroup.add(id);
        }
        return opt;
    }

    /** Hides the whole row while the option is disabled. */
    static void hideRowWhenDisabled(String path) {
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

    static <T> Consumer<T> mark(Mark m, Consumer<T> set, Supplier<T> get) {
        return switch (m) {
            case RT -> custom(set, get);
            case PATH -> customPath(set, get);
            case NONE -> set;
        };
    }

    static BooleanOptionBuilder boolOpt(ConfigBuilder b, String path, boolean def, Consumer<Boolean> set, Supplier<Boolean> get,
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

    static IntegerOptionBuilder intOpt(ConfigBuilder b, String path, int def, int min, int max, int step,
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

    static Function<Integer, Component> unit(String u) {
        return v -> Component.literal(v + u);
    }

    static final Identifier[] NO_DEPS = {};

    static Function<Integer, Component> percent() {
        return v -> Component.literal(v + "%");
    }

    // ---------------------------------------------------------------- registration

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions().setName("Bedray").addPage(builder.createExternalPage()
            .setName(tr("bedray.settings.open", "Bedray Settings..."))
            .setScreenConsumer(BedrayConfigScreen::open));
        LOGGER.info("[Bedray/SodiumPage] external settings page registered");
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
        SodiumEssentialPage.build(builder, essential, backendId, modeId, gameDir, storage);
        modOptions.addPage(essential);

        OptionPageBuilder advanced = builder.createOptionPage().setName(tr("bedray.page.advanced_main", "Bedray Advanced"));
        curPage = PAGE_ADVANCED;
        curGroup = null;
        SodiumAdvancedPage.build(builder, advanced, backendId, modeId, gameDir, storage);
        modOptions.addPage(advanced);

        LOGGER.info("[Bedray/SodiumPage] registered 2 pages: Bedray ({} options), Bedray Advanced ({} options, expert={})",
            PAGE_ESSENTIAL.size(), PAGE_ADVANCED.size(), SettingsCatalog.expertUi());
        for (UpscalerBackend b : UpscalerBackend.values()) {
            LOGGER.info("[VKB-AUTOTEST] ui_upscaler backend={} status={}", b, UpscalerSelection.status(b, gameDir));
        }
    }

    static OptionGroupBuilder group(ConfigBuilder builder, String key, String fallback, List<Identifier> idsHolder) {
        curGroup = idsHolder;
        return builder.createOptionGroup().setName(tr(key, fallback));
    }

    static boolean rtSupported() {
        return VulkanCapabilityChecker.isHardwareRayTracingSupported();
    }

    static boolean hardwareRenderer(ConfigState state) {
        return state.readEnumOption(id("renderer"), VulkanRtxConfig.RendererSelection.class)
            == VulkanRtxConfig.RendererSelection.HARDWARE_RT;
    }

    /**
     * RT predicates call hardwareRenderer before reading the mode, including when composed with another toggle.
     * Sodium guards every read on the provider's ConfigState; a helper call does not declare dependencies for its caller.
     */
    static Identifier[] rtDependencies(Identifier modeId, Identifier... additional) {
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

    public static Component shaderHostName(ShaderHostKind kind) {
        return switch (kind) {
            case NATIVE -> tr("bedray.shader_host.native", "Native RT");
            case IRIS -> tr("bedray.shader_host.iris", "Iris Shaderpack");
        };
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


    static Set<RtMode> allowedRtModes() {
        EnumSet<RtMode> set = VulkanRtxConfig.raysUnlocked() ? EnumSet.allOf(RtMode.class) : EnumSet.of(RtMode.OFF, RtMode.PATH_TRACED);
        if (VulkanRtxConfig.rtMode != null) {
            set.add(VulkanRtxConfig.rtMode);   // the stored value always stays selectable
        }
        return set;
    }

    static OptionBuilder godRaysOption(ConfigBuilder builder, Function<ConfigState, Boolean> anyRt, Identifier[] dMode, StorageEventHandler st) {
        EnumOptionBuilder<RtAtmosphereSettings.GodRays> g = builder.createEnumOption(id("atm_godrays"), RtAtmosphereSettings.GodRays.class);
        g.setName(nameOf("atm_godrays")).setTooltip(v -> tipOf("atm_godrays")).setDefaultValue(RtAtmosphereSettings.GodRays.POST)
            .setAllowedValues(EnumSet.allOf(RtAtmosphereSettings.GodRays.class))
            .setElementNameProvider(v -> switch (v) {
                case OFF -> tr("bedray.onoff.off", "Off");
                case POST -> tr("bedray.godrays.post", "Detailed");
                case INLINE -> tr("bedray.godrays.inline", "Simple (older)");
            })
            .setEnabledProvider(anyRt, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(v -> RtAtmosphereSettings.set("godrays", v.ordinal()), RtAtmosphereSettings::godRays)
            .setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
        return track("atm_godrays", g);
    }

    static void addUpscalerBackend(ConfigBuilder builder, OptionGroupBuilder group, Identifier backendId, Path gameDir, StorageEventHandler st) {
        EnumOptionBuilder<UpscalerChoice> backendOpt = builder.createEnumOption(backendId, UpscalerChoice.class);
        EnumSet<UpscalerChoice> allowed = EnumSet.of(UpscalerChoice.BILINEAR, UpscalerChoice.FSR_AUTO, UpscalerChoice.DLSS, UpscalerChoice.XESS);
        if (SettingsCatalog.expertUi()) {
            allowed.add(UpscalerChoice.FSR1);
            allowed.add(UpscalerChoice.NIS);
        }
        var providers = FsrRuntime.availableVersions();
        for (UpscalerChoice choice : UpscalerChoice.values()) {
            if (choice.version() != null && choice.version().isAvailableIn(providers)) allowed.add(choice);
        }
        allowed.add(UpscalerChoice.of(VulkanRtxConfig.activeBackend, VulkanRtxConfig.fsrVersion));
        Set<UpscalerChoice> hardwareBackends = EnumSet.copyOf(allowed);
        Map<UpscalerBackend, UpscalerSelection.Status> statuses = new java.util.EnumMap<>(UpscalerBackend.class);
        for (UpscalerBackend backend : UpscalerBackend.values()) statuses.put(backend, UpscalerSelection.status(backend, gameDir));
        Map<UpscalerBackend, String> runtimeVersions = UpscalerSettingsVersions.installedVersions();
        backendOpt.setName(nameOf("upscaler_backend"))
            .setTooltip(val -> Component.empty().append(backendTooltip(val.backend(), statuses.get(val.backend()))).append("\n").append(tipOf("upscaler_backend")))
            .setDefaultValue(UpscalerChoice.BILINEAR)
            .setAllowedValuesProvider(state -> hardwareRenderer(state) ? hardwareBackends : EnumSet.of(UpscalerChoice.BILINEAR), id("renderer"))
            .setElementNameProvider(val -> {
                var backend = val.backend();
                boolean restart = UpscalerPreregistration.requiresRestart(backend);
                if (val.version() == null) {
                    return backendName(backend, statuses.get(backend), restart, runtimeVersions.get(backend));
                }
                if (val.version() == FsrVersion.AUTO) {
                    return backendName(backend, statuses.get(backend), restart, tr("bedray.fsr_version.auto", "Auto").getString());
                }
                Component label = tr("bedray.fsr_version." + val.name().toLowerCase(Locale.ROOT), val.version().displayName());
                return val.version().pick(providers).map(provider -> label.copy().append(" · ").append(provider.name())).orElseGet(() ->
                    label.copy().append(" · ").append(tr("bedray.settings.status.unsupported_hardware", "Unavailable")));
            })
            .setBinding(val -> {
                UpscalerSwitch.Outcome outcome = VulkanRtxConfig.applyUpscaler(val.backend(), null, null, null, val.version(), "ui_backend");
                LOGGER.info("[VKB-AUTOTEST] ui_upscaler selected={} active={} outcome={}", val, VulkanRtxConfig.activeBackend, outcome);
                VulkanRtxManager.applyResolutionScalingNow();
            }, () -> UpscalerChoice.of(VulkanRtxConfig.activeBackend, VulkanRtxConfig.fsrVersion))
            .setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
        if (restartNeeded(gameDir, UpscalerBackend.XESS) || restartNeeded(gameDir, UpscalerBackend.DLSS) || restartNeeded(gameDir, UpscalerBackend.FSR)) {
            backendOpt.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
        }
        group.addOption(track("upscaler_backend", backendOpt));
    }

    /** One "Upscaler Quality" row per backend, only the one of the selected backend is shown; Built-in shows "3D Scale". */
    static void addQualityModes(ConfigBuilder builder, OptionGroupBuilder group, Identifier backendId, Path gameDir, StorageEventHandler st) {
        for (UpscalerBackend ub : new UpscalerBackend[] {UpscalerBackend.DLSS, UpscalerBackend.XESS, UpscalerBackend.FSR, UpscalerBackend.FSR1}) {
            String path = ub.name().toLowerCase(Locale.ROOT) + "_mode";
            EnumOptionBuilder<UpscalerMode> o = builder.createEnumOption(id(path), UpscalerMode.class);
            o.setName(nameOf(path)).setTooltip(val -> ub.isSpatial() ? tipOf(path) : modeTooltip(ub))
                .setDefaultValue(UpscalerModes.defaultMode(ub))
                .setAllowedValues(EnumSet.copyOf(UpscalerModes.modesFor(ub)))
                .setElementNameProvider(val -> modeName(ub, val))
                .setEnabledProvider(s -> s.readEnumOption(backendId, UpscalerChoice.class).backend() == ub, backendId)
                .setControlHiddenWhenDisabled(true)
                .setBinding(val -> {
                    UpscalerSwitch.Outcome outcome = switch (ub) {
                        case XESS -> VulkanRtxConfig.applyUpscaler(VulkanRtxConfig.activeBackend, val, null, "ui_xess_mode");
                        case DLSS -> VulkanRtxConfig.applyUpscaler(VulkanRtxConfig.activeBackend, null, val, "ui_dlss_mode");
                        case FSR -> VulkanRtxConfig.applyUpscaler(VulkanRtxConfig.activeBackend, null, null, val, null, "ui_fsr_mode");
                        case FSR1 -> VulkanRtxConfig.applyUpscalerFull(VulkanRtxConfig.activeBackend, null, null, null, null, val, "ui_fsr1_mode");
                        default -> throw new IllegalStateException("No quality selector for " + ub);
                    };
                    LOGGER.info("[VKB-AUTOTEST] ui_upscaler {}={} ratio={} outcome={}", path, val, qualityRatioForLog(ub, val), outcome);
                }, () -> VulkanRtxConfig.modeOf(ub))
                .setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
            if (restartNeeded(gameDir, ub)) {
                o.setFlags(OptionFlag.REQUIRES_GAME_RESTART);
            }
            hideRowWhenDisabled(path);
            group.addOption(track(path, o));
            addCustomUpscalerScale(builder, group, backendId, ub, id(path), st);
        }
        addCustomUpscalerScale(builder, group, backendId, UpscalerBackend.NIS, null, st);
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
            .setEnabledProvider(s -> s.readEnumOption(backendId, UpscalerChoice.class).backend() == UpscalerBackend.BILINEAR, backendId)
            .setControlHiddenWhenDisabled(true).setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
        hideRowWhenDisabled("quality_preset");
        group.addOption(track("quality_preset", q));
        group.addOption(intOpt(builder, "custom_scale", 100, 25, 100, 5, percent(),
            v -> {
                VulkanRtxConfig.customScalePercent = v;
                VulkanRtxConfig.syncUpscalerTypeWithPreset();
                VulkanRtxManager.applyResolutionScalingNow();
            }, () -> VulkanRtxConfig.customScalePercent,
            s -> s.readEnumOption(presetId, VulkanRtxConfig.QualityPreset.class) == VulkanRtxConfig.QualityPreset.CUSTOM
                && s.readEnumOption(backendId, UpscalerChoice.class).backend() == UpscalerBackend.BILINEAR,
            new Identifier[] {id("quality_preset"), backendId}, Mark.NONE, OptionImpact.MEDIUM, true, st));
    }

    static void addCustomUpscalerScale(ConfigBuilder builder, OptionGroupBuilder group, Identifier backendId,
                                               UpscalerBackend backend, Identifier modeId, StorageEventHandler st) {
        String path = backend.name().toLowerCase(Locale.ROOT) + "_custom_scale";
        Identifier[] deps = modeId == null ? new Identifier[] {backendId} : new Identifier[] {backendId, modeId};
        group.addOption(intOpt(builder, path, UpscalerModes.CUSTOM_DEFAULT_PERCENT, UpscalerModes.customMinPercent(backend),
            UpscalerModes.CUSTOM_MAX_PERCENT, 1, percent(), value -> {
                VulkanRtxConfig.setCustomPercent(backend, value);
                if (VulkanRtxConfig.activeBackend == backend && VulkanRtxConfig.modeOf(backend) == UpscalerMode.CUSTOM) {
                    VulkanRtxConfig.reapplyUpscaler("ui_custom_scale");
                }
            }, () -> VulkanRtxConfig.customPercent(backend),
            state -> state.readEnumOption(backendId, UpscalerChoice.class).backend() == backend
                && (modeId == null || state.readEnumOption(modeId, UpscalerMode.class) == UpscalerMode.CUSTOM),
            deps, Mark.NONE, OptionImpact.MEDIUM, true, st));
    }

    static void addDlssModel(ConfigBuilder builder, OptionGroupBuilder group, Identifier backendId, StorageEventHandler st) {
        String path = "dlss_sr_preset";
        var option = builder.createEnumOption(id(path), DlssSrPreset.class);
        option.setName(nameOf(path)).setTooltip(value -> tipOf(path)).setDefaultValue(DlssSrPreset.AUTO)
            .setAllowedValues(EnumSet.allOf(DlssSrPreset.class))
            .setElementNameProvider(value -> value == DlssSrPreset.AUTO ? tr("bedray.fsr_version.auto", "Auto") : Component.literal(value.name()))
            .setEnabledProvider(state -> state.readEnumOption(backendId, UpscalerChoice.class) == UpscalerChoice.DLSS, backendId)
            .setControlHiddenWhenDisabled(true)
            .setBinding(value -> VulkanRtxConfig.dlssSrPreset = value, () -> VulkanRtxConfig.dlssSrPreset)
            .setImpact(OptionImpact.MEDIUM).setStorageHandler(st);
        hideRowWhenDisabled(path);
        group.addOption(track(path, option));
    }

    static OptionBuilder presetsButton(ConfigBuilder builder) {
        var b = builder.createExternalButtonOption(id("presets_open"));
        b.setName(tr("bedray.opt.presets_open", "My presets..."))
            .setTooltip(tr("bedray.opt.presets_open.tip", "Save the current Bedray settings under a name, load or delete your presets, copy them as text or import them. Presets contain setting values only."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> Minecraft.getInstance().gui.setScreen(new PresetsScreen(screen)));
        return b;
    }

    static OptionBuilder nativesManagerButton(ConfigBuilder builder) {
        var b = builder.createExternalButtonOption(id("natives_manager_open"));
        b.setName(tr("bedray.opt.natives_manager_open", "Upscaler & Frame Gen DLLs..."))
            .setTooltip(tr("bedray.opt.natives_manager_open.tip", "Open the library manager to view installed upscalers (FSR, DLSS, XeSS, Streamline), open the folder, or drag-and-drop ZIP archives."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> Minecraft.getInstance().gui.setScreen(new NativesManagerScreen(screen)));
        return b;
    }

    /** "Reset ..." row: sets every listed option to its factory default (like Sodium's own reset); press Apply to keep it. */
    static OptionBuilder resetListButton(ConfigBuilder builder, String key, String name, List<Identifier> ids) {
        var b = builder.createExternalButtonOption(id(key));
        b.setName(tr("bedray.opt." + key, name))
            .setTooltip(tr("bedray.opt." + key + ".tip", "Sets every setting listed here back to its default. Press Apply to keep the change, Undo to cancel it."))
            .setEnabledProvider(s -> true)
            .setScreenConsumer(screen -> resetOptions(ids, key));
        return b;
    }

    public static void resetOptions(List<Identifier> ids, String why) {
        BedraySettingsActions.resetOptions(ids, why);
    }

    public static List<Identifier> pageIds(boolean advanced) {
        return advanced ? PAGE_ADVANCED : PAGE_ESSENTIAL;
    }

    public static List<Identifier> groupIds(String key) {
        return GROUP_IDS.getOrDefault(key, List.of());
    }

    // ---------------------------------------------------------------- page 2: advanced

    static OptionGroupBuilder collapsible(ConfigBuilder builder, String key, String fallback, String shortKey) {
        List<Identifier> ids = new ArrayList<>();
        GROUP_IDS.put(shortKey, ids);
        curGroup = ids;
        return builder.createOptionGroup().setName(tr(key, fallback));
    }

    static void endGroup(ConfigBuilder builder, OptionPageBuilder page, OptionGroupBuilder g, String shortKey) {
        List<Identifier> ids = new ArrayList<>(GROUP_IDS.get(shortKey));
        g.addOption(resetListButton(builder, "reset_group_" + shortKey, "Reset this group", ids));
        page.addOptionGroup(g);
        curGroup = null;
    }

    public static void afterPresetApplied() {
        BedraySettingsActions.afterPresetApplied();
    }

    public static void applyPathPresetFromUi(RtSettings.PathPreset val) {
        BedraySettingsActions.applyPathPresetFromUi(val);
    }

    static void applyScreenConfig(net.caffeinemc.mods.sodium.client.config.structure.Config config, Map<String, String> imported) {
        BedraySettingsActions.applyScreenConfig(config, imported);
    }

    static void requestValueRefresh() {
        BedraySettingsActions.requestValueRefresh();
    }

    public static boolean consumeValueRefreshRequest() {
        return BedraySettingsActions.consumeValueRefreshRequest();
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

    static net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder resetAllDefaultsButton(
            ConfigBuilder builder, String key, String name, String tooltip) {
        var b = builder.createExternalButtonOption(id(key));
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setEnabledProvider(st -> true)
            .setScreenConsumer(VulkanSodiumConfig::resetAllToDefaultsAndReopen);
        return b;
    }

    public static void resetAllToDefaultsAndReopen(net.minecraft.client.gui.screens.Screen current) {
        BedraySettingsActions.resetAllToDefaultsAndReopen(current);
    }

    public static void applyRtModeFromUi(RtMode val) {
        BedraySettingsActions.applyRtModeFromUi(val);
    }

    public static void applyRendererFromUi(VulkanRtxConfig.RendererSelection renderer) {
        BedraySettingsActions.applyRendererFromUi(renderer);
    }

    /** #35: the settings-page shader_host toggle. Persists the choice and queues a live switch applied at the next frame head. */
    public static void applyShaderHostFromUi(ShaderHostKind kind) {
        ShaderHosts.requestSwitch(kind);
        requestValueRefresh();
    }

    public static void applyComputeSceneFromUi(GraphicsBackendRegistry.SceneMode scene) {
        BedraySettingsActions.applyComputeSceneFromUi(scene);
    }

    /**
     * Button (Sodium ExternalButtonOption, javap: setScreenConsumer(Consumer of the current Screen)) that resets one level,
     * saves the toml and reopens the settings screen so that every control shows the new values.
     */
    static net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder resetButton(ConfigBuilder builder,
            String key, String name, String tooltip, RtSettings.Level level, Function<ConfigState, Boolean> enabled, Identifier... deps) {
        var b = builder.createExternalButtonOption(id(key));
        b.setName(tr("bedray.option." + key, name))
            .setTooltip(tr("bedray.option." + key + ".tooltip", tooltip))
            .setEnabledProvider(enabled, deps)
            .setScreenConsumer(screen -> resetAndReopen(level, screen));
        return b;
    }

    public static void resetAndReopen(RtSettings.Level level, net.minecraft.client.gui.screens.Screen current) {
        BedraySettingsActions.resetAndReopen(level, current);
    }

    /** Boolean option that does not mark the preset Custom (page layout toggles). */
    static BooleanOptionBuilder plainBool(ConfigBuilder builder, Identifier optId, String key, String name, String tooltip,
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

    /** A page value: written only when it differs (Sodium saves changed options only), then the preset becomes Custom. */
    static <T> Consumer<T> custom(Consumer<T> set, Supplier<T> get) {
        return v -> {
            if (!v.equals(get.get())) {
                set.accept(v);
                RtSettings.markCustom();
                requestValueRefresh();
            }
        };
    }

    /** A Path Tracing value: written only when it differs, then the PT preset becomes Custom (the RT preset is untouched). */
    static <T> Consumer<T> customPath(Consumer<T> set, Supplier<T> get) {
        return v -> {
            if (!v.equals(get.get())) {
                set.accept(v);
                RtSettings.markPathCustom();
                requestValueRefresh();
                io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.resetHistory();
            }
        };
    }

    static BooleanOptionBuilder pathBool(ConfigBuilder builder, String key, String name, String tooltip, boolean def,
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

    static IntegerOptionBuilder pathInt(ConfigBuilder builder, String key, String name, String tooltip,
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

    static BooleanOptionBuilder bool(ConfigBuilder builder, Identifier optId, String key, String name, String tooltip,
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

    static IntegerOptionBuilder integer(ConfigBuilder builder, Identifier optId, String key, String name, String tooltip,
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
    static boolean restartNeeded(Path gameDir, UpscalerBackend backend) {
        return UpscalerPreregistration.providersDecided()
            && UpscalerSelection.status(backend, gameDir) == UpscalerSelection.Status.AVAILABLE
            && UpscalerPreregistration.requiresRestart(backend);
    }

    static Component modeTooltip(UpscalerBackend backend) {
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
    static Component backendName(UpscalerBackend backend, UpscalerSelection.Status status, boolean requiresRestart, String version) {
        var base = Component.translatableWithFallback(backend.getTranslationKey(), backend.getDisplayName());
        if (version != null) base.append(" · ").append(version);
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

    static Component backendTooltip(UpscalerBackend backend, UpscalerSelection.Status status) {
        boolean restart = status == UpscalerSelection.Status.AVAILABLE && UpscalerPreregistration.requiresRestart(backend);
        String key = "bedray.upscaler.tooltip." + backend.name().toLowerCase(Locale.ROOT) + "."
            + (restart ? "restart_required" : status.name().toLowerCase(Locale.ROOT));
        String fallback = switch (status) {
            case AVAILABLE -> switch (backend) {
                case BILINEAR -> "Built-in bilinear stretch of the scaled 3D target. No temporal reconstruction.";
                case FSR -> restart
                    ? "Temporal super resolution through the installed AMD FSR Vulkan providers. The Vulkan device was created without the FSR features (library appeared after startup, or -Dvkrtx.upscaler.preregister=false): applied after a game restart."
                    : "Temporal super resolution through the installed AMD FSR Vulkan providers. Applied immediately.";
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
                case FSR -> "Install the signed Vulkan files from AMD FSR SDK 2.4 or FidelityFX SDK 1.1.4, then restart. See the runtime library guide.";
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

    /** Shows table ratios for presets whose scale is known. The custom slider shows the pending percentage. */
    static Component modeName(UpscalerBackend backend, UpscalerMode mode) {
        Component name = Component.translatableWithFallback(mode.translationKey(), mode.englishName());
        if (mode == UpscalerMode.CUSTOM) {
            return name;
        }
        if (mode == UpscalerMode.DLAA) {
            return name.copy().append(" (100%)");
        }
        if (backend == UpscalerBackend.DLSS) {
            return name;
        }
        return name.copy().append(ratioSuffix(backend, mode));
    }

    /** " (1.5x, 67.0%)": the table ratio of the mode and the render scale it implies; CUSTOM is the user's percent. */
    static String ratioSuffix(UpscalerBackend backend, UpscalerMode mode) {
        double ratio = mode == UpscalerMode.CUSTOM
            ? UpscalerModes.customRatio(VulkanRtxConfig.customPercent(backend))
            : UpscalerModes.ratio(backend, mode);
        return String.format(Locale.ROOT, " (%.1fx, %.1f%%)", ratio, 100.0 / ratio);
    }

}
