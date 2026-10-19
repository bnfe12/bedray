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
 * Today nothing reads {@link #active()} in the render path: native is the only executor and rt/world runs unchanged.
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

    public static synchronized void setSelectorForTesting(ShaderHostSelector sel, ShaderHostSelector.Selection seln) {
        selector = sel;
        selection = seln;
    }

    public static synchronized void resetForTesting() {
        selector = null;
        selection = null;
    }
}
