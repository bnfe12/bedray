package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Session state of the shader-host selector. {@link #bootstrap} runs once from RtxBootstrap's early hook (before device
 * creation, no Minecraft class touched) and logs the decision:
 * <pre>[VKB-SHADERHOST] host=native state=READY requested=native detail="..."</pre>
 * The selected Iris host supplies an optional fullscreen post stage after the world renderer.
 * A READY pack probe means compilation succeeded; runtime command/presentation state is separate.
 */
@Experimental("I0")
public final class ShaderHosts {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/ShaderHost");

    private static ShaderHostSelector selector;
    private static ShaderHostSelector.Selection selection;
    private static Path gameDir;
    private static volatile ShaderHostKind pendingRequest;

    private ShaderHosts() {}

    public static synchronized ShaderHostSelector.Selection bootstrap(Path gameDir) {
        ShaderHosts.gameDir = gameDir;
        ShaderHostConfig.readFrom(io.github.andrexarlay.vulkancompat.rtx.config.ConfigFiles.resolve(gameDir.resolve("config").resolve("bedray.toml")));
        IrisShaderHost iris = new IrisShaderHost(new PackReportCache(ScanLimits.DEFAULT));
        selector = new ShaderHostSelector(new NativeShaderHost(), iris);
        selection = selector.select(ShaderHostConfig.requested(), ShaderHostContext.beforeDevice(gameDir));
        LOGGER.info(selection.logLine());
        PackReport r = iris.lastReport();
        if (r != null) {
            LOGGER.info("[VKB-SHADERHOST] {}", r.summary());
            for (String w : r.plannedWork()) {
                LOGGER.info("[VKB-SHADERHOST] pack=\"{}\" needs {}", r.name(), w);
            }
            for (String w : r.warnings()) {
                LOGGER.warn("[VKB-SHADERHOST] pack=\"{}\" warning: {}", r.name(), w);
            }
        }
        return selection;
    }

    /** Active host of this session (NATIVE until bootstrap ran). */
    public static synchronized ShaderHostKind active() {
        return selection == null ? ShaderHostKind.NATIVE : selection.active();
    }

    public static synchronized ShaderHostSelector.Selection selection() {
        return selection;
    }

    public static synchronized IrisShaderHost iris() {
        if (selector == null) {
            return null;
        }
        ShaderHost h = selector.host(ShaderHostKind.IRIS);
        return h instanceof IrisShaderHost irisHost ? irisHost : null;
    }

    public static synchronized ShaderHostSelector selector() {
        return selector;
    }

    /** A compiled graph is not an active renderer after a runtime failure. Preserve the reason on fallback. */
    public static synchronized void noteIrisFailure(String reason) {
        if (selector == null || selection == null) return;
        selector.disableForSession(ShaderHostKind.IRIS, reason);
        ShaderHostStatus failure = ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.DISABLED_FOR_SESSION, reason);
        selection = new ShaderHostSelector.Selection(selection.requested(), failure, ShaderHostKind.NATIVE,
            ShaderHostStatus.of(ShaderHostKind.NATIVE, ShaderHostState.READY, "Native image retained after Iris failure"));
        LOGGER.warn(selection.logLine());
    }

    public static synchronized void setSelectorForTesting(ShaderHostSelector sel, ShaderHostSelector.Selection seln) {
        selector = sel;
        selection = seln;
    }

    public static synchronized void resetForTesting() {
        selector = null;
        selection = null;
        gameDir = null;
        pendingRequest = null;
    }

    /** Outcome of a frame-boundary host switch attempt ({@link #applyPendingRequest()}). */
    public record SwitchResult(Outcome outcome, ShaderHostKind active, String detail) {
        public enum Outcome { NOOP, LIVE, DEFERRED_RESTART, UNAVAILABLE, NOT_BOOTSTRAPPED }
    }

    /** UI-facing: record the user's host choice for the next frame boundary and persist it for restart. */
    public static synchronized void requestSwitch(ShaderHostKind target) {
        if (target == null) {
            return;
        }
        ShaderHostConfig.setPersisted(target);
        pendingRequest = target;
    }

    /** The host choice waiting to be applied at the next frame boundary (null when none). */
    public static synchronized ShaderHostKind pendingRequest() {
        return pendingRequest;
    }

    /**
     * Frame-head applier (render thread, before {@code RtCaptureControl.onFrameBegin} reads {@link #active()}): applies a
     * pending {@link #requestSwitch} atomically so a frame never mixes native and iris work. The live device is never
     * mutated: when the target needs device extensions the device lacks, the choice stays persisted and applies after
     * restart ({@link HostInvalidation}). Returns null when nothing is pending (the cheap per-frame path).
     */
    public static synchronized SwitchResult applyPendingRequest() {
        ShaderHostKind target = pendingRequest;
        if (target == null) {
            return null;
        }
        pendingRequest = null;
        if (selector == null || selection == null || gameDir == null) {
            return new SwitchResult(SwitchResult.Outcome.NOT_BOOTSTRAPPED, active(), "shader host not bootstrapped");
        }
        ShaderHostKind from = selection.active();
        if (target == from) {
            return new SwitchResult(SwitchResult.Outcome.NOOP, from, "already active");
        }
        ShaderHostContext ctx = new ShaderHostContext(gameDir, Optional.of(VulkanCapabilityChecker.getDeviceExtensions()));
        // Probe the target first: this compiles the Iris raster core and populates its device-extension needs.
        ShaderHostSelector.Selection next = selector.select(target, ctx);
        HostInvalidation.Plan plan = selector.planSwitch(from, target, ctx);
        if (plan.restartRequired()) {
            SwitchResult r = new SwitchResult(SwitchResult.Outcome.DEFERRED_RESTART, from,
                "needs device extensions " + plan.missingExtensions() + "; applies after restart");
            LOGGER.info("[VKB-SHADERHOST] switch {} -> {} deferred: {}", from.configName(), target.configName(), r.detail());
            return r;
        }
        if (next.active() != target) {
            SwitchResult r = new SwitchResult(SwitchResult.Outcome.UNAVAILABLE, from, next.requestedStatus().detail());
            LOGGER.warn("[VKB-SHADERHOST] switch {} -> {} unavailable: {}", from.configName(), target.configName(), r.detail());
            return r;
        }
        // Drop the old host's GPU resources while the device is alive, then swap. Native RT is torn down per-frame by
        // RtFrameRenderer.render's iris gate; the Iris executor must be released here before iris stops being active.
        if (from == ShaderHostKind.IRIS) {
            IrisRenderBridge.teardown();
            IrisRenderBridge.resetTiming();
        }
        selection = next;
        LOGGER.info("[VKB-SHADERHOST] live switch {} -> {}", from.configName(), target.configName());
        LOGGER.info(selection.logLine());
        return new SwitchResult(SwitchResult.Outcome.LIVE, target, "switched live");
    }
}
