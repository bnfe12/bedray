package io.github.andrexarlay.vulkancompat.rt.sodium;

import io.github.andrexarlay.vulkancompat.rtx.raytracing.RtActivation;

/**
 * Single switch of the world RT path: Sodium geometry capture (mixins H1-H4), BLAS/TLAS and rays. Off by default.
 * <p>
 * The value is decided ONCE per frame at the head of {@code GameRenderer.render()} by {@link RtCaptureControl#onFrameBegin}
 * ({@link RtActivation}: config on, device supports RT, no session failure, no temporal upscaler) and published in a volatile
 * field, because Sodium worker threads read it concurrently and must not touch render-thread state. When it is off, the
 * capture mixins return before any allocation, so a disabled RT costs nothing (before this, the gate read only the config
 * flag and kept decoding every chunk build after RT had been disabled for the session).
 * {@code -Dvkbridge.rt.geometry=true} forces capture on (tests / harness, independent of the UI option).
 */
public final class RtGeometryGate {
    private static final boolean FORCED = Boolean.getBoolean("vkbridge.rt.geometry");

    private static volatile boolean active = false;
    private static volatile RtActivation.Reason blockReason = RtActivation.Reason.CONFIG_OFF;
    private static volatile String failure;

    private RtGeometryGate() {
    }

    public static boolean enabled() {
        return FORCED || active;
    }

    public static boolean forced() {
        return FORCED;
    }

    /** Why RT does not run right now (null while it runs). */
    public static RtActivation.Reason blockReason() {
        return blockReason;
    }

    /** {@code stage=.. result=..} of the failure that disabled RT for this session, null when none. */
    public static String failure() {
        return failure;
    }

    /** Called by the RT frame renderer when it gives up; the capture stops at the next frame head. */
    public static void markFailed(String stage, String result) {
        failure = "stage=" + stage + " result=" + result;
    }

    /** An explicit user action (RT option switched off) clears a session failure, so switching it on retries. */
    public static void clearFailure() {
        failure = null;
    }

    /** Test hook (no frame loop in unit tests). */
    public static void setForTests(boolean on) {
        publish(on, on ? null : RtActivation.Reason.CONFIG_OFF);
    }

    static void publish(boolean on, RtActivation.Reason reason) {
        blockReason = reason;
        active = on;
    }
}
