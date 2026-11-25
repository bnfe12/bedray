package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Bedray's Slang functions as Mosaic nodes, and graphs of them back to Slang.
 *
 * <p>Node types: {@code bedray.fn:<module>/<name>} calls a function (a method is {@code <Struct>.<name>} with a
 * {@code self} input), {@code bedray.make:<module>/<Struct>} builds a struct from its fields, {@code bedray.break:...}
 * splits one, {@code bedray.const:<module>/<NAME>} reads a {@code static const}. A function graph has
 * {@code slang.param} nodes (mask {@code "<type> <name>"}) for its parameters, one {@code slang.result} (mask: the
 * return type) and {@code slang.out} nodes (mask {@code "<type> <name>"}) for out parameters. Every port carries its
 * Slang type; float..float4 (and int/uint/bool, cast on the way in) are Mosaic's numeric kinds, everything else is
 * {@link MosaicGraph.Kind#S}, which only connects to the same Slang type.
 */
public final class MosaicSlangGraph {

    public static final String FN = "bedray.fn:", MAKE = "bedray.make:", BREAK = "bedray.break:", CONST = "bedray.const:";
    public static final String PARAM = "slang.param", RESULT = "slang.result", OUT = "slang.out";
    /** The ordering token of a void call: wire it into another call's {@code after} to keep side effects in order. */
    public static final String THEN = "then", AFTER = "after", SELF = "self";

    private static volatile MosaicSlangLib.Library lib;
    private static volatile Path root;

    private MosaicSlangGraph() {}

    public static void install(MosaicSlangLib.Library library, Path packRoot) {
        lib = library;
        root = packRoot;
    }

    /** The installed library, loading the bundled pack on first use when nothing was installed. */
    public static MosaicSlangLib.Library library() {
        if (lib == null) {
            Path r = bundledRoot();
            if (r != null) install(load(r), r);
        }
        return lib;
    }

    public static Path packRoot() {
        library();
        return root;
    }

    /** Every .slang file under a folder, keyed by its path relative to it ("shading/microfacet.slang"). */
    public static Map<String, String> sources(Path dir) {
        Map<String, String> src = new TreeMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".slang"))::iterator)
                src.put(dir.relativize(f).toString().replace('\\', '/'), Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Map.of();
        }
        return src;
    }

    public static MosaicSlangLib.Library load(Path dir) { return MosaicSlangLib.parse(sources(dir)); }

    /** The pack in a source checkout: -Dmosaic.slang, or common/src/main/resources/shaders/slang above the working folder. */
    public static Path bundledRoot() {
        String prop = System.getProperty("mosaic.slang");
        if (prop != null && Files.isDirectory(Path.of(prop))) return Path.of(prop);
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            for (String rel : new String[] {"common/src/main/resources/shaders/slang", "src/main/resources/shaders/slang",
                "main/resources/shaders/slang", "resources/shaders/slang"}) {
                Path c = p.resolve(rel);
                if (Files.isRegularFile(c.resolve("bedray.slang"))) return c;
            }
        }
        return null;
    }

    public static boolean handles(String type) {
        return type.startsWith(FN) || type.startsWith(MAKE) || type.startsWith(BREAK) || type.startsWith(CONST)
            || type.equals(PARAM) || type.equals(RESULT) || type.equals(OUT);
    }

    /** A function of the library, or null. */
    public static MosaicSlangLib.Function function(String type) {
        MosaicSlangLib.Library l = library();
        return l == null || !type.startsWith(FN) ? null : l.functions().get(type.substring(FN.length()));
    }

    public static MosaicSlangLib.Struct struct(String type) {
        MosaicSlangLib.Library l = library();
        if (l == null) return null;
        String id = type.startsWith(MAKE) ? type.substring(MAKE.length()) : type.startsWith(BREAK) ? type.substring(BREAK.length()) : null;
        return id == null ? null : l.structs().get(id);
    }

    public static MosaicSlangLib.Constant constant(String type) {
        MosaicSlangLib.Library l = library();
        return l == null || !type.startsWith(CONST) ? null : l.constants().get(type.substring(CONST.length()));
    }

    /** Is the type a real thing of the library (or a function-graph interface node)? */
    public static boolean isKnown(String type) {
        if (type.equals(PARAM) || type.equals(RESULT) || type.equals(OUT)) return true;
        MosaicSlangLib.Function f = function(type);
        if (f != null) return f.isNode();
        return struct(type) != null || constant(type) != null;
    }

    // ------------------------------------------------------------------ types

    /** Mosaic kind of a Slang type: float..float4 and int/uint/bool vectors are numeric, the rest is S. */
    public static MosaicGraph.Kind kind(String slangType) {
        if (slangType == null) return MosaicGraph.Kind.S;
        String t = slangType.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(float|half|int|uint|bool|min16float)([1-4]?)$").matcher(t);
        if (!m.matches()) {
            MosaicGraph.Kind named = MosaicGraph.Kind.named(t);
            return named == null ? MosaicGraph.Kind.S : named;
        }
        return MosaicGraph.Kind.ofSize(m.group(2).isEmpty() ? 1 : Integer.parseInt(m.group(2)));
    }

    static boolean isFloatType(String t) { return t.matches("float[1-4]?|half[1-4]?"); }

    /** One port: name, Slang type, Mosaic kind, wired-in required. */
    public record SPort(String name, String slangType, MosaicGraph.Kind kind, boolean required) {
        public MosaicGraph.Port port() { return new MosaicGraph.Port(name, kind, required, slangType); }
    }

    private static String[] typedName(String mask, String fallbackType) {
        if (mask == null || mask.isBlank()) return new String[] {fallbackType, "value"};
        String m = mask.trim();
        int sp = m.lastIndexOf(' ');
        return sp < 0 ? new String[] {m, "value"} : new String[] {m.substring(0, sp).trim(), m.substring(sp + 1).trim()};
    }

    /** Input ports of a library type ({@code mask} is only read by the function-graph nodes). */
    public static List<SPort> inputs(String type, String mask) {
        List<SPort> out = new ArrayList<>();
        switch (type) {
            case PARAM -> { return out; }
            case RESULT -> {
                String t = mask == null || mask.isBlank() ? "float4" : mask.trim();
                out.add(new SPort("value", t, kind(t), true));
                return out;
            }
            case OUT -> {
                String t = typedName(mask, "float")[0];
                out.add(new SPort("value", t, kind(t), true));
                return out;
            }
            default -> { }
        }
        MosaicSlangLib.Function f = function(type);
        if (f != null) {
            if (f.owner() != null) out.add(new SPort(SELF, f.owner(), MosaicGraph.Kind.S, true));
            for (MosaicSlangLib.Param p : f.params()) if (p.isIn()) out.add(new SPort(p.name(), p.type(), kind(p.type()), false));
            out.add(new SPort(AFTER, "void", MosaicGraph.Kind.S, false));
            return out;
        }
        MosaicSlangLib.Struct s = struct(type);
        if (s != null) {
            if (type.startsWith(MAKE)) for (MosaicSlangLib.Field fl : s.fields()) out.add(new SPort(fl.name(), fl.type(), kind(fl.type()), false));
            else out.add(new SPort("value", s.name(), MosaicGraph.Kind.S, true));
        }
        return out;
    }

    /** Output ports: the primary output first (name ""), then named ones. */
    public static List<SPort> outputs(String type, String mask) {
        List<SPort> out = new ArrayList<>();
        switch (type) {
            case PARAM -> {
                String t = typedName(mask, "float")[0];
                out.add(new SPort("", t, kind(t), false));
                return out;
            }
            case RESULT, OUT -> { return out; }
            default -> { }
        }
        MosaicSlangLib.Function f = function(type);
        if (f != null) {
            if (!f.isVoid()) out.add(new SPort("", f.returnType(), kind(f.returnType()), false));
            else out.add(new SPort("", "void", MosaicGraph.Kind.S, false));
            if (f.owner() != null && f.mutating()) out.add(new SPort(SELF, f.owner(), MosaicGraph.Kind.S, false));
            for (MosaicSlangLib.Param p : f.params()) if (p.isOut()) out.add(new SPort(p.name(), p.type(), kind(p.type()), false));
            if (f.isVoid()) out.add(new SPort(THEN, "void", MosaicGraph.Kind.S, false));
            return out;
        }
        MosaicSlangLib.Struct s = struct(type);
        if (s != null) {
            if (type.startsWith(MAKE)) out.add(new SPort("", s.name(), MosaicGraph.Kind.S, false));
            else {
                out.add(new SPort("", s.name(), MosaicGraph.Kind.S, false));
                for (MosaicSlangLib.Field fl : s.fields()) out.add(new SPort(fl.name(), fl.type(), kind(fl.type()), false));
            }
            return out;
        }
        MosaicSlangLib.Constant c = constant(type);
        if (c != null) out.add(new SPort("", c.type(), kind(c.type()), false));
        return out;
    }

    public static SPort output(String type, String mask, String name) {
        for (SPort p : outputs(type, mask)) if (p.name().equals(name)) return p;
        return null;
    }

    public static SPort input(String type, String mask, String name) {
        for (SPort p : inputs(type, mask)) if (p.name().equals(name)) return p;
        return null;
    }

    /** Short label for menus and node heads. */
    public static String label(String type, String mask) {
        MosaicSlangLib.Function f = function(type);
        if (f != null) return f.label();
        MosaicSlangLib.Struct s = struct(type);
        if (s != null) return (type.startsWith(MAKE) ? "Make " : "Break ") + s.name();
        MosaicSlangLib.Constant c = constant(type);
        if (c != null) return c.name();
        return switch (type) {
            case PARAM -> "Parameter " + typedName(mask, "float")[1];
            case RESULT -> "Return";
            case OUT -> "Out " + typedName(mask, "float")[1];
            default -> type;
        };
    }

    /** The module a library type belongs to, or null. */
    public static String moduleOf(String type) {
        MosaicSlangLib.Function f = function(type);
        if (f != null) return f.module();
        MosaicSlangLib.Struct s = struct(type);
        if (s != null) return s.module();
        MosaicSlangLib.Constant c = constant(type);
        return c == null ? null : c.module();
    }

    // ------------------------------------------------------------------ codegen

    /** A function written by a graph: its Slang text and the modules it needs. */
    public record Code(String text, Set<String> imports, List<String> problems) {}

    /**
     * Writes the graph as a Slang function. Parameters come from {@code slang.param} nodes (in the order they appear),
     * out parameters from {@code slang.out} nodes; the return type is the {@code slang.result} mask ("void" when there is none).
     */
    public static Code function(MosaicGraph g, String name) {
        Emitter e = new Emitter(g);
        List<String> params = new ArrayList<>();
        String ret = "void";
        MosaicGraph.Node result = null;
        List<MosaicGraph.Node> outs = new ArrayList<>();
        for (MosaicGraph.Node n : g.nodes()) {
            switch (n.type()) {
                case PARAM -> {
                    String[] tn = typedName(n.mask(), "float");
                    params.add(tn[0] + " " + tn[1]);
                    e.names.put(n.id(), tn[1]);
                }
                case RESULT -> {
                    result = n;
                    ret = n.mask() == null || n.mask().isBlank() ? "float4" : n.mask().trim();
                }
                case OUT -> {
                    outs.add(n);
                    String[] tn = typedName(n.mask(), "float");
                    params.add("out " + tn[0] + " " + tn[1]);
                }
                default -> { }
            }
        }
        for (MosaicGraph.Node o : outs) {
            String[] tn = typedName(o.mask(), "float");
            String v = e.input(o, "value", tn[0]);
            e.body.append("    ").append(tn[1]).append(" = ").append(v).append(";\n");
        }
        String retExpr = result == null ? null : e.input(result, "value", ret);
        e.flushSideEffects();
        StringBuilder sb = new StringBuilder();
        sb.append(ret).append(' ').append(name).append('(').append(String.join(", ", params)).append(") {\n");
        sb.append(e.body);
        if (retExpr != null) sb.append("    return ").append(retExpr).append(";\n");
        sb.append("}\n");
        return new Code(sb.toString(), e.imports, e.problems);
    }

    /** A stand-alone compute shader that runs a function graph once (what {@link #check} compiles). */
    public static String checkUnit(Code code) {
        StringBuilder sb = new StringBuilder("// Mosaic: a Bedray function graph\n");
        for (String m : code.imports()) sb.append(MosaicSlangLib.importOf(m)).append('\n');
        sb.append('\n').append(code.text()).append('\n');
        sb.append("[shader(\"compute\")]\n[numthreads(1, 1, 1)]\nvoid main() {}\n");
        return sb.toString();
    }

    /** slangc result. */
    public record Check(boolean ok, String log, byte[] spirv) {}

    /** Compiles a unit against the pack (-I the pack folder) with Bedray's own flags. */
    public static Check check(String unit, Path slangc, Path packRoot) {
        if (slangc == null) return new Check(false, "slangc not found (Settings > Compiler)", null);
        if (packRoot == null) return new Check(false, "Bedray's Slang pack was not found", null);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mosaic-fn");
            Path src = dir.resolve("unit.slang"), spv = dir.resolve("unit.spv");
            Files.writeString(src, unit, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder(slangc.toString(), src.toString(), "-target", "spirv", "-profile", "cs_6_5", "-entry", "main",
                "-stage", "compute", "-matrix-layout-column-major", "-I", packRoot.toString(), "-o", spv.toString())
                .redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Check(false, "slangc timed out", null);
            }
            boolean ok = p.exitValue() == 0 && Files.isRegularFile(spv);
            return new Check(ok, log.trim(), ok ? Files.readAllBytes(spv) : null);
        } catch (IOException e) {
            return new Check(false, "slangc: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(false, "interrupted", null);
        } finally {
            if (dir != null) try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (IOException ignored) {
                // temp folder
            }
        }
    }

    /** Writes expressions for nodes on demand (each node once, in dependency order). */
    static final class Emitter {
        final MosaicGraph g;
        final StringBuilder body = new StringBuilder();
        final Map<String, String> names = new LinkedHashMap<>();
        final Set<String> done = new LinkedHashSet<>();
        final Set<String> imports = new LinkedHashSet<>();
        final List<String> problems = new ArrayList<>();

        Emitter(MosaicGraph g) { this.g = g; }

        String var(String id) { return "n_" + id.replaceAll("[^A-Za-z0-9_]", "_"); }

        /** Void calls nobody ordered after: still run (their side effects are the point), in graph order. */
        void flushSideEffects() {
            for (MosaicGraph.Node n : g.nodes()) {
                MosaicSlangLib.Function f = function(n.type());
                if (f != null && f.isVoid()) emit(n.id());
            }
        }

        /** The expression feeding {@code port} of {@code n}, converted to {@code want}. */
        String input(MosaicGraph.Node n, String port, String want) {
            String ref = n.inputs().get(port);
            if (ref == null) {
                double[] d = n.defaultOf(port);
                if (d == null) return zero(want);
                return literal(d, want);
            }
            String[] parts = MosaicGraph.split(ref);
            String v = ref(parts[0], parts[1]);
            String have = typeOf(parts[0], parts[1]);
            return convert(v, have, want);
        }

        String typeOf(String id, String port) {
            MosaicGraph.Node n = g.node(id);
            if (n == null) return "float";
            if (handles(n.type())) {
                SPort p = output(n.type(), n.mask(), port);
                return p == null ? "float" : p.slangType();
            }
            MosaicGraph.Kind k = port.isEmpty() ? g.kind(id) : MosaicGraph.outputKind(n.type(), port);
            return k == null ? "float" : k.slang();
        }

        static String convert(String v, String have, String want) {
            if (want == null || have.equals(want) || want.equals("void")) return v;
            MosaicGraph.Kind kh = kind(have), kw = kind(want);
            if (kh == MosaicGraph.Kind.S || kw == MosaicGraph.Kind.S) return v;
            if (kh == kw && !isFloatType(want)) return want + "(" + v + ")";
            if (kh == kw) return v;
            if (kh == MosaicGraph.Kind.F) return want + "(" + v + ")";
            // narrowing / widening between vectors: take the leading components, pad with 0 (alpha 1)
            int hs = kh.size(), ws = kw.size();
            if (ws < hs) return (isFloatType(want) ? "" : want) + "(" + v + "." + "xyzw".substring(0, ws) + ")";
            StringBuilder sb = new StringBuilder(want).append('(').append(v);
            for (int i = hs; i < ws; i++) sb.append(i == 3 ? ", 1.0" : ", 0.0");
            return sb.append(')').toString();
        }

        static String zero(String type) {
            MosaicGraph.Kind k = kind(type);
            if (k == MosaicGraph.Kind.S) return "(" + type + ")0";
            return type + "(0)";
        }

        static String num(double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e9) return String.format(Locale.ROOT, "%.1f", d);
            return String.format(Locale.ROOT, "%.9g", d).replaceAll("0+(e|$)", "$1").replaceAll("\\.(e|$)", ".0$1");
        }

        static String literal(double[] d, String type) {
            MosaicGraph.Kind k = kind(type);
            if (k == MosaicGraph.Kind.S) return zero(type);
            int n = k.size();
            StringBuilder sb = new StringBuilder();
            boolean integral = !isFloatType(type);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                double v = i < d.length ? d[i] : d.length == 1 ? d[0] : 0;
                sb.append(integral ? Long.toString(Math.round(v)) : num(v));
            }
            return n == 1 && !integral ? sb.toString() : type + "(" + sb + ")";
        }

        String ref(String id, String port) {
            MosaicGraph.Node n = g.node(id);
            if (n == null) return "0";
            emit(id);
            if (n.type().equals(PARAM)) return names.get(id);
            MosaicSlangLib.Function f = function(n.type());
            if (f != null) {
                if (port.isEmpty()) return f.isVoid() ? "0" : var(id);
                if (port.equals(THEN)) return "0";
                return var(id) + "_" + port;
            }
            if (n.type().startsWith(BREAK)) return port.isEmpty() ? var(id) : var(id) + "." + port;
            if (n.type().startsWith(CONST)) return constant(n.type()).name();
            return var(id) + (port.isEmpty() ? "" : "_" + port);
        }

        void emit(String id) {
            if (!done.add(id)) return;
            MosaicGraph.Node n = g.node(id);
            if (n == null) return;
            String t = n.type();
            String v = var(id);
            MosaicSlangLib.Function f = function(t);
            if (f != null) {
                imports.add(f.module());
                if (n.inputs().containsKey(AFTER)) {
                    String[] parts = MosaicGraph.split(n.inputs().get(AFTER));
                    emit(parts[0]);
                }
                List<String> args = new ArrayList<>();
                for (MosaicSlangLib.Param p : f.params()) {
                    String pv = v + "_" + p.name();
                    switch (p.direction()) {
                        case "out" -> {
                            line(p.type() + " " + pv + ";");
                            args.add(pv);
                        }
                        case "inout" -> {
                            line(p.type() + " " + pv + " = " + input(n, p.name(), p.type()) + ";");
                            args.add(pv);
                        }
                        default -> args.add(n.inputs().containsKey(p.name()) || n.defaultOf(p.name()) != null || p.defaultValue() == null
                            ? input(n, p.name(), p.type()) : p.defaultValue());
                    }
                }
                String call = f.name() + "(" + String.join(", ", args) + ")";
                if (f.owner() != null) {
                    line(f.owner() + " " + v + "_self = " + input(n, SELF, f.owner()) + ";");
                    call = v + "_self." + call;
                }
                line(f.isVoid() ? call + ";" : f.returnType() + " " + v + " = " + call + ";");
                return;
            }
            MosaicSlangLib.Struct s = struct(t);
            if (s != null) {
                imports.add(s.module());
                if (t.startsWith(MAKE)) {
                    line(s.name() + " " + v + " = (" + s.name() + ")0;");
                    for (MosaicSlangLib.Field fl : s.fields())
                        if (n.inputs().containsKey(fl.name()) || n.defaultOf(fl.name()) != null)
                            line(v + "." + fl.name() + " = " + input(n, fl.name(), fl.type()) + ";");
                } else {
                    line(s.name() + " " + v + " = " + input(n, "value", s.name()) + ";");
                }
                return;
            }
            MosaicSlangLib.Constant c = constant(t);
            if (c != null) {
                imports.add(c.module());
                return;
            }
            if (t.equals(PARAM)) return;
            line(g.kind(id).slang() + " " + v + " = " + mosaic(n) + ";");
        }

        void line(String s) { body.append("    ").append(s).append('\n'); }

        /** The everyday Mosaic nodes inside a function graph (all produce float4 internally, converted on use). */
        String mosaic(MosaicGraph.Node n) {
            MosaicGraph.Kind k = g.kind(n.id());
            String a = n.inputs().containsKey("a") || n.defaultOf("a") != null ? input(n, "a", k.slang()) : null;
            String b = n.inputs().containsKey("b") || n.defaultOf("b") != null ? input(n, "b", k.slang()) : null;
            String expr = switch (n.type()) {
                case "mosaic.const" -> literal(n.value(), k.slang());
                case "mosaic.vec" -> {
                    List<String> parts = new ArrayList<>();
                    for (String p : List.of("x", "y", "z", "w").subList(0, Math.clamp(n.index(), 1, 4))) parts.add(input(n, p, "float"));
                    yield k.slang() + "(" + String.join(", ", parts) + ")";
                }
                case "mosaic.swizzle" -> {
                    String[] src = MosaicGraph.split(n.inputs().getOrDefault("a", ""));
                    yield "(" + ref(src[0], src[1]) + ")." + n.mask();
                }
                case "mosaic.add" -> "(" + a + " + " + b + ")";
                case "mosaic.sub" -> "(" + a + " - " + b + ")";
                case "mosaic.mul" -> "(" + a + " * " + b + ")";
                case "mosaic.div" -> "(" + a + " / " + b + ")";
                case "mosaic.min" -> "min(" + a + ", " + b + ")";
                case "mosaic.max" -> "max(" + a + ", " + b + ")";
                case "mosaic.neg" -> "(-" + a + ")";
                case "mosaic.abs", "mosaic.floor", "mosaic.sqrt", "mosaic.exp", "mosaic.normalize", "mosaic.saturate" ->
                    n.type().substring("mosaic.".length()) + "(" + a + ")";
                case "mosaic.fract" -> "frac(" + a + ")";
                case "mosaic.reroute" -> a;
                case "mosaic.mix" -> "lerp(" + a + ", " + b + ", " + input(n, "t", k.slang()) + ")";
                case "mosaic.clamp" -> "clamp(" + a + ", " + input(n, "lo", k.slang()) + ", " + input(n, "hi", k.slang()) + ")";
                case "mosaic.dot" -> "dot(" + input(n, "a", "float3") + ", " + input(n, "b", "float3") + ")";
                case "mosaic.cross" -> "cross(" + input(n, "a", "float3") + ", " + input(n, "b", "float3") + ")";
                case "mosaic.length" -> "length(" + a + ")";
                default -> {
                    problems.add("node " + n.id() + " (" + n.type() + ") is not available inside a Bedray function graph");
                    yield zero(k.slang());
                }
            };
            return expr;
        }
    }
}
