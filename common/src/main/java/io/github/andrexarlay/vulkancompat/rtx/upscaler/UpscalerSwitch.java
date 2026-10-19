package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a user choice of upscaler backend and mode to the running game, or says honestly that it needs a restart.
 * The only entry used by the settings page and by autotest scenarios (through {@code VulkanRtxConfig.applyUpscaler}).
 * <p>
 * Live when BILINEAR, or when the device requirements of the backend were registered at startup
 * ({@link UpscalerPreregistration#requiresRestart}); otherwise the choice is only stored (persisted by the config) and takes
 * effect after a restart, because the Vulkan device was created without the extensions the backend needs.
 * No Minecraft classes (unit-tested).
 */
public final class UpscalerSwitch {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/UpscalerSwitch");

    public enum Outcome {
        /** The wanted upscaler is already the requested one. */
        UNCHANGED,
        /** The request changed; the runtimes reconfigure at the head of the next frame. */
        APPLIED_LIVE,
        /** The device lacks the requirements of the backend: stored only, applied after a restart. */
        DEFERRED_RESTART
    }

    private UpscalerSwitch() {}

    public static Outcome apply(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode, String reason) {
        return apply(backend, xessMode, dlssMode, null, null, reason);
    }

    public static Outcome apply(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode,
                                UpscalerMode fsrMode, FsrVersion fsrVersion, String reason) {
        return apply(backend, xessMode, dlssMode, fsrMode, fsrVersion, null, 0, reason);
    }

    /** Full form: plus the FSR1/NIS mode and the custom percent of the selected backend (UpscalerRequest.build). */
    public static Outcome apply(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode,
                                UpscalerMode fsrMode, FsrVersion fsrVersion, UpscalerMode spatialMode, int customPercent,
                                String reason) {
        if (UpscalerPreregistration.requiresRestart(backend)) {
            LOGGER.info("[VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason={} phase=deferred_restart (device requirements of {} were not registered at startup)",
                UpscalerRequest.current(), backend, reason, backend);
            return Outcome.DEFERRED_RESTART;
        }
        return UpscalerRequest.set(backend, xessMode, dlssMode, fsrMode, fsrVersion, spatialMode, customPercent, reason)
            ? Outcome.APPLIED_LIVE : Outcome.UNCHANGED;
    }
}
