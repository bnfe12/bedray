package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPackOptions;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackDiscovery;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** Compiled graph per program folder ({@code ""} = root); a pack with world folders has one per used folder. */
    private volatile Map<String, IrisPassGraph> graphs = Map.of();
    private volatile String activeFolder = "";
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

    /** Compiled pass graph of the current dimension's folder (null when probe was not READY, after teardown, or when that
     * dimension's folder failed to build). */
    public IrisPassGraph activeGraph() {
        return graphs.get(activeFolder);
    }

    /** Program folder of the current dimension ({@code ""} = root). */
    public String activeFolder() {
        return activeFolder;
    }

    /**
     * Selects the program folder Iris would use for {@code dimensionId} (e.g. {@code minecraft:the_nether}).
     * @return true when the active graph changed, so the caller must retire resources built for the previous one
     */
    public boolean selectDimension(String dimensionId) {
        PackReport report = lastReport;
        if (report == null || dimensionId == null) return false;
        String folder = IrisPassGraph.folderFor(report, dimensionId);
        if (folder.equals(activeFolder)) return false;
        activeFolder = folder;
        return true;
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
        return activeGraph() != null ? ShaderHostState.READY : ShaderHostState.NOT_INSTALLED;
    }

    public void setStatusForTesting(ShaderHostStatus status) {
        this.lastStatus = status;
    }

    public void setActiveGraphForTesting(IrisPassGraph graph) {
        this.graphs = graph == null ? Map.of() : Map.of(graph.folder(), graph);
        this.activeFolder = graph == null ? "" : graph.folder();
    }

    /** Releases the compiled pass graphs and cached probe state. */
    public void teardown() {
        graphs = Map.of();
        activeFolder = "";
        lastReport = null;
        lastStatus = null;
    }

    @Override
    public ShaderHostStatus probe(ShaderHostContext ctx) {
        try { lastStatus = doProbe(ctx); }
        catch (RuntimeException | LinkageError error) {
            graphs = Map.of();
            lastStatus = status(ShaderHostState.COMPILE_FAILED, "Shader host initialization failed: " + error.getMessage());
        }
        return lastStatus;
    }

    private ShaderHostStatus doProbe(ShaderHostContext ctx) {
        lastReport = null;
        graphs = Map.of();
        activeFolder = "";
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
        String primary = IrisPassGraph.folderFor(report, IrisPassGraph.OVERWORLD);
        Map<String, IrisPassGraph> built = new LinkedHashMap<>();
        List<String> otherFailures = new ArrayList<>();
        try (PackSource src = PackSource.open(pack, ScanLimits.defaults())) {
            PackReport scanned = ShaderpackScanner.scan(src);
            IrisPackOptions.Request request = IrisPackOptions.readRequest(pack);
            List<String> folders = new ArrayList<>(IrisPassGraph.usedFolders(scanned));
            folders.remove(primary);
            folders.addFirst(primary);
            for (String folder : folders) {
                IrisPassGraph.ParseResult result = IrisPassGraph.build(src, scanned, request, folder);
                if (result.success()) {
                    built.put(folder, result.graph());
                } else if (folder.equals(primary)) {
                    // The overworld folder is the pack's main program set; without it the host stays native.
                    return result.status();
                } else {
                    otherFailures.add(folderLabel(folder) + ": " + result.status().detail());
                }
            }
        } catch (IOException e) {
            return status(ShaderHostState.UNSUPPORTED_PACK_FEATURES, "pack refused while building: " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof SpirvCompiler.SpirvCompileException sce) {
                return status(ShaderHostState.COMPILE_FAILED, sce.errorLog());
            }
            return status(ShaderHostState.COMPILE_FAILED, e.getMessage());
        }

        graphs = Map.copyOf(built);
        activeFolder = primary;
        IrisPassGraph graph = built.get(primary);
        return ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY,
            "pack " + report.name() + " (" + graph.passCount() + " passes in " + folderLabel(primary) + ", folders "
                + built.keySet().stream().map(IrisShaderHost::folderLabel).toList() + ", "
                + graph.options().changedOptions() + " changed options, profile="
                + (graph.options().matchedProfile() == null ? "custom" : graph.options().matchedProfile())
                + ") compiled; in-game execution is not verified"
                + (otherFailures.isEmpty() ? "" : "; not built: " + String.join("; ", otherFailures))
                + (graph.options().diagnostics().isEmpty() ? "" : "; " + String.join("; ", graph.options().diagnostics())));
    }

    private static String folderLabel(String folder) {
        return folder.isEmpty() ? "shaders/" : folder + "/";
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
