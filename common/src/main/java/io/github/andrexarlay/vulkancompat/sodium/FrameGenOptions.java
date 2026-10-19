package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.Availability;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * "Frame Generation" block of the Vulkan settings page (NVIDIA DLSS Frame Generation through Streamline). Everything shown is
 * backed by {@link FrameGenRuntime} / {@link FrameGenSettings}: values that the adapter cannot do are not offered (3x/4x need
 * {@code numFramesToGenerateMax}, reported by the driver through {@code slDLSSGGetState}), and the status line is built from the
 * live counters. Wired into the page by one call in {@code VulkanSodiumConfig}: {@code page.addOptionGroup(FrameGenOptions.build(...))}.
 * <ul>
 * <li>Mode: Off / 2x / 3x / 4x, applied on the next frame (no restart) when Streamline is loaded; otherwise the choice is stored and
 *     the tooltip says that Streamline is loaded on the next start;</li>
 * <li>Reflex: Off / On / Boost (applied live);</li>
 * <li>Frame cap: presented frames per second through the Reflex limiter, 0 = off;</li>
 * <li>Status: read-only line, Active/Inactive/Unsupported with the real reason and the measured fps.</li>
 * </ul>
 */
public final class FrameGenOptions {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGenOptions");

    private FrameGenOptions() {}

    /** Choices of the mode control (Mode itself has no 1x entry besides OFF). */
    public enum UiMode { OFF, X2, X3, X4 }

    public enum UiStatus { STATUS }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("bedray", path);
    }

    private static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    private static boolean live() {
        return FrameGenRuntime.availability() == Availability.READY;
    }

    public static OptionGroupBuilder build(ConfigBuilder builder, StorageEventHandler storage) {
        OptionGroupBuilder group = builder.createOptionGroup().setName(tr("bedray.group.frame_generation", "Frame Generation"));
        group.addOption(mode(builder, storage));
        for (var o : details(builder, storage)) {
            group.addOption(o);
        }
        return group;
    }

    /** The Frame Generation mode control (first page of the settings). */
    public static net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder mode(ConfigBuilder builder, StorageEventHandler storage) {
        Identifier modeId = id("frame_generation_mode");
        EnumOptionBuilder<UiMode> mode = builder.createEnumOption(modeId, UiMode.class);
        mode.setName(tr("bedray.option.frame_generation", "Frame Generation"))
            .setTooltip(val -> modeTooltip())
            .setDefaultValue(UiMode.OFF)
            .setAllowedValuesProvider(state -> allowedModes())
            .setElementNameProvider(val -> switch (val) {
                case OFF -> tr("bedray.fg.off", "Off");
                case X2 -> tr("bedray.fg.x2", "2x");
                case X3 -> tr("bedray.fg.x3", "3x (Multi Frame Generation)");
                case X4 -> tr("bedray.fg.x4", "4x (Multi Frame Generation)");
            })
            .setEnabledProvider(state -> FrameGenRuntime.availability() == Availability.READY
                || FrameGenRuntime.availability() == Availability.NEEDS_RESTART)
            .setControlHiddenWhenDisabled(false)
            .setBinding(val -> {
                FrameGenSettings.Mode m = FrameGenSettings.Mode.valueOf(val.name());
                FrameGenSettings.setMode(m);
                io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.save();
                LOGGER.info("[VKB-AUTOTEST] ui_frame_generation mode={} availability={}", m, FrameGenRuntime.availability());
            }, () -> UiMode.valueOf(FrameGenSettings.mode().name()))
            .setImpact(OptionImpact.HIGH)
            .setStorageHandler(storage);
        return mode;
    }

    /** The Frame Generation provider (Auto / NVIDIA DLSS-G / AMD FSR 3) on the main page next to Frame Generation mode. */
    public static net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder provider(ConfigBuilder builder, StorageEventHandler storage) {
        EnumOptionBuilder<FrameGenSettings.Provider> provider = builder.createEnumOption(id("frame_generation_provider"), FrameGenSettings.Provider.class);
        provider.setName(tr("bedray.option.fg_provider", "Frame Generation Method"))
            .setTooltip(tr("bedray.option.fg_provider.tooltip",
                "Auto uses NVIDIA DLSS Frame Generation (Streamline) when the NVIDIA driver and the Streamline files are present, otherwise AMD FSR 3 Frame Generation "
                    + "(needs amd_fidelityfx_vk.dll, any GPU, 2x only). Both replace the swapchain, so changing the method takes effect after a restart."))
            .setDefaultValue(FrameGenSettings.Provider.AUTO)
            .setElementNameProvider(val -> Component.literal(val.label()))
            .setBinding(val -> {
                FrameGenSettings.setProvider(val);
                io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.save();
                LOGGER.info("[VKB-AUTOTEST] ui_frame_generation provider={} active={}", val, io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenProvider.active());
            }, FrameGenSettings::provider)
            .setImpact(OptionImpact.HIGH)
            .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
            .setStorageHandler(storage);
        return provider;
    }

    /** Reflex, frame cap and the read-only status line (advanced page). */
    public static java.util.List<net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder> details(ConfigBuilder builder, StorageEventHandler storage) {
        EnumOptionBuilder<FrameGenSettings.Reflex> reflex = builder.createEnumOption(id("frame_generation_reflex"), FrameGenSettings.Reflex.class);
        reflex.setName(tr("bedray.option.reflex", "NVIDIA Reflex"))
            .setTooltip(tr("bedray.option.reflex.tooltip",
                "Reflex low latency (Streamline sl.reflex). Frame generation always sends the Reflex markers it needs; this switch chooses the sleep mode: Off, On (low latency), Boost (also raises GPU clocks). Applied immediately."))
            .setDefaultValue(FrameGenSettings.Reflex.ON)
            .setElementNameProvider(val -> switch (val) {
                case OFF -> tr("bedray.reflex.off", "Off");
                case ON -> tr("bedray.reflex.on", "On");
                case BOOST -> tr("bedray.reflex.boost", "On + Boost");
            })
            .setEnabledProvider(state -> live())
            .setControlHiddenWhenDisabled(false)
            .setBinding(FrameGenSettings::setReflex, FrameGenSettings::reflex)
            .setImpact(OptionImpact.LOW)
            .setStorageHandler(storage);


        IntegerOptionBuilder limit = builder.createIntegerOption(id("frame_generation_limit"));
        limit.setName(tr("bedray.option.fg_limit", "Presented FPS Cap"))
            .setTooltip(tr("bedray.option.fg_limit.tooltip",
                "Caps the presented frame rate through the Reflex frame limiter (the rendered rate is the cap divided by the multiplier). 0 = no cap. Useful to keep frame pacing steady near the monitor refresh rate. Applied immediately."))
            .setDefaultValue(0)
            .setRange(0, 360, 10)
            .setValueFormatter(v -> v == 0 ? tr("bedray.fg.limit.off", "Off") : Component.literal(v + " fps"))
            .setEnabledProvider(state -> live())
            .setControlHiddenWhenDisabled(false)
            .setBinding(FrameGenSettings::setFrameLimitFps, FrameGenSettings::frameLimitFps)
            .setImpact(OptionImpact.LOW)
            .setStorageHandler(storage);


        EnumOptionBuilder<UiStatus> status = builder.createEnumOption(id("frame_generation_status"), UiStatus.class);
        status.setName(tr("bedray.option.fg_status", "Frame Generation Status"))
            .setTooltip(val -> Component.literal(FrameGenRuntime.statusLine()))
            .setDefaultValue(UiStatus.STATUS)
            .setAllowedValues(EnumSet.of(UiStatus.STATUS))
            .setElementNameProvider(val -> Component.literal(FrameGenRuntime.statusBadge()))
            .setEnabledProvider(state -> false)
            .setControlHiddenWhenDisabled(false)
            .setBinding(val -> { }, () -> UiStatus.STATUS)
            .setStorageHandler(storage);
        return java.util.List.of(reflex, limit, status);
    }

    /** Values the mode control offers right now (3x/4x only when NVIDIA DLSS-G is selected and the driver reports numFramesToGenerateMax >= 2/3). */
    static Set<UiMode> allowedModes() {
        Availability a = FrameGenRuntime.availability();
        EnumSet<UiMode> set = EnumSet.of(UiMode.OFF);
        boolean isAmd = FrameGenSettings.provider() == FrameGenSettings.Provider.AMD
            || io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenProvider.amd();
        if (a == Availability.READY) {
            set.add(UiMode.X2);
            int max = FrameGenRuntime.maxMultiplier();
            if (!isAmd && max >= 3) {
                set.add(UiMode.X3);
            }
            if (!isAmd && max >= 4) {
                set.add(UiMode.X4);
            }
        } else if (a == Availability.NEEDS_RESTART) {
            set.add(UiMode.X2);
            if (!isAmd) {
                set.add(UiMode.X3);
                set.add(UiMode.X4);
            }
        }
        // the stored value stays selectable so the control never shows a value that is not in the list
        set.add(UiMode.valueOf(FrameGenSettings.mode().name()));
        return set;
    }

    private static Component modeTooltip() {
        String base = "Frame Generation inserts generated frames between rendered ones (2x with AMD FSR 3 or NVIDIA DLSS-G; 3x/4x Multi Frame Generation on RTX 50). "
            + "Choose the method (Auto / NVIDIA DLSS-G / AMD FSR 3) in the row above. Needs a temporal upscaler (FSR, DLSS or XeSS); it switches off in menus, while loading and when the window loses focus. ";
        String extra = switch (FrameGenRuntime.availability()) {
            case READY -> "Applied immediately, no restart. " + (FrameGenRuntime.maxMultiplier() < 3 ? "3x/4x are not offered on this provider/GPU." : "");
            case NEEDS_RESTART -> "The frame generation swapchain is hooked when the game starts: your choice is saved and takes effect after a restart.";
            case NO_BINARIES -> "Unavailable: install amd_fidelityfx_vk.dll (FSR 3) or Streamline DLLs via the 'Upscaler & Frame Gen DLLs...' button below.";
            case FAILED -> "Failed: " + FrameGenRuntime.statusLine();
            case UNSUPPORTED -> FrameGenRuntime.statusLine();
        };
        return Component.literal(base + extra);
    }

    /** For restart flagging if the page wants it: true when a mode change cannot apply live. */
    static boolean restartForMode() {
        return FrameGenRuntime.availability() == Availability.NEEDS_RESTART;
    }

    @SuppressWarnings("unused")
    private static final OptionFlag[] NONE = {};
}
