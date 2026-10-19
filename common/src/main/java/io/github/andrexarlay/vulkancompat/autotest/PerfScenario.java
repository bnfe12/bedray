package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.rtx.debug.PerfMonitor;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code perf}: fixed camera, Hardware RT (and so the Sodium geometry capture) switched off, then for each step the upscaler
 * is applied through the Config API (same call as the settings page), the game runs {@code -Dvkrtx.autotest.perfWarmupSec}
 * (default 6) and then {@code -Dvkrtx.autotest.perfSamples} (default 6) PerfMonitor windows of 0.5 s are logged as
 * {@code [VKB-AUTOTEST] scenario=perf step=N label=.. sample=K perf=<the [VKB-PERF] key=value line>}. A screenshot per step;
 * at the end the F3 overlay is shown and {@code f3_overlay.png} is taken.
 * <p>
 * Steps: {@code -Dvkrtx.autotest.perfSteps=BILINEAR:NATIVE,DLSS:QUALITY,...} (BILINEAR takes a 3D scale preset: NATIVE,
 * QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE). Render distance: {@code -Dvkrtx.autotest.renderDistance=<n>} (0 = keep
 * the options value; restored before the game exits). Chunk loading time before the first step:
 * {@code -Dvkrtx.autotest.perfLoadSec} (default 20). A step whose runtime does not reach the requested state fails the
 * scenario (no silent skip).
 */
public final class PerfScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.perfSteps";
    public static final String DEFAULT_STEPS = "BILINEAR:NATIVE,DLSS:QUALITY,DLSS:PERFORMANCE,XESS:QUALITY,BILINEAR:PERFORMANCE";
    private static final double WARMUP_SEC = Double.parseDouble(System.getProperty("vkrtx.autotest.perfWarmupSec", "6"));
    private static final double LOAD_SEC = Double.parseDouble(System.getProperty("vkrtx.autotest.perfLoadSec", "20"));
    private static final int SAMPLES = Integer.getInteger("vkrtx.autotest.perfSamples", 6);
    private static final int RENDER_DISTANCE = Integer.getInteger("vkrtx.autotest.renderDistance", 0);
    /** {@code -Dvkrtx.autotest.perfRt=true}: ray tracing (RtMode RAY_TRACED) stays ON in every step (GPU-heavy case, also with XeSS/DLSS/FSR). */
    private static final boolean WITH_RT = Boolean.getBoolean("vkrtx.autotest.perfRt");

    record Step(UpscalerBackend backend, UpscalerMode mode, VulkanRtxConfig.QualityPreset preset) {
        String label() {
            return backend == UpscalerBackend.BILINEAR
                ? "bilinear_" + preset.name().toLowerCase(Locale.ROOT)
                : backend.name().toLowerCase(Locale.ROOT) + "_" + mode.name().toLowerCase(Locale.ROOT);
        }
    }

    private int savedRenderDistance = -1;

    @Override
    public String name() {
        return "perf";
    }

    static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] p = item.split(":");
            UpscalerBackend backend = UpscalerBackend.valueOf(p[0].trim().toUpperCase(Locale.ROOT));
            if (backend == UpscalerBackend.BILINEAR) {
                VulkanRtxConfig.QualityPreset preset = p.length > 1
                    ? VulkanRtxConfig.QualityPreset.valueOf(p[1].trim().toUpperCase(Locale.ROOT))
                    : VulkanRtxConfig.QualityPreset.NATIVE;
                steps.add(new Step(backend, null, preset));
            } else {
                UpscalerMode mode = p.length > 1
                    ? UpscalerMode.valueOf(p[1].trim().toUpperCase(Locale.ROOT)) : UpscalerModes.defaultMode(backend);
                if (!UpscalerModes.isOffered(backend, mode)) {
                    throw new IllegalArgumentException(backend + " does not offer " + mode);
                }
                steps.add(new Step(backend, mode, null));
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("no steps in '" + text + "'");
        }
        return steps;
    }

    @Override
    public void run(AutotestContext ctx) {
        List<Step> steps = parseSteps(System.getProperty(STEPS_PROPERTY, DEFAULT_STEPS));
        var mc = ctx.minecraft();
        // default: capture off for every step. Session value only (rtMode, not setRtMode: the persisted mode is not touched).
        VulkanRtxConfig.rtMode = WITH_RT ? RtMode.RAY_TRACED : RtMode.OFF;
        VulkanRtxConfig.enableHardwareRayTracing = WITH_RT;
        mc.debugEntries.setOverlayVisible(false);
        if (RENDER_DISTANCE > 0) {
            savedRenderDistance = mc.options.renderDistance().get();
            mc.options.renderDistance().set(RENDER_DISTANCE);
        }
        ctx.log("scenario", name(), "event", "start", "steps", steps.toString().replace(' ', '_'), "render_distance",
            String.valueOf(mc.options.renderDistance().get()), "window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight(),
            "warmup_sec", String.valueOf(WARMUP_SEC), "samples", String.valueOf(SAMPLES), "load_sec", String.valueOf(LOAD_SEC));
        ScenarioTime.waitSeconds(ctx, LOAD_SEC, () -> step(ctx, steps, 0));
    }

    private void step(AutotestContext ctx, List<Step> steps, int index) {
        if (index >= steps.size()) {
            ending(ctx);
            return;
        }
        Step s = steps.get(index);
        if (s.backend() == UpscalerBackend.BILINEAR) {
            VulkanRtxConfig.qualityPreset = s.preset();
        }
        UpscalerSwitch.Outcome outcome = VulkanRtxConfig.applyUpscaler(s.backend(),
            s.backend() == UpscalerBackend.XESS ? s.mode() : null, s.backend() == UpscalerBackend.DLSS ? s.mode() : null,
            s.backend() == UpscalerBackend.FSR ? s.mode() : null, null, "autotest_perf_" + index);
        VulkanRtxManager.applyResolutionScalingNow();
        ctx.log("scenario", name(), "step", String.valueOf(index), "label", s.label(), "outcome", outcome.name());
        if (outcome == UpscalerSwitch.Outcome.DEFERRED_RESTART) {
            ctx.fail("perf step " + index + " " + s.label() + " needs a restart (device requirements not registered)", null);
            return;
        }
        ScenarioTime.waitSeconds(ctx, WARMUP_SEC, () -> {
            UpscalerBackend active = TemporalUpscaler.active();
            boolean ok = s.backend() == UpscalerBackend.BILINEAR ? active == null
                : active == s.backend() && s.mode().name().equals(TemporalUpscaler.modeName());
            if (!ok) {
                ctx.fail("perf step " + index + " expected " + s.label() + " but active=" + active
                    + (active == null ? "" : " mode=" + TemporalUpscaler.modeName()), null);
                return;
            }
            if (RtGeometryGate.enabled() != WITH_RT) {
                ctx.fail("perf step " + index + ": RT gate=" + RtGeometryGate.enabled() + " but perfRt=" + WITH_RT + " (block: " + RtGeometryGate.blockReason() + ")", null);
                return;
            }
            sample(ctx, steps, index, 0, PerfMonitor.publishedVersion());
        });
    }

    /** A screen (inventory, pause menu after a focus loss) changes what is rendered: closed and logged, never measured. */
    private void closeScreen(AutotestContext ctx, String where) {
        var gui = ctx.minecraft().gui;
        if (gui.screen() != null) {
            ctx.log("scenario", name(), "event", "screen_closed", "where", where, "screen", gui.screen().getClass().getName());
            gui.setScreen(null);
        }
    }

    private void sample(AutotestContext ctx, List<Step> steps, int index, int k, long seenVersion) {
        closeScreen(ctx, "step_" + index + "_sample_" + k);
        if (k >= SAMPLES) {
            ctx.shot(String.format(Locale.ROOT, "%02d_%s.png", index, steps.get(index).label()), () -> step(ctx, steps, index + 1));
            return;
        }
        ScenarioTime.waitUntil(ctx, () -> PerfMonitor.publishedVersion() != seenVersion, 5.0, () -> {
            ctx.log("scenario", name(), "step", String.valueOf(index), "label", steps.get(index).label(),
                "sample", String.valueOf(k), "perf", DebugInfo.lastPerfLine());
            sample(ctx, steps, index, k + 1, PerfMonitor.publishedVersion());
        }, () -> ctx.fail("perf: no PerfMonitor window published within 5 s", null));
    }

    private void ending(AutotestContext ctx) {
        var mc = ctx.minecraft();
        mc.debugEntries.setOverlayVisible(true);
        ScenarioTime.waitSeconds(ctx, 1.5, () -> {
            for (String line : DebugInfo.lines()) {
                ctx.log("scenario", name(), "f3", DebugText.plain(line));
            }
            ctx.shot("f3_overlay.png", () -> {
                if (savedRenderDistance > 0) {
                    mc.options.renderDistance().set(savedRenderDistance);
                }
                ctx.log("scenario", name(), "event", "done");
                ctx.finish();
            });
        });
    }
}
