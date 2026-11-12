package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Presentation text for the upscaler rows of the Sodium page: backend and mode labels, their tooltips,
 * and the render-scale ratios they show. These are pure functions of the backend/mode/status arguments
 * and the configured custom percent; they hold no page state, so the option builders in
 * {@link VulkanSodiumConfig} call them while building rows.
 */
final class UpscalerOptionTexts {
    private UpscalerOptionTexts() {
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

    static Component backendTooltip(UpscalerBackend backend, UpscalerSelection.Status status) {
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