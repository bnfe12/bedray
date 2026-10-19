package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugLineProvider;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;

import java.util.List;
import java.util.Locale;

/**
 * F3 lines of the RT modes (feat/rt-modes) for the block "Bedray", registered as the {@code rt_modes}
 * {@link DebugLineProvider} (built after the {@code rt} state lines). Shown only while the RT gate is on: selected mode vs
 * the pass that rendered the last frame, preset, RT distance, accumulation, denoiser, and whether the image goes through a
 * temporal upscaler. Called once per PerfMonitor window, not per frame.
 */
public final class RtModesDebug {
    private RtModesDebug() {}

    public static DebugLineProvider provider() {
        return DebugInfo.provider("rt_modes", RtModesDebug::appendLines);
    }

    static void appendLines(List<String> out) {
        if (!RtGeometryGate.enabled()) {
            return;
        }
        RtMode pass = RtFrameRenderer.lastPassMode();
        boolean reflectionTraced = pass == RtMode.PATH_TRACED || RtSettings.reflections();
        String waterStyle = RtSettings.waterStyle() == 1 ? "REALISTIC" : "VANILLA";
        String caustics = waterStyle.equals("REALISTIC") ? "REALISTIC_MODE_ONLY" : "OFF";
        out.add(String.format(Locale.ROOT,
            "[RT-WATER] style=%s reflection=%s transmission=THIN_INTERFACE_APPROX caustics=%s mask=0x%02X",
            waterStyle, reflectionTraced ? "RAY_TRACED" : "DISABLED", caustics, RtFrameRenderer.getReflectionMask()));
        out.add(RtFrameRenderer.getHermeticTelemetry());
        String localLightsState = RtFrameRenderer.hasActiveLocalLights() ? "ACTIVE" : "DISABLED";
        out.add(String.format(Locale.ROOT,
            "[RT-TRANSMISSION] model=THIN_INTERFACE_APPROX refraction=STRAIGHT_THROUGH volumeExit=UNSUPPORTED caustics=%s localLights=%s",
            caustics, localLightsState));

        // Temporal upscalers and Streamline use the camera-only velocity compute pass. DLSS-RR's
        // path-tracer G-buffer motion is a separate input and is only present when RR is active.
        out.add(String.format(Locale.ROOT,
            "[RT-MV] source=VELOCITY_REPROJECT convention=PREV_MINUS_CURR units=PIXELS depthEncoding=DEPTH_HARDWARE entityMotion=%s dlssRrGbuffer=%s",
            RtFrameRenderer.dlssRrRuntimeActive() && !Boolean.getBoolean("vkrtx.entityMotion.off") ? "RR_MOTION" : "NONE",
            RtFrameRenderer.dlssRrRuntimeActive() ? "VIEW_Z" : "NONE"));

        // Frame Generation telemetry must reflect the Streamline runtime actually hooked to present.
        // The legacy FrameGenerationPipeline is an unused scaffold and always reports unsupported.
        out.add("[FRAME-GEN] " + FrameGenRuntime.statusLine());

        // Dirt RT telemetry
        // Dirt RT / Iris host is an unfinished scaffold: its line appears only after a Dirt RT pack was selected
        String dirtRt = io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipeline.getF3Telemetry();
        if (!dirtRt.contains("selectedPack=none")) {
            out.add(dirtRt);
        }
    }

    private static String onOff(boolean v) {
        return v ? "on" : "off";
    }

    static String reflectionSettings() {
        return reflectionSettings(RtSettings.reflections(), RtSettings.reflectionQuality(), RtSettings.reflectionDistance());
    }

    static String reflectionSettings(boolean enabled, int quality, int distance) {
        return enabled ? String.format(Locale.ROOT, " q%d d%d", quality, distance) : "";
    }

    static String denoiserStatusLine(RtSettings.PathDenoiser requested, io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode selected,
                                    String runtime, String resolutionReason, String runtimeFailure) {
        StringBuilder line = new StringBuilder("requested=").append(requested.name().toLowerCase(Locale.ROOT))
            .append(" selected=").append(selected.logName()).append(" runtime=").append(runtime);
        if (resolutionReason != null && !resolutionReason.isBlank() && !"setting".equals(resolutionReason)) {
            line.append(" reason=").append(resolutionReason.replace('\n', ' ').replace('\r', ' '));
        }
        if (runtimeFailure != null && !runtimeFailure.isBlank()) {
            line.append(" last_failure=").append(runtimeFailure.replace('\n', ' ').replace('\r', ' '));
        }
        return line.toString();
    }
}
