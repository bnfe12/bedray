package io.github.andrexarlay.vulkancompat.rtx.debug;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;

/**
 * Real, generated and shown frame rates for the F3 screen, measured from the two counters of the frame generation runtime
 * ({@link FrameGenRuntime#renderedFrames()} = frames the game rendered while generation was on, {@link FrameGenRuntime#presentedFrames()}
 * = frames the swapchain actually presented, from {@code slDLSSGGetState}) over our own one second window. No multiplier is used
 * to estimate anything: real = rendered delta / window, shown = presented delta / window, generated = shown - real. The multiplier
 * text in brackets is the applied setting of the runtime, not a computed value.
 * <p>
 * Minecraft's own number ({@code Minecraft.getFps()}) counts rendered frames only, which is why the stock F3 line never shows the
 * generated ones. Render thread only; {@link #update} is called only while an F3 entry is on screen, so nothing runs with F3 hidden.
 */
public final class F3Fps {
    static final long WINDOW_NS = 1_000_000_000L;
    /** A window older than this (F3 was hidden meanwhile) is dropped instead of averaged into a stale rate. */
    private static final long STALE_NS = 3 * WINDOW_NS;

    private static long windowStartNs;
    private static long rendered0;
    private static long presented0;

    private static boolean generating;
    private static double realFps;
    private static double shownFps;
    private static int multiplier = 1;
    private static boolean stateActive;

    private static String lastVanilla;
    private static long lastReal = -1;
    private static long lastShown = -1;
    private static int lastMultiplier;
    private static boolean lastIdleWarn;
    private static String lastComposed;

    private F3Fps() {}

    /** Advances the measuring window; cheap (two counter reads and a compare) except once per second. */
    static void update(long nowNs) {
        long r = FrameGenRuntime.renderedFrames();
        long p = FrameGenRuntime.presentedFrames();
        if (windowStartNs == 0L || nowNs - windowStartNs > STALE_NS || r < rendered0 || p < presented0) {
            windowStartNs = nowNs;
            rendered0 = r;
            presented0 = p;
            generating = false;
            return;
        }
        long dt = nowNs - windowStartNs;
        if (dt < WINDOW_NS) {
            return;
        }
        long dr = r - rendered0;
        long dp = p - presented0;
        double sec = dt / 1.0e9;
        realFps = dr / sec;
        shownFps = dp / sec;
        generating = dr > 0 && dp > dr;
        FrameGenRuntime.Snapshot s = FrameGenRuntime.snapshot();
        multiplier = Math.max(1, s.multiplier());
        stateActive = s.status().state() == FrameGenRuntime.State.ACTIVE;
        windowStartNs = nowNs;
        rendered0 = r;
        presented0 = p;
    }

    /** True when the last full window presented more frames than the game rendered. */
    static boolean generating() {
        return generating;
    }

    static double realFps() {
        return realFps;
    }

    static double shownFps() {
        return shownFps;
    }

    static int multiplier() {
        return multiplier;
    }

    /** Runtime says ACTIVE but the counters of the last window show no generated frame. */
    static boolean activeButIdle() {
        return stateActive && !generating;
    }

    /**
     * Main F3 FPS line: Minecraft's text with the leading "N fps" replaced by real + generated = shown while the counters show
     * generation, otherwise unchanged. The composed string is cached until one of its inputs changes.
     */
    public static String decorate(String vanillaLine) {
        update(System.nanoTime());
        boolean warn = activeButIdle();
        if (!generating && !warn) {
            return vanillaLine;
        }
        long real = Math.round(realFps);
        long shown = Math.round(shownFps);
        if (lastComposed != null && real == lastReal && shown == lastShown && multiplier == lastMultiplier && warn == lastIdleWarn
            && vanillaLine.equals(lastVanilla)) {
            return lastComposed;
        }
        lastVanilla = vanillaLine;
        lastReal = real;
        lastShown = shown;
        lastMultiplier = multiplier;
        lastIdleWarn = warn;
        lastComposed = generating ? compose(vanillaLine, real, shown, multiplier)
            : vanillaLine + " " + F3Text.WARN + "FG: no generated frames" + F3Text.R;
        return lastComposed;
    }

    /**
     * {@code "<real> real + <gen> gen = <shown> fps (Nx) T: .."}: the digits and the word fps at the start of Minecraft's line
     * ({@code "%d fps T: inf (immediate) @165Hz"}) are replaced, the rest is kept. A line without the word fps gets the text in
     * front.
     */
    static String compose(String vanillaLine, long real, long shown, int mult) {
        long gen = Math.max(0L, shown - real);
        int fpsWord = vanillaLine.indexOf(" fps");
        String rest = fpsWord >= 0 ? vanillaLine.substring(fpsWord + 4) : " " + vanillaLine;
        StringBuilder b = new StringBuilder(96);
        b.append(F3Text.REAL).append(real).append(" real").append(F3Text.R)
            .append(F3Text.OFF).append(" + ").append(F3Text.R)
            .append(F3Text.GEN).append(gen).append(" gen").append(F3Text.R)
            .append(F3Text.OFF).append(" = ").append(F3Text.R)
            .append(F3Text.VAL).append(shown).append(" fps").append(F3Text.R)
            .append(F3Text.OFF).append(" (").append(mult).append("x)").append(F3Text.R)
            .append(rest);
        return b.toString();
    }
}
