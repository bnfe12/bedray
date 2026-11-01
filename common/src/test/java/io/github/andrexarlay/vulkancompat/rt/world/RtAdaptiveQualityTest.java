package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the adaptive quality governor ({@link RtAdaptiveQuality}).
 *
 * <p>It is allowed to spend image detail, never the player's settings: the spp / bounces of the settings page are a ceiling,
 * so no scene is ever traced better than what was chosen, and the ladder never reaches 0 rays or 0 bounces. The other half of
 * the contract is timing: a step needs sustained evidence, the next one waits, and a step up must predict that it will fit -
 * the ladder doubles the work in one step, so a band around the budget alone would let it trade places with itself and reset
 * the path accumulation every few seconds.
 */
public class RtAdaptiveQualityTest {
    private static final double FRAME = 16.0;
    private static final double BUDGET = 10.0;
    private static final int SPP = 2;        // the ladder then is spp 2 -> 1, then bounces 3 -> 2 -> 1
    private static final int BOUNCES = 3;
    private static final int LEVELS = RtAdaptiveQuality.levels(SPP, BOUNCES);

    private static final String GOVERNOR = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtAdaptiveQuality.java";
    private static final String FRAME_RENDERER = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java";
    private static final String SLANG_PASS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java";
    private static final String TIMER = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtGpuTimer.java";
    private static final String SETTINGS = "src/main/java/io/github/andrexarlay/vulkancompat/rtx/config/RtSettings.java";
    private static final String CONFIG = "src/main/java/io/github/andrexarlay/vulkancompat/sodium/VulkanSodiumConfig.java";
    private static final String CATALOG = "src/main/java/io/github/andrexarlay/vulkancompat/sodium/SettingsCatalog.java";
    private static final String LANG_RU = "src/main/resources/assets/bedray/lang/ru_ru.json";

    // ------------------------------------------------------------------ ladder

    @Test
    public void raysGiveWayBeforeBounces() {
        assertEquals(5, RtAdaptiveQuality.bouncesAt(0, 4, 5), "level 0 is the player's own path length");
        assertEquals(5, RtAdaptiveQuality.bouncesAt(3, 4, 5), "all three spare rays go before the first bounce");
        assertEquals(4, RtAdaptiveQuality.bouncesAt(4, 4, 5), "then the path gets shorter");
        assertEquals(4, RtAdaptiveQuality.sppAt(0, 4));
        assertEquals(3, RtAdaptiveQuality.sppAt(1, 4));
        assertEquals(1, RtAdaptiveQuality.sppAt(3, 4));
    }

    @Test
    public void ladderStopsAtOneRayAndOneBounce() {
        assertEquals(0, RtAdaptiveQuality.levels(1, 1), "one ray and one bounce is still a path tracer: nothing to give up");
        assertEquals(0, RtAdaptiveQuality.levels(0, 1));
        assertEquals(1, RtAdaptiveQuality.sppAt(99, 2));
        assertEquals(2, RtAdaptiveQuality.sppAt(-5, 2), "a negative level is the player's own values");
        assertEquals(1, RtAdaptiveQuality.bouncesAt(99, 4, 3));
        assertEquals(2, RtAdaptiveQuality.sppAt(0, 2));
    }

    @Test
    public void governorNeverExceedsThePlayersValues() {
        for (int spp = 1; spp <= 8; spp++) {
            for (int bounces = 1; bounces <= 8; bounces++) {
                int levels = RtAdaptiveQuality.levels(spp, bounces);
                for (int level = 0; level <= levels + 2; level++) {
                    int effSpp = RtAdaptiveQuality.sppAt(level, spp);
                    int effBounces = RtAdaptiveQuality.bouncesAt(level, spp, bounces);
                    assertTrue(effSpp >= 1 && effSpp <= spp, "spp " + effSpp + " out of range for " + spp);
                    assertTrue(effBounces >= 1 && effBounces <= bounces, "bounces " + effBounces + " out of range for " + bounces);
                }
                assertEquals(1, RtAdaptiveQuality.sppAt(levels, spp), "the last step is 1 ray");
                assertEquals(1, RtAdaptiveQuality.bouncesAt(levels, spp, bounces), "the last step is 1 bounce");
                assertEquals(spp, RtAdaptiveQuality.sppAt(0, spp), "level 0 is exactly what the player chose");
                assertEquals(bounces, RtAdaptiveQuality.bouncesAt(0, spp, bounces));
            }
        }
    }

    @Test
    public void costModelRoundsUpSoThePredictionIsPessimistic() {
        assertEquals(6, RtAdaptiveQuality.cost(0, SPP, BOUNCES));
        assertEquals(3, RtAdaptiveQuality.cost(1, SPP, BOUNCES));
        assertEquals(2, RtAdaptiveQuality.cost(2, SPP, BOUNCES));
        assertEquals(1, RtAdaptiveQuality.cost(3, SPP, BOUNCES));
        for (int level = 1; level <= LEVELS; level++) {
            assertTrue(RtAdaptiveQuality.cost(level, SPP, BOUNCES) < RtAdaptiveQuality.cost(level - 1, SPP, BOUNCES),
                "a higher level always costs less");
        }
    }

    @Test
    public void budgetIsAShareOfAFrameAndOffIsZero() {
        assertEquals(0.0, RtSettings.AdaptiveQuality.OFF.budgetMs(), "off means no comparison at all");
        assertEquals(1000.0 * RtSettings.AdaptiveQuality.BUDGET_SHARE / 60.0, RtSettings.AdaptiveQuality.FPS60.budgetMs(), 1e-9);
        assertTrue(RtSettings.AdaptiveQuality.FPS120.budgetMs() < RtSettings.AdaptiveQuality.FPS60.budgetMs(),
            "a faster target leaves less time for the RT work");
        assertTrue(RtSettings.AdaptiveQuality.BUDGET_SHARE <= 0.8, "the world raster and the present still need their time");
    }

    @Test
    public void optionIsOffByDefault() throws IOException {
        assertTrue(read(SETTINGS).contains("adaptiveQuality = AdaptiveQuality.OFF"),
            "a budget is a target: a GPU that cannot reach it would sit at one ray forever");
        assertTrue(read(CONFIG).contains("setDefaultValue(RtSettings.AdaptiveQuality.OFF)"), "the page must agree with the field");
    }

    // ------------------------------------------------------------------ policy

    @Test
    public void slowFramesStepDownAfterSustainedEvidence() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        int frames = (int) (RtAdaptiveQuality.SLOW_MS / FRAME);
        for (int i = 0; i < frames - 1; i++) {
            assertEquals(0, evaluate(p, 13.0, FRAME), "one slow frame is a hitch, not a scene");
        }
        assertEquals(1, evaluate(p, 13.0, FRAME), "sustained 30% over budget costs a step");
    }

    @Test
    public void oneLongStallIsNotAHeapOfEvidence() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        assertEquals(0, evaluate(p, 13.0, 60_000.0), "alt-tab and shader compiles must not count as slow frames");
        assertEquals(1, evaluate(p, 13.0, 60_000.0), "two capped stalls do add up");
    }

    @Test
    public void stepIsFollowedByAQuietWindow() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        while (evaluate(p, 13.0, FRAME) == 0) {
            // walk to the first step
        }
        assertEquals(1, p.level);
        int quiet = (int) (RtAdaptiveQuality.DWELL_MS / FRAME) - 2;
        for (int i = 0; i < quiet; i++) {
            assertEquals(1, evaluate(p, 13.0, FRAME),
                "the timestamps of the first frames after a step still describe the old setting");
        }
    }

    @Test
    public void headroomGivesTheValuesBackSlowly() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        p.level = 2;
        int frames = (int) (RtAdaptiveQuality.FAST_MS / FRAME);
        for (int i = 0; i < frames; i++) {
            assertEquals(2, evaluate(p, 5.0, FRAME), "one fast frame is not a reason to raise quality");
        }
        assertEquals(1, evaluate(p, 5.0, FRAME));
    }

    @Test
    public void bandAroundTheBudgetIsDead() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        p.level = 1;
        for (int i = 0; i < 5000; i++) {
            assertEquals(1, evaluate(p, BUDGET, FRAME), "exactly on budget nothing happens");
            assertEquals(1, evaluate(p, BUDGET * 1.02, FRAME), "2% over budget is noise");
            assertEquals(1, evaluate(p, BUDGET * 0.8, FRAME), "20% under budget is noise too");
        }
    }

    @Test
    public void evidenceIsClearedWhenTheFrameRecovers() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        for (int i = 0; i < 200; i++) {
            assertEquals(0, evaluate(p, 13.0, FRAME));
            assertEquals(0, evaluate(p, BUDGET, FRAME), "a frame back on budget wipes the evidence");
        }
    }

    @Test
    public void levelIsClampedToTheCurrentCeiling() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        p.level = 3;
        assertEquals(1, p.evaluate(1, SPP, BOUNCES, 5.0, BUDGET, FRAME), "the player lowered the ceiling: the level follows it down");
        p.level = 0;
        assertEquals(0, p.evaluate(0, SPP, BOUNCES, 13.0, BUDGET, FRAME), "nothing to give up: no step");
    }

    @Test
    public void missingFrameTimeIsNotEvidence() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, evaluate(p, 13.0, 0.0));
            assertEquals(0, evaluate(p, 13.0, Double.NaN));
        }
    }

    /**
     * The ladder changes the traced work by 2x in its first step, so a scene whose cost after a step lands at 58% of the
     * budget would be stepped up again into the red and stepped back down - a 2x quality flip every few seconds, and with the
     * denoiser off a path accumulation restart with each flip. The scene below is exactly that case (14 ms at the player's
     * values, 5.8 ms after the first step): the governor must stop at one step and stay there.
     */
    @Test
    public void predictionStopsTheTwoToOneLimitCycle() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        double measured = 14.0;
        int changes = 0;
        int previous = 0;
        for (int frame = 0; frame < 11_250; frame++) {   // three minutes of 60 FPS
            double truth = 1.0 + 1.6 * RtAdaptiveQuality.cost(p.level, SPP, BOUNCES);
            measured = measured * 0.9 + truth * 0.1;      // the timer's exponential average
            int level = evaluate(p, measured, FRAME);
            if (level != previous) {
                if (frame > 625) {                        // ignore the first ten seconds
                    changes++;
                }
                previous = level;
            }
        }
        assertEquals(0, changes, "a scene that cannot afford the next level must settle instead of flipping");
        assertEquals(1, p.level);
    }

    @Test
    public void aCheapSceneClimbsBackToThePlayersValues() {
        RtAdaptiveQuality.Policy p = new RtAdaptiveQuality.Policy();
        p.level = LEVELS;
        double measured = 1.0;
        for (int frame = 0; frame < 11_250; frame++) {
            double truth = 0.5 + 0.8 * RtAdaptiveQuality.cost(p.level, SPP, BOUNCES);
            measured = measured * 0.9 + truth * 0.1;
            evaluate(p, measured, FRAME);
        }
        assertEquals(0, p.level, "with enough room the player's own values must come back");
    }

    // ------------------------------------------------------------------ wiring

    @Test
    public void observerOnlySpendsWhatThePlayerChose() {
        int oldSpp = RtSettings.pathSpp;
        int oldBounces = RtSettings.pathBounces;
        RtSettings.AdaptiveQuality oldQuality = RtSettings.adaptiveQuality;
        try {
            RtSettings.pathSpp = 4;
            RtSettings.pathBounces = 5;
            RtSettings.adaptiveQuality = RtSettings.AdaptiveQuality.FPS60;
            double budget = RtSettings.adaptiveBudgetMs();
            assertTrue(budget > 0.0);
            for (int i = 0; i < 60; i++) {
                RtAdaptiveQuality.observe(budget * 3.0, FRAME);
            }
            assertTrue(RtAdaptiveQuality.level() > 0, "a scene three times over budget has to cost something");
            assertTrue(RtAdaptiveQuality.spp(4) <= 4 && RtAdaptiveQuality.bounces(4, 5) <= 5, "never above the player's values");

            RtAdaptiveQuality.observe(-1.0, FRAME);
            assertEquals(0, RtAdaptiveQuality.level(), "without a measurement the player's values are the honest answer");
            assertEquals(4, RtAdaptiveQuality.spp(4));

            RtAdaptiveQuality.observe(Double.NaN, FRAME);
            assertEquals(0, RtAdaptiveQuality.level(), "no GPU timers at all: same answer");

            RtSettings.adaptiveQuality = RtSettings.AdaptiveQuality.OFF;
            for (int i = 0; i < 60; i++) {
                RtAdaptiveQuality.observe(budget * 3.0, FRAME);
            }
            assertEquals(0, RtAdaptiveQuality.level(), "off means the settings page is the only source of the values");
        } finally {
            RtSettings.pathSpp = oldSpp;
            RtSettings.pathBounces = oldBounces;
            RtSettings.adaptiveQuality = oldQuality;
            RtAdaptiveQuality.reset();
        }
    }

    @Test
    public void aSettingsChangeStartsFromThePlayersValues() {
        int oldSpp = RtSettings.pathSpp;
        int oldBounces = RtSettings.pathBounces;
        RtSettings.AdaptiveQuality oldQuality = RtSettings.adaptiveQuality;
        try {
            RtSettings.pathSpp = 4;
            RtSettings.pathBounces = 5;
            RtSettings.adaptiveQuality = RtSettings.AdaptiveQuality.FPS60;
            for (int i = 0; i < 60; i++) {
                RtAdaptiveQuality.observe(RtSettings.adaptiveBudgetMs() * 3.0, FRAME);
            }
            assertTrue(RtAdaptiveQuality.level() > 0);
            RtAdaptiveQuality.reset();
            assertEquals(0, RtAdaptiveQuality.level());
            assertEquals(4, RtAdaptiveQuality.spp(4), "a new setup begins at the player's values");
        } finally {
            RtSettings.pathSpp = oldSpp;
            RtSettings.pathBounces = oldBounces;
            RtSettings.adaptiveQuality = oldQuality;
            RtAdaptiveQuality.reset();
        }
    }

    @Test
    public void governorNeverWritesSettings() throws IOException {
        String src = read(GOVERNOR);
        assertFalse(src.contains("RtSettings.pathSpp ="), "the governor must not change the player's rays per pixel");
        assertFalse(src.contains("RtSettings.pathBounces ="), "the governor must not change the player's bounces");
        assertFalse(src.contains("RtSettings.pathPreset"), "a governed frame must not turn the profile into Custom");
        assertFalse(src.contains("RtSettings.adaptiveQuality ="), "the session override is not written back to the config");
    }

    @Test
    public void pathPassTracesTheGovernedValues() throws IOException {
        String pass = read(SLANG_PASS);
        assertTrue(pass.contains("params.bounces = RtAdaptiveQuality.bounces(RtSettings.spp(), RtSettings.bounces());"),
            "the pass must trace the governed path length");
        assertTrue(pass.contains("params.spp = RtAdaptiveQuality.spp(RtSettings.spp());"),
            "the pass must trace the governed rays per pixel: rays go first in the ladder, so a pinned one would make "
                + "both the slider and the budget silent");
    }

    @Test
    public void rendererFeedsTheMeasuredTimeOnlyInPathMode() throws IOException {
        String src = read(FRAME_RENDERER);
        int at = src.indexOf("RtAdaptiveQuality.observe(");
        assertTrue(at > 0, "the frame loop must feed the governor");
        String before = src.substring(Math.max(0, at - 500), at);
        assertTrue(before.contains("RtMode.PATH_TRACED"), "rays mode has no spp/bounces to govern");
        assertTrue(src.contains("gpuTimer.recentMs(SPAN_RAYS, SPAN_DENOISE)"),
            "only the work the ladder can move may drive the budget: a chunk rebuild is not cheaper with fewer rays");
        assertTrue(src.contains("Double.NaN"), "no GPU timers at all must be told apart from a fast frame");
        assertTrue(src.contains("RtAdaptiveQuality.reset();"), "a mode or settings change starts from the player's values");
    }

    @Test
    public void timerExposesASmoothedFrameTime() throws IOException {
        String src = read(TIMER);
        assertTrue(src.contains("double recentMs(int from, int to)"), "the governor needs one number per frame");
        assertTrue(src.contains("ewmaMs[span]"), "raw stamps of one frame are too noisy for a decision");
        assertTrue(src.contains("return -1.0"), "no sample yet must be told apart from a fast frame");
    }

    @Test
    public void settingIsRegisteredAndDescribedHonestly() throws IOException {
        String config = read(CONFIG);
        int at = config.indexOf("createEnumOption(id(\"adaptive_quality\")");
        assertTrue(at > 0, "the option must exist on the Path Tracing page");
        String block = config.substring(at, config.indexOf("track(\"adaptive_quality\"", at));
        assertTrue(block.contains("setEnabledProvider(pathOn"), "it means nothing in rays mode");
        assertFalse(block.contains("customPath("), "changing the governor is not a custom profile");
        assertTrue(block.contains("OptionImpact.LOW"), "on its own it costs nothing");

        String catalog = read(CATALOG);
        assertTrue(catalog.contains("putNoImage(\"adaptive_quality\""), "the panel needs a description for it");
        assertTrue(catalog.contains("never goes above your values"), "the description must say which way the trade goes");
        assertTrue(catalog.contains("Off by default"), "and that it is not a silent change to the chosen quality");
        assertTrue(catalog.contains("Path Tracing only"), "and where it applies");

        String ru = read(LANG_RU);
        for (String key : new String[] {"bedray.opt.adaptive_quality", "bedray.opt.adaptive_quality.desc",
            "bedray.opt.adaptive_quality.cost", "bedray.opt.adaptive_quality.req"}) {
            assertTrue(ru.contains("\"" + key + "\""), "missing translation: " + key);
        }
    }

    private static int evaluate(RtAdaptiveQuality.Policy p, double rtMs, double dtMs) {
        return p.evaluate(LEVELS, SPP, BOUNCES, rtMs, BUDGET, dtMs);
    }

    private static String read(String path) throws IOException {
        String text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        assertNotNull(text, "missing source: " + path);
        return text;
    }
}
