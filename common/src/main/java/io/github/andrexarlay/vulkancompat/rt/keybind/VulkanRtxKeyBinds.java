package io.github.andrexarlay.vulkancompat.rt.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.debug.VkbDebugEntry;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-game hotkeys for real-time ray tracing control, registered in Minecraft's key mapping system
 * (accessible in Controls -> Key Binds) and supported on both Fabric and NeoForge.
 */
public final class VulkanRtxKeyBinds {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/KeyBinds");

    public static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bedray", "main"));

    public static final KeyMapping KEY_TOGGLE_RT =
        new KeyMapping("key.bedray.toggle_rt", InputConstants.KEY_O, CATEGORY);

    public static final KeyMapping KEY_CYCLE_MODE =
        new KeyMapping("key.bedray.cycle_mode", InputConstants.KEY_I, CATEGORY);

    public static final KeyMapping KEY_CYCLE_DENOISER =
        new KeyMapping("key.bedray.cycle_denoiser", InputConstants.KEY_U, CATEGORY);

    public static final KeyMapping KEY_TOGGLE_VOXEL_SHARPNESS =
        new KeyMapping("key.bedray.toggle_voxel_sharpness", InputConstants.KEY_Y, CATEGORY);

    public static final KeyMapping KEY_TOGGLE_DEBUG_OVERLAY =
        new KeyMapping("key.bedray.toggle_debug_overlay", InputConstants.KEY_K, CATEGORY);

    public static final KeyMapping[] ALL_KEYS = new KeyMapping[] {
        KEY_TOGGLE_RT,
        KEY_CYCLE_MODE,
        KEY_CYCLE_DENOISER,
        KEY_TOGGLE_VOXEL_SHARPNESS,
        KEY_TOGGLE_DEBUG_OVERLAY
    };

    private static RtMode lastActiveRtMode = RtMode.PATH_TRACED;

    private VulkanRtxKeyBinds() {}

    /**
     * Appends our key mappings to Minecraft's {@link net.minecraft.client.Options#keyMappings} array.
     */
    public static KeyMapping[] registerKeys(KeyMapping[] existing) {
        if (existing == null) {
            return ALL_KEYS.clone();
        }
        for (KeyMapping key : ALL_KEYS) {
            if (!ArrayUtils.contains(existing, key)) {
                existing = ArrayUtils.add(existing, key);
            }
        }
        return existing;
    }

    /**
     * Polled on every client tick when in game and no GUI screen is open.
     */
    public static void handleKeybinds() {
        while (KEY_TOGGLE_RT.consumeClick()) {
            toggleRayTracing();
        }
        while (KEY_CYCLE_MODE.consumeClick()) {
            cycleRtMode();
        }
        while (KEY_CYCLE_DENOISER.consumeClick()) {
            cycleDenoiser();
        }
        while (KEY_TOGGLE_VOXEL_SHARPNESS.consumeClick()) {
            toggleVoxelSharpness();
        }
        while (KEY_TOGGLE_DEBUG_OVERLAY.consumeClick()) {
            toggleDebugOverlay();
        }
    }

    public static void toggleRayTracing() {
        boolean active = VulkanRtxConfig.effectiveRtMode().isActive();
        if (active) {
            lastActiveRtMode = VulkanRtxConfig.effectiveRtMode();
            VulkanSodiumConfig.applyRtModeFromUi(RtMode.OFF);
            VulkanRtxConfig.save();
            showOverlayMessage(Component.translatableWithFallback(
                "bedray.msg.rt_off", "[Bedray] Ray Tracing: OFF"));
            LOGGER.info("[VKB-RT] hotkey toggle_rt disabled");
        } else {
            RtMode mode = (lastActiveRtMode != null && lastActiveRtMode.isActive())
                ? lastActiveRtMode
                : RtMode.PATH_TRACED;
            VulkanSodiumConfig.applyRtModeFromUi(mode);
            VulkanRtxConfig.save();
            showOverlayMessage(Component.translatableWithFallback(
                "bedray.msg.rt_on", "[Bedray] Ray Tracing: ON (" + modeDisplayName(mode) + ")",
                modeDisplayName(mode)));
            LOGGER.info("[VKB-RT] hotkey toggle_rt enabled mode={}", mode.logName());
        }
    }

    public static void cycleRtMode() {
        RtMode cur = VulkanRtxConfig.effectiveRtMode();
        if (!VulkanRtxConfig.raysUnlocked()) {
            // public release: Off <-> Path Tracing only (Ray Tracing (Fast) is hidden, see experimental_rays)
            applyRtMode(cur == RtMode.PATH_TRACED ? RtMode.OFF : RtMode.PATH_TRACED);
            return;
        }
        int debug = RtConfig.effectiveMode();
        RtMode next;
        if (debug == RtConfig.MODE_AO || cur == RtMode.AO) {
            next = RtMode.DIRECT;
        } else if (debug == RtConfig.MODE_SHADOW || cur == RtMode.DIRECT) {
            next = RtMode.PATH_TRACED;
        } else if (cur == RtMode.PATH_TRACED) {
            next = RtMode.RAY_TRACED;
        } else {
            next = RtMode.AO;
        }
        applyRtMode(next);
    }

    public static void applyRtMode(RtMode mode) {
        lastActiveRtMode = mode;
        switch (mode) {
            case PATH_TRACED -> {
                RtConfig.setDebugView("");
                VulkanSodiumConfig.applyRtModeFromUi(RtMode.PATH_TRACED);
            }
            case RAY_TRACED -> {
                RtConfig.setDebugView("");
                VulkanSodiumConfig.applyRtModeFromUi(RtMode.RAY_TRACED);
            }
            case AO -> {
                RtConfig.setDebugView("ao");
                VulkanSodiumConfig.applyRtModeFromUi(RtMode.AO);
            }
            case DIRECT -> {
                RtConfig.setDebugView("shadow");
                VulkanSodiumConfig.applyRtModeFromUi(RtMode.DIRECT);
            }
            case OFF -> {
                RtConfig.setDebugView("");
                VulkanSodiumConfig.applyRtModeFromUi(RtMode.OFF);
            }
        }
        VulkanRtxConfig.save();
        showOverlayMessage(Component.translatableWithFallback(
            "bedray.msg.mode", "[Bedray] RT Mode: " + modeDisplayName(mode),
            modeDisplayName(mode)));
        LOGGER.info("[VKB-RT] hotkey cycle_mode mode={}", mode.logName());
    }

    public static void cycleDenoiser() {
        RtSettings.PathDenoiser cur = RtSettings.pathDenoiser;
        RtSettings.PathDenoiser next = switch (cur) {
            case DLSS_RR -> RtSettings.PathDenoiser.NRD;
            case NRD -> RtSettings.PathDenoiser.OFF;
            case OFF -> RtSettings.PathDenoiser.DLSS_RR;
        };
        RtSettings.pathDenoiser = next;
        VulkanRtxConfig.save();
        String name = switch (next) {
            case DLSS_RR -> "DLSS-RR (Ray Reconstruction)";
            case NRD -> "NRD (ReBLUR)";
            case OFF -> "OFF";
        };
        showOverlayMessage(Component.translatableWithFallback(
            "bedray.msg.denoiser", "[Bedray] Denoiser: " + name, name));
        LOGGER.info("[VKB-RT] hotkey cycle_denoiser denoiser={}", next);
    }

    public static void toggleVoxelSharpness() {
        RtSettings.dlssRrGeometricNormals = !RtSettings.dlssRrGeometricNormals;
        VulkanRtxConfig.save();
        String state = RtSettings.dlssRrGeometricNormals ? "ON (Geometric Normals)" : "OFF (Smooth Normals)";
        showOverlayMessage(Component.translatableWithFallback(
            "bedray.msg.voxel_sharpness", "[Bedray] Voxel Sharpness: " + state, state));
        LOGGER.info("[VKB-RT] hotkey toggle_voxel_sharpness geometricNormals={}", RtSettings.dlssRrGeometricNormals);
    }

    public static void toggleDebugOverlay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.debugEntries != null) {
            boolean enabled = mc.debugEntries.toggleStatus(VkbDebugEntry.ID);
            if (enabled && !mc.debugEntries.isOverlayVisible()) {
                mc.debugEntries.setOverlayVisible(true);
            }
            String state = enabled ? "ON" : "OFF";
            showOverlayMessage(Component.translatableWithFallback(
                "bedray.msg.debug_overlay", "[Bedray] RT Debug Overlay: " + state, state));
            LOGGER.info("[VKB-RT] hotkey toggle_debug_overlay enabled={}", enabled);
        }
    }

    public static String modeDisplayName(RtMode mode) {
        return switch (mode) {
            case PATH_TRACED -> "Path Tracing";
            case RAY_TRACED -> "Hybrid (Ray Tracing)";
            case AO -> "Ambient Occlusion (AO)";
            case DIRECT -> "Direct Light & Shadows";
            case OFF -> "Off";
        };
    }

    public static void showOverlayMessage(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.setOverlayMessage(message, false);
        }
    }
}
