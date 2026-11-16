package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.RtDebugView;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.GiExecutionStatusRow;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.QualityProfile;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.RendererStatus;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.SelfShadow;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.WaterStyle;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.GROUP_IDS;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.PAGE_ESSENTIAL;
import static io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.clearCurGroup;
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
 * The first Sodium page, "Bedray": the essentials (renderer, ray tracing mode, light quality, water, upscaler, frame
 * generation) followed by the presets and reset group. Split out of {@link VulkanSodiumConfig}.
 */
final class SodiumEssentialPage {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Bedray/SodiumPage");

    private SodiumEssentialPage() {
    }

    static void build(ConfigBuilder builder, OptionPageBuilder page, Identifier backendId, Identifier modeId,
                      Path gameDir, StorageEventHandler st) {
        Function<ConfigState, Boolean> anyRt = s -> hardwareRenderer(s) && s.readEnumOption(modeId, RtMode.class).isActive();
        Function<ConfigState, Boolean> pathOn = s -> hardwareRenderer(s) && s.readEnumOption(modeId, RtMode.class) == RtMode.PATH_TRACED;
        Identifier[] dMode = rtDependencies(modeId);

        // ---- Ray tracing and light
        OptionGroupBuilder rt = group(builder, "bedray.group.main_rt", "Ray Tracing & Light", new ArrayList<>());
        GROUP_IDS.put("main_rt", curGroupIds());
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
            .append(tipOf("renderer_status")).append("\n").append(VulkanSodiumConfig.rendererStatus()))
            .setDefaultValue(RendererStatus.STATUS).setElementNameProvider(v -> Component.literal(VulkanSodiumConfig.rendererStatus()))
            .setEnabledProvider(s -> false).setControlHiddenWhenDisabled(false)
            .setBinding(v -> {}, () -> RendererStatus.STATUS).setStorageHandler(st);
        rt.addOption(track("renderer_status", rendererStatus));
        EnumOptionBuilder<ShaderHostKind> shaderHost = builder.createEnumOption(id("shader_host"), ShaderHostKind.class);
        shaderHost.setName(nameOf("shader_host")).setTooltip(v -> tipOf("shader_host"))
            .setDefaultValue(ShaderHostKind.NATIVE)
            .setAllowedValues(EnumSet.allOf(ShaderHostKind.class))
            .setElementNameProvider(VulkanSodiumConfig::shaderHostName)
            .setBinding(VulkanSodiumConfig::applyShaderHostFromUi, ShaderHostConfig::persisted)
            .setImpact(OptionImpact.HIGH).setStorageHandler(st);
        rt.addOption(track("shader_host", shaderHost));
        EnumOptionBuilder<GiExecutionStatusRow> giStatus = builder.createEnumOption(id("gi_execution_status"), GiExecutionStatusRow.class);
        giStatus.setName(nameOf("gi_execution_status")).setTooltip(v -> Component.empty()
            .append(tipOf("gi_execution_status")).append("\n")
            .append(io.github.andrexarlay.vulkancompat.rtx.raytracing.GiExecutionStatus.live()))
            .setDefaultValue(GiExecutionStatusRow.STATUS)
            .setElementNameProvider(v -> Component.literal(
                io.github.andrexarlay.vulkancompat.rtx.raytracing.GiExecutionStatus.live()))
            .setEnabledProvider(s -> false).setControlHiddenWhenDisabled(false)
            .setBinding(v -> {}, () -> GiExecutionStatusRow.STATUS).setStorageHandler(st);
        rt.addOption(track("gi_execution_status", giStatus));
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
        EnumOptionBuilder<SelfShadow> selfShadow = builder.createEnumOption(id("self_shadow"), SelfShadow.class);
        selfShadow.setName(nameOf("self_shadow")).setTooltip(v -> tipOf("self_shadow")).setDefaultValue(SelfShadow.FULL)
            .setAllowedValues(EnumSet.allOf(SelfShadow.class))
            .setElementNameProvider(v -> switch (v) {
                case FULL -> tr("bedray.self_shadow.full", "Full");
                case OFF -> tr("bedray.self_shadow.off", "Off");
            })
            .setEnabledProvider(anyRt, dMode).setControlHiddenWhenDisabled(false)
            .setBinding(v -> RtSettings.selfShadow = v.storedValue(),
                () -> SelfShadow.fromStored(RtSettings.selfShadow))
            .setImpact(OptionImpact.LOW).setStorageHandler(st);
        rt.addOption(track("self_shadow", selfShadow));
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
        GROUP_IDS.put("main_upscaling", curGroupIds());
        addUpscalerBackend(builder, up, backendId, gameDir, st);
        addQualityModes(builder, up, backendId, gameDir, st);
        addDlssModel(builder, up, backendId, st);
        up.addOption(track("frame_generation_provider", FrameGenOptions.provider(builder, st)));
        up.addOption(track("frame_generation_mode", FrameGenOptions.mode(builder, st)));
        page.addOptionGroup(up);

        // ---- Presets, reset
        OptionGroupBuilder tools = group(builder, "bedray.group.main_tools", "Presets & Reset", new ArrayList<>());
        GROUP_IDS.put("main_tools", curGroupIds());
        tools.addOption(nativesManagerButton(builder));
        tools.addOption(presetsButton(builder));
        tools.addOption(resetListButton(builder, "reset_page_main", "Reset this page", PAGE_ESSENTIAL));
        page.addOptionGroup(tools);
        clearCurGroup();
    }
}
