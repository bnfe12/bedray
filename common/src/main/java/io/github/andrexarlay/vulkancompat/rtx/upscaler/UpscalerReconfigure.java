package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import java.util.Objects;

/**
 * Pure decision logic of the runtime upscaler switch, shared by {@code XessRuntime} and {@code DlssRuntime}
 * (they own the native calls; this class decides what to do at the head of a frame).
 * <p>
 * Phases: IDLE (no context), READY (context in use), RECONFIGURE_PENDING (the old context is out of use, it is destroyed
 * once the GPU work that may still reference it has finished), FAILED (this request failed, no retry until the request
 * changes: an explicit new choice of the user is not a hidden retry).
 * <p>
 * Safe destruction without {@code vkDeviceWaitIdle} in the frame path: the old context is retired and destroyed
 * {@link #RETIRE_DELAY_FRAMES} frame heads later. The same number is used by {@code RtWorld.DEFER_FRAMES} for section
 * buffers ("the engine keeps up to 2 submissions in flight", RtWorld class comment), so every command buffer that was
 * recorded with the old context has been submitted frames ago and its fence-bound work is complete by then.
 */
public final class UpscalerReconfigure {
    /** Frame heads to wait before the retired context is destroyed (see class comment). */
    public static final int RETIRE_DELAY_FRAMES = 3;

    public enum Phase { IDLE, READY, RECONFIGURE_PENDING, FAILED }

    public enum Step {
        /** Nothing to do. */
        NONE,
        /** READY context no longer matches the request: take it out of use (state -> RECONFIGURE_PENDING). */
        BEGIN_RETIRE,
        /** The retire delay elapsed: destroy the old context (state -> IDLE), then plan again. */
        DESTROY_RETIRED,
        /** No context and something is requested: create it. */
        CREATE,
        /** FAILED for an older request and the request changed: state -> IDLE, then plan again. */
        CLEAR_FAILED
    }

    private UpscalerReconfigure() {}

    /**
     * @param desired   mode requested for this technology, null when it is not the selected backend
     * @param active    mode of the READY context (null otherwise)
     * @param attempted mode of the context that was created last (the one a FAILED state belongs to)
     * @param frame     index of the current frame head
     * @param retireAt  frame head at which the retired context may be destroyed
     */
    public static <M> Step plan(Phase phase, M desired, M active, M attempted, long frame, long retireAt) {
        return switch (phase) {
            case RECONFIGURE_PENDING -> frame >= retireAt ? Step.DESTROY_RETIRED : Step.NONE;
            case READY -> Objects.equals(desired, active) ? Step.NONE : Step.BEGIN_RETIRE;
            case FAILED -> Objects.equals(desired, attempted) ? Step.NONE : Step.CLEAR_FAILED;
            case IDLE -> desired != null ? Step.CREATE : Step.NONE;
        };
    }

    /**
     * What a runtime context was created for: the vendor mode plus the custom render percent (0 = the mode's own size). Used as
     * the {@code M} of {@link #plan} so that a changed custom percent re-creates the context like a changed mode.
     */
    public record Target<M>(M mode, int customPercent) {
        @Override
        public String toString() {
            return customPercent > 0 ? "CUSTOM(" + customPercent + "%,base=" + mode + ")" : String.valueOf(mode);
        }
    }

    /** {@code null} when {@code mode} is null (technology not selected / no context), else the target. */
    public static <M> Target<M> target(M mode, int customPercent) {
        return mode == null ? null : new Target<>(mode, customPercent);
    }

    /**
     * Custom render size: {@code round(output * percent / 100)} per axis, clamped into [min, max] of the library's dynamic
     * range when one is given (min/max may be null).
     */
    public static int[] customSize(int outW, int outH, int percent, int[] min, int[] max) {
        int w = Math.max(1, (int) Math.round(outW * percent / 100.0));
        int h = Math.max(1, (int) Math.round(outH * percent / 100.0));
        if (min != null) {
            w = Math.max(w, min[0]);
            h = Math.max(h, min[1]);
        }
        if (max != null) {
            w = Math.min(w, max[0]);
            h = Math.min(h, max[1]);
        }
        return new int[] {Math.min(w, outW), Math.min(h, outH)};
    }

    /** {@code from} / {@code to} text of the {@code upscaler_reconfigure} log line for one technology. */
    public static String describe(String tech, Object mode) {
        return mode == null ? "none" : tech + "/" + mode;
    }
}
