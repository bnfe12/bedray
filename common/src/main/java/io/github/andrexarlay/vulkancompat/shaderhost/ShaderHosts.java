package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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

    private ShaderHosts() {}

    public static synchronized ShaderHostSelector.Selection bootstrap(Path gameDir) {
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
    }
}
