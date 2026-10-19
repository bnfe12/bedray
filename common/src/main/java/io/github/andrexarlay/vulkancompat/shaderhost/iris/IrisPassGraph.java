package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostStatus;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IncludeExpander;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisGlslTranslator;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackScanner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage I3-lite (Step A scope): pass graph parser and validator for Iris fullscreen raster passes.
 * <p>
 * Discovers and orders all {@code composite*.fsh/vsh} and {@code final.fsh/vsh} passes from the shaders root folder.
 * If the pack requires features outside Step A (such as gbuffers, shadow passes, deferred passes, compute shaders,
 * geometry/tessellation shaders, dimension folders, SSBO, custom images/textures, or ray tracing), validation produces
 * an honest {@link ShaderHostState#UNSUPPORTED_PACK_FEATURES} status with details.
 * <p>
 * For supported packs, this class expands {@code #include}s, translates GLSL to Vulkan GLSL 450 via {@link IrisGlslTranslator},
 * and compiles shaders to SPIR-V bytecode via {@link SpirvCompiler}.
 */
public final class IrisPassGraph {

    private static final Pattern COMPOSITE_PATTERN = Pattern.compile("^composite(\\d*)(_[a-z])?$");
    private static final Pattern FINAL_PATTERN = Pattern.compile("^final(_[a-z])?$");
    private static final Pattern VEC2_TEXCOORD = Pattern.compile("\\b(?:varying|in)\\s+vec2\\s+texcoord\\b");

    /**
     * Represents a single fullscreen raster pass in the graph.
     */
    public record Pass(
        String name,
        ProgramName.Family family,
        int orderIndex,
        String vertexPath,
        String fragmentPath,
        String vertexSource,
        String fragmentSource,
        IrisGlslTranslator.Result vertexTranslation,
        IrisGlslTranslator.Result fragmentTranslation,
        ByteBuffer vertexSpirv,
        ByteBuffer fragmentSpirv
    ) {
        public boolean isFinal() {
            return family == ProgramName.Family.FINAL;
        }

        public List<Integer> drawBuffers() {
            return fragmentTranslation.drawBuffers();
        }

        public Set<String> allPushUsed() {
            Set<String> set = new TreeSet<>(vertexTranslation.pushUsed());
            set.addAll(fragmentTranslation.pushUsed());
            return Collections.unmodifiableSet(set);
        }

        public Set<Integer> allSamplerBindings() {
            Set<Integer> set = new TreeSet<>(vertexTranslation.samplerBindings());
            set.addAll(fragmentTranslation.samplerBindings());
            return Collections.unmodifiableSet(set);
        }
    }

    /**
     * Result of building or validating an {@link IrisPassGraph}.
     */
    public record ParseResult(
        boolean success,
        ShaderHostStatus status,
        IrisPassGraph graph
    ) {}

    private final String packName;
    private final String shadersRoot;
    private final List<Pass> passes;

    public IrisPassGraph(String packName, String shadersRoot, List<Pass> passes) {
        this.packName = Objects.requireNonNull(packName, "packName");
        this.shadersRoot = Objects.requireNonNull(shadersRoot, "shadersRoot");
        this.passes = List.copyOf(passes);
    }

    public String packName() {
        return packName;
    }

    public String shadersRoot() {
        return shadersRoot;
    }

    public List<Pass> passes() {
        return passes;
    }

    public int passCount() {
        return passes.size();
    }

    public List<Pass> compositePasses() {
        return passes.stream().filter(p -> p.family() == ProgramName.Family.COMPOSITE).toList();
    }

    public Pass finalPass() {
        return passes.stream().filter(Pass::isFinal).findFirst().orElse(null);
    }

    /**
     * Validates that the pack's declared structure and programs are supported within the Step A scope.
     * Returns {@link ShaderHostState#READY} if supported, or {@link ShaderHostState#UNSUPPORTED_PACK_FEATURES}
     * with an explanatory message if unsupported features are present.
     */
    public static ShaderHostStatus validate(PackReport report) {
        Objects.requireNonNull(report, "report");
        List<String> unsupported = new ArrayList<>();

        // 1. Dimension folders: Step A only supports the root shaders/ folder.
        List<String> nonRootFolders = report.dimensions().keySet().stream().filter(f -> !f.isEmpty()).toList();
        if (!nonRootFolders.isEmpty()) {
            unsupported.add("dimension folders " + nonRootFolders + " (step A supports root shaders/ only)");
        }

        // 2. Ray tracing: not supported in raster step A.
        if (report.rtModel() != PackReport.RtModel.NONE) {
            unsupported.add("ray tracing (" + report.rtModel() + ")");
        }
        if (!report.rtDispatch().isEmpty()) {
            unsupported.add("vulkanite rt passes " + report.rtDispatch().keySet());
        }

        // 3. Storage buffers and custom images/textures.
        if (!report.bufferObjects().isEmpty() || report.requiredFeatures().contains("SSBO")) {
            unsupported.add("SSBO buffer objects " + report.bufferObjects());
        }
        if (!report.customImages().isEmpty() || report.requiredFeatures().contains("CUSTOM_IMAGES")) {
            unsupported.add("custom images " + report.customImages());
        }
        if (!report.customTextures().isEmpty()) {
            unsupported.add("custom textures " + report.customTextures());
        }

        // 4. Programs in root folder: only COMPOSITE and FINAL are allowed.
        SortedMap<String, Set<ProgramName.Stage>> rootPrograms = report.dimensions().get("");
        if (rootPrograms == null || rootPrograms.isEmpty()) {
            unsupported.add("no programs found in shaders root");
        } else {
            List<String> forbiddenPrograms = new ArrayList<>();
            List<String> forbiddenStages = new ArrayList<>();
            List<String> missingFragment = new ArrayList<>();
            for (Map.Entry<String, Set<ProgramName.Stage>> entry : rootPrograms.entrySet()) {
                String prog = entry.getKey();
                Set<ProgramName.Stage> stages = entry.getValue();
                ProgramName pn = null;
                for (ProgramName.Stage st : stages) {
                    pn = ProgramName.classify(prog + "." + st.ext);
                    if (pn != null) break;
                }
                if (pn == null || (pn.family() != ProgramName.Family.COMPOSITE && pn.family() != ProgramName.Family.FINAL)) {
                    forbiddenPrograms.add(prog + " (" + (pn == null ? "unknown" : pn.family()) + ")");
                }
                for (ProgramName.Stage st : stages) {
                    if (st != ProgramName.Stage.VERTEX && st != ProgramName.Stage.FRAGMENT) {
                        forbiddenStages.add(prog + "." + st.ext);
                    }
                }
                if (!stages.contains(ProgramName.Stage.FRAGMENT)) {
                    missingFragment.add(prog);
                }
            }
            if (!forbiddenPrograms.isEmpty()) {
                unsupported.add("unsupported programs: " + forbiddenPrograms + " (step A supports composite*.fsh/vsh and final.fsh/vsh only)");
            }
            if (!forbiddenStages.isEmpty()) {
                unsupported.add("unsupported shader stages: " + forbiddenStages);
            }
            if (!missingFragment.isEmpty()) {
                unsupported.add("programs missing fragment shader (.fsh): " + missingFragment);
            }
        }

        if (!unsupported.isEmpty()) {
            return ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                "pack " + report.name() + " has unsupported features: " + String.join("; ", unsupported));
        }

        return ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY, "pack structure is supported for step A");
    }

    /**
     * Builds and compiles the {@link IrisPassGraph} from a {@link PackSource} and pre-scanned {@link PackReport}.
     */
    public static ParseResult build(PackSource src, PackReport report) throws IOException {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(report, "report");

        // 1. Initial structural validation
        ShaderHostStatus validation = validate(report);
        if (validation.state() != ShaderHostState.READY) {
            return new ParseResult(false, validation, null);
        }

        SortedMap<String, Set<ProgramName.Stage>> rootPrograms = report.dimensions().get("");
        if (rootPrograms == null || rootPrograms.isEmpty()) {
            return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                "pack " + report.name() + " has no programs"), null);
        }

        // 2. Discover and sort composite and final passes
        List<String> compositeNames = new ArrayList<>();
        List<String> finalNames = new ArrayList<>();

        for (String prog : rootPrograms.keySet()) {
            Matcher mc = COMPOSITE_PATTERN.matcher(prog);
            if (mc.matches()) {
                compositeNames.add(prog);
                continue;
            }
            Matcher mf = FINAL_PATTERN.matcher(prog);
            if (mf.matches()) {
                finalNames.add(prog);
            }
        }

        compositeNames.sort((a, b) -> {
            Matcher ma = COMPOSITE_PATTERN.matcher(a);
            Matcher mb = COMPOSITE_PATTERN.matcher(b);
            ma.matches();
            mb.matches();
            int numA = ma.group(1).isEmpty() ? 0 : Integer.parseInt(ma.group(1));
            int numB = mb.group(1).isEmpty() ? 0 : Integer.parseInt(mb.group(1));
            int cmp = Integer.compare(numA, numB);
            if (cmp != 0) return cmp;
            String subA = ma.group(2) == null ? "" : ma.group(2);
            String subB = mb.group(2) == null ? "" : mb.group(2);
            cmp = subA.compareTo(subB);
            return cmp != 0 ? cmp : a.compareTo(b);
        });

        finalNames.sort((a, b) -> {
            Matcher ma = FINAL_PATTERN.matcher(a);
            Matcher mb = FINAL_PATTERN.matcher(b);
            ma.matches();
            mb.matches();
            String subA = ma.group(1) == null ? "" : ma.group(1);
            String subB = mb.group(1) == null ? "" : mb.group(1);
            int cmp = subA.compareTo(subB);
            return cmp != 0 ? cmp : a.compareTo(b);
        });

        List<String> orderedPasses = new ArrayList<>(compositeNames);
        orderedPasses.addAll(finalNames);

        if (orderedPasses.isEmpty()) {
            return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                "pack " + report.name() + " has no composite or final passes"), null);
        }

        // 3. Translate and compile each pass in sequence
        IncludeExpander expander = new IncludeExpander(report.shadersRoot(), entry -> new String(src.read(entry), StandardCharsets.UTF_8));
        List<Pass> passes = new ArrayList<>();
        int passOrder = 0;

        for (String passName : orderedPasses) {
            ProgramName.Family family = passName.startsWith("final") ? ProgramName.Family.FINAL : ProgramName.Family.COMPOSITE;
            String fshPath = report.shadersRoot() + passName + ".fsh";

            if (!src.entries().contains(fshPath)) {
                return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                    "pass " + passName + " is missing required fragment shader " + fshPath), null);
            }

            // Expand fragment shader
            String expandedFsh;
            try {
                expandedFsh = expander.expand(fshPath);
            } catch (PackScanException e) {
                return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                    "failed expanding " + fshPath + ": " + e.getMessage()), null);
            }

            // Expand or synthesize vertex shader
            String vshPath = report.shadersRoot() + passName + ".vsh";
            String expandedVsh;
            if (src.entries().contains(vshPath)) {
                try {
                    expandedVsh = expander.expand(vshPath);
                } catch (PackScanException e) {
                    return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                        "failed expanding " + vshPath + ": " + e.getMessage()), null);
                }
            } else {
                expandedVsh = defaultFullscreenVertexShader(expandedFsh);
                vshPath = vshPath + " [default]";
            }

            // Translate vertex shader to Vulkan GLSL 450
            Map<String, Integer> varyingLocations = new LinkedHashMap<>();
            IrisGlslTranslator.Result vResult = IrisGlslTranslator.translate(expandedVsh, IrisGlslTranslator.Stage.VERTEX,
                varyingLocations, passName + ".vsh");
            if (!vResult.unsupported().isEmpty()) {
                return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                    passName + ".vsh unsupported features: " + vResult.unsupported()), null);
            }

            // Translate fragment shader to Vulkan GLSL 450 (using vertex varyings)
            IrisGlslTranslator.Result fResult = IrisGlslTranslator.translate(expandedFsh, IrisGlslTranslator.Stage.FRAGMENT,
                vResult.varyings(), passName + ".fsh");
            if (!fResult.unsupported().isEmpty()) {
                return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.UNSUPPORTED_PACK_FEATURES,
                    passName + ".fsh unsupported features: " + fResult.unsupported()), null);
            }

            // Compile vertex to SPIR-V
            ByteBuffer vSpv;
            try {
                vSpv = SpirvCompiler.compile(vResult.source(), IrisGlslTranslator.Stage.VERTEX, passName + ".vsh");
            } catch (SpirvCompiler.SpirvCompileException e) {
                return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.COMPILE_FAILED,
                    passName + ".vsh compile error: " + e.errorLog()), null);
            }

            // Compile fragment to SPIR-V
            ByteBuffer fSpv;
            try {
                fSpv = SpirvCompiler.compile(fResult.source(), IrisGlslTranslator.Stage.FRAGMENT, passName + ".fsh");
            } catch (SpirvCompiler.SpirvCompileException e) {
                return new ParseResult(false, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.COMPILE_FAILED,
                    passName + ".fsh compile error: " + e.errorLog()), null);
            }

            passes.add(new Pass(passName, family, passOrder++, vshPath, fshPath, expandedVsh, expandedFsh,
                vResult, fResult, vSpv, fSpv));
        }

        IrisPassGraph graph = new IrisPassGraph(report.name(), report.shadersRoot(), passes);
        return new ParseResult(true, ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY,
            "all " + passes.size() + " passes (" + orderedPasses + ") translated and compiled to SPIR-V"), graph);
    }

    /**
     * Convenience method to scan and build directly from a {@link PackSource}.
     */
    public static ParseResult build(PackSource src) throws IOException {
        PackReport report = ShaderpackScanner.scan(src);
        return build(src, report);
    }

    /**
     * Convenience method to open, scan, and build from a filesystem {@link Path}.
     */
    public static ParseResult build(Path packPath, ScanLimits limits) throws IOException {
        try (PackSource src = PackSource.open(packPath, limits)) {
            return build(src);
        }
    }

    /**
     * Synthesizes the default fullscreen vertex shader according to the OptiFine/Iris specification,
     * ensuring varying texcoord matches the dimension used by the fragment shader.
     */
    public static String defaultFullscreenVertexShader(String fragmentSource) {
        String cleanFsh = IrisGlslTranslator.stripCommentsPreservingLines(fragmentSource);
        boolean isVec2 = VEC2_TEXCOORD.matcher(cleanFsh).find();
        if (isVec2) {
            return String.join("\n",
                "#version 120",
                "varying vec2 texcoord;",
                "void main() {",
                "    gl_Position = ftransform();",
                "    texcoord = gl_MultiTexCoord0.xy;",
                "}",
                "");
        }
        return String.join("\n",
            "#version 120",
            "varying vec4 texcoord;",
            "void main() {",
            "    gl_Position = ftransform();",
            "    texcoord = gl_MultiTexCoord0;",
            "}",
            "");
    }
}
