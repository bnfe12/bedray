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
    /** Pieces of a function body (see {@link MosaicSlangDecompile}). */
    public static final String LITERAL = "slang.literal", OP = "slang.op", CALL = "slang.call", DO = "slang.do", MEMBER = "slang.member",
        SET = "slang.set", LET = "slang.let", CODE = "slang.code";
    /** A stage setting as a public parameter (mask: its bedray.toml key, value: the number). */
    public static final String SETTING = "bedray.setting";
    static final Set<String> BODY = Set.of(PARAM, RESULT, OUT, LITERAL, OP, CALL, DO, MEMBER, SET, LET, CODE, SETTING);
    /** A port that takes or gives any Slang value (slangc checks it). */
    public static final String AUTO = "auto";
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
        return type.startsWith(FN) || type.startsWith(MAKE) || type.startsWith(BREAK) || type.startsWith(CONST) || BODY.contains(type);
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
        if (BODY.contains(type)) return true;
        MosaicSlangLib.Function f = function(type);
        if (f != null) return f.isNode() || f.entry(); // a pass entry is a node of stage maps (it opens; nothing calls it)
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
    public static List<SPort> inputs(String type, String mask) { return inputs(type, mask, 8); }

    public static List<SPort> inputs(String type, String mask, int index) {
        List<SPort> out = new ArrayList<>();
        switch (type) {
            case PARAM -> { return out; }
            case RESULT -> {
                String t = mask == null || mask.isBlank() ? "float4" : mask.trim();
                out.add(new SPort("value", t, kind(t), !t.equals("void")));
                return out;
            }
            case OUT, LET -> {
                String t = typedName(mask, "float")[0];
                out.add(new SPort("value", t, kind(t), type.equals(OUT)));
                return out;
            }
            case LITERAL, SETTING -> { return out; }
            case OP -> {
                String op = mask == null ? "+" : mask;
                out.add(new SPort("a", AUTO, MosaicGraph.Kind.S, true));
                if (!(op.length() == 2 && op.endsWith("u"))) out.add(new SPort("b", AUTO, MosaicGraph.Kind.S, true));
                if (op.equals("?:")) out.add(new SPort("c", AUTO, MosaicGraph.Kind.S, true));
                return out;
            }
            case MEMBER -> {
                out.add(new SPort("a", AUTO, MosaicGraph.Kind.S, true));
                return out;
            }
            case SET -> {
                out.add(new SPort("a", AUTO, MosaicGraph.Kind.S, true));
                out.add(new SPort("value", AUTO, MosaicGraph.Kind.S, true));
                return out;
            }
            case CALL, DO -> {
                if (mask != null && mask.startsWith(".")) out.add(new SPort(SELF, AUTO, MosaicGraph.Kind.S, true));
                for (int i = 0; i < Math.max(0, index); i++) out.add(new SPort("a" + i, AUTO, MosaicGraph.Kind.S, false));
                if (type.equals(DO)) out.add(new SPort(AFTER, "void", MosaicGraph.Kind.S, false));
                return out;
            }
            case CODE -> {
                for (String[] p : codeSignature(mask).params()) out.add(new SPort(p[2], p[1], kind(p[1]), false));
                out.add(new SPort(AFTER, "void", MosaicGraph.Kind.S, false));
                return out;
            }
            default -> { }
        }
        MosaicSlangLib.Function f = function(type);
        if (f != null) {
            if (f.owner() != null) out.add(new SPort(SELF, f.owner(), MosaicGraph.Kind.S, false));
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
            case LET -> {
                String t = typedName(mask, "float")[0];
                out.add(new SPort("", t, kind(t), false));
                return out;
            }
            case LITERAL, OP, CALL, MEMBER, SET -> {
                out.add(new SPort("", AUTO, MosaicGraph.Kind.S, false));
                return out;
            }
            case SETTING -> {
                out.add(new SPort("", "float", MosaicGraph.Kind.F, false));
                return out;
            }
            case DO -> {
                out.add(new SPort("", "void", MosaicGraph.Kind.S, false));
                return out;
            }
            case CODE -> {
                CodeSig sig = codeSignature(mask);
                out.add(new SPort("", sig.ret(), kind(sig.ret()), false));
                for (String[] p : sig.params()) if (p[0].equals("inout")) out.add(new SPort(p[2], p[1], kind(p[1]), false));
                return out;
            }
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
        if ((type.equals(CALL) || type.equals(DO)) && (name.matches("a\\d+") || name.equals(SELF) || name.equals(AFTER)))
            return new SPort(name, name.equals(AFTER) ? "void" : AUTO, MosaicGraph.Kind.S, false);
        for (SPort p : inputs(type, mask)) if (p.name().equals(name)) return p;
        return null;
    }

    /** The interface of a code block: "//@ in float3 V; inout uint seed; ret float3" on its first line. */
    public record CodeSig(List<String[]> params, String ret, String body) {}

    public static CodeSig codeSignature(String mask) {
        List<String[]> params = new ArrayList<>();
        String ret = "void", body = mask == null ? "" : mask;
        if (mask != null && mask.startsWith("//@")) {
            int nl = mask.indexOf('\n');
            String head = (nl < 0 ? mask : mask.substring(0, nl)).substring(3);
            body = nl < 0 ? "" : mask.substring(nl + 1);
            for (String part : head.split(";")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (p.startsWith("ret ")) { ret = p.substring(4).trim(); continue; }
                String[] w = p.split("\\s+");
                if (w.length >= 3) params.add(new String[] {w[0], String.join(" ", java.util.Arrays.copyOfRange(w, 1, w.length - 1)), w[w.length - 1]});
            }
        }
        return new CodeSig(params, ret, body);
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
            case LET -> typedName(mask, "float")[1];
            case LITERAL -> mask == null ? "literal" : mask;
            case OP -> "op " + (mask == null ? "" : mask.replaceAll("u$", ""));
            case CALL, DO -> mask == null ? "call" : mask;
            case MEMBER -> mask == null ? "member" : mask;
            case SET -> "set " + (mask == null ? "" : mask);
            case CODE -> "Slang block";
            case SETTING -> {
                MosaicRenderer.Setting st = MosaicRenderer.setting(mask);
                yield st == null ? String.valueOf(mask) : st.label();
            }
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
    public record Code(String text, Set<String> imports, List<String> problems, String helpers, String body) {}

    /**
     * Writes the graph as a Slang function. Parameters come from {@code slang.param} nodes (in the order they appear),
     * out parameters from {@code slang.out} nodes; the return type is the {@code slang.result} mask ("void" when there is none).
     */
    public static Code function(MosaicGraph g, String name) {
        Emitter e = new Emitter(g);
        e.fnName = name;
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
        String retExpr = null;
        if (result != null && ret.equals("void")) {
            if (result.inputs().containsKey("value")) e.emit(MosaicGraph.split(result.inputs().get("value"))[0]);
        } else if (result != null) retExpr = e.input(result, "value", ret);
        e.flushSideEffects();
        StringBuilder body = new StringBuilder(e.body);
        if (retExpr != null) body.append("    return ").append(retExpr).append(";\n");
        StringBuilder sb = new StringBuilder(e.helpers);
        sb.append(ret).append(' ').append(name).append('(').append(String.join(", ", params)).append(") {\n");
        sb.append(body);
        sb.append("}\n");
        return new Code(sb.toString(), e.imports, e.problems, e.helpers.toString(), body.toString());
    }

    /**
     * The module source with one function's body replaced by a graph's (the signature, attributes and defaults stay
     * the original's; code-block helpers go right above it, mutating or static like it). Missing imports are added.
     */
    public static String override(String moduleSource, MosaicSlangLib.Function f, Code code) {
        String head = moduleSource.substring(f.start(), f.bodyStart());
        String mods = (head.contains("[mutating]") ? "[mutating] " : "") + (head.matches("(?s).*\\bstatic\\s.*") ? "static " : "");
        String indent = indentOf(moduleSource, f.start());
        StringBuilder helpers = new StringBuilder();
        if (!code.helpers().isBlank()) for (String line : code.helpers().split("\n", -1)) {
            if (line.isEmpty()) { helpers.append('\n'); continue; }
            boolean top = !Character.isWhitespace(line.charAt(0)) && !line.equals("}");
            helpers.append(indent).append(top ? mods : "").append(line).append('\n');
        }
        StringBuilder body = new StringBuilder("{\n");
        for (String line : code.body().split("\n")) if (!line.isEmpty()) body.append(indent).append(line).append('\n');
        body.append(indent).append('}');
        return moduleSource.substring(0, f.start()) + helpers.toString().stripLeading()
            + (helpers.length() > 0 ? indent : "") + head + body + moduleSource.substring(f.end());
    }

    /** Several functions of one module at once (from the last to the first, so offsets hold), then the imports they need. */
    public static String overrideAll(String moduleSource, String module, Map<MosaicSlangLib.Function, Code> codes) {
        List<Map.Entry<MosaicSlangLib.Function, Code>> list = new ArrayList<>(codes.entrySet());
        list.sort((a, b) -> Integer.compare(b.getKey().start(), a.getKey().start()));
        String src = moduleSource;
        Set<String> imports = new LinkedHashSet<>();
        for (Map.Entry<MosaicSlangLib.Function, Code> e : list) {
            src = override(src, e.getKey(), e.getValue());
            imports.addAll(e.getValue().imports());
        }
        return withImports(src, module, imports);
    }

    static String indentOf(String s, int at) {
        int ls = s.lastIndexOf('\n', at - 1) + 1;
        int k = ls;
        while (k < at && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k++;
        return s.substring(ls, k);
    }

    /** Adds {@code import} lines for modules the text does not import yet (after its last import). */
    public static String withImports(String src, String self, Set<String> modules) {
        StringBuilder add = new StringBuilder();
        for (String m : modules) {
            if (m.equals(self)) continue;
            if (src.contains("\"" + m + ".slang\"")) continue;
            add.append(MosaicSlangLib.importOf(m)).append('\n');
        }
        if (add.length() == 0) return src;
        java.util.regex.Matcher im = java.util.regex.Pattern.compile("(?m)^\\s*(__exported\\s+)?import\\s+[^;]+;\\s*$").matcher(src);
        int at = -1;
        while (im.find()) at = im.end();
        if (at < 0) {
            java.util.regex.Matcher mod = java.util.regex.Pattern.compile("(?m)^\\s*(public\\s+)?module\\s+\\w+\\s*;\\s*$").matcher(src);
            at = mod.find() ? mod.end() : 0;
        }
        return src.substring(0, at) + "\n" + add + src.substring(at);
    }

    /** Compiles the pack with some modules replaced: a unit that imports each of them. */
    public static Check checkModules(Map<String, String> replaced, Path slangc, Path packRoot) {
        if (slangc == null) return new Check(false, "slangc not found (Settings > Compiler)", null);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mosaic-pack");
            Path parent = packRoot.toAbsolutePath().getParent();
            Path copy = dir.resolve(packRoot.getFileName().toString());
            try (var w = Files.walk(parent)) {
                for (Path p : (Iterable<Path>) w::iterator) {
                    Path t = dir.resolve(parent.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t);
                }
            }
            StringBuilder unit = new StringBuilder("// Mosaic: the pack with graph-written functions\n");
            for (Map.Entry<String, String> e : replaced.entrySet()) {
                Files.writeString(copy.resolve(e.getKey() + ".slang"), e.getValue(), StandardCharsets.UTF_8);
                unit.append(MosaicSlangLib.importOf(e.getKey())).append('\n');
            }
            unit.append("[shader(\"compute\")]\n[numthreads(1, 1, 1)]\nvoid mosaicCheck() {}\n");
            return checkUnit(unit.toString(), slangc, copy, "mosaicCheck");
        } catch (IOException e) {
            return new Check(false, "copy of the pack: " + e.getMessage(), null);
        } finally {
            deleteTree(dir);
        }
    }

    static void deleteTree(Path dir) {
        if (dir == null) return;
        try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // temp folder
        }
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
    public static Check check(String unit, Path slangc, Path packRoot) { return checkUnit(unit, slangc, packRoot, "main"); }

    static Check checkUnit(String unit, Path slangc, Path packRoot, String entry) {
        if (slangc == null) return new Check(false, "slangc not found (Settings > Compiler)", null);
        if (packRoot == null) return new Check(false, "Bedray's Slang pack was not found", null);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mosaic-fn");
            Path src = dir.resolve("unit.slang"), spv = dir.resolve("unit.spv");
            Files.writeString(src, unit, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder(slangc.toString(), src.toString(), "-target", "spirv", "-profile", "cs_6_5", "-entry", entry,
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
        final StringBuilder helpers = new StringBuilder();
        String fnName = "fn";

        Emitter(MosaicGraph g) { this.g = g; }

        String var(String id) { return "n_" + id.replaceAll("[^A-Za-z0-9_]", "_"); }

        /** Void calls nobody ordered after: still run (their side effects are the point), in graph order. */
        void flushSideEffects() {
            for (MosaicGraph.Node n : g.nodes()) {
                MosaicSlangLib.Function f = function(n.type());
                if (f != null && f.isVoid() || n.type().equals(DO) || n.type().equals(CODE)) emit(n.id());
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
            if (want == null || have.equals(want) || want.equals("void") || have.equals(AUTO) || want.equals(AUTO)) return v;
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
            if (n.type().equals(LITERAL)) return n.mask();
            if (n.type().equals(MEMBER)) return "(" + input(n, "a", null) + ")" + n.mask();
            if (n.type().equals(DO)) return "0";
            if (n.type().equals(CODE)) return port.isEmpty() ? var(id) : var(id) + "_" + port;
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
            if (t.equals(PARAM) || t.equals(LITERAL) || t.equals(MEMBER)) return;
            if (n.inputs().containsKey(AFTER)) emit(MosaicGraph.split(n.inputs().get(AFTER))[0]);
            switch (t) {
                case LET -> {
                    String[] tn = typedName(n.mask(), "float");
                    String vt = tn[0].equals(AUTO) ? "var" : tn[0];
                    String arr = "";
                    if (vt.contains("[")) {
                        arr = vt.substring(vt.indexOf('['));
                        vt = vt.substring(0, vt.indexOf('['));
                    }
                    if (!n.inputs().containsKey("value") && n.defaultOf("value") == null) line(vt + " " + v + arr + ";");
                    else line(vt + " " + v + arr + " = " + input(n, "value", tn[0].equals(AUTO) ? null : tn[0]) + ";");
                    return;
                }
                case OP -> {
                    String op = n.mask() == null ? "+" : n.mask();
                    String a = input(n, "a", null);
                    String expr;
                    if (op.length() == 2 && op.endsWith("u")) expr = "(" + op.charAt(0) + a + ")";
                    else if (op.equals("?:")) expr = "(" + a + " ? " + input(n, "b", null) + " : " + input(n, "c", null) + ")";
                    else if (op.equals("[]")) expr = a + "[" + input(n, "b", null) + "]";
                    else expr = "(" + a + " " + op + " " + input(n, "b", null) + ")";
                    line("var " + v + " = " + expr + ";");
                    return;
                }
                case CALL, DO -> {
                    String callee = n.mask() == null ? "" : n.mask();
                    List<String> args = new ArrayList<>();
                    for (int i = 0; i < n.index(); i++) args.add(input(n, "a" + i, null));
                    String call;
                    if (callee.startsWith("(")) call = "(" + callee + "(" + String.join(", ", args) + "))";
                    else if (callee.startsWith(".")) call = "(" + input(n, SELF, null) + ")" + callee + "(" + String.join(", ", args) + ")";
                    else call = callee + "(" + String.join(", ", args) + ")";
                    line(t.equals(DO) ? call + ";" : "var " + v + " = " + call + ";");
                    return;
                }
                case SET -> {
                    line("var " + v + " = " + input(n, "a", null) + ";");
                    line(v + n.mask() + " = " + input(n, "value", null) + ";");
                    return;
                }
                case CODE -> {
                    CodeSig sig = codeSignature(n.mask());
                    String helper = "mosaic_" + fnName + "_" + v;
                    List<String> decl = new ArrayList<>(), args = new ArrayList<>();
                    for (String[] p : sig.params()) {
                        String pt = p[1], arr = "";
                        if (pt.contains("[")) {
                            arr = pt.substring(pt.indexOf('['));
                            pt = pt.substring(0, pt.indexOf('['));
                        }
                        decl.add(p[0] + " " + pt + " " + p[2] + arr);
                        boolean wired = n.inputs().containsKey(p[2]);
                        if (p[0].equals("inout") || !wired) {
                            // a variable the block fills (declared without a value, as in the original)
                            line(pt + " " + v + "_" + p[2] + arr + (wired ? " = " + input(n, p[2], p[1]) : "") + ";");
                            args.add(v + "_" + p[2]);
                        } else args.add(input(n, p[2], p[1]));
                    }
                    helpers.append(sig.ret()).append(' ').append(helper).append('(').append(String.join(", ", decl)).append(") {\n")
                        .append(indent(sig.body())).append("\n}\n\n");
                    String call = helper + "(" + String.join(", ", args) + ")";
                    line(sig.ret().equals("void") ? call + ";" : sig.ret() + " " + v + " = " + call + ";");
                    return;
                }
                default -> { }
            }
            line(g.kind(id).slang() + " " + v + " = " + mosaic(n) + ";");
        }

        void line(String s) { body.append("    ").append(s).append('\n'); }

        static String indent(String s) {
            StringBuilder sb = new StringBuilder();
            for (String l : s.strip().split("\n", -1)) sb.append("    ").append(l.stripTrailing()).append('\n');
            return sb.toString().stripTrailing();
        }

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
