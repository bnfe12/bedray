package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy;

import java.util.List;
import java.util.Locale;

/**
 * Honest supported / enabled / implemented status for the GI-stack execution and geometry technologies that have no
 * runtime toggle of their own (raygen, NV SER, opacity micromap, partitioned TLAS). Pure: it reasons from the enabled
 * feature mask and the startup {@code -D} requests, never from physical support, so it is unit-tested without a game
 * ({@code GiExecutionStatusTest}). The settings screen shows it as a read-only row ({@code gi_execution_status}); nothing
 * here enables a feature — device features are negotiated only at startup before {@code vkCreateDevice}.
 * <p>
 * "supported" = the logical device was created with the feature; "enabled" = the player actually requested the path this
 * session; "implemented" = Bedray has working code for it (a supported feature with no code is reported as not implemented,
 * never as a toggle that does nothing). See docs/ai-agent-addenda/13/14/15 for the deferral evidence.
 */
public final class GiExecutionStatus {

    /** One technology's honest state. {@code note} explains a downgrade or deferral in one line. */
    public record Tech(String key, String name, boolean supported, boolean enabled, boolean implemented, String note) {
    }

    private GiExecutionStatus() {
    }

    /** The four GI execution/geometry technologies, in the auto-policy's monotone order. */
    public static List<Tech> technologies(int capabilityMask, boolean raygenRequested, boolean serRequested) {
        boolean pipeline = (capabilityMask & BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE) != 0;
        boolean reorder = (capabilityMask & BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV) != 0;
        boolean micromap = (capabilityMask & BedrayCoreLayouts.CAP_OPACITY_MICROMAP) != 0;
        boolean serSupported = GiStackPolicy.serAvailable(capabilityMask);
        return List.of(
            new Tech("raygen", "Raygen PT pipeline", pipeline, raygenRequested && pipeline, true,
                pipeline ? "Measured -7.8% gpu_rt vs compute RayQuery (RD16, PT on)."
                         : "Needs the ray tracing pipeline feature; compute RayQuery is the fallback."),
            new Tech("ser", "NV Shader Execution Reordering", serSupported, serRequested && serSupported, true,
                serSupported ? "Measured 2.14x slower gpu_rt (quiet steady-state A/B); off by default. Opt-in: -Dbedray.rt.ser=true."
                             : "Needs the NV ray tracing invocation reorder feature (and the raygen pipeline)."),
            new Tech("omm", "Opacity micromap (2-state)", micromap, false, false,
                "Deferred: no measured terrain win; software candidate-alpha handles cutout. Not implemented."),
            new Tech("ptlas", "Partitioned TLAS (NV)", false, false, false,
                "Regional in-place TLAS update is active (idle-skip + refit). Partitioned TLAS deferred; not implemented."));
    }

    /** Multi-line read-only status for the settings tooltip; one line per technology. */
    public static String describe(int capabilityMask, boolean raygenRequested, boolean serRequested) {
        StringBuilder sb = new StringBuilder();
        for (Tech t : technologies(capabilityMask, raygenRequested, serRequested)) {
            sb.append(t.name()).append(": ")
              .append(state(t.supported(), t.enabled(), t.implemented()))
              .append(" — ").append(t.note()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** The honest three-state label: implemented+enabled beats implemented+supported beats supported-only beats absent. */
    static String state(boolean supported, boolean enabled, boolean implemented) {
        if (!implemented) {
            return supported ? "supported, not implemented" : "not implemented";
        }
        if (enabled) {
            return "enabled";
        }
        return supported ? "supported, off" : "unsupported";
    }

    /** Live status from the device probe; an honest placeholder before a world is open. */
    public static String live() {
        GiStackPolicy.Device device = GiDeviceProbe.probe();
        if (device == null) {
            return "Open a world to read the device feature mask.";
        }
        boolean raygen = Boolean.getBoolean(RtRayPipelineDeviceRequirements.RAYGEN_PROPERTY);
        boolean ser = Boolean.getBoolean(RtRayPipelineDeviceRequirements.SER_PROPERTY);
        return describe(device.capabilityMask(), raygen, ser);
    }

    /** Compact one-line summary for logs and the autotest read-back. */
    public static String summary(int capabilityMask, boolean raygenRequested, boolean serRequested) {
        StringBuilder sb = new StringBuilder();
        for (Tech t : technologies(capabilityMask, raygenRequested, serRequested)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "%s=%s", t.key(), state(t.supported(), t.enabled(), t.implemented())));
        }
        return sb.toString();
    }
}