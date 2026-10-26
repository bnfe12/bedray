package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Adaptive quality of the Path Tracing pass: the pass keeps itself inside the GPU budget of
 * {@link RtSettings.AdaptiveQuality} by lowering the knobs the player already owns, and gives them back when the frame has
 * room again. It is a governor, not a preset: the spp / bounces of the settings page are its ceiling, so a scene is never
 * traced better than what the player chose, only cheaper. The budget covers what the ladder can actually move - the traced
 * rays and the denoiser; the chunk build and the atmosphere pass are measured for the log but excluded, because a section
 * rebuild while walking would otherwise pin the scene at one ray with nothing to gain from it.
 * <p>
 * Order of the steps matters. Rays per pixel go first (halving the sample count is the largest single win, and the denoiser
 * averages the extra grain away), path length second (a shorter path dims the corners of a room, which reads as a lighting
 * change rather than noise). Bounces never drop below 1 and rays never below 1: without either the pass stops being a path
 * tracer.
 * <p>
 * The budget is measured, not guessed: {@code RtGpuTimer} reads Vulkan timestamps four frames late and this class follows
 * them with an exponential average. A step is made only after {@link #SLOW_MS} of sustained evidence and the next decision
 * waits {@link #DWELL_MS}, because the timestamps of the first frames after a change still describe the old setting.
 * <p>
 * The ladder is coarse - every step doubles or halves the traced work - so a band around the budget cannot stop a limit
 * cycle by itself: a scene that sits at 60-70% of the budget after a step down would be pushed back up into the red on the
 * next quiet stretch. The step up therefore predicts its own cost first (see {@link #cost}): it is taken only when the
 * measured time scaled by the cost of the higher level still fits, with {@link #PREDICT_MARGIN} of slack for the model's
 * error. That leaves one step into the budget and nothing to bounce against.
 */
public final class RtAdaptiveQuality {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    /** Over budget by more than 5% is "too slow"; below 75% of it is headroom; between the two nothing happens. */
    static final double TOO_SLOW = 1.05;
    static final double HEADROOM = 0.75;
    /** Slack on the predicted cost of the next level up: the model rounds up, but a sublinear pass is not guaranteed. */
    static final double PREDICT_MARGIN = 1.2;
    /** Sustained time above / below the budget before a step, and the quiet time after one. */
    static final double SLOW_MS = 400.0;
    static final double FAST_MS = 2500.0;
    static final double DWELL_MS = 1500.0;
    /** One frame of evidence is capped here: a long stall (alt-tab, shader compile) must not count as a slow scene. */
    static final double MAX_STEP_MS = 250.0;

    private static final Policy POLICY = new Policy();
    private static int loggedLevel = -1;
    private static boolean loggedNoTimer;

    private RtAdaptiveQuality() {
    }

    /** Steps between the player's values and 1 spp / 1 bounce; 0 when there is nothing to give up. */
    static int levels(int spp, int bounces) {
        return Math.max(0, spp - 1) + Math.max(0, bounces - 1);
    }

    static int sppAt(int level, int spp) {
        int steps = Math.max(0, spp - 1);
        return Math.max(1, spp - Math.min(Math.max(level, 0), steps));
    }

    static int bouncesAt(int level, int spp, int bounces) {
        int steps = Math.max(0, spp - 1);
        return Math.max(1, bounces - Math.max(0, level - steps));
    }

    /**
     * Work of one pixel at a level in the shader's own terms: rays times path segments. Used only to predict how much more a
     * step up will cost. It rounds up - a path is counted as {@code bounces} segments where the shader traces
     * {@code bounces + 1}, the per-pixel setup work is in neither factor, and Russian roulette cuts the tail - so no step
     * grows faster than this model says, which is the safe direction for a prediction.
     */
    static int cost(int level, int spp, int bounces) {
        return sppAt(level, spp) * bouncesAt(level, spp, bounces);
    }

    /** Rays per pixel for this frame: the player's value or less. */
    public static int spp(int userSpp) {
        return sppAt(POLICY.level, userSpp);
    }

    /** Path length for this frame: the player's value or less, but never below 1. */
    public static int bounces(int userSpp, int userBounces) {
        return bouncesAt(POLICY.level, userSpp, userBounces);
    }

    /** 0 = the player's own values. */
    public static int level() {
        return POLICY.level;
    }

    /**
     * Back to the player's own values. Called when the mode or a traced setting changes: the level that was reached described
     * a setup that no longer exists, and the first timings of the new one say nothing about how heavy it is.
     */
    public static void reset() {
        POLICY.level = 0;
        POLICY.resetTimers();
    }

    /**
     * One traced frame of the Path Tracing mode: {@code rtMs} is the smoothed GPU time of the traced rays and the denoiser
     * ({@link Double#NaN} when the GPU timers are off, negative while no sample has been read yet), {@code dtMs} the wall
     * time since the previous traced frame.
     */
    public static void observe(double rtMs, double dtMs) {
        int userSpp = RtSettings.spp();
        int userBounces = RtSettings.bounces();
        int levels = levels(userSpp, userBounces);
        double budget = RtSettings.adaptiveBudgetMs();
        if (levels <= 0 || budget <= 0.0 || Double.isNaN(budget)) {
            POLICY.level = 0;
            POLICY.resetTimers();
            return;
        }
        if (Double.isNaN(rtMs)) {
            if (!loggedNoTimer) {
                loggedNoTimer = true;
                LOGGER.info("[VKB-RT] adaptive_quality off: no GPU timings to compare with budget={}ms (-Dvkrtx.rt.gpuTimers=false disables them)",
                    ms(budget));
            }
            POLICY.level = 0;
            POLICY.resetTimers();
            return;
        }
        if (!(rtMs > 0.0)) {
            // The first frames of a session have no readable stamps yet (they are harvested a few frames late); the player's
            // own values are the only honest answer until the measurement exists.
            POLICY.level = 0;
            POLICY.resetTimers();
            return;
        }
        loggedNoTimer = false;
        int before = POLICY.level;
        POLICY.level = POLICY.evaluate(levels, userSpp, userBounces, rtMs, budget, dtMs);
        if (POLICY.level != before || loggedLevel < 0) {
            logStep(before, levels, userSpp, userBounces, rtMs, budget);
        }
    }

    private static void logStep(int before, int levels, int userSpp, int userBounces, double rtMs, double budget) {
        loggedLevel = POLICY.level;
        String reason = POLICY.level > before ? "over budget" : POLICY.level < before ? "headroom" : "active";
        String floor = POLICY.level >= levels && POLICY.level > before
            ? ", at the floor: 1 ray / 1 bounce cannot reach this budget" : "";
        LOGGER.info("[VKB-RT] adaptive_quality level={}/{} spp {}->{} bounces {}->{} measured={}ms budget={}ms ({}{})",
            POLICY.level, levels, sppAt(before, userSpp), sppAt(POLICY.level, userSpp),
            bouncesAt(before, userSpp, userBounces), bouncesAt(POLICY.level, userSpp, userBounces),
            ms(rtMs), ms(budget), reason, floor);
    }

    private static String ms(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** The decision itself, kept free of settings and logging so the policy can be tested frame by frame. */
    static final class Policy {
        int level;
        double slowMs;
        double fastMs;
        double dwellMs;

        int evaluate(int levels, int userSpp, int userBounces, double rtMs, double budgetMs, double dtMs) {
            int max = Math.max(0, levels);
            level = Math.min(Math.max(level, 0), max);
            double dt = Double.isNaN(dtMs) || dtMs <= 0.0 ? 0.0 : Math.min(dtMs, MAX_STEP_MS);
            if (dwellMs > 0.0) {
                dwellMs -= dt;
                return level;
            }
            if (rtMs > budgetMs * TOO_SLOW) {
                slowMs += dt;
                fastMs = 0.0;
            } else if (rtMs < budgetMs * HEADROOM) {
                fastMs += dt;
                slowMs = 0.0;
            } else {
                slowMs = 0.0;
                fastMs = 0.0;
            }
            if (slowMs >= SLOW_MS && level < max) {
                level++;
                changed();
            } else if (fastMs >= FAST_MS && level > 0) {
                if (fitsAfterStepUp(userSpp, userBounces, rtMs, budgetMs)) {
                    level--;
                    changed();
                } else {
                    // The measurement is cheap but the next level is not affordable: do not keep that evidence, or one lucky
                    // sample at the end of the window would be spent on a step that has to be taken back.
                    fastMs = 0.0;
                }
            }
            return level;
        }

        /** Would the higher level still fit, judged by what it costs relative to the current one? */
        private boolean fitsAfterStepUp(int userSpp, int userBounces, double rtMs, double budgetMs) {
            int here = Math.max(1, RtAdaptiveQuality.cost(level, userSpp, userBounces));
            double ratio = (double) RtAdaptiveQuality.cost(level - 1, userSpp, userBounces) / here;
            return rtMs * ratio * PREDICT_MARGIN < budgetMs;
        }

        private void changed() {
            slowMs = 0.0;
            fastMs = 0.0;
            dwellMs = DWELL_MS;
        }

        void resetTimers() {
            slowMs = 0.0;
            fastMs = 0.0;
            dwellMs = 0.0;
        }
    }
}
