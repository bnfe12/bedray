package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A validated Mosaic node graph: parse, port checks, kind resolution and topological order of
 * {@code shaders/bedray/mosaic.json}. Pure Java, unit-testable, no Vulkan calls.
 * <p>
 * {@link #analyze(String)} never throws: it reports every problem it finds (errors, warnings, hints, each with the
 * node, the port and the kind of quick fix that applies), the kinds it could resolve, the user functions and a cost
 * estimate; the graph itself is present only when there is no error. {@link #parse(String)} is the strict form used
 * by the host: the first error becomes an {@link IllegalArgumentException}.
 * <p>
 * Port kinds are float / vec2 / vec3 / vec4. Arithmetic operands and the scalar-safe unaries take any kind (vector
 * pairs must match or one side be a float); every other port takes exactly its declared kind; the output color takes
 * float, vec2, vec3 or vec4. {@code mosaic.trace} also exposes {@code pos} (vec3), {@code hit}, {@code inst} and
 * {@code prim} (float) as named outputs. User functions ({@code fn.<name>}) are typed by their signature.
 */
public final class MosaicGraph {

    /** Port data kinds, ordered so {@code ordinal()} compares width. */
    public enum Kind {
        F, V2, V3, V4,
        /** A Slang value that is not a float vector (a struct such as SurfaceHit, a matrix, a call token): connects only to its own type. */
        S;

        public int size() { return this == S ? 0 : ordinal() + 1; }

        public String glsl() { return switch (this) { case F -> "float"; case V2 -> "vec2"; case V3 -> "vec3"; case V4 -> "vec4"; case S -> "void"; }; }

        public String slang() { return switch (this) { case F -> "float"; case V2 -> "float2"; case V3 -> "float3"; case V4 -> "float4"; case S -> "void"; }; }

        public static Kind ofSize(int n) { return values()[Math.clamp(n, 1, 4) - 1]; }

        /** float / vec2..4 / float2..4, or null. */
        public static Kind named(String text) {
            if (text == null) return null;
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "float", "float1", "vec1" -> F;
                case "vec2", "float2" -> V2;
                case "vec3", "float3" -> V3;
                case "vec4", "float4" -> V4;
                default -> null;
            };
        }
    }

    /** One node of the graph. {@code index} is the push/buffer slot, or the vector size of {@code mosaic.vec}. */
    public record Node(String id, String type, Map<String, String> inputs, double[] value, int index, String mask,
                       Map<String, double[]> defaults) {
        public Node(String id, String type, Map<String, String> inputs, double[] value, int index, String mask) {
            this(id, type, inputs, value, index, mask, Map.of());
        }

        /** The inline value of an unwired port (Blender-style), or null. */
        public double[] defaultOf(String port) { return defaults.get(port); }

        public boolean clampFlag() { return (index & 1) != 0; }
    }

    /** One port of one node type. */
    public record Port(String name, Kind kind, boolean required, String slangType) {
        public Port(String name, Kind kind, boolean required) { this(name, kind, required, null); }
    }

    public enum Severity { ERROR, WARNING, HINT }

    /** The quick fix an editor can offer for a problem. */
    public enum Fix { NONE, CONNECT, CONVERT, ADD_OUTPUT, DELETE_NODE, EDIT_CODE, CHANGE_PARAM }

    /** One finding. {@code message} is the full readable text (it names the node). */
    public record Problem(Severity severity, String node, String port, String message, Fix fix) {}

    /** Static cost per pixel of the nodes that reach the output (after optimization when computed by the codegen). */
    public record Cost(int rays, int samples, int ops, int nodes) {
        /** A rough relative weight: a ray query ~ 40 ALU ops, a sample ~ 4. */
        public int weight() { return rays * 40 + samples * 4 + ops; }
    }

    /** Everything an editor needs; {@code graph} is null when there is any error. */
    public record Analysis(MosaicGraph graph, List<Problem> problems, Map<String, Kind> kinds,
                           Map<String, MosaicFunction> functions, Set<String> reachable, Cost cost) {
        public boolean ok() { return graph != null; }

        public List<Problem> errors() { return problems.stream().filter(p -> p.severity() == Severity.ERROR).toList(); }

        public List<Problem> warnings() { return problems.stream().filter(p -> p.severity() == Severity.WARNING).toList(); }

        public String firstError() {
            for (Problem p : problems) if (p.severity() == Severity.ERROR) return p.message();
            return null;
        }

        /** Problems of one node, worst first. */
        public List<Problem> of(String nodeId) {
            List<Problem> out = new ArrayList<>();
            for (Severity s : Severity.values())
                for (Problem p : problems) if (s == p.severity() && nodeId.equals(p.node())) out.add(p);
            return out;
        }
    }

    private static final Set<String> KNOWN = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
        // sources
        "mosaic.uv", "mosaic.texel", "mosaic.size", "mosaic.time", "mosaic.frame", "mosaic.eye",
        // fixed stage resources
        "mosaic.sceneColor", "mosaic.sceneDepth", "mosaic.lut", "mosaic.buffer", "mosaic.push",
        // ray tracing
        "mosaic.trace", "mosaic.shadow",
        // surface and fluids (host fluid volume, binding 15)
        "mosaic.position", "mosaic.normal", "mosaic.sunDir", "mosaic.worldPos", "mosaic.waterDepth", "mosaic.waterMask", "mosaic.lavaMask", "mosaic.underwater",
        // world volume (binding 15): materials and light
        "mosaic.hitMaterial", "mosaic.materialMask", "mosaic.materialDepth", "mosaic.skyLight", "mosaic.blockLight",
        "mosaic.cameraMaterial",
        // groups / rewrite
        "mosaic.const", "mosaic.swizzle", "mosaic.vec",
        // infix math
        "mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div",
        // comparisons (float operands, 1.0/0.0 result)
        "mosaic.gt", "mosaic.lt",
        // two-operand functions
        "mosaic.min", "mosaic.max", "mosaic.mix", "mosaic.clamp", "mosaic.step",
        "mosaic.dot", "mosaic.cross", "mosaic.reflect",
        // one-operand functions
        "mosaic.neg", "mosaic.floor", "mosaic.fract", "mosaic.sqrt", "mosaic.exp",
        "mosaic.abs", "mosaic.saturate", "mosaic.normalize", "mosaic.length", "mosaic.lum",
        // Blender-style utility nodes
        "mosaic.math", "mosaic.mixColor", "mosaic.mapRange", "mosaic.colorRamp", "mosaic.reroute",
        "mosaic.vectorMath", "mosaic.separate", "mosaic.hueSat", "mosaic.brightContrast", "mosaic.gamma", "mosaic.invert",
        "mosaic.fresnel", "mosaic.mapping", "mosaic.smoothstep", "mosaic.blackbody",
        // procedural textures
        "mosaic.noise", "mosaic.voronoi", "mosaic.whiteNoise", "mosaic.checker", "mosaic.wave", "mosaic.gradient",
        // multi-pass: write a named buffer, read it (any uv), the previous frame; weather and sky (binding 16)
        "mosaic.pass", "mosaic.bufferIn", "mosaic.history", "mosaic.rain", "mosaic.thunder", "mosaic.temperature", "mosaic.skyColor",
        "mosaic.fogColor",
        // Bedray's renderer as nodes (MosaicRenderer): stages of the real pipeline and the frame they produce
        "bedray.scene", "bedray.materials", "bedray.sky", "bedray.pathTracer", "bedray.restirDi", "bedray.restirPt", "bedray.radianceCascades", "bedray.rays", "bedray.fog", "bedray.denoiser", "bedray.exposure", "bedray.post", "bedray.voxels", "bedray.raster", "bedray.upscaler", "bedray.output", "bedray.stageMap", "user.post", "user.hdr", "user.sky",
        // the Slang pack map (MosaicPack): modules wired by their imports into the passes of a frame
        "slang.module", "slang.pack",
        // output
        "mosaic.output",
        // material graphs (baked on the CPU into LabPBR textures, see MosaicMaterial)
        "mat.texture", "mat.image", "mat.bsdf", "mat.output")));

    /** The output node of a shader graph ({@code mosaic.output}) or of a material graph ({@code mat.output}). */
    public static boolean isOutput(String type) {
        return type.equals("mosaic.output") || type.equals("mat.output") || type.equals(MosaicRenderer.OUTPUT) || type.equals("slang.pack")
            || type.equals(MosaicSlangGraph.RESULT) || type.equals(MosaicStageMap.OUTPUT);
    }

    /** The Slang pack map (output {@code slang.pack}): a view of Bedray's modules, never compiled. */
    /** A Bedray function graph (output {@code slang.result}): compiles to a Slang function, not a pass. */
    public boolean isFunctionGraph() { return node(outputId) != null && node(outputId).type().equals(MosaicSlangGraph.RESULT); }

    /** The inside of a renderer stage (output bedray.stageMap): a view, never compiled. */
    public boolean isStageMap() { return node(outputId) != null && node(outputId).type().equals(MosaicStageMap.OUTPUT); }

    public boolean isPackMap() { return node(outputId) != null && node(outputId).type().equals("slang.pack"); }

    /** A renderer graph (output {@code bedray.output}): Bedray's own stages and settings, never compiled into a shader. */
    public boolean isRenderer() { return node(outputId) != null && node(outputId).type().equals(MosaicRenderer.OUTPUT); }

    /** Whether the graph has Buffer nodes (it runs as several passes, see MosaicPasses). */
    public boolean multiPass() { return uses("mosaic.pass"); }

    /** Node types a material graph may use: CPU-evaluable, no scene or ray inputs. */
    public static final Set<String> MATERIAL_SAFE = Set.of("mosaic.uv", "mosaic.texel", "mosaic.size", "mosaic.const", "mosaic.swizzle",
        "mosaic.vec", "mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div", "mosaic.gt", "mosaic.lt", "mosaic.min", "mosaic.max",
        "mosaic.mix", "mosaic.clamp", "mosaic.step", "mosaic.dot", "mosaic.cross", "mosaic.reflect", "mosaic.neg", "mosaic.floor",
        "mosaic.fract", "mosaic.sqrt", "mosaic.exp", "mosaic.abs", "mosaic.saturate", "mosaic.normalize", "mosaic.length", "mosaic.lum",
        "mosaic.math", "mosaic.mixColor", "mosaic.mapRange", "mosaic.colorRamp", "mosaic.reroute", "mosaic.noise", "mosaic.voronoi",
        "mosaic.whiteNoise", "mosaic.checker", "mosaic.wave", "mosaic.gradient", "mat.texture", "mat.image", "mat.bsdf", "mat.output");

    private static final Map<String, List<Port>> PORTS = buildPorts();

    private static Map<String, List<Port>> buildPorts() {
        Map<String, List<Port>> m = new LinkedHashMap<>();
        m.put("mosaic.sceneColor", List.of(new Port("uv", Kind.V2, false)));
        m.put("mosaic.sceneDepth", List.of(new Port("uv", Kind.V2, false)));
        m.put("mosaic.lut", List.of(new Port("uv", Kind.V2, false)));
        m.put("mosaic.trace", List.of(
            new Port("origin", Kind.V3, false), new Port("direction", Kind.V3, false), new Port("tMax", Kind.F, false)));
        m.put("mosaic.shadow", List.of(new Port("direction", Kind.V3, false), new Port("origin", Kind.V3, false)));
        m.put("mosaic.output", List.of(new Port("color", Kind.V4, true)));
        for (MosaicRenderer.Stage s : MosaicRenderer.stages())
            m.put(s.type(), s.rank() == 0 ? List.of() : List.of(new Port("frame", Kind.V4, s.rank() >= 30)));
        // a stage of your own: its inside is a function graph run in Bedray's frame (user.post: after the tone curve and looks)
        for (String t : List.of("user.post", "user.hdr", "user.sky")) m.put(t, List.of(new Port("frame", Kind.V4, true)));
        m.put(MosaicRenderer.OUTPUT, List.of(new Port("frame", Kind.V4, true)));
        m.put("mosaic.pass", List.of(new Port("color", Kind.V4, true)));
        m.put("mosaic.bufferIn", List.of(new Port("uv", Kind.V2, false)));
        m.put("mosaic.history", List.of(new Port("uv", Kind.V2, false)));
        m.put("mat.texture", List.of(new Port("uv", Kind.V2, false)));
        m.put("mat.image", List.of(new Port("uv", Kind.V2, false)));
        m.put("mat.bsdf", List.of(new Port("baseColor", Kind.V4, false), new Port("roughness", Kind.F, false),
            new Port("metallic", Kind.F, false), new Port("specular", Kind.F, false), new Port("emission", Kind.F, false),
            new Port("height", Kind.F, false), new Port("normalStrength", Kind.F, false), new Port("ao", Kind.F, false)));
        m.put("mat.output", List.of(new Port("surface", Kind.V4, true)));
        for (String t : List.of("mosaic.position", "mosaic.normal", "mosaic.worldPos", "mosaic.waterDepth", "mosaic.waterMask", "mosaic.lavaMask",
            "mosaic.hitMaterial", "mosaic.materialMask", "mosaic.materialDepth", "mosaic.skyLight", "mosaic.blockLight"))
            m.put(t, List.of(new Port("uv", Kind.V2, false)));
        m.put("mosaic.vec", List.of(new Port("x", Kind.F, true), new Port("y", Kind.F, false),
            new Port("z", Kind.F, false), new Port("w", Kind.F, false)));
        m.put("mosaic.mix", List.of(new Port("a", Kind.V4, true), new Port("b", Kind.V4, true), new Port("t", Kind.F, false)));
        m.put("mosaic.clamp", List.of(new Port("a", Kind.V4, true), new Port("lo", Kind.F, false), new Port("hi", Kind.F, false)));
        for (String t : List.of("mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div",
            "mosaic.min", "mosaic.max", "mosaic.step", "mosaic.dot"))
            m.put(t, List.of(new Port("a", Kind.V4, true), new Port("b", Kind.V4, true)));
        for (String t : List.of("mosaic.cross", "mosaic.reflect"))
            m.put(t, List.of(new Port("a", Kind.V3, true), new Port("b", Kind.V3, true)));
        m.put("mosaic.lum", List.of(new Port("a", Kind.V3, true)));
        m.put("mosaic.math", List.of(new Port("a", Kind.V4, false), new Port("b", Kind.V4, false)));
        m.put("mosaic.mixColor", List.of(new Port("a", Kind.V4, false), new Port("b", Kind.V4, false), new Port("fac", Kind.F, false)));
        m.put("mosaic.mapRange", List.of(new Port("value", Kind.V4, false), new Port("fromMin", Kind.F, false),
            new Port("fromMax", Kind.F, false), new Port("toMin", Kind.F, false), new Port("toMax", Kind.F, false)));
        m.put("mosaic.colorRamp", List.of(new Port("fac", Kind.F, false)));
        m.put("mosaic.vectorMath", List.of(new Port("a", Kind.V3, false), new Port("b", Kind.V3, false), new Port("scale", Kind.F, false)));
        m.put("mosaic.separate", List.of(new Port("a", Kind.V4, true)));
        m.put("mosaic.hueSat", List.of(new Port("color", Kind.V4, false), new Port("hue", Kind.F, false), new Port("saturation", Kind.F, false),
            new Port("value", Kind.F, false), new Port("fac", Kind.F, false)));
        m.put("mosaic.brightContrast", List.of(new Port("color", Kind.V4, false), new Port("bright", Kind.F, false), new Port("contrast", Kind.F, false)));
        m.put("mosaic.gamma", List.of(new Port("color", Kind.V4, false), new Port("gamma", Kind.F, false)));
        m.put("mosaic.invert", List.of(new Port("color", Kind.V4, false), new Port("fac", Kind.F, false)));
        m.put("mosaic.fresnel", List.of(new Port("normal", Kind.V3, false), new Port("view", Kind.V3, false), new Port("ior", Kind.F, false)));
        m.put("mosaic.mapping", List.of(new Port("vector", Kind.V3, false), new Port("location", Kind.V3, false), new Port("rotation", Kind.V3, false),
            new Port("scale", Kind.V3, false)));
        m.put("mosaic.smoothstep", List.of(new Port("x", Kind.V4, false), new Port("edge0", Kind.F, false), new Port("edge1", Kind.F, false)));
        m.put("mosaic.blackbody", List.of(new Port("temperature", Kind.F, false)));
        m.put("mosaic.reroute", List.of(new Port("a", Kind.V4, true)));
        m.put("mosaic.noise", List.of(new Port("vector", Kind.V3, false), new Port("scale", Kind.F, false),
            new Port("detail", Kind.F, false), new Port("roughness", Kind.F, false), new Port("distortion", Kind.F, false)));
        m.put("mosaic.voronoi", List.of(new Port("vector", Kind.V3, false), new Port("scale", Kind.F, false),
            new Port("randomness", Kind.F, false)));
        m.put("mosaic.whiteNoise", List.of(new Port("vector", Kind.V3, false)));
        m.put("mosaic.checker", List.of(new Port("vector", Kind.V3, false), new Port("scale", Kind.F, false),
            new Port("color1", Kind.V4, false), new Port("color2", Kind.V4, false)));
        m.put("mosaic.wave", List.of(new Port("vector", Kind.V3, false), new Port("scale", Kind.F, false),
            new Port("distortion", Kind.F, false), new Port("detail", Kind.F, false)));
        m.put("mosaic.gradient", List.of(new Port("vector", Kind.V3, false)));
        for (String t : List.of("mosaic.gt", "mosaic.lt"))
            m.put(t, List.of(new Port("a", Kind.F, true), new Port("b", Kind.F, true)));
        for (String t : List.of("mosaic.neg", "mosaic.floor", "mosaic.fract", "mosaic.sqrt", "mosaic.exp",
            "mosaic.abs", "mosaic.saturate", "mosaic.normalize", "mosaic.length", "mosaic.swizzle"))
            m.put(t, List.of(new Port("a", Kind.V4, true)));
        return Map.copyOf(m);
    }

    /** Named outputs of {@code mosaic.trace} in socket order; every other node exposes only its bare id. */
    public static final List<String> TRACE_OUTPUTS = List.of("pos", "hit", "inst", "prim");

    /** Kind of a named output of {@code mosaic.trace}. */
    static Kind traceOutputKind(String name) { return name.equals("pos") ? Kind.V3 : Kind.F; }

    private static final Map<String, Map<String, Kind>> NAMED = Map.of(
        "mosaic.trace", orderedKinds("pos", Kind.V3, "hit", Kind.F, "inst", Kind.F, "prim", Kind.F),
        "mosaic.noise", orderedKinds("color", Kind.V3),
        "mosaic.voronoi", orderedKinds("color", Kind.V3, "position", Kind.V3),
        "mosaic.whiteNoise", orderedKinds("color", Kind.V3),
        "mosaic.checker", orderedKinds("fac", Kind.F),
        "mosaic.separate", orderedKinds("y", Kind.F, "z", Kind.F, "w", Kind.F));

    private static Map<String, Kind> orderedKinds(Object... pairs) {
        Map<String, Kind> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Kind) pairs[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    /** Extra named outputs of a node type, in socket order (the primary output has no name). */
    public static List<String> namedOutputs(String type) {
        if (MosaicSlangGraph.handles(type)) {
            List<String> out = new ArrayList<>();
            for (MosaicSlangGraph.SPort p : MosaicSlangGraph.outputs(type, null)) if (!p.name().isEmpty()) out.add(p.name());
            return out;
        }
        Map<String, Kind> m = NAMED.get(type);
        return m == null ? List.of() : List.copyOf(m.keySet());
    }

    /** Kind of a named output of a node (its mask matters for Bedray function-graph nodes). */
    static Kind outputKindOf(Node n, String name) {
        if (MosaicSlangGraph.handles(n.type())) {
            MosaicSlangGraph.SPort p = MosaicSlangGraph.output(n.type(), n.mask(), name);
            return p == null ? null : p.kind();
        }
        return outputKind(n.type(), name);
    }

    /** Kind of a named output, or null when the type has no such output. */
    public static Kind outputKind(String type, String name) {
        if (MosaicSlangGraph.handles(type)) {
            MosaicSlangGraph.SPort p = MosaicSlangGraph.output(type, null, name);
            return p == null ? null : p.kind();
        }
        Map<String, Kind> m = NAMED.get(type);
        return m == null ? null : m.get(name);
    }

    /** Operation names of {@code mosaic.math} (Blender's Math node), in menu order. */
    public static final List<String> MATH_OPS = List.of("add", "subtract", "multiply", "divide", "power", "logarithm",
        "sqrt", "inverseSqrt", "absolute", "exponent", "minimum", "maximum", "lessThan", "greaterThan", "sign",
        "round", "floor", "ceil", "truncate", "fraction", "modulo", "snap", "pingpong",
        "sine", "cosine", "tangent", "arcsine", "arccosine", "arctangent", "arctan2", "radians", "degrees");

    /** Blend modes of {@code mosaic.mixColor}. */
    public static final List<String> BLEND_MODES = List.of("mix", "add", "multiply", "subtract", "screen", "overlay", "darken",
        "lighten", "difference", "divide", "softLight", "linearLight", "burn", "dodge");

    public static final List<String> RAMP_MODES = List.of("linear", "constant", "ease");
    /** Operations of {@code mosaic.vectorMath} (Blender's Vector Math), in menu order. */
    public static final List<String> VECTOR_OPS = List.of("add", "subtract", "multiply", "divide", "crossProduct", "project", "reflect",
        "dotProduct", "distance", "length", "scale", "normalize", "absolute", "minimum", "maximum", "floor", "ceil", "fraction", "modulo",
        "snap", "sine", "cosine", "tangent");
    /** Vector Math operations whose result is a float. */
    public static final Set<String> VECTOR_SCALAR = Set.of("dotProduct", "distance", "length");
    public static final List<String> GRADIENT_MODES = List.of("linear", "quadratic", "easing", "diagonal", "spherical", "radial");
    public static final List<String> WAVE_MODES = List.of("bands", "rings");

    /** Ports of a built-in type (empty for unknown and function types; see {@link #portsOf(String, Map)}). */
    static List<Port> portsOf(String type) { return PORTS.getOrDefault(type, List.of()); }

    /** Ports of any type, user functions included. */
    public static List<Port> portsOf(String type, Map<String, MosaicFunction> functions) {
        if (MosaicFunction.isFunctionType(type) && functions != null) {
            MosaicFunction f = functions.get(MosaicFunction.nameOf(type));
            if (f == null) return List.of();
            List<Port> out = new ArrayList<>();
            for (MosaicFunction.Param p : f.params()) out.add(new Port(p.name(), p.kind(), p.required()));
            return out;
        }
        if (MosaicSlangGraph.handles(type)) {
            List<Port> out = new ArrayList<>();
            for (MosaicSlangGraph.SPort p : MosaicSlangGraph.inputs(type, null)) out.add(p.port());
            return out;
        }
        return portsOf(type);
    }

    /** All built-in node types for editors and catalogs. */
    public static Set<String> knownTypes() { return KNOWN; }

    public static boolean isKnown(String type) { return KNOWN.contains(type) || MosaicSlangGraph.handles(type) && MosaicSlangGraph.isKnown(type); }

    /** Node types whose single input passes any numeric kind through unchanged (scalar-safe GLSL unaries). */
    private static final Set<String> PASS_THROUGH = Set.of(
        "mosaic.neg", "mosaic.floor", "mosaic.fract", "mosaic.sqrt", "mosaic.exp", "mosaic.abs",
        "mosaic.saturate", "mosaic.normalize", "mosaic.length", "mosaic.swizzle");

    private static final Set<String> ARITH = Set.of(
        "mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div", "mosaic.min", "mosaic.max",
        "mosaic.step", "mosaic.mix", "mosaic.clamp");

    /** Ports of the Blender-style nodes that take any numeric kind (the node works per component). */
    static boolean anyKind(String type, String port) {
        return switch (type) {
            case "mosaic.math", "mosaic.mixColor" -> port.equals("a") || port.equals("b");
            case "mosaic.mapRange" -> port.equals("value");
            case "mosaic.separate" -> port.equals("a");
            case "mosaic.smoothstep" -> port.equals("x");
            case "mosaic.reroute" -> port.equals("a");
            case "mat.bsdf" -> port.equals("baseColor");
            default -> false;
        };
    }

    /**
     * The kind an input port insists on, or null when it accepts several (arithmetic operands, pass-through unaries,
     * dot, and the output's color). Editors use it to color sockets and explain ports.
     */
    public static Kind acceptedKind(String type, String port) { return acceptedKind(type, port, null); }

    public static Kind acceptedKind(String type, String port, Map<String, MosaicFunction> functions) {
        if (isOutput(type) || type.equals("mosaic.dot")) return null;
        if ((port.equals("a") || port.equals("b")) && (PASS_THROUGH.contains(type) || ARITH.contains(type))) return null;
        if (anyKind(type, port)) return null;
        for (Port p : portsOf(type, functions)) if (p.name().equals(port)) return p.kind();
        return null;
    }

    // ------------------------------------------------------------------ instance

    private final Map<String, Node> nodes;
    private final Map<String, Kind> kinds;
    private final Map<String, MosaicFunction> functions;
    private final String outputId;

    private MosaicGraph(Map<String, Node> nodes, Map<String, Kind> kinds, Map<String, MosaicFunction> functions,
                        String outputId) {
        this.nodes = nodes;
        this.kinds = kinds;
        this.functions = functions;
        this.outputId = outputId;
    }

    public Node node(String id) { return nodes.get(id); }
    public String outputId() { return outputId; }
    public Map<String, MosaicFunction> functions() { return functions; }
    public MosaicFunction function(String type) { return functions.get(MosaicFunction.nameOf(type)); }
    public List<Port> ports(String type) { return portsOf(type, functions); }

    /** Output kind of the primary output of a node (resolved at parse). */
    public Kind kind(String id) {
        Kind k = kinds.get(id);
        if (k == null) throw new IllegalArgumentException("node " + id + " has no resolved kind");
        return k;
    }

    /** Kind of an edge reference ("id" or "id.named"). */
    public Kind refKind(String ref) {
        String[] parts = split(ref);
        return parts[1].isEmpty() ? kind(parts[0]) : outputKindOf(nodes.get(parts[0]), parts[1]);
    }

    // ------------------------------------------------------------------ parse / analyze

    /** Strict parse: the first error as an {@link IllegalArgumentException} (the message names the node). */
    public static MosaicGraph parse(String json) {
        Analysis a = analyze(json);
        if (!a.ok()) throw new IllegalArgumentException(a.firstError());
        return a.graph();
    }

    /** Reports every problem; never throws. */
    public static Analysis analyze(String json) {
        return new Analyzer().run(json);
    }

    /** One analysis pass; collects problems instead of stopping at the first. */
    private static final class Analyzer {
        final List<Problem> problems = new ArrayList<>();
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final Set<String> broken = new HashSet<>();     // ids whose definition is unusable (no kind)
        final Map<String, Kind> kinds = new LinkedHashMap<>();
        final Set<String> unresolved = new HashSet<>(); // kind could not be resolved (error reported upstream)
        final Map<String, MosaicFunction> functions = new LinkedHashMap<>();
        final Set<String> cycleReported = new HashSet<>();

        void error(String node, String port, String message, Fix fix) {
            problems.add(new Problem(Severity.ERROR, node, port, message, fix));
        }

        void warn(String node, String message, Fix fix) {
            problems.add(new Problem(Severity.WARNING, node, null, message, fix));
        }

        void hint(String node, String message, Fix fix) {
            problems.add(new Problem(Severity.HINT, node, null, message, fix));
        }

        Analysis run(String json) {
            JsonObject root;
            try {
                root = JsonParser.parseString(json).getAsJsonObject();
            } catch (RuntimeException e) {
                error(null, null, "mosaic.json is not valid JSON: " + e.getMessage(), Fix.NONE);
                return done(null);
            }
            if (root.has("functions")) readFunctions(root.get("functions"));
            if (!root.has("nodes") || !root.get("nodes").isJsonArray()) {
                error(null, null, "the graph needs a non-empty \"nodes\" array", Fix.NONE);
                return done(null);
            }
            List<String> outputs = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("nodes")) if (el.isJsonObject()) mute(el.getAsJsonObject());
            for (JsonElement el : root.getAsJsonArray("nodes")) readNode(el, outputs);
            String outputId = outputs.isEmpty() ? null : outputs.get(0);
            if (outputs.size() != 1) {
                if (outputs.isEmpty())
                    error(null, null, "the graph needs exactly one output node, found 0", Fix.ADD_OUTPUT);
                for (int i = 1; i < outputs.size(); i++)
                    error(outputs.get(i), null, "the graph needs exactly one output node, found " + outputs.size()
                        + " (" + outputs.get(i) + " is extra)", Fix.DELETE_NODE);
            }
            String outType = outputId == null ? null : nodes.get(outputId).type();
            boolean renderer = MosaicRenderer.OUTPUT.equals(outType);
            for (Node n : nodes.values()) {
                boolean stage = MosaicRenderer.stage(n.type()) != null || n.type().equals(MosaicRenderer.OUTPUT) || n.type().startsWith("user.");
                if (renderer && !stage && !n.type().equals("mosaic.reroute") && !MosaicFunction.isFunctionType(n.type()))
                    error(n.id(), null, "node " + n.id() + " (" + n.type() + ") is a shader node: a renderer graph wires Bedray's stages", Fix.DELETE_NODE);
                if (!renderer && outType != null && stage)
                    error(n.id(), null, "node " + n.id() + " is a Bedray renderer stage: it belongs to the Renderer workspace", Fix.DELETE_NODE);
            }
            if (renderer) checkRenderer();
            for (Node n : nodes.values()) {
                if ("mat.output".equals(outType) && !MATERIAL_SAFE.contains(n.type()))
                    error(n.id(), null, "node " + n.id() + " (" + n.type() + ") reads the rendered scene and cannot be baked into a texture;"
                        + " material graphs use textures, values, math and procedural nodes", Fix.DELETE_NODE);
                if ("mosaic.output".equals(outType) && n.type().startsWith("mat."))
                    error(n.id(), null, "node " + n.id() + " (" + n.type() + ") belongs to material graphs (Materials mode),"
                        + " not to a shader graph", Fix.DELETE_NODE);
            }
            for (Node n : nodes.values()) kindOf(n.id(), new ArrayList<>());
            for (Node n : nodes.values()) {
                if (broken.contains(n.id())) continue;
                for (Map.Entry<String, String> e : n.inputs().entrySet()) checkPort(n, e.getKey(), e.getValue());
            }
            boolean hasErrors = problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
            return done(hasErrors ? null : outputId);
        }

        Analysis done(String outputId) {
            Set<String> reachable = new LinkedHashSet<>();
            if (outputId != null || nodes.values().stream().anyMatch(n -> isOutput(n.type())))
                for (Node n : nodes.values()) if (isOutput(n.type())) upstream(n.id(), reachable);
            // a Buffer node is live when something live reads its buffer (and so on, through every pass)
            for (boolean grew = true; grew; ) {
                grew = false;
                for (Node r : List.copyOf(nodes.values()))
                    if (r.type().equals("mosaic.bufferIn") && reachable.contains(r.id()))
                        for (Node p : nodes.values())
                            if (p.type().equals("mosaic.pass") && java.util.Objects.equals(p.mask(), r.mask()) && !reachable.contains(p.id())) {
                                upstream(p.id(), reachable);
                                grew = true;
                            }
            }
            checkPasses();
            warnings(reachable);
            boolean hasErrors = problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
            MosaicGraph g = hasErrors || outputId == null ? null
                : new MosaicGraph(Map.copyOf(nodes), Map.copyOf(kinds), Map.copyOf(functions), outputId);
            // keep insertion order for nodes: Map.copyOf loses it, so wrap a LinkedHashMap instead
            if (g != null) g = new MosaicGraph(Collections.unmodifiableMap(new LinkedHashMap<>(nodes)),
                Collections.unmodifiableMap(new LinkedHashMap<>(kinds)), Collections.unmodifiableMap(new LinkedHashMap<>(functions)),
                outputId);
            Cost cost = cost(reachable.isEmpty() ? nodes.keySet() : reachable);
            return new Analysis(g, List.copyOf(problems), Collections.unmodifiableMap(new LinkedHashMap<>(kinds)),
                Collections.unmodifiableMap(new LinkedHashMap<>(functions)), Collections.unmodifiableSet(reachable), cost);
        }

        /** Renderer graphs: stages wired in pipeline order, and the stages a stage needs are upstream of it. */
        void checkRenderer() {
            for (Node n : nodes.values()) {
                MosaicRenderer.Stage s = MosaicRenderer.stage(n.type());
                if (s == null) continue;
                for (String ref : n.inputs().values()) {
                    Node src = nodes.get(split(ref)[0]);
                    MosaicRenderer.Stage ss = src == null ? null : MosaicRenderer.stage(src.type());
                    if (ss != null && ss.rank() >= s.rank())
                        error(n.id(), "frame", ss.label() + " cannot feed " + s.label() + ": Bedray runs " + s.label() + " earlier in the frame"
                            + " (order: scene, materials, sky, path tracer or ray effects, ReSTIR DI, ReSTIR PT, cascades, fog, denoiser,"
                            + " exposure, post)", Fix.CONNECT);
                }
                if (!s.needs().isEmpty()) {
                    Set<String> up = new LinkedHashSet<>();
                    upstream(n.id(), up);
                    for (String need : s.needs())
                        if (up.stream().noneMatch(id -> nodes.get(id) != null && nodes.get(id).type().equals(need)))
                            error(n.id(), null, s.label() + " needs " + MosaicRenderer.stage(need).label() + " before it", Fix.CONNECT);
                }
            }
            // exactly one render mode in the frame: the wired one is Bedray's rt_mode
            Set<String> live = new LinkedHashSet<>();
            for (Node n : nodes.values()) if (n.type().equals(MosaicRenderer.OUTPUT)) upstream(n.id(), live);
            List<String> modes = new ArrayList<>();
            for (String id : live) {
                MosaicRenderer.Stage st = MosaicRenderer.stage(nodes.get(id) == null ? "" : nodes.get(id).type());
                if (st != null && st.isMode()) modes.add(id);
            }
            if (modes.size() > 1)
                for (String id : modes.subList(1, modes.size()))
                    error(id, null, "two render modes in one frame (" + String.join(", ", modes) + "): wire one of Raster, Path tracer or"
                        + " Hybrid ray effects into the frame; keep the others beside it to switch", Fix.CONNECT);
            if (modes.isEmpty() && !live.isEmpty())
                hint(null, "no render mode wired in: Bedray runs Raster (wire in Path tracer or Hybrid ray effects to trace rays)", Fix.NONE);
        }

        /** Buffer names: one word, written once, read only by name that exists, never read inside their own pass. */
        void checkPasses() {
            Map<String, String> written = new LinkedHashMap<>();
            for (Node n : nodes.values()) {
                if (!n.type().equals("mosaic.pass")) continue;
                if (n.mask() == null || !n.mask().matches("[A-Za-z]\\w{0,23}")) {
                    error(n.id(), null, "node " + n.id() + ": a buffer needs a name (one word, e.g. bloom)", Fix.CHANGE_PARAM);
                    continue;
                }
                if (written.containsKey(n.mask()))
                    error(n.id(), null, "buffer " + n.mask() + " is written by " + written.get(n.mask()) + " and " + n.id() + ": one writer per buffer",
                        Fix.CHANGE_PARAM);
                written.putIfAbsent(n.mask(), n.id());
                if (n.index() != 0 && n.index() != 1 && n.index() != 2 && n.index() != 4 && n.index() != 8)
                    error(n.id(), null, "node " + n.id() + ": scale is 1, 2, 4 or 8 (a fraction of the screen)", Fix.CHANGE_PARAM);
            }
            for (Node n : nodes.values()) {
                if (!n.type().equals("mosaic.bufferIn")) continue;
                if (n.index() >= MosaicPasses.FIRST_BUFFER_BINDING) continue; // a pass graph: already bound by the planner
                if (n.mask() == null || !written.containsKey(n.mask())) {
                    error(n.id(), null, "node " + n.id() + " reads buffer " + n.mask() + ", which no Buffer node writes"
                        + (written.isEmpty() ? "" : " (buffers: " + written.keySet() + ")"), Fix.CHANGE_PARAM);
                    continue;
                }
                Set<String> own = new LinkedHashSet<>();
                upstream(written.get(n.mask()), own);
                if (own.contains(n.id()))
                    error(n.id(), null, "buffer " + n.mask() + " is read while it is being written: use Previous frame for feedback",
                        Fix.CHANGE_PARAM);
            }
        }

        void upstream(String id, Set<String> into) {
            if (!into.add(id)) return;
            Node n = nodes.get(id);
            if (n == null) return;
            for (String ref : n.inputs().values()) upstream(split(ref)[0], into);
        }

        // -------------------------------------------------------------- functions

        void readFunctions(JsonElement el) {
            if (!el.isJsonArray()) {
                error(null, null, "\"functions\" must be an array of node definitions", Fix.NONE);
                return;
            }
            for (JsonElement f : el.getAsJsonArray()) {
                try {
                    if (!f.isJsonObject()) throw new IllegalArgumentException("every function must be an object");
                    MosaicFunction fn = MosaicFunction.fromJson(f.getAsJsonObject());
                    if (functions.containsKey(fn.name())) throw new IllegalArgumentException("two functions are called " + fn.name());
                    functions.put(fn.name(), fn);
                } catch (RuntimeException e) {
                    error(null, null, "function: " + e.getMessage(), Fix.EDIT_CODE);
                }
            }
        }

        // -------------------------------------------------------------- nodes

        /**
         * A muted node ({@code "muted": true}, M in the editor) passes its first wired input through unchanged, like
         * Blender's mute; one with no wired input gives zero. Output nodes cannot be muted.
         */
        void mute(JsonObject o) {
            if (!o.has("muted") || !o.get("muted").isJsonPrimitive() || !o.get("muted").getAsBoolean()) return;
            String type = text(o, "type"), id = text(o, "id");
            if (type == null || isOutput(type)) return;
            JsonObject in = o.has("inputs") && o.get("inputs").isJsonObject() ? o.getAsJsonObject("inputs") : new JsonObject();
            String ref = null;
            for (Port p : portsOf(type, functions))
                if (ref == null && in.has(p.name()) && in.get(p.name()).isJsonPrimitive()) ref = in.get(p.name()).getAsString();
            if (ref == null) for (var e : in.entrySet()) if (ref == null && e.getValue().isJsonPrimitive()) ref = e.getValue().getAsString();
            for (String k : List.of("mask", "index", "value", "defaults", "inputs")) o.remove(k);
            if (ref != null) {
                o.addProperty("type", "mosaic.reroute");
                JsonObject a = new JsonObject();
                a.addProperty("a", ref);
                o.add("inputs", a);
            } else {
                o.addProperty("type", "mosaic.const");
                JsonArray zero = new JsonArray();
                zero.add(0.0);
                o.add("value", zero);
            }
            hint(id, "node " + id + " is muted (M): it passes its input through", Fix.NONE);
        }

        void readNode(JsonElement el, List<String> outputs) {
            if (!el.isJsonObject()) {
                error(null, null, "every node must be an object", Fix.NONE);
                return;
            }
            JsonObject o = el.getAsJsonObject();
            String id = text(o, "id");
            if (id == null) {
                error(null, null, "node needs a string field id", Fix.NONE);
                return;
            }
            if (!id.matches("\\w+")) {
                error(null, null, "node id must be one word: " + id, Fix.NONE);
                return;
            }
            if (nodes.containsKey(id)) {
                error(id, null, "duplicate node id " + id, Fix.NONE);
                return;
            }
            String type = text(o, "type");
            boolean ok = true;
            if (type == null) {
                error(id, null, "node " + id + ": needs a string field type", Fix.NONE);
                type = "?";
                ok = false;
            } else if (MosaicFunction.isFunctionType(type) ? !functions.containsKey(MosaicFunction.nameOf(type)) : !isKnown(type)) {
                error(id, null, "node " + id + ": unknown type " + type
                    + (MosaicFunction.isFunctionType(type) ? " (no function " + MosaicFunction.nameOf(type) + " in this file)" : ""),
                    Fix.DELETE_NODE);
                ok = false;
            }
            List<Port> ports = !ok ? List.of() : MosaicSlangGraph.handles(type)
                ? MosaicSlangGraph.inputs(type, text(o, "mask"), o.has("index") && o.get("index").isJsonPrimitive() ? o.get("index").getAsInt() : 0)
                    .stream().map(MosaicSlangGraph.SPort::port).toList()
                : portsOf(type, functions);
            Map<String, String> inputs = new LinkedHashMap<>();
            if (o.has("inputs")) {
                if (!o.get("inputs").isJsonObject()) {
                    error(id, null, "node " + id + ": inputs must be an object {port: nodeId}", Fix.NONE);
                } else {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("inputs").entrySet()) {
                        String port = e.getKey();
                        if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) {
                            error(id, port, "node " + id + ": port " + port + " must name another node", Fix.CONNECT);
                            continue;
                        }
                        if (ok && !type.startsWith("slang.") && ports.stream().noneMatch(p -> p.name().equals(port))) {
                            error(id, port, "node " + id + " (" + type + "): no port " + port, Fix.NONE);
                            continue;
                        }
                        inputs.put(port, e.getValue().getAsString());
                    }
                }
            }
            Map<String, double[]> defaults = new LinkedHashMap<>();
            if (o.has("defaults") && o.get("defaults").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("defaults").entrySet()) {
                    MosaicRenderer.Stage stage = MosaicRenderer.stage(type);
                    if (stage != null ? stage.setting(e.getKey()) == null : ok && ports.stream().noneMatch(p -> p.name().equals(e.getKey()))) {
                        error(id, e.getKey(), "node " + id + " (" + type + "): no " + (stage != null ? "setting " : "port ") + e.getKey()
                            + (stage != null ? "" : " for an inline value"), Fix.NONE);
                        continue;
                    }
                    double[] v = numbers(e.getValue(), id);
                    if (v != null) defaults.put(e.getKey(), v);
                }
            }
            for (Port p : ports)
                if (p.required() && !inputs.containsKey(p.name()) && !defaults.containsKey(p.name()))
                    error(id, p.name(), "node " + id + " (" + type + "): port " + p.name() + " is required", Fix.CONNECT);
            double[] value = null;
            if (o.has("value") && type.equals("mosaic.colorRamp")) {
                value = rampStops(o.get("value"), id);
            } else if (o.has("value")) {
                value = numbers(o.get("value"), id);
                if (value == null) ok = false;
            } else if (type.equals("mosaic.const")) {
                error(id, null, "node " + id + ": a constant needs value: [1..4 numbers]", Fix.CHANGE_PARAM);
                ok = false;
            }
            int index = 0;
            if (o.has("index")) {
                if (!o.get("index").isJsonPrimitive() || !o.get("index").getAsJsonPrimitive().isNumber()) {
                    error(id, null, "node " + id + ": index must be a number", Fix.CHANGE_PARAM);
                } else {
                    index = o.get("index").getAsInt();
                    if (type.equals("mosaic.push") && (index < 0 || index > 3)) {
                        error(id, null, "node " + id + ": push index must be 0..3", Fix.CHANGE_PARAM);
                        index = 0;
                    }
                    if ((type.equals("mosaic.materialMask") || type.equals("mosaic.materialDepth"))
                        && (index < 0 || index >= MosaicWorld.Material.values().length)) {
                        error(id, null, "node " + id + ": material must be 0.." + (MosaicWorld.Material.values().length - 1),
                            Fix.CHANGE_PARAM);
                        index = 0;
                    }
                    if (type.equals("mosaic.buffer") && (index < 0 || index > 1023)) {
                        error(id, null, "node " + id + ": buffer index must be 0..1023", Fix.CHANGE_PARAM);
                        index = 0;
                    }
                }
            }
            if (type.equals("mosaic.vec")) {
                index = 3;
                if (o.has("size")) {
                    try {
                        index = o.get("size").getAsInt();
                    } catch (RuntimeException e) {
                        index = -1;
                    }
                    if (index < 2 || index > 4) {
                        error(id, null, "node " + id + ": size must be 2, 3 or 4", Fix.CHANGE_PARAM);
                        index = 3;
                    }
                }
            }
            String mask = text(o, "mask");
            String modeError = switch (type) {
                case "mosaic.math" -> mask == null || MATH_OPS.contains(mask) ? null : "math operation must be one of " + MATH_OPS;
                case "mosaic.mixColor" -> mask == null || BLEND_MODES.contains(mask) ? null : "blend mode must be one of " + BLEND_MODES;
                case "mosaic.colorRamp" -> mask == null || RAMP_MODES.contains(mask) ? null : "interpolation must be one of " + RAMP_MODES;
                case "mosaic.vectorMath" -> mask == null || VECTOR_OPS.contains(mask) ? null : "operation must be one of " + VECTOR_OPS;
                case "mosaic.gradient" -> mask == null || GRADIENT_MODES.contains(mask) ? null : "gradient type must be one of " + GRADIENT_MODES;
                case "mosaic.wave" -> mask == null || WAVE_MODES.contains(mask) ? null : "wave type must be one of " + WAVE_MODES;
                default -> null;
            };
            if (modeError != null) error(id, null, "node " + id + ": " + modeError, Fix.CHANGE_PARAM);
            nodes.put(id, new Node(id, type, Map.copyOf(inputs), value, index, mask, Collections.unmodifiableMap(defaults)));
            if (!ok) broken.add(id);
            if (isOutput(type)) outputs.add(id);
        }

        /** Color ramp stops: groups of 5 numbers (position, r, g, b, a), 1 to 16 stops, positions ascending in 0..1. */
        double[] rampStops(JsonElement el, String id) {
            if (!el.isJsonArray() || el.getAsJsonArray().size() % 5 != 0 || el.getAsJsonArray().isEmpty() || el.getAsJsonArray().size() > 80) {
                error(id, null, "node " + id + ": a color ramp needs 1 to 16 stops of 5 numbers (position, r, g, b, a)", Fix.CHANGE_PARAM);
                return null;
            }
            double[] v = new double[el.getAsJsonArray().size()];
            for (int i = 0; i < v.length; i++) {
                JsonElement e = el.getAsJsonArray().get(i);
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble())) {
                    error(id, null, "node " + id + ": color ramp stops must be finite numbers", Fix.CHANGE_PARAM);
                    return null;
                }
                v[i] = e.getAsDouble();
            }
            for (int i = 5; i < v.length; i += 5)
                if (v[i] < v[i - 5]) {
                    error(id, null, "node " + id + ": color ramp stop positions must ascend", Fix.CHANGE_PARAM);
                    return null;
                }
            return v;
        }

        double[] numbers(JsonElement el, String id) {
            if (!el.isJsonArray()) {
                error(id, null, "node " + id + ": value needs one to four numbers", Fix.CHANGE_PARAM);
                return null;
            }
            JsonArray v = el.getAsJsonArray();
            if (v.size() < 1 || v.size() > 4) {
                error(id, null, "node " + id + ": value needs one to four numbers", Fix.CHANGE_PARAM);
                return null;
            }
            double[] out = new double[v.size()];
            for (int i = 0; i < v.size(); i++) {
                if (!v.get(i).isJsonPrimitive() || !v.get(i).getAsJsonPrimitive().isNumber()) {
                    error(id, null, "node " + id + ": value must hold numbers", Fix.CHANGE_PARAM);
                    return null;
                }
                out[i] = v.get(i).getAsDouble();
                if (!Double.isFinite(out[i])) {
                    error(id, null, "node " + id + ": value must be finite", Fix.CHANGE_PARAM);
                    return null;
                }
            }
            return out;
        }

        static String text(JsonObject o, String key) {
            return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isString()
                ? o.get(key).getAsString() : null;
        }

        // -------------------------------------------------------------- kinds

        /** Resolved kind, or null when it cannot be known (the reason was reported where it arose). */
        Kind kindOf(String id, List<String> visiting) {
            if (kinds.containsKey(id)) return kinds.get(id);
            if (unresolved.contains(id) || broken.contains(id)) return null;
            Node n = nodes.get(id);
            if (n == null) return null;
            if (visiting.contains(id)) {
                if (cycleReported.add(id))
                    error(id, null, "the graph has a cycle through node " + id, Fix.CONNECT);
                return null;
            }
            visiting.add(id);
            Kind k = computeKind(n, visiting);
            visiting.remove(visiting.size() - 1);
            if (k == null) unresolved.add(id);
            else kinds.put(id, k);
            return k;
        }

        Kind operand(Node n, String port, List<String> visiting) {
            String ref = n.inputs().get(port);
            if (ref == null) {
                double[] d = n.defaultOf(port);
                if (d == null) d = MosaicEval.portDefault(n.type(), port);
                return d == null ? null : Kind.ofSize(d.length);
            }
            String[] parts = split(ref);
            Node src = nodes.get(parts[0]);
            if (src == null) return null; // reported by checkPort
            Kind primary = kindOf(parts[0], visiting);
            if (parts[1].isEmpty()) return primary;
            return outputKindOf(src, parts[1]); // null (reported by checkPort) when the name does not exist
        }

        static Kind widest(Kind a, Kind b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.ordinal() >= b.ordinal() ? a : b;
        }

        Kind computeKind(Node n, List<String> visiting) {
            return switch (n.type()) {
                case "mosaic.uv", "mosaic.texel", "mosaic.size" -> Kind.V2;
                case "mosaic.time", "mosaic.frame" -> Kind.F;
                case "mosaic.eye" -> Kind.V3;
                case "mosaic.sceneColor", "mosaic.lut", "mosaic.buffer", "mosaic.push" -> Kind.V4;
                case "mosaic.sceneDepth", "mosaic.shadow", "mosaic.trace" -> Kind.F;
                case "mosaic.position", "mosaic.worldPos", "mosaic.normal", "mosaic.sunDir" -> Kind.V3;
                case "mosaic.waterDepth", "mosaic.waterMask", "mosaic.lavaMask", "mosaic.underwater", "mosaic.hitMaterial",
                     "mosaic.materialMask", "mosaic.materialDepth", "mosaic.skyLight", "mosaic.blockLight",
                     "mosaic.cameraMaterial" -> Kind.F;
                case "mosaic.const" -> n.value() == null ? null : Kind.ofSize(n.value().length);
                case "mosaic.vec" -> Kind.ofSize(n.index());
                case "mosaic.swizzle" -> swizzleKind(n, visiting);
                case "mosaic.output", "mat.output", "mat.texture", "mat.image", "mat.bsdf", "mosaic.pass", "mosaic.bufferIn", "mosaic.history" -> Kind.V4;
                case "mosaic.rain", "mosaic.thunder", "mosaic.temperature" -> Kind.F;
                case "bedray.output", "slang.module", "slang.pack", "bedray.stageMap" -> Kind.V4;
                case "mosaic.skyColor", "mosaic.fogColor" -> Kind.V3;
                case "bedray.scene", "bedray.materials", "bedray.sky", "bedray.pathTracer", "bedray.restirDi", "bedray.restirPt",
                     "bedray.radianceCascades", "bedray.rays", "bedray.fog", "bedray.denoiser", "bedray.exposure", "bedray.post",
                     "bedray.voxels", "bedray.raster", "bedray.upscaler", "user.post", "user.hdr", "user.sky" -> Kind.V4;
                case "mosaic.gt", "mosaic.lt", "mosaic.dot", "mosaic.length", "mosaic.lum" -> Kind.F;
                case "mosaic.cross", "mosaic.reflect" -> Kind.V3;
                case "mosaic.math" -> {
                    Kind k = widest(operand(n, "a", visiting), operand(n, "b", visiting));
                    yield k == null ? Kind.F : k;
                }
                case "mosaic.mixColor" -> {
                    Kind k = widest(operand(n, "a", visiting), operand(n, "b", visiting));
                    yield k == null ? Kind.V4 : k;
                }
                case "mosaic.mapRange" -> {
                    Kind k = operand(n, "value", visiting);
                    yield k == null ? Kind.F : k;
                }
                case "mosaic.reroute" -> operand(n, "a", visiting);
                case "mosaic.colorRamp", "mosaic.checker" -> Kind.V4;
                case "mosaic.vectorMath" -> VECTOR_SCALAR.contains(n.mask() == null ? "add" : n.mask()) ? Kind.F : Kind.V3;
                case "mosaic.separate", "mosaic.fresnel" -> Kind.F;
                case "mosaic.hueSat", "mosaic.brightContrast", "mosaic.gamma", "mosaic.invert" -> Kind.V4;
                case "mosaic.mapping", "mosaic.blackbody" -> Kind.V3;
                case "mosaic.smoothstep" -> {
                    Kind k = operand(n, "x", visiting);
                    yield k == null ? Kind.F : k;
                }
                case "mosaic.noise", "mosaic.voronoi", "mosaic.whiteNoise", "mosaic.wave", "mosaic.gradient" -> {
                    for (String port : n.inputs().keySet()) operand(n, port, visiting);
                    yield Kind.F;
                }
                case "mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div", "mosaic.min", "mosaic.max", "mosaic.step" -> {
                    Kind a = operand(n, "a", visiting), b = operand(n, "b", visiting);
                    yield a == null || b == null ? null : a.ordinal() >= b.ordinal() ? a : b;
                }
                case "mosaic.mix", "mosaic.clamp", "mosaic.neg", "mosaic.floor", "mosaic.fract", "mosaic.sqrt",
                     "mosaic.exp", "mosaic.abs", "mosaic.saturate", "mosaic.normalize" -> operand(n, "a", visiting);
                default -> {
                    if (MosaicSlangGraph.handles(n.type())) {
                        for (String port : n.inputs().keySet()) operand(n, port, visiting);
                        List<MosaicSlangGraph.SPort> outs = MosaicSlangGraph.outputs(n.type(), n.mask());
                        yield outs.isEmpty() ? Kind.S : outs.get(0).kind();
                    }
                    if (MosaicFunction.isFunctionType(n.type())) {
                        MosaicFunction f = functions.get(MosaicFunction.nameOf(n.type()));
                        // touch inputs so their own problems are found; the output kind comes from the signature
                        for (String port : n.inputs().keySet()) operand(n, port, visiting);
                        yield f == null ? null : f.output();
                    }
                    yield null;
                }
            };
        }

        Kind swizzleKind(Node n, List<String> visiting) {
            if (n.mask() == null || n.mask().isEmpty() || n.mask().length() > 4) {
                error(n.id(), null, "node " + n.id() + " (mosaic.swizzle): mask needs one to four components", Fix.CHANGE_PARAM);
                return null;
            }
            Kind source = operand(n, "a", visiting);
            if (source == null) return null;
            if (source == Kind.F) {
                error(n.id(), "a", "node " + n.id() + " (mosaic.swizzle): the input must be a vector, not a float", Fix.CONVERT);
                return null;
            }
            for (char c : n.mask().toCharArray()) {
                int i = "xyzw".indexOf(c) >= 0 ? "xyzw".indexOf(c) : "rgba".indexOf(c);
                if (i < 0) {
                    error(n.id(), null, "node " + n.id() + " (mosaic.swizzle): component " + c + " is not one of xyzwrgba",
                        Fix.CHANGE_PARAM);
                    return null;
                }
                if (i >= source.size()) {
                    error(n.id(), null, "node " + n.id() + " (mosaic.swizzle): component " + c + " does not exist on vec"
                        + source.size(), Fix.CHANGE_PARAM);
                    return null;
                }
            }
            return Kind.ofSize(n.mask().length());
        }

        // -------------------------------------------------------------- edges

        Kind refKind(String ref) {
            String[] parts = split(ref);
            if (!parts[1].isEmpty()) {
                Node src = nodes.get(parts[0]);
                return src == null ? null : outputKindOf(src, parts[1]);
            }
            return kinds.get(parts[0]);
        }

        void checkPort(Node n, String port, String ref) {
            String[] parts = split(ref);
            Node source = nodes.get(parts[0]);
            if (source == null) {
                error(n.id(), port, "node " + n.id() + ": input names unknown node " + parts[0], Fix.CONNECT);
                return;
            }
            Kind s;
            if (parts[1].isEmpty()) {
                s = kinds.get(parts[0]);
            } else {
                s = outputKindOf(source, parts[1]);
                if (s == null) {
                    error(n.id(), port, "node " + n.id() + ": " + ref + " does not exist ("
                        + (namedOutputs(source.type()).isEmpty() ? source.type() + " has no named outputs" : "named outputs of "
                        + source.type() + ": " + namedOutputs(source.type())) + ")", Fix.CONNECT);
                    return;
                }
            }
            if (s == null) return; // unknown upstream: its own problem is already reported
            MosaicSlangGraph.SPort slangIn = MosaicSlangGraph.handles(n.type()) ? MosaicSlangGraph.input(n.type(), n.mask(), port) : null;
            if (s == Kind.S || slangIn != null && slangIn.kind() == Kind.S) {
                String have = MosaicSlangGraph.handles(source.type())
                    ? java.util.Optional.ofNullable(MosaicSlangGraph.output(source.type(), source.mask(), parts[1])).map(MosaicSlangGraph.SPort::slangType).orElse("?")
                    : s.slang();
                MosaicSlangGraph.SPort in = MosaicSlangGraph.handles(n.type()) ? MosaicSlangGraph.input(n.type(), n.mask(), port) : null;
                String wantType = in == null ? null : in.slangType();
                if (have.equals(MosaicSlangGraph.AUTO) || MosaicSlangGraph.AUTO.equals(wantType) || port.equals(MosaicSlangGraph.AFTER)
                    || wantType != null && have.equals("void") && n.type().equals(MosaicSlangGraph.RESULT)) {
                    // slangc checks values whose type is only known in Slang
                } else if (wantType == null) {
                    error(n.id(), port, "node " + n.id() + ": port " + port + " takes a number, found a Slang " + have
                        + " (break it into fields first)", Fix.CONVERT);
                } else if (!wantType.equals(have)) {
                    error(n.id(), port, "node " + n.id() + ": port " + port + " takes " + wantType + ", found " + have, Fix.CONVERT);
                }
                return;
            }
            Kind want = slangIn != null ? slangIn.kind() : acceptedKind(n.type(), port, functions);
            if (slangIn != null) {
                // Slang converts numbers itself (a float splats to float3, vectors truncate): the generator writes the cast
                // a function graph converts numbers to the declared type (float -> float3 splats, vectors pad or cut)
            } else if (isOutput(n.type()) || n.type().equals("mosaic.pass")) {
                // float, vec2, vec3 and vec4 all widen to the stored color
            } else if (want != null && s != want) {
                error(n.id(), port, "node " + n.id() + ": port " + port + " takes " + label(want) + ", found "
                    + sourceLabel(parts[0], parts[1], s), Fix.CONVERT);
                return;
            }
            String otherRef = port.equals("a") ? n.inputs().get("b") : port.equals("b") ? n.inputs().get("a") : null;
            Kind o = otherRef == null ? null : refKind(otherRef);
            switch (n.type()) {
                case "mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div", "mosaic.step" -> {
                    if (n.type().equals("mosaic.step") && port.equals("a") && s != Kind.F && o == Kind.F)
                        error(n.id(), port, "node " + n.id() + ": step with a vector edge a needs a vector b"
                            + " of the same kind (a float edge works with any b)", Fix.CONVERT);
                    else if (o != null && port.equals("b") && s != Kind.F && o != Kind.F && s != o)
                        error(n.id(), port, "node " + n.id() + ": operands must be the same kind"
                            + " (or one side a float), found " + o + " and " + sourceLabel(parts[0], parts[1], s), Fix.CONVERT);
                }
                case "mosaic.mix" -> {
                    Kind a = refKind(n.inputs().getOrDefault("a", ref));
                    if (port.equals("b") && a != null && s != a)
                        error(n.id(), port, "node " + n.id() + ": mix operands a and b must have the same kind", Fix.CONVERT);
                }
                case "mosaic.math" -> {
                    if (o != null && port.equals("b") && s != Kind.F && o != Kind.F && s != o)
                        error(n.id(), port, "node " + n.id() + ": a and b must be the same kind (or one a float), found " + o + " and "
                            + sourceLabel(parts[0], parts[1], s), Fix.CONVERT);
                }
                case "mosaic.min", "mosaic.max" -> {
                    if (port.equals("b") && o != null && s != o)
                        error(n.id(), port, "node " + n.id() + ": " + n.type() + " needs operands of the same kind", Fix.CONVERT);
                }
                case "mosaic.dot" -> {
                    if (s == Kind.F || (port.equals("b") && o != null && s != o))
                        error(n.id(), port, "node " + n.id() + ": dot needs two vectors of the same kind, found " + s, Fix.CONVERT);
                }
                default -> { }
            }
        }

        // -------------------------------------------------------------- warnings and cost

        void warnings(Set<String> reachable) {
            boolean anyOutput = nodes.values().stream().anyMatch(n -> isOutput(n.type()) && !n.type().equals(MosaicStageMap.OUTPUT));
            for (Node n : nodes.values()) {
                if (anyOutput && !reachable.contains(n.id()) && !broken.contains(n.id()) && !n.type().startsWith("slang.")
                    && !n.type().startsWith(MosaicSlangGraph.FN))
                    warn(n.id(), "node " + n.id() + " is not connected to the output, so it does nothing", Fix.DELETE_NODE);
                if (n.type().equals("mosaic.div")) {
                    double[] b = constValue(n.inputs().get("b"));
                    if (b != null && allZero(b)) warn(n.id(), "node " + n.id() + ": division by a constant zero", Fix.CHANGE_PARAM);
                }
                if (n.type().equals("mosaic.sqrt")) {
                    double[] a = constValue(n.inputs().get("a"));
                    if (a != null && anyNegative(a)) warn(n.id(), "node " + n.id() + ": square root of a negative constant is NaN",
                        Fix.CHANGE_PARAM);
                }
                if (n.type().equals("mosaic.normalize")) {
                    double[] a = constValue(n.inputs().get("a"));
                    if (a != null && allZero(a)) warn(n.id(), "node " + n.id() + ": normalizing a zero vector is NaN", Fix.CHANGE_PARAM);
                }
            }
            int rays = 0;
            for (String id : reachable) {
                Node n = nodes.get(id);
                if (n != null && (n.type().equals("mosaic.trace") || n.type().equals("mosaic.shadow"))) rays++;
            }
            if (rays > 3)
                warn(null, rays + " ray queries per pixel: each one traces the whole scene, expect a large frame cost",
                    Fix.NONE);

        }

        double[] constValue(String ref) {
            if (ref == null) return null;
            Node src = nodes.get(split(ref)[0]);
            return src != null && src.type().equals("mosaic.const") ? src.value() : null;
        }

        static boolean allZero(double[] v) {
            for (double d : v) if (d != 0) return false;
            return true;
        }

        static boolean anyNegative(double[] v) {
            for (double d : v) if (d < 0) return true;
            return false;
        }

        Cost cost(Set<String> ids) {
            int rays = 0, samples = 0, ops = 0, count = 0;
            for (String id : ids) {
                Node n = nodes.get(id);
                if (n == null) continue;
                count++;
                Kind k = kinds.getOrDefault(id, Kind.V4);
                switch (n.type()) {
                    case "mosaic.trace", "mosaic.shadow" -> rays++;
                    case "mosaic.sceneColor", "mosaic.sceneDepth", "mosaic.lut" -> samples++;
                    case "mosaic.const", "mosaic.uv", "mosaic.texel", "mosaic.size", "mosaic.time", "mosaic.frame",
                         "mosaic.push", "mosaic.buffer", "mosaic.output", "mosaic.swizzle", "mosaic.vec" -> { }
                    case "mosaic.sqrt", "mosaic.exp", "mosaic.normalize", "mosaic.length" -> ops += 4 * k.size();
                    case "mosaic.waterDepth", "mosaic.waterMask", "mosaic.lavaMask", "mosaic.materialDepth" -> { samples++; ops += 120; }
                    case "mosaic.noise", "mosaic.wave" -> ops += 60;
                    case "mosaic.voronoi" -> ops += 90;
                    case "mosaic.colorRamp" -> ops += 12;
                    case "mosaic.hitMaterial", "mosaic.materialMask", "mosaic.skyLight", "mosaic.blockLight" -> { samples++; ops += 12; }
                    case "mosaic.position", "mosaic.worldPos" -> { samples++; ops += 16; }
                    case "mosaic.div" -> ops += 2 * k.size();
                    case "mosaic.eye" -> ops += 12;
                    default -> {
                        if (MosaicFunction.isFunctionType(n.type())) ops += 16;
                        else ops += k.size();
                    }
                }
            }
            return new Cost(rays, samples, ops, count);
        }
    }

    private static String sourceLabel(String id, String name, Kind k) {
        return (name.isEmpty() ? id : id + "." + name) + " (" + k + ")";
    }

    private static String label(Kind k) {
        if (k == Kind.S) return "a Slang value";
        return switch (k) { case F -> "a float"; case V2 -> "a vec2"; case V3 -> "a vec3"; case V4 -> "a vec4"; case S -> "a Slang value"; };
    }

    // ------------------------------------------------------------------ order

    /** Depth-first topological order - deterministic (graph insertion order). */
    public List<Node> ordered() {
        List<Node> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (Node n : nodes.values()) visit(n.id(), visited, out);
        return out;
    }

    private void visit(String id, Set<String> visited, List<Node> out) {
        if (!visited.add(id)) return;
        Node n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("unknown node " + id);
        for (String in : n.inputs().values()) visit(split(in)[0], visited, out);
        out.add(n);
    }

    /** Splits an edge reference "id" or "id.outname"; the primary output has no suffix. */
    public static String[] split(String ref) {
        int dot = ref.indexOf('.');
        if (dot <= 0 || dot == ref.length() - 1) return new String[] {ref, ""};
        return new String[] {ref.substring(0, dot), ref.substring(dot + 1)};
    }

    // ------------------------------------------------------------------ needs

    public boolean needsBuffer() { return uses("mosaic.buffer"); }
    public boolean needsLut() { return uses("mosaic.lut"); }

    /** A material graph (output {@code mat.output}): baked on the CPU, never compiled into a shader. */
    public boolean isMaterial() { return node(outputId) != null && node(outputId).type().equals("mat.output"); }
    public boolean needsPush() { return uses("mosaic.push"); }
    /** Highest push slot the graph reads; the emitter sizes the push block to cover exactly this. */
    public int maxPushIndex() {
        int max = -1;
        for (Node n : nodes.values())
            if (n.type().equals("mosaic.push")) max = Math.max(max, n.index());
        return max;
    }
    public boolean needsRay() { return uses("mosaic.trace") || uses("mosaic.shadow"); }
    public boolean needsDepth() {
        return uses("mosaic.sceneDepth") || uses("mosaic.position") || uses("mosaic.worldPos") || needsFluids();
    }
    public boolean needsFluids() {
        for (Node n : nodes.values()) if (n.type().startsWith("mosaic.water") || n.type().equals("mosaic.lavaMask")
            || n.type().equals("mosaic.underwater") || n.type().contains("aterial") || n.type().endsWith("Light")) return true;
        return false;
    }
    public boolean needsSceneColor() { return uses("mosaic.sceneColor"); }

    private boolean uses(String type) {
        for (Node n : nodes.values()) if (n.type().equals(type)) return true;
        return false;
    }

    /** All nodes in file order. */
    public java.util.Collection<Node> nodes() { return nodes.values(); }
}
