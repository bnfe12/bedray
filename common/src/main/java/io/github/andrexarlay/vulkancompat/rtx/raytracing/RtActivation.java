package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;

/**
 * Pure decision whether the world ray tracing path (Sodium geometry capture, BLAS/TLAS, rays) may run this frame, and if
 * not, why. No Minecraft classes (unit-tested in RtActivationChecks). The order of the checks is the order of the reasons a
 * user has to fix.
 *
 * <p>RT runs together with the temporal upscalers (XeSS/DLSS/FSR): {@link #TEMPORAL_EXCLUSIVE} is false since feat/next
 * (joint mode of feat/rt-modes e4c9b27). With the flag set to true the old block comes back (a block, not a session
 * failure: selecting the Bilinear upscaler lifts it at the next frame head).
 */
public final class RtActivation {
    public enum Reason {
        BACKEND_NOT_VULKAN("backend is not Vulkan"),
        CONFIG_OFF("off in settings"),
        DEVICE_WITHOUT_RT("device has no VK_KHR_ray_query / acceleration_structure"),
        FAILED("failed this session (toggle RT off and on to retry)"),
        INCOMPATIBLE_TEMPORAL_UPSCALER("incompatible with DLSS/XeSS for now - select the Bilinear upscaler");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * False since feat/next: feat/rt-modes (e4c9b27 "RT together with DLSS/XeSS") traces the RT image at the render resolution
     * with the raster jitter, and the temporal upscaler reconstructs it. The INCOMPATIBLE_TEMPORAL_UPSCALER reason stays for a
     * build that has to turn the joint mode off again; the failure/config/device reasons and the capture gating are unaffected.
     */
    public static final boolean TEMPORAL_EXCLUSIVE = false;

    private RtActivation() {}

    /**
     * @param requested          upscaler the user selected (request of the runtimes)
     * @param requestedFailed    the selected temporal backend is in state FAILED (so it does not run: no conflict)
     * @param temporalActive     a temporal context still owns the world target (e.g. while it is being retired)
     * @return null when RT may run, otherwise the first blocking reason
     */
    public static Reason blockReason(boolean vulkan, boolean configOn, boolean deviceSupportsRt, boolean failed,
                                     UpscalerBackend requested, boolean requestedFailed, boolean temporalActive) {
        if (!vulkan) {
            return Reason.BACKEND_NOT_VULKAN;
        }
        if (!configOn) {
            return Reason.CONFIG_OFF;
        }
        if (!deviceSupportsRt) {
            return Reason.DEVICE_WITHOUT_RT;
        }
        if (failed) {
            return Reason.FAILED;
        }
        boolean temporalRequested = requested == UpscalerBackend.XESS || requested == UpscalerBackend.DLSS;
        if (TEMPORAL_EXCLUSIVE && ((temporalRequested && !requestedFailed) || temporalActive)) {
            return Reason.INCOMPATIBLE_TEMPORAL_UPSCALER;
        }
        return null;
    }

    /** True for the backends RT cannot run with (used by the settings page). */
    public static boolean conflictsWith(UpscalerBackend backend) {
        return TEMPORAL_EXCLUSIVE && (backend == UpscalerBackend.XESS || backend == UpscalerBackend.DLSS);
    }
}
