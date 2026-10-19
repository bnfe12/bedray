package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerSwitch;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial.SpatialUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code upscaler_switch}: switches the upscaler at runtime through the Config API ({@code VulkanRtxConfig.applyUpscalerFull},
 * the same call as the settings page, no restart), waits {@link #FRAMES_PER_STEP} frames on each step, checks that the
 * runtime really reached the requested state and takes a screenshot per step.
 * <p>
 * Default steps: {@code BILINEAR, XESS:QUALITY, XESS:PERFORMANCE, DLSS:QUALITY, DLSS:PERFORMANCE, BILINEAR}. Override with
 * {@code -Dvkrtx.autotest.upscalerSteps=...}. Step syntax {@code BACKEND[:MODE[:FSR_VERSION]][@PERCENT]}:
 * {@code @PERCENT} is the custom render scale (mode CUSTOM for XESS/DLSS/FSR/FSR1/NIS; for BILINEAR the Custom 3D-scale preset,
 * without it BILINEAR is Native 100%). Examples: {@code BILINEAR@50,FSR1:CUSTOM@50,NIS:CUSTOM@50,FSR1:QUALITY,DLSS:CUSTOM@60}.
 * A step fails the scenario when the outcome is DEFERRED_RESTART or when, after the wait, the active backend / mode / spatial
 * render size differ from the step: there is no silent skip. Screenshots: {@code NN_<backend>_<mode>[_<pct>].png}.
 */
public final class UpscalerSwitchScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.upscalerSteps";
    public static final String DEFAULT_STEPS = "BILINEAR,XESS:QUALITY,XESS:PERFORMANCE,DLSS:QUALITY,DLSS:PERFORMANCE,BILINEAR";
    public static final int FRAMES_PER_STEP = 120;

    /**
     * One step; {@code mode} is null for BILINEAR, {@code fsrVersion} is set for FSR only, {@code percent} is 0 or the custom
     * render scale.
     */
    public record Step(UpscalerBackend backend, UpscalerMode mode, FsrVersion fsrVersion, int percent) {
        public Step(UpscalerBackend backend, UpscalerMode mode) {
            this(backend, mode, backend == UpscalerBackend.FSR ? FsrVersion.AUTO : null, 0);
        }

        public Step(UpscalerBackend backend, UpscalerMode mode, FsrVersion fsrVersion) {
            this(backend, mode, fsrVersion, 0);
        }

        String label() {
            return backend.name().toLowerCase(Locale.ROOT) + (mode == null ? "" : "_" + mode.name().toLowerCase(Locale.ROOT))
                + (fsrVersion == null || fsrVersion == FsrVersion.AUTO ? "" : "_" + fsrVersion.name().toLowerCase(Locale.ROOT))
                + (percent > 0 ? "_" + percent : "");
        }
    }

    @Override
    public String name() {
        return "upscaler_switch";
    }

    /**
     * Parses {@code BACKEND[:MODE[:FSR_VERSION]][@PERCENT],...}; a missing mode is the default mode of the technology, the third
     * part (FSR only) is {@code auto|3.1|2}. Unknown input throws.
     */
    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            int percent = 0;
            int at = item.indexOf('@');
            if (at >= 0) {
                percent = Integer.parseInt(item.substring(at + 1).trim());
                if (percent < UpscalerModes.CUSTOM_MIN_PERCENT || percent > UpscalerModes.CUSTOM_MAX_PERCENT) {
                    throw new IllegalArgumentException("percent outside 25..100: " + item);
                }
                item = item.substring(0, at);
            }
            String[] parts = item.split(":");
            UpscalerBackend backend = UpscalerBackend.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
            UpscalerMode mode = null;
            if (backend != UpscalerBackend.BILINEAR) {
                mode = parts.length > 1 ? UpscalerMode.valueOf(parts[1].trim().toUpperCase(Locale.ROOT))
                    : (percent > 0 ? UpscalerMode.CUSTOM : UpscalerModes.defaultMode(backend));
                if (!UpscalerModes.isOffered(backend, mode)) {
                    throw new IllegalArgumentException(backend + " does not offer mode " + mode);
                }
                if ((mode == UpscalerMode.CUSTOM) != (percent > 0)) {
                    throw new IllegalArgumentException("CUSTOM needs @PERCENT and @PERCENT needs CUSTOM: " + raw.trim());
                }
            } else if (parts.length > 1) {
                throw new IllegalArgumentException("BILINEAR has no mode: " + item);
            }
            FsrVersion version = null;
            if (backend == UpscalerBackend.FSR) {
                version = parts.length > 2 ? FsrVersion.parse(parts[2]) : FsrVersion.AUTO;
            } else if (parts.length > 2) {
                throw new IllegalArgumentException("only FSR steps take a version: " + item);
            }
            steps.add(new Step(backend, mode, version, percent));
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("no steps in '" + text + "'");
        }
        return steps;
    }

    @Override
    public void run(AutotestContext ctx) {
        List<Step> steps = parseSteps(System.getProperty(STEPS_PROPERTY, DEFAULT_STEPS));
        ctx.log("scenario", name(), "event", "start", "steps", steps.toString(), "frames_per_step", String.valueOf(FRAMES_PER_STEP));
        // let the world settle before the first switch
        ctx.waitFrames(60, () -> step(ctx, steps, 0));
    }

    private void step(AutotestContext ctx, List<Step> steps, int index) {
        if (index >= steps.size()) {
            ctx.log("scenario", name(), "event", "done", "steps", String.valueOf(steps.size()));
            ctx.finish();
            return;
        }
        Step s = steps.get(index);
        if (s.backend() == UpscalerBackend.BILINEAR) {
            VulkanRtxConfig.qualityPreset = s.percent() > 0 ? VulkanRtxConfig.QualityPreset.CUSTOM : VulkanRtxConfig.QualityPreset.NATIVE;
            if (s.percent() > 0) {
                VulkanRtxConfig.customScalePercent = s.percent();
            }
        } else if (s.percent() > 0) {
            VulkanRtxConfig.setCustomPercent(s.backend(), s.percent());
        }
        UpscalerSwitch.Outcome outcome = VulkanRtxConfig.applyUpscalerFull(
            s.backend(), s.backend() == UpscalerBackend.XESS ? s.mode() : null,
            s.backend() == UpscalerBackend.DLSS ? s.mode() : null,
            s.backend() == UpscalerBackend.FSR ? s.mode() : null, s.fsrVersion(),
            s.backend() == UpscalerBackend.FSR1 ? s.mode() : null, "autotest_step_" + index);
        VulkanRtxManager.applyResolutionScalingNow(); // same as the settings page after a backend change
        ctx.log("scenario", name(), "step", String.valueOf(index), "backend", s.backend().name(),
            "mode", String.valueOf(s.mode()), "percent", String.valueOf(s.percent()), "outcome", outcome.name(),
            "frame", String.valueOf(ctx.frames()));
        if (outcome == UpscalerSwitch.Outcome.DEFERRED_RESTART) {
            ctx.fail("upscaler_switch step " + index + " " + s + " needs a restart: device requirements not registered", null);
            return;
        }
        long dispatchesBefore = SpatialUpscaler.dispatches();
        ctx.waitFrames(FRAMES_PER_STEP, () -> check(ctx, steps, index, s, dispatchesBefore));
    }

    private void check(AutotestContext ctx, List<Step> steps, int index, Step s, long dispatchesBefore) {
        UpscalerBackend active = TemporalUpscaler.active();
        UpscalerBackend spatial = SpatialUpscaler.selected();
        String activeMode = active == null ? "none" : TemporalUpscaler.modeName();
        var world = WorldTargetManager.getWorldTarget();
        String worldSize = world == null ? "-" : world.width + "x" + world.height;
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state",
            "expected", s.toString(), "active", String.valueOf(active), "active_mode", activeMode,
            "spatial", String.valueOf(spatial), "spatial_dispatches", String.valueOf(SpatialUpscaler.dispatches()),
            "xess_state", XessRuntime.state().name(), "dlss_state", DlssRuntime.state().name(),
            "fsr_state", FsrRuntime.state().name(), "fsr_provider", String.valueOf(FsrRuntime.activeProvider()),
            "input", active == null ? "-" : TemporalUpscaler.inputWidth() + "x" + TemporalUpscaler.inputHeight(),
            "output", active == null ? "-" : TemporalUpscaler.outputWidth() + "x" + TemporalUpscaler.outputHeight(),
            "world_target", worldSize);
        boolean ok;
        if (s.backend() == UpscalerBackend.BILINEAR) {
            ok = active == null && spatial == null;
        } else if (s.backend().isSpatial()) {
            // the spatial path ran during this step (dispatch counter grew and the backend is still selected, i.e. not FAILED)
            ok = active == null && spatial == s.backend() && SpatialUpscaler.dispatches() > dispatchesBefore;
        } else {
            // custom requests run under a base vendor mode (UpscalerRequest.build): compare the backend only
            ok = active == s.backend() && (s.mode() == UpscalerMode.CUSTOM || s.mode().name().equals(activeMode));
        }
        if (!ok) {
            ctx.fail("upscaler_switch step " + index + " expected " + s + " but active=" + active + " mode=" + activeMode
                + " spatial=" + spatial + " (xess=" + XessRuntime.state() + ", dlss=" + DlssRuntime.state() + ", fsr=" + FsrRuntime.state() + ")", null);
            return;
        }
        if (s.backend() == UpscalerBackend.FSR && s.fsrVersion() != FsrVersion.AUTO
            && !s.fsrVersion().matches(FsrRuntime.activeProvider())) {
            ctx.fail("upscaler_switch step " + index + " expected FSR provider " + s.fsrVersion() + " but got " + FsrRuntime.activeProvider(), null);
            return;
        }
        ctx.shot(String.format(Locale.ROOT, "%02d_%s.png", index, s.label()), () -> step(ctx, steps, index + 1));
    }
}
