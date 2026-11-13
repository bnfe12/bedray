package io.github.andrexarlay.vulkancompat.rt.sodium;

import io.github.andrexarlay.vulkancompat.rtx.raytracing.RtActivation;

/**
 * Switches of the world geometry path. Two distinct consumers read this gate:
 * <ul>
 *   <li>{@link #enabled()} — native RT rays, BLAS/TLAS, particles and the isolated world target. RT-only: config on, device
 *   supports RT, no session failure, no temporal upscaler ({@link RtActivation}). Unchanged by the Iris host, so the native
 *   path stays byte-identical.</li>
 *   <li>{@link #captureEnabled()} — Sodium geometry capture (mixins H1-H4) and entity capture. True when native RT runs OR the
 *   Iris raster host needs geometry ({@link #irisActive()}). The Iris forward executor draws gbuffers/shadow from the same
 *   decoded sections, so capture must run for it even with RT off — but native rays must NOT (never mix native + iris in one
 *   frame; {@code RtFrameRenderer.render} returns early while {@link #irisActive()}).</li>
 * </ul>
 * Both values are decided ONCE per frame at the head of {@code GameRenderer.render()} by {@link RtCaptureControl#onFrameBegin}
 * and published in volatile fields, because Sodium worker threads read them concurrently and must not touch render-thread
 * state. When capture is off, the capture mixins return before any allocation, so a disabled path costs nothing.
 * {@code -Dvkbridge.rt.geometry=true} forces capture on (tests / harness, independent of the UI option).
 */
public final class RtGeometryGate {
    private static final boolean FORCED = Boolean.getBoolean("vkbridge.rt.geometry");

    private static volatile boolean active = false;
    private static volatile boolean irisActive = false;
    private static volatile RtActivation.Reason blockReason = RtActivation.Reason.CONFIG_OFF;
    private static volatile String failure;

    private RtGeometryGate() {
    }

    /** Native RT rays / BLAS / particles / isolated world target. RT-only, never set by the Iris host. */
    public static boolean enabled() {
        return FORCED || active;
    }

    /** Sodium + entity geometry capture: native RT OR the Iris raster host. The capture mixins read this, not {@link #enabled()}. */
    public static boolean captureEnabled() {
        return FORCED || active || irisActive;
    }

    /** True while the Iris raster host owns the frame's geometry (native rays are suppressed in that case). */
    public static boolean irisActive() {
        return irisActive;
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

    /** Test hook for the Iris capture flag (no frame loop in unit tests). */
    public static void setIrisForTests(boolean on) {
        publishIris(on);
    }

    static void publish(boolean on, RtActivation.Reason reason) {
        blockReason = reason;
        active = on;
    }

    /** Published by {@link RtCaptureControl#onFrameBegin}: the Iris raster host needs geometry capture this frame. */
    static void publishIris(boolean on) {
        irisActive = on;
    }
}
