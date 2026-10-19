package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;

import static io.github.andrexarlay.vulkancompat.rtx.raytracing.RtActivation.Reason;

/** Java-level checks of the RT block decision (not GPU, not in-game evidence). */
public final class RtActivationChecks {
    private RtActivationChecks() {}

    public static void runAll() {
        UpscalerBackend bil = UpscalerBackend.BILINEAR;
        check(null, RtActivation.blockReason(true, true, true, false, bil, false, false), "all good -> runs");
        check(Reason.BACKEND_NOT_VULKAN, RtActivation.blockReason(false, true, true, false, bil, false, false), "GL");
        check(Reason.CONFIG_OFF, RtActivation.blockReason(true, false, true, false, bil, false, false), "config off");
        check(Reason.DEVICE_WITHOUT_RT, RtActivation.blockReason(true, true, false, false, bil, false, false), "no RT device");
        check(Reason.FAILED, RtActivation.blockReason(true, true, true, true, bil, false, false), "session failure");
        // joint mode (TEMPORAL_EXCLUSIVE=false since feat/next): a temporal upscaler never blocks RT
        if (RtActivation.TEMPORAL_EXCLUSIVE) {
            throw new AssertionError("TEMPORAL_EXCLUSIVE must be false: RT runs together with XeSS/DLSS/FSR");
        }
        for (UpscalerBackend b : UpscalerBackend.values()) {
            check(null, RtActivation.blockReason(true, true, true, false, b, false, false), b + " requested -> RT runs");
            check(null, RtActivation.blockReason(true, true, true, false, b, false, true), b + " requested, temporal context active -> RT runs");
            if (RtActivation.conflictsWith(b)) {
                throw new AssertionError("conflictsWith(" + b + ") must be false in the joint mode");
            }
        }
        check(Reason.CONFIG_OFF, RtActivation.blockReason(true, false, true, true, UpscalerBackend.DLSS, false, true),
            "config off wins over everything else");
    }

    private static void check(Reason expected, Reason actual, String what) {
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + " got " + actual);
        }
    }
}
