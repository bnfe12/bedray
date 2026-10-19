package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;

import java.util.Locale;

/**
 * {@code rt_upscaler} (was {@code rt_block} on feat/debug-perf, where RT and XeSS/DLSS were mutually exclusive): ray tracing
 * (session mode {@code -Dvkrtx.autotest.rtUpscalerMode=rays|path}, default rays) together with a temporal upscaler
 * ({@code -Dvkrtx.autotest.rtUpscalerBackend=DLSS|XESS|FSR}, default DLSS, Quality). Expected: the upscaler is active, the
 * RT gate is on, sections are captured and the TLAS has instances (RT runs WITH the upscaler, RtActivation.TEMPORAL_EXCLUSIVE
 * is false). Then Bilinear: RT keeps running. Then RT off: capture off, the decoded sections dropped, the RT world released.
 * F3 is shown in all shots; the F3 lines are logged per phase.
 */
public final class RtUpscalerScenario implements AutotestScenario {
    private static final UpscalerBackend BACKEND = UpscalerBackend.valueOf(
        System.getProperty("vkrtx.autotest.rtUpscalerBackend", "DLSS").trim().toUpperCase(Locale.ROOT));
    private static final RtMode MODE = parseMode(System.getProperty("vkrtx.autotest.rtUpscalerMode", "rays"));

    private static RtMode parseMode(String text) {
        RtMode m = RtMode.parse(text);
        if (m == null || !m.isActive()) {
            throw new IllegalArgumentException("vkrtx.autotest.rtUpscalerMode must be rays or path, got '" + text + "'");
        }
        return m;
    }

    @Override
    public String name() {
        return "rt_upscaler";
    }

    @Override
    public void run(AutotestContext ctx) {
        ctx.minecraft().debugEntries.setOverlayVisible(true);
        // session value only (not setRtMode: the persisted mode in the toml is not touched)
        VulkanRtxConfig.rtMode = MODE;
        VulkanRtxConfig.enableHardwareRayTracing = true;
        VulkanRtxConfig.applyUpscaler(BACKEND, BACKEND == UpscalerBackend.XESS ? UpscalerMode.QUALITY : null,
            BACKEND == UpscalerBackend.DLSS ? UpscalerMode.QUALITY : null,
            BACKEND == UpscalerBackend.FSR ? UpscalerMode.QUALITY : null, null, "autotest_rt_upscaler");
        VulkanRtxManager.applyResolutionScalingNow();
        ScenarioTime.waitUntil(ctx, () -> TemporalUpscaler.active() == BACKEND && rtRunning(), 40.0,
            () -> ScenarioTime.waitSeconds(ctx, 4.0, () -> {
                state(ctx, "upscaled_rt_on");
                if (TemporalUpscaler.active() != BACKEND || !rtRunning()) {
                    ctx.fail("rt_upscaler: RT stopped while " + BACKEND + " is active", null);
                    return;
                }
                ctx.shot("01_" + BACKEND.name().toLowerCase(Locale.ROOT) + "_" + MODE.logName() + ".png", () -> toBilinear(ctx));
            }),
            () -> {
                state(ctx, "timeout");
                ctx.fail("rt_upscaler: expected RT running together with " + BACKEND + " within 40 s (active="
                    + TemporalUpscaler.active() + " block=" + RtGeometryGate.blockReason() + " failure=" + RtGeometryGate.failure() + ")", null);
            });
    }

    private static boolean rtRunning() {
        var stats = RtFrameRenderer.lastStats();
        return RtGeometryGate.enabled() && RtGeometryGate.failure() == null && RtFrameRenderer.worldReady() && stats != null
            && stats.instances() > 0 && SectionGeometryRegistry.INSTANCE.sectionCount() > 0;
    }

    private void toBilinear(AutotestContext ctx) {
        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
        VulkanRtxConfig.applyUpscaler(UpscalerBackend.BILINEAR, null, null, "autotest_rt_upscaler_bilinear");
        VulkanRtxManager.applyResolutionScalingNow();
        ScenarioTime.waitUntil(ctx, () -> TemporalUpscaler.active() == null && rtRunning(), 30.0,
            () -> ScenarioTime.waitSeconds(ctx, 3.0, () -> {
                state(ctx, "bilinear_rt_on");
                ctx.shot("02_bilinear_" + MODE.logName() + ".png", () -> rtOff(ctx));
            }),
            () -> {
                state(ctx, "timeout");
                ctx.fail("rt_upscaler: RT not running within 30 s after switching to Bilinear", null);
            });
    }

    private void rtOff(AutotestContext ctx) {
        VulkanRtxConfig.rtMode = RtMode.OFF;
        VulkanRtxConfig.enableHardwareRayTracing = false;
        RtGeometryGate.clearFailure(); // same as the settings binding switching off
        ScenarioTime.waitSeconds(ctx, 2.0, () -> {
            state(ctx, "rt_off");
            if (RtGeometryGate.enabled() || SectionGeometryRegistry.INSTANCE.sectionCount() != 0 || RtFrameRenderer.worldReady()) {
                ctx.fail("rt_upscaler: RT off but capture/world still alive", null);
                return;
            }
            ctx.shot("03_rt_off.png", ctx::finish);
        });
    }

    private void state(AutotestContext ctx, String phase) {
        var stats = RtFrameRenderer.lastStats();
        ctx.log("scenario", name(), "phase", phase, "mode", MODE.logName(), "gate", String.valueOf(RtGeometryGate.enabled()),
            "block", String.valueOf(RtGeometryGate.blockReason()), "failure", String.valueOf(RtGeometryGate.failure()),
            "temporal", String.valueOf(TemporalUpscaler.active()),
            "sections", String.valueOf(SectionGeometryRegistry.INSTANCE.sectionCount()),
            "rt_world", String.valueOf(RtFrameRenderer.worldReady()),
            "pass_mode", String.valueOf(RtFrameRenderer.lastPassMode()),
            "tlas_instances", stats == null ? "-" : String.valueOf(stats.instances()));
        for (String line : DebugInfo.lines()) {
            ctx.log("scenario", name(), "phase", phase, "f3", DebugText.plain(line));
        }
    }
}
