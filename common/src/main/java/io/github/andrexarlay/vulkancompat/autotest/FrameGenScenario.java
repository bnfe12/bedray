package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.State;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings.Mode;

import java.util.Locale;

/**
 * {@code framegen}: DLSS Frame Generation end to end in the fixed test world (run with {@code -Dvkrtx.framegen=dlss},
 * the Streamline libname/dir properties and a temporal upscaler at native resolution, e.g. {@code -Dvkrtx.fsr=NATIVE_AA};
 * the window must stay focused, Streamline disables itself otherwise).
 * <ol>
 * <li>SETTLE: wait until {@link FrameGenRuntime#status()} is ACTIVE (doubled presents counted), at most {@link #SETTLE_FRAMES} frames;</li>
 * <li>SOAK: {@code -Dvkrtx.autotest.fgSeconds} (default 300) seconds, a report every 30 s with rendered/presented deltas; every
 * window with focus must have presented/rendered >= {@link #MIN_RATIO};</li>
 * <li>TOGGLE: {@code setUserEnabled(false/true)} three times (generation off: presented == rendered, on: doubled again);</li>
 * <li>RESIZE: window 1024x576 and back to 1280x720 (swapchain re-creation), generation must resume;</li>
 * <li>engine screenshot of the last frame and finish. Any failed check ends the run with result=FAIL and the reason.</li>
 * </ol>
 * Log lines: {@code [VKB-AUTOTEST] scenario=framegen phase=.. rendered=.. presented=.. ratio=..}.
 */
public final class FrameGenScenario implements AutotestScenario {
    public static final int SETTLE_FRAMES = 2400;
    public static final double MIN_RATIO = 1.9;
    private static final long REPORT_NS = 30_000_000_000L;

    private enum Phase { BASELINE, SETTLE, SOAK, MODES, TOGGLE_OFF, TOGGLE_ON, RESIZE_SMALL, RESIZE_BACK, SHOT, DONE }

    private Phase phase = Phase.SETTLE;
    private long phaseStartFrame;
    private long phaseStartNs;
    private long reportStartNs;
    private long reportRendered0;
    private long reportPresented0;
    private long phaseRendered0;
    private long phasePresented0;
    private int toggleCycle;
    private long soakNs;
    private int checksFailed;
    private boolean sawUnfocused;
    private static final boolean KEEP_FOCUS = Boolean.getBoolean("vkrtx.autotest.keepFocus");
    private int focusRequests;
    private int modeIndex;
    private long baselineFrames0;
    private int baselineWindows;
    private long modeBaseRendered;
    private long modeBasePresented;
    private static final Mode[] MODE_SWEEP = {Mode.X3, Mode.X4, Mode.X2, Mode.OFF, Mode.X2};

    @Override
    public String name() {
        return "framegen";
    }

    @Override
    public void run(AutotestContext ctx) {
        soakNs = Long.getLong("vkrtx.autotest.fgSeconds", 300L) * 1_000_000_000L;
        ctx.log("scenario", name(), "event", "start", "soak_seconds", String.valueOf(soakNs / 1_000_000_000L));
        if (FrameGenSettings.startupMode() == Mode.OFF) {
            // reference run without frame generation (-Dvkrtx.framegen=off): same camera motion, rendered fps per 10 s window
            baselineFrames0 = ctx.frames();
            enter(ctx, Phase.BASELINE);
            return;
        }
        enter(ctx, Phase.SETTLE);
    }

    private void enter(AutotestContext ctx, Phase p) {
        phase = p;
        phaseStartFrame = ctx.frames();
        phaseStartNs = System.nanoTime();
        phaseRendered0 = FrameGenRuntime.renderedFrames();
        phasePresented0 = FrameGenRuntime.presentedFrames();
        if (p == Phase.SOAK) {
            reportStartNs = phaseStartNs;
            reportRendered0 = phaseRendered0;
            reportPresented0 = phasePresented0;
        }
        FrameGenRuntime.Status st = FrameGenRuntime.status();
        ctx.log("scenario", name(), "phase", p.name().toLowerCase(Locale.ROOT), "event", "enter", "frame", String.valueOf(ctx.frames()),
            "fg_state", st.state().name(), "reason", st.reason());
    }

    private void startMode(AutotestContext ctx) {
        Mode m = MODE_SWEEP[modeIndex];
        if (m.multiplier() > FrameGenRuntime.maxMultiplier() && m != Mode.OFF) {
            ctx.log("scenario", name(), "phase", "modes", "event", "skip", "mode", m.name(), "reason", "adapter max multiplier " + FrameGenRuntime.maxMultiplier());
            modeIndex++;
            if (modeIndex < MODE_SWEEP.length) {
                startMode(ctx);
                return;
            }
            FrameGenRuntime.setUserEnabled(false);
            enter(ctx, Phase.TOGGLE_OFF);
            return;
        }
        FrameGenSettings.setMode(m);
        enter(ctx, Phase.MODES);
    }

    private double ratioSince(long r0, long p0) {
        long r = FrameGenRuntime.renderedFrames() - r0;
        long p = FrameGenRuntime.presentedFrames() - p0;
        return r <= 0 ? 0.0 : (double) p / (double) r;
    }

    private void check(AutotestContext ctx, boolean ok, String what) {
        if (!ok) {
            checksFailed++;
            ctx.log("scenario", name(), "event", "check_failed", "what", what);
        }
    }

    @Override
    public void onFrame(AutotestContext ctx) {
        var player = ctx.minecraft().player;
        if (player != null && phase != Phase.SETTLE && phase != Phase.DONE) {
            // continuous camera motion so that motion vectors, depth and the HUD handling of frame generation are really exercised
            float yaw = player.getYRot() + 0.8f;
            player.setYRot(yaw);
            player.yRotO = yaw - 0.8f;
        }
        if (KEEP_FOCUS && ctx.frames() % 90 == 0) {
            focusRequests++;
            boolean ok = FrameGenRuntime.focusGameWindowForTest();
            if (!ok && (focusRequests <= 5 || focusRequests % 20 == 0)) {
                ctx.log("scenario", name(), "event", "focus_request", "n", String.valueOf(focusRequests), "result", String.valueOf(ok));
            }
        }
        FrameGenRuntime.Status st = FrameGenRuntime.status();
        if (phase != Phase.BASELINE && (st.state() == State.FAILED || st.state() == State.UNAVAILABLE)) {
            ctx.fail("framegen unavailable: " + st.state() + " " + st.reason(), null);
            phase = Phase.DONE;
            return;
        }
        long framesIn = ctx.frames() - phaseStartFrame;
        long now = System.nanoTime();
        switch (phase) {
            case BASELINE -> {
                if (now - phaseStartNs >= 10_000_000_000L * (baselineWindows + 1)) {
                    baselineWindows++;
                    long frames = ctx.frames() - baselineFrames0;
                    ctx.log("scenario", name(), "phase", "baseline", "event", "report", "window", String.valueOf(baselineWindows),
                        "fps", String.format(Locale.ROOT, "%.1f", frames / 10.0));
                    baselineFrames0 = ctx.frames();
                    if (baselineWindows >= 6) {
                        ctx.log("scenario", name(), "event", "baseline_done");
                        ctx.finish();
                        phase = Phase.DONE;
                    }
                }
            }
            case SETTLE -> {
                if (st.state() == State.ACTIVE) {
                    enter(ctx, Phase.SOAK);
                } else if (framesIn > SETTLE_FRAMES) {
                    ctx.fail("framegen never became ACTIVE in " + SETTLE_FRAMES + " frames: " + st.state() + " " + st.reason(), null);
                    phase = Phase.DONE;
                }
            }
            case SOAK -> {
                if (st.reason().equals("window_not_focused")) {
                    sawUnfocused = true;
                }
                if (now - reportStartNs >= REPORT_NS) {
                    double ratio = ratioSince(reportRendered0, reportPresented0);
                    ctx.log("scenario", name(), "phase", "soak", "event", "report", "rendered", String.valueOf(FrameGenRuntime.renderedFrames() - reportRendered0),
                        "presented", String.valueOf(FrameGenRuntime.presentedFrames() - reportPresented0),
                        "ratio", String.format(Locale.ROOT, "%.3f", ratio), "fg_state", st.state().name(), "reason", st.reason());
                    check(ctx, st.state() == State.ACTIVE && ratio >= MIN_RATIO, "soak ratio " + ratio + " state=" + st.state() + " reason=" + st.reason());
                    reportStartNs = now;
                    reportRendered0 = FrameGenRuntime.renderedFrames();
                    reportPresented0 = FrameGenRuntime.presentedFrames();
                }
                if (now - phaseStartNs >= soakNs) {
                    modeIndex = 0;
                    startMode(ctx);
                }
            }
            case MODES -> {
                Mode m = MODE_SWEEP[modeIndex];
                if (framesIn == 300) {
                    modeBaseRendered = FrameGenRuntime.renderedFrames();
                    modeBasePresented = FrameGenRuntime.presentedFrames();
                }
                if (framesIn >= 900) {
                    long r = FrameGenRuntime.renderedFrames() - modeBaseRendered;
                    long pr = FrameGenRuntime.presentedFrames() - modeBasePresented;
                    double ratio = r <= 0 ? 0.0 : (double) pr / r;
                    int max = FrameGenRuntime.maxMultiplier();
                    int expected = m == Mode.OFF ? 1 : Math.min(m.multiplier(), max);
                    ctx.log("scenario", name(), "phase", "modes", "event", "report", "mode", m.name(), "expected_multiplier", String.valueOf(expected),
                        "rendered", String.valueOf(r), "presented", String.valueOf(pr), "ratio", String.format(Locale.ROOT, "%.3f", ratio),
                        "fg_state", st.state().name(), "reason", st.reason());
                    if (m == Mode.OFF) {
                        check(ctx, st.state() == State.READY && st.reason().equals("mode_off"), "mode OFF state=" + st.state() + " " + st.reason());
                    } else {
                        check(ctx, Math.abs(ratio - expected) < 0.15 * expected, "mode " + m + " ratio " + ratio + " expected " + expected);
                    }
                    modeIndex++;
                    if (modeIndex < MODE_SWEEP.length) {
                        startMode(ctx);
                    } else {
                        FrameGenRuntime.setUserEnabled(false);
                        enter(ctx, Phase.TOGGLE_OFF);
                    }
                }
            }
            case TOGGLE_OFF -> {
                if (framesIn >= 240) {
                    double ratio = ratioSince(phaseRendered0, phasePresented0);
                    ctx.log("scenario", name(), "phase", "toggle_off", "event", "report", "cycle", String.valueOf(toggleCycle),
                        "fg_state", st.state().name(), "reason", st.reason(), "rendered_counted_while_on", String.valueOf(FrameGenRuntime.renderedFrames() - phaseRendered0));
                    check(ctx, st.state() == State.READY && st.reason().equals("user_off"), "toggle off state=" + st.state() + " " + st.reason());
                    FrameGenRuntime.setUserEnabled(true);
                    enter(ctx, Phase.TOGGLE_ON);
                }
            }
            case TOGGLE_ON -> {
                if (framesIn >= 600) {
                    double ratio = ratioSince(phaseRendered0, phasePresented0);
                    ctx.log("scenario", name(), "phase", "toggle_on", "event", "report", "cycle", String.valueOf(toggleCycle),
                        "ratio", String.format(Locale.ROOT, "%.3f", ratio), "fg_state", st.state().name(), "reason", st.reason());
                    check(ctx, ratio >= MIN_RATIO && st.state() == State.ACTIVE, "toggle on ratio " + ratio + " state=" + st.state());
                    toggleCycle++;
                    if (toggleCycle < 3) {
                        FrameGenRuntime.setUserEnabled(false);
                        enter(ctx, Phase.TOGGLE_OFF);
                    } else {
                        ctx.minecraft().getWindow().setWindowed(1024, 576);
                        enter(ctx, Phase.RESIZE_SMALL);
                    }
                }
            }
            case RESIZE_SMALL -> {
                if (framesIn >= 900) {
                    double ratio = ratioSince(phaseRendered0, phasePresented0);
                    ctx.log("scenario", name(), "phase", "resize_small", "event", "report", "ratio", String.format(Locale.ROOT, "%.3f", ratio),
                        "fg_state", st.state().name(), "reason", st.reason(), "window", ctx.minecraft().getWindow().getWidth() + "x" + ctx.minecraft().getWindow().getHeight());
                    check(ctx, st.state() == State.ACTIVE && FrameGenRuntime.renderedFrames() > phaseRendered0 + 200, "resize small: generation did not resume, state=" + st.state() + " " + st.reason());
                    ctx.minecraft().getWindow().setWindowed(1280, 720);
                    enter(ctx, Phase.RESIZE_BACK);
                }
            }
            case RESIZE_BACK -> {
                if (framesIn >= 900) {
                    ctx.log("scenario", name(), "phase", "resize_back", "event", "report", "fg_state", st.state().name(), "reason", st.reason(),
                        "window", ctx.minecraft().getWindow().getWidth() + "x" + ctx.minecraft().getWindow().getHeight());
                    check(ctx, st.state() == State.ACTIVE && FrameGenRuntime.renderedFrames() > phaseRendered0 + 200, "resize back: generation did not resume, state=" + st.state() + " " + st.reason());
                    enter(ctx, Phase.SHOT);
                    ctx.shot("framegen_end.png", () -> {
                        FrameGenRuntime.Status end = FrameGenRuntime.status();
                        ctx.log("scenario", name(), "event", "summary", "rendered_total", String.valueOf(end.renderedFrames()),
                            "presented_total", String.valueOf(end.presentedFrames()), "doubled_frames", String.valueOf(end.doubledFrames()),
                            "checks_failed", String.valueOf(checksFailed), "saw_unfocused", String.valueOf(sawUnfocused));
                        if (checksFailed > 0) {
                            ctx.fail("framegen: " + checksFailed + " checks failed (see check_failed lines)", null);
                        } else {
                            ctx.finish();
                        }
                    });
                    phase = Phase.DONE;
                }
            }
            default -> {
            }
        }
    }
}
