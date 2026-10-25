package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackDiscovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compatibility host for Iris/OptiFine-style shaderpacks. Validates pack structure, parses fullscreen composite and final
 * passes, and compiles them to SPIR-V bytecode via {@link IrisPassGraph}.
 */
@Experimental("I3")
public final class IrisShaderHost implements ShaderHost {
    private final PackReportCache cache;
    private volatile PackReport lastReport;
    private volatile IrisPassGraph activeGraph;
    private volatile ShaderHostStatus lastStatus;

    public IrisShaderHost(PackReportCache cache) {
        this.cache = cache;
    }

    @Override
    public ShaderHostKind kind() {
        return ShaderHostKind.IRIS;
    }

    /** Report of the pack seen by the last probe (null when none was read). */
    public PackReport lastReport() {
        return lastReport;
    }

    /** Currently active and compiled pass graph (null when probe was not READY or after teardown). */
    public IrisPassGraph activeGraph() {
        return activeGraph;
    }

    /** Last probe status (null before first probe or after teardown). */
    public ShaderHostStatus lastStatus() {
        return lastStatus;
    }

    /** Current state of this host (READY if activeGraph is present, else state from lastStatus or NOT_INSTALLED). */
    public ShaderHostState state() {
        if (lastStatus != null) {
            return lastStatus.state();
        }
        return activeGraph != null ? ShaderHostState.READY : ShaderHostState.NOT_INSTALLED;
    }

    public void setStatusForTesting(ShaderHostStatus status) {
        this.lastStatus = status;
    }

    public void setActiveGraphForTesting(IrisPassGraph graph) {
        this.activeGraph = graph;
    }

    /** Releases the active pass graph and cached probe state. */
    public void teardown() {
        activeGraph = null;
        lastReport = null;
        lastStatus = null;
    }

    @Override
    public ShaderHostStatus probe(ShaderHostContext ctx) {
        try { lastStatus = doProbe(ctx); }
        catch (RuntimeException | LinkageError error) {
            activeGraph = null;
            lastStatus = status(ShaderHostState.COMPILE_FAILED, "Shader host initialization failed: " + error.getMessage());
        }
        return lastStatus;
    }

    private ShaderHostStatus doProbe(ShaderHostContext ctx) {
        lastReport = null;
        activeGraph = null;
        Optional<String> choice;
        try {
            choice = IrisPackChoice.read(ctx.gameDir());
        } catch (IOException e) {
            return status(ShaderHostState.NOT_INSTALLED, "config/iris.properties unreadable: " + e);
        }
        if (choice.isEmpty()) {
            return status(ShaderHostState.NOT_INSTALLED,
                "no shaderpack chosen (config/iris.properties enableShaders/shaderPack, or -D" + IrisPackChoice.PACK_PROPERTY + ")");
        }
        Path pack;
        try {
            pack = ShaderpackDiscovery.resolveChoice(ctx.shaderpacksDir(), choice.get());
        } catch (PackScanException e) {
            return status(ShaderHostState.NOT_INSTALLED, e.getMessage());
        }
        if (!Files.exists(pack)) {
            return status(ShaderHostState.NOT_INSTALLED, "shaderpack not found: shaderpacks/" + choice.get());
        }
        PackReport report;
        try {
            report = cache.get(pack);
        } catch (IOException e) {
            return status(ShaderHostState.UNSUPPORTED_PACK_FEATURES, "pack refused while reading: " + e.getMessage());
        }
        lastReport = report;
        if (ctx.deviceExtensions().isPresent()) {
            Set<String> missing = new TreeSet<>(report.extraDeviceExtensions());
            missing.removeAll(ctx.deviceExtensions().get());
            if (!missing.isEmpty()) {
                return status(ShaderHostState.DEVICE_UNSUPPORTED, "pack " + report.name() + " needs " + missing + " (not on this device)");
            }
        }
        ShaderHostStatus validation = IrisPassGraph.validate(report);
        if (validation.state() != ShaderHostState.READY) {
            return validation;
        }

        if (!Boolean.getBoolean("bedray.iris.post"))
            return status(ShaderHostState.DISABLED_FOR_SESSION,
                "Post-only Vulkan host is opt-in: -Dbedray.iris.post=true; no general Iris compatibility claim");

        io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisStandardMacros.configure(ctx.gameDir());
        IrisPassGraph.ParseResult result;
        try {
            result = IrisPassGraph.build(pack, ScanLimits.defaults());
        } catch (IOException e) {
            return status(ShaderHostState.UNSUPPORTED_PACK_FEATURES, "pack refused while building: " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof SpirvCompiler.SpirvCompileException sce) {
                return status(ShaderHostState.COMPILE_FAILED, sce.errorLog());
            }
            return status(ShaderHostState.COMPILE_FAILED, e.getMessage());
        }

        if (!result.success()) {
            return result.status();
        }

        IrisPassGraph graph = result.graph();
        activeGraph = graph;
        return ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY,
            "pack " + report.name() + " (" + graph.passCount() + " passes, " + graph.options().changedOptions()
                + " changed options, profile=" + (graph.options().matchedProfile() == null ? "custom" : graph.options().matchedProfile())
                + ") compiled; in-game execution is not verified"
                + (graph.options().diagnostics().isEmpty() ? "" : "; " + String.join("; ", graph.options().diagnostics())));
    }

    @Override
    public Set<String> extraDeviceExtensions() {
        PackReport r = lastReport;
        return r == null ? Set.of() : r.extraDeviceExtensions();
    }

    private static ShaderHostStatus status(ShaderHostState state, String detail) {
        return ShaderHostStatus.of(ShaderHostKind.IRIS, state, detail);
    }
}
