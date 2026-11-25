package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens a function of Bedray's pack as a node graph: its parameters, every local variable (one {@code slang.let} per
 * assignment), operators, constructors, swizzles, struct fields, calls to other pack functions (real {@code bedray.fn}
 * nodes) and the return. Straight code becomes nodes; a control block ({@code if}, {@code for}, {@code while},
 * {@code switch}) is one {@code slang.code} node whose ports are the variables it reads and writes, so the whole
 * function is always a graph that {@link MosaicSlangGraph#function} turns back into the same Slang. The developer's
 * comments above a statement become the note of its node.
 */
public final class MosaicSlangDecompile {

    private MosaicSlangDecompile() {}

    /** The graph (document JSON with positions and notes), its size, and how much of it is code blocks. */
    public record Result(String json, int nodes, int codeBlocks, boolean whole) {}

    static final class Unsupported extends RuntimeException {
        Unsupported(String why) { super(why, null, false, false); }
    }

    private static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "do");
    private static final Set<String> OUT_BUILTINS = Set.of("sincos", "modf", "frexp", "InterlockedAdd", "InterlockedMin", "InterlockedMax",
        "InterlockedOr", "InterlockedAnd", "InterlockedXor", "InterlockedExchange", "InterlockedCompareExchange", "GetDimensions",
        "InterlockedCompareStore");
    private static final Pattern DECL = Pattern.compile(
        "^(?:(?:const|static|precise|uniform)\\s+)*([A-Za-z_][\\w.]*(?:<[^<>;=]*>)?(?:\\s*\\*)?)(?:\\s+|(?<=\\*))([A-Za-z_]\\w*)\\s*(\\[[^\\]]*\\])?\\s*(=|;|,|$)");
    /** Resource methods that read or write the resource, never their arguments. */
    private static final Set<String> NO_WRITE_METHODS = Set.of("Sample", "SampleLevel", "SampleGrad", "SampleBias", "SampleCmp",
        "SampleCmpLevelZero", "Load", "Load2", "Load3", "Load4", "Gather", "GatherRed", "GatherGreen", "GatherBlue", "GatherAlpha",
        "Store", "Store2", "Store3", "Store4", "IncrementCounter", "DecrementCounter", "Append", "Consume", "CalculateLevelOfDetail");

    /** Splits at commas outside (), [] and {}. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<' && false) depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
    private static final Pattern ASSIGN = Pattern.compile("^([A-Za-z_]\\w*)((?:\\s*\\.\\s*[A-Za-z_]\\w*|\\s*\\[[^\\]]*\\])*)\\s*(=|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|<<=|>>=)(?!=)");

    public static Result decompile(MosaicSlangLib.Function f, MosaicSlangLib.Library lib) {
        try {
            Builder b = new Builder(f, lib);
            b.guards = true;
            b.run();
            return b.result(true);
        } catch (Unsupported first) {
            // early returns with work after them that must not run both ways: the rest stays one block
        }
        try {
            Builder b = new Builder(f, lib);
            b.run();
            return b.result(true);
        } catch (Unsupported e) {
            Builder b = new Builder(f, lib);
            b.wholeAsCode(e.getMessage());
            return b.result(false);
        }
    }

    // ================================================================== statements

    record Stmt(String text, String note) {}

    /** Splits a body "{ ... }" into top-level statements with the comments above each. */
    static List<Stmt> statements(String body) {
        String b = body.trim();
        if (b.startsWith("{")) b = b.substring(1, b.lastIndexOf('}'));
        List<Stmt> out = new ArrayList<>();
        int i = 0, n = b.length();
        StringBuilder note = new StringBuilder();
        while (i < n) {
            char c = b.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && b.charAt(i + 1) == '/') {
                int e = b.indexOf('\n', i);
                if (e < 0) e = n;
                if (note.length() > 0) note.append(' ');
                note.append(b.substring(i + 2, e).trim());
                i = e;
                continue;
            }
            if (c == '/' && i + 1 < n && b.charAt(i + 1) == '*') {
                int e = b.indexOf("*/", i + 2);
                if (e < 0) e = n - 2;
                if (note.length() > 0) note.append(' ');
                note.append(b.substring(i + 2, e).replaceAll("\\s*\\n\\s*\\*?\\s*", " ").trim());
                i = e + 2;
                continue;
            }
            int end = statementEnd(b, i);
            out.add(new Stmt(b.substring(i, end).trim(), note.length() == 0 ? null : note.toString()));
            note.setLength(0);
            i = end;
        }
        return out;
    }

    /** End (exclusive) of the statement starting at {@code i}. */
    static int statementEnd(String s, int i) {
        int n = s.length();
        i = skipSpace(s, i);
        if (i >= n) return n;
        if (s.charAt(i) == '[') { // attribute such as [unroll] / [branch]
            int close = matching(s, i, '[', ']');
            return statementEnd(s, close + 1);
        }
        if (s.charAt(i) == '{') return matching(s, i, '{', '}') + 1;
        String word = wordAt(s, i);
        switch (word) {
            case "if" -> {
                int p = matching(s, skipSpace(s, i + 2), '(', ')');
                int e = statementEnd(s, p + 1);
                int k = skipSpace(s, e);
                if (wordAt(s, k).equals("else")) return statementEnd(s, k + 4);
                return e;
            }
            case "for", "while", "switch" -> {
                int p = matching(s, skipSpace(s, i + word.length()), '(', ')');
                return statementEnd(s, p + 1);
            }
            case "do" -> {
                int e = statementEnd(s, i + 2);
                int k = skipSpace(s, e); // while ( ... ) ;
                int p = matching(s, skipSpace(s, k + 5), '(', ')');
                int semi = s.indexOf(';', p);
                return semi < 0 ? n : semi + 1;
            }
            default -> { }
        }
        int depth = 0;
        for (int k = i; k < n; k++) {
            char c = s.charAt(k);
            if (c == '/' && k + 1 < n && s.charAt(k + 1) == '/') { int e = s.indexOf('\n', k); k = e < 0 ? n : e; continue; }
            if (c == '/' && k + 1 < n && s.charAt(k + 1) == '*') { int e = s.indexOf("*/", k + 2); k = e < 0 ? n : e + 1; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ';' && depth == 0) return k + 1;
        }
        return n;
    }

    static int skipSpace(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { int e = s.indexOf('\n', i); i = e < 0 ? s.length() : e; continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { int e = s.indexOf("*/", i + 2); i = e < 0 ? s.length() : e + 2; continue; }
            break;
        }
        return i;
    }

    static String wordAt(String s, int i) {
        int k = i;
        while (k < s.length() && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) k++;
        return s.substring(i, k);
    }

    static int matching(String s, int open, char o, char c) {
        if (open >= s.length() || s.charAt(open) != o) throw new Unsupported("expected " + o);
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == o) depth++;
            else if (ch == c && --depth == 0) return k;
        }
        throw new Unsupported("unbalanced " + o);
    }

    // ================================================================== tokens

    record Tok(String text, char kind) {} // kind: i ident, n number, s string, p punct

    static List<Tok> tokens(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        String[] ops = {"<<=", ">>=", "&&", "||", "==", "!=", "<=", ">=", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "++", "--", "::"};
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isLetter(c) || c == '_') {
                int k = i;
                while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) k++;
                out.add(new Tok(s.substring(i, k), 'i'));
                i = k;
                continue;
            }
            if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                int k = i;
                if (c == '0' && k + 1 < n && (s.charAt(k + 1) == 'x' || s.charAt(k + 1) == 'X')) {
                    k += 2;
                    while (k < n && (Character.isLetterOrDigit(s.charAt(k)))) k++;
                } else {
                    while (k < n && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '.')) k++;
                    if (k < n && (s.charAt(k) == 'e' || s.charAt(k) == 'E')) {
                        k++;
                        if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                        while (k < n && Character.isDigit(s.charAt(k))) k++;
                    }
                    while (k < n && "fFuUlLhH".indexOf(s.charAt(k)) >= 0) k++;
                }
                out.add(new Tok(s.substring(i, k), 'n'));
                i = k;
                continue;
            }
            if (c == '"') {
                int k = s.indexOf('"', i + 1);
                if (k < 0) k = n - 1;
                out.add(new Tok(s.substring(i, k + 1), 's'));
                i = k + 1;
                continue;
            }
            String op = null;
            for (String o : ops) if (s.startsWith(o, i)) { op = o; break; }
            if (op == null) op = String.valueOf(c);
            out.add(new Tok(op, 'p'));
            i += op.length();
        }
        return out;
    }

    // ================================================================== builder

    static final class Builder {
        final MosaicSlangLib.Function fn;
        final MosaicSlangLib.Library lib;
        final List<JsonObject> nodes = new ArrayList<>();
        final Map<String, String> vars = new LinkedHashMap<>();     // name -> ref of its current value (null: declared, no value)
        final Map<String, String> types = new HashMap<>();          // name -> declared type ("auto" when let/var)
        final Map<String, String> shared = new HashMap<>();         // literal / const text -> node id
        final Map<String, Integer> letCount = new HashMap<>();
        final Map<String, Set<String>> deps = new HashMap<>();      // node id -> source node ids (layout)
        String lastEffect;
        String resultRef;
        /** Early returns as selections: "if (c) return e;" then the rest gives "c ? e : rest" (only while nothing after it has effects). */
        boolean guards;
        final List<String[]> guardList = new ArrayList<>(); // {condition ref, value ref}
        final List<Map<String, String>> guardOuts = new ArrayList<>();
        int guardEffects = -1;
        int seq, codeBlocks;
        String pendingNote;
        final Set<String> moduleImports = new LinkedHashSet<>();

        Builder(MosaicSlangLib.Function fn, MosaicSlangLib.Library lib) {
            this.fn = fn;
            this.lib = lib;
            String src = lib.sources().entrySet().stream().filter(e -> MosaicSlangLib.moduleId(e.getKey()).equals(fn.module()))
                .map(Map.Entry::getValue).findFirst().orElse("");
            Matcher m = Pattern.compile("import\\s+\"([^\"]+)\"").matcher(src);
            while (m.find()) moduleImports.add(MosaicSlangLib.moduleId(m.group(1)));
            Matcher named = Pattern.compile("(?m)^\\s*(?:__exported\\s+)?import\\s+([A-Za-z_][\\w.]*)\\s*;").matcher(src);
            while (named.find()) moduleImports.add(named.group(1).replace('.', '/'));
        }

        String add(String type, String mask, int index, Map<String, String> inputs, String label) {
            String id = "n" + (++seq);
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("type", type);
            if (mask != null) o.addProperty("mask", mask);
            if (index != 0) o.addProperty("index", index);
            if (label != null) o.addProperty("label", label);
            if (pendingNote != null) {
                o.addProperty("note", pendingNote);
                pendingNote = null;
            }
            JsonObject in = new JsonObject();
            Set<String> d = new LinkedHashSet<>();
            for (Map.Entry<String, String> e : inputs.entrySet()) {
                if (e.getValue() == null) continue;
                in.addProperty(e.getKey(), e.getValue());
                d.add(MosaicGraph.split(e.getValue())[0]);
            }
            if (in.size() > 0) o.add("inputs", in);
            deps.put(id, d);
            nodes.add(o);
            return id;
        }

        void run() {
            for (MosaicSlangLib.Param p : fn.params()) {
                if (p.direction().equals("out")) {
                    vars.put(p.name(), null);
                } else {
                    String id = add(MosaicSlangGraph.PARAM, p.type() + " " + p.name(), 0, Map.of(), null);
                    vars.put(p.name(), id);
                }
                types.put(p.name(), p.type());
            }
            List<Stmt> stmts = statements(fn.body());
            for (int i = 0; i < stmts.size(); i++) {
                Stmt s = stmts.get(i);
                pendingNote = s.note();
                if (resultRef != null) throw new Unsupported("code after return");
                if (guards && guard(s.text())) continue;
                if (wordAt(s.text().trim(), 0).equals("if") && !containsReturn(s.text()) && selectIf(s.text())) continue;
                if (isControl(s.text())) {
                    if (!guardList.isEmpty()) throw new Unsupported("a block after an early return");
                    if (containsReturn(s.text())) {
                        StringBuilder tail = new StringBuilder();
                        for (int k = i; k < stmts.size(); k++) {
                            if (stmts.get(k).note() != null) tail.append("// ").append(stmts.get(k).note()).append('\n');
                            tail.append(stmts.get(k).text()).append('\n');
                        }
                        code(tail.toString(), true);
                        break;
                    }
                    code(s.text(), false);
                    continue;
                }
                int mark = nodes.size(), seqMark = seq;
                Map<String, String> varsMark = new LinkedHashMap<>(vars), typesMark = new HashMap<>(types), sharedMark = new HashMap<>(shared);
                Map<String, Integer> letMark = new HashMap<>(letCount);
                String effectMark = lastEffect;
                try {
                    statement(s.text());
                } catch (Unsupported e) {
                    if (containsReturn(s.text())) throw e;
                    while (nodes.size() > mark) deps.remove(nodes.remove(nodes.size() - 1).get("id").getAsString());
                    seq = seqMark;
                    vars.clear(); vars.putAll(varsMark);
                    types.clear(); types.putAll(typesMark);
                    shared.clear(); shared.putAll(sharedMark);
                    letCount.clear(); letCount.putAll(letMark);
                    lastEffect = effectMark;
                    pendingNote = s.note();
                    code(declarationsAsAssignments(s.text()), false);
                }
            }
            if (!guardList.isEmpty() && (lastEffect != null && guardEffects != effectCount || fn.isVoid()))
                throw new Unsupported("effects after an early return");
            for (MosaicSlangLib.Param p : fn.params()) {
                if (!p.isOut()) continue;
                String v = valueOf(p.name());
                for (int g = guardList.size() - 1; g >= 0; g--) {
                    String early = guardOuts.get(g).get(p.name());
                    if (early == null) early = valueOf(p.name());
                    if (!early.equals(v)) v = add(MosaicSlangGraph.OP, "?:", 0, map("a", guardList.get(g)[0], "b", early, "c", v), null);
                }
                add(MosaicSlangGraph.OUT, p.type() + " " + p.name(), 0, Map.of("value", v), null);
            }
            if (!fn.isVoid() && resultRef != null)
                for (int g = guardList.size() - 1; g >= 0; g--)
                    resultRef = add(MosaicSlangGraph.OP, "?:", 0, map("a", guardList.get(g)[0], "b", guardList.get(g)[1], "c", resultRef),
                        g == 0 ? "early return" : null);
            pendingNote = fn.comment();
            if (!fn.isVoid()) {
                if (resultRef == null) throw new Unsupported("no return");
                add(MosaicSlangGraph.RESULT, fn.returnType(), 0, Map.of("value", resultRef), null);
            } else {
                Map<String, String> in = new LinkedHashMap<>();
                if (lastEffect != null) in.put("value", lastEffect);
                add(MosaicSlangGraph.RESULT, "void", 0, in, null);
            }
        }

        /**
         * A declaration that has to stay Slang ("float4 d = tex.Load(p);"): the variable is declared here (its type
         * known) and the block assigns it, so the code after it still sees the variable.
         */
        String declarationsAsAssignments(String text) {
            String s = MosaicSlangLib.stripComments(text).trim();
            Matcher d = DECL.matcher(s);
            if (!d.find() || Set.of("return", "else", "case", "break", "continue", "discard").contains(d.group(1)) || s.startsWith(d.group(1) + "(")
                || !s.endsWith(";"))
                return text;
            String type = d.group(1);
            String rest = s.substring(s.indexOf(d.group(2), d.start(2)), s.length() - 1);
            StringBuilder out = new StringBuilder();
            for (String part : splitArgs(rest)) {
                String p = part.trim();
                String name = wordAt(p, 0);
                String after = p.substring(name.length()).trim();
                String t = type;
                if (after.startsWith("[")) {
                    int close = after.indexOf(']');
                    t = type + after.substring(0, close + 1);
                    after = after.substring(close + 1).trim();
                }
                types.put(name, t);
                vars.put(name, null);
                if (after.startsWith("=")) {
                    String value = after.substring(1).trim();
                    if (value.startsWith("{")) {
                        // an initializer list only initializes: build it in a local of the block, then copy it out
                        String arr = t.contains("[") ? t.substring(t.indexOf('[')) : "";
                        String base = t.contains("[") ? t.substring(0, t.indexOf('[')) : t;
                        out.append(base).append(' ').append(name).append("_init").append(arr).append(" = ").append(value).append(";\n");
                        out.append(name).append(" = ").append(name).append("_init;\n");
                    } else out.append(name).append(" = ").append(value).append(";\n");
                }
            }
            return out.length() == 0 ? "" : out.toString();
        }

        // ------------------------------------------------------------ if / else as selections

        static final Set<String> SAFE_CALLS = Set.of("abs", "min", "max", "clamp", "saturate", "lerp", "mix", "dot", "cross", "normalize",
            "length", "sqrt", "rsqrt", "pow", "exp", "exp2", "log", "log2", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "floor",
            "ceil", "frac", "fract", "fmod", "step", "smoothstep", "sign", "any", "all", "select", "distance", "reflect", "refract",
            "isnan", "isinf", "isfinite", "asuint", "asfloat", "asint", "round", "trunc", "mad", "fma", "rcp", "degrees", "radians",
            "countbits", "firstbithigh", "firstbitlow", "reversebits", "f16tof32", "f32tof16", "faceforward", "transpose", "mul", "determinant");
        final Map<String, Boolean> pure = new HashMap<>();

        /** A pack function that only computes: no indexing, no method calls (resources), only safe calls inside. */
        boolean pureFunction(String name, Set<String> seen) {
            Boolean known = pure.get(name);
            if (known != null) return known;
            if (!seen.add(name)) return false;
            boolean ok = false;
            for (MosaicSlangLib.Function f : lib.functions().values()) {
                if (f.owner() != null || !f.name().equals(name)) continue;
                ok = safeText(MosaicSlangLib.stripComments(f.body()), seen);
                if (!ok) break;
            }
            pure.put(name, ok);
            return ok;
        }

        /** Text that only computes: every call is an intrinsic, a constructor or a pure pack function; no [] and no method calls. */
        boolean safeText(String s, Set<String> seen) {
            List<Tok> ts = tokens(s);
            for (int i = 0; i < ts.size(); i++) {
                Tok t = ts.get(i);
                if (t.kind() == 'p' && t.text().equals("[")) return false;
                if (t.kind() == 'i' && (t.text().equals("return") || CONTROL.contains(t.text()) || OUT_BUILTINS.contains(t.text()))) return false;
                if (t.kind() == 'i' && i + 1 < ts.size() && ts.get(i + 1).text().equals("(")) {
                    if (i > 0 && ts.get(i - 1).text().equals(".")) return false;
                    String n = t.text();
                    if (!SAFE_CALLS.contains(n) && !isType(n) && !pureFunction(n, seen)) return false;
                }
            }
            return true;
        }

        /**
         * "if (a) { x = ...; } else if (b) { ... } else { ... }" whose branches only compute: every branch runs as nodes,
         * then each variable a branch changed becomes a choice (a ? then : else). False (nothing changed) when it is not one.
         */
        boolean selectIf(String text) {
            String s = MosaicSlangLib.stripComments(text).trim();
            List<String[]> arms = new ArrayList<>(); // {condition or null for else, body}
            int i = 0;
            try {
                while (true) {
                    i = skipSpace(s, i);
                    if (!wordAt(s, i).equals("if")) return false;
                    int open = skipSpace(s, i + 2), close = matching(s, open, '(', ')');
                    String cond = s.substring(open + 1, close);
                    int bodyStart = skipSpace(s, close + 1), bodyEnd = statementEnd(s, bodyStart);
                    arms.add(new String[] {cond, s.substring(bodyStart, bodyEnd)});
                    i = skipSpace(s, bodyEnd);
                    if (i >= s.length()) break;
                    if (!wordAt(s, i).equals("else")) return false;
                    i = skipSpace(s, i + 4);
                    if (wordAt(s, i).equals("if")) continue;
                    int e = statementEnd(s, i);
                    arms.add(new String[] {null, s.substring(i, e)});
                    if (skipSpace(s, e) < s.length()) return false;
                    break;
                }
            } catch (Unsupported e) {
                return false;
            }
            for (String[] arm : arms) {
                if (arm[0] != null && !safeText(arm[0], new java.util.HashSet<>())) return false;
                String body = arm[1].trim();
                if (body.startsWith("{")) body = body.substring(1, body.lastIndexOf('}'));
                if (!safeText(body, new java.util.HashSet<>())) return false;
                arm[1] = body;
            }
            int mark = nodes.size(), seqMark = seq;
            Map<String, String> base = new LinkedHashMap<>(vars);
            Map<String, String> typesMark = new HashMap<>(types), sharedMark = new HashMap<>(shared);
            Map<String, Integer> letMark = new HashMap<>(letCount);
            String effectMark = lastEffect;
            int effects = effectCount;
            try {
                List<String> conds = new ArrayList<>();
                List<Map<String, String>> results = new ArrayList<>();
                for (String[] arm : arms) {
                    vars.clear();
                    vars.putAll(base);
                    if (arm[0] != null) conds.add(expr(arm[0]));
                    for (Stmt st : statements("{" + arm[1] + "}")) {
                        if (isControl(st.text())) throw new Unsupported("a block inside a branch");
                        statement(st.text());
                    }
                    if (effectCount != effects) throw new Unsupported("a branch with effects");
                    Map<String, String> r = new LinkedHashMap<>();
                    for (String v : base.keySet()) r.put(v, vars.get(v));
                    results.add(r);
                }
                boolean hasElse = arms.get(arms.size() - 1)[0] == null;
                vars.clear();
                vars.putAll(base);
                for (String v : base.keySet()) {
                    String fallback = hasElse ? results.get(results.size() - 1).get(v) : base.get(v);
                    boolean changed = false;
                    for (Map<String, String> r : results) if (!java.util.Objects.equals(r.get(v), base.get(v))) changed = true;
                    if (!changed) continue;
                    if (fallback == null) fallback = valueOf(v);
                    String value = fallback;
                    for (int k = conds.size() - 1; k >= 0; k--) {
                        String then = results.get(k).get(v);
                        if (then == null) then = base.get(v) != null ? base.get(v) : valueOf(v);
                        if (then.equals(value)) continue;
                        value = add(MosaicSlangGraph.OP, "?:", 0, map("a", conds.get(k), "b", then, "c", value), k == 0 ? "if" : "else if");
                    }
                    let(v, value);
                }
                return true;
            } catch (Unsupported e) {
                while (nodes.size() > mark) deps.remove(nodes.remove(nodes.size() - 1).get("id").getAsString());
                seq = seqMark;
                vars.clear();
                vars.putAll(base);
                types.clear();
                types.putAll(typesMark);
                shared.clear();
                shared.putAll(sharedMark);
                letCount.clear();
                letCount.putAll(letMark);
                lastEffect = effectMark;
                effectCount = effects;
                return false;
            }
        }

        static boolean isControl(String s) {
            String w = wordAt(s, 0);
            return CONTROL.contains(w) || s.startsWith("{") || s.startsWith("[");
        }

        static boolean containsReturn(String s) {
            return Pattern.compile("\\breturn\\b").matcher(MosaicSlangLib.stripComments(s)).find();
        }

        String valueOf(String var) {
            String v = vars.get(var);
            if (v != null) return v;
            String t = types.get(var);
            if (t == null || t.equals("auto")) throw new Unsupported("variable " + var + " has no value");
            // declared without a value ("BlockEmitterSampler s;"): an empty variable node, filled by what follows
            int k = letCount.merge(var, 1, Integer::sum);
            String id = add(MosaicSlangGraph.LET, t + " " + var, 0, Map.of(), k == 1 ? var : var + " (" + k + ")");
            vars.put(var, id);
            return id;
        }

        String literal(String text) {
            return shared.computeIfAbsent("lit:" + text, k -> add(MosaicSlangGraph.LITERAL, text, 0, Map.of(), null));
        }

        int effectCount;

        void effect(String id) {
            lastEffect = id;
            effectCount++;
            if (!guardList.isEmpty()) throw new Unsupported("an effect after an early return");
        }

        /** "if (cond) return value;" (no else, no braces or one statement in them) as a guard; false when it is not one. */
        boolean guard(String text) {
            String s = MosaicSlangLib.stripComments(text).trim();
            Matcher m = Pattern.compile("^if\\s*\\(").matcher(s);
            if (!m.find() || fn.isVoid()) return false;
            int close;
            try {
                close = matching(s, m.end() - 1, '(', ')');
            } catch (Unsupported e) {
                return false;
            }
            String rest = s.substring(close + 1).trim();
            if (rest.startsWith("{") && rest.endsWith("}")) rest = rest.substring(1, rest.length() - 1).trim();
            if (!rest.startsWith("return") || rest.indexOf(';') != rest.length() - 1 || rest.contains("{")) return false;
            String value = rest.substring(6, rest.length() - 1).trim();
            if (value.isEmpty()) return false;
            String cond = expr(s.substring(m.end(), close));
            String v = expr(value);
            guardList.add(new String[] {cond, v});
            Map<String, String> outs = new HashMap<>();
            for (MosaicSlangLib.Param p : fn.params()) if (p.isOut() && vars.get(p.name()) != null) outs.put(p.name(), vars.get(p.name()));
            guardOuts.add(outs);
            guardEffects = effectCount;
            return true;
        }

        Map<String, String> after(Map<String, String> in) {
            if (lastEffect != null) in.put(MosaicSlangGraph.AFTER, lastEffect);
            return in;
        }

        // ------------------------------------------------------------ simple statements

        void statement(String text) {
            String s = MosaicSlangLib.stripComments(text).trim();
            if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
            if (s.isEmpty()) return;
            if (s.equals("return")) {
                resultRef = "void";
                return;
            }
            if (s.startsWith("return ") || s.startsWith("return(")) {
                resultRef = expr(s.substring(6).trim());
                return;
            }
            if (s.startsWith("let ") || s.startsWith("var ")) {
                for (String part : splitArgs(s.substring(4))) {
                    int eq = part.indexOf('=');
                    if (eq < 0) throw new Unsupported("let without value");
                    String name = part.substring(0, eq).trim().replaceAll(":.*", "").trim();
                    types.put(name, "auto");
                    let(name, expr(part.substring(eq + 1).trim()));
                }
                return;
            }
            Matcher d = DECL.matcher(s);
            if (d.find() && !Set.of("return", "else", "case", "break", "continue", "discard").contains(d.group(1))
                && !s.startsWith(d.group(1) + "(")) {
                String type = d.group(1);
                String rest = s.substring(s.indexOf(d.group(2), d.start(2)));
                for (String part : splitArgs(rest)) {
                    String p = part.trim();
                    String name = wordAt(p, 0);
                    if (p.substring(name.length()).trim().startsWith("[")) throw new Unsupported("local array " + name);
                    types.put(name, type);
                    int eq = p.indexOf('=');
                    if (eq < 0) vars.put(name, null);
                    else let(name, expr(p.substring(eq + 1).trim()));
                }
                return;
            }
            Matcher a = ASSIGN.matcher(s);
            if (a.find()) {
                String name = a.group(1), path = a.group(2).replaceAll("\\s+", ""), op = a.group(3);
                if (!vars.containsKey(name)) throw new Unsupported("assignment to " + name + ", which is not a local");
                String rhs = expr(s.substring(a.end()).trim());
                if (path.isEmpty()) {
                    String v = op.equals("=") ? rhs : binary(op.substring(0, op.length() - 1), valueOf(name), rhs);
                    let(name, v);
                } else {
                    String old = valueOf(name);
                    String member = path;
                    String cur = op.equals("=") ? rhs : binary(op.substring(0, op.length() - 1), expr(name + path), rhs);
                    if (member.contains("[")) throw new Unsupported("indexed assignment");
                    String set = add(MosaicSlangGraph.SET, member, 0, map("a", old, "value", cur), name + member + " =");
                    vars.put(name, set);
                }
                return;
            }
            Matcher inc = Pattern.compile("^(?:(\\+\\+|--)\\s*([A-Za-z_]\\w*)|([A-Za-z_]\\w*)\\s*(\\+\\+|--))$").matcher(s);
            if (inc.matches()) {
                String name = inc.group(2) != null ? inc.group(2) : inc.group(3);
                String op = (inc.group(1) != null ? inc.group(1) : inc.group(4)).substring(0, 1);
                if (!vars.containsKey(name)) throw new Unsupported("++ on " + name);
                String one = literal(types.getOrDefault(name, "").startsWith("uint") ? "1u" : "1");
                let(name, binary(op, valueOf(name), one));
                return;
            }
            // an expression statement: a call for its effect (or its out parameters)
            String ref = expr(s);
            String id = MosaicGraph.split(ref)[0];
            for (JsonObject o : nodes) {
                if (!o.get("id").getAsString().equals(id)) continue;
                if (o.get("type").getAsString().equals(MosaicSlangGraph.CALL)) {
                    o.addProperty("type", MosaicSlangGraph.DO);
                    if (lastEffect != null && !lastEffect.equals(id)) {
                        if (!o.has("inputs")) o.add("inputs", new JsonObject());
                        o.getAsJsonObject("inputs").addProperty(MosaicSlangGraph.AFTER, lastEffect);
                    }
                }
            }
            effect(id);
        }

        void let(String name, String value) {
            int k = letCount.merge(name, 1, Integer::sum);
            String t = types.getOrDefault(name, "auto");
            String id = add(MosaicSlangGraph.LET, t + " " + name, 0, Map.of("value", value), k == 1 ? name : name + " (" + k + ")");
            vars.put(name, id);
        }

        String binary(String op, String a, String b) {
            return add(MosaicSlangGraph.OP, op, 0, map("a", a, "b", b), null);
        }

        static Map<String, String> map(String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return m;
        }

        // ------------------------------------------------------------ code blocks

        void code(String text, boolean tail) {
            if (text.isBlank()) return;
            String stripped = MosaicSlangLib.stripComments(text);
            Set<String> idents = new LinkedHashSet<>();
            for (Tok t : tokens(stripped)) if (t.kind() == 'i') idents.add(t.text());
            List<String> ins = new ArrayList<>(), outs = new ArrayList<>();
            for (String v : vars.keySet()) {
                if (!idents.contains(v)) continue;
                String t = types.get(v);
                if (t == null || t.equals("auto")) throw new Unsupported("block uses " + v + " of unknown type");
                if (tail || writes(stripped, v)) outs.add(v);
                else ins.add(v);
            }
            StringBuilder head = new StringBuilder("//@");
            for (String v : ins) head.append(" in ").append(types.get(v)).append(' ').append(v).append(';');
            for (String v : outs) head.append(" inout ").append(types.get(v)).append(' ').append(v).append(';');
            String ret = tail ? fn.returnType() : "void";
            head.append(" ret ").append(ret);
            Map<String, String> in = new LinkedHashMap<>();
            for (String v : ins) if (vars.get(v) != null) in.put(v, vars.get(v));
            for (String v : outs) if (vars.get(v) != null) in.put(v, vars.get(v));
            String id = add(MosaicSlangGraph.CODE, head + "\n" + text.trim(), 0, after(in), tail ? "return block" : blockLabel(text));
            codeBlocks++;
            effect(id);
            for (String v : outs) vars.put(v, id + "." + v);
            if (tail) resultRef = fn.isVoid() ? id : id;
        }

        static String blockLabel(String text) {
            String w = wordAt(text.trim(), 0);
            return w.isEmpty() ? "block" : w + " block";
        }

        static boolean writes(String s, String v) {
            String q = Pattern.quote(v);
            return Pattern.compile("\\b" + q + "\\s*(?:\\.[\\w.]+|\\[[^\\]]*\\])*\\s*(?:=(?!=)|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|<<=|>>=|\\+\\+|--)").matcher(s).find()
                || Pattern.compile("(?:\\+\\+|--)\\s*" + q + "\\b").matcher(s).find()
                || Pattern.compile("[(,]\\s*" + q + "\\s*[,)]").matcher(s).find()
                || Pattern.compile("\\b" + q + "\\s*\\.\\s*\\w+\\s*\\(").matcher(s).find();
        }

        void wholeAsCode(String why) {
            nodes.clear();
            vars.clear();
            types.clear();
            shared.clear();
            seq = 0;
            lastEffect = null;
            resultRef = null;
            codeBlocks = 0;
            for (MosaicSlangLib.Param p : fn.params()) {
                types.put(p.name(), p.type());
                vars.put(p.name(), p.direction().equals("out") ? null : add(MosaicSlangGraph.PARAM, p.type() + " " + p.name(), 0, Map.of(), null));
            }
            pendingNote = "Kept as Slang: " + why;
            String body = fn.body().trim();
            body = body.substring(1, body.lastIndexOf('}'));
            code(body, true);
            for (MosaicSlangLib.Param p : fn.params())
                if (p.isOut()) add(MosaicSlangGraph.OUT, p.type() + " " + p.name(), 0, Map.of("value", valueOf(p.name())), null);
            add(MosaicSlangGraph.RESULT, fn.isVoid() ? "void" : fn.returnType(), 0,
                fn.isVoid() ? Map.of("value", lastEffect) : Map.of("value", resultRef), null);
        }

        // ------------------------------------------------------------ expressions

        List<Tok> toks;
        int pos;

        String expr(String text) {
            List<Tok> saved = toks;
            int savedPos = pos;
            toks = tokens(text);
            pos = 0;
            try {
                String r = ternary();
                if (pos != toks.size()) throw new Unsupported("could not read '" + text + "'");
                return r;
            } finally {
                toks = saved;
                pos = savedPos;
            }
        }

        Tok peek() { return pos < toks.size() ? toks.get(pos) : null; }
        Tok peek(int k) { return pos + k < toks.size() ? toks.get(pos + k) : null; }
        boolean at(String s) { Tok t = peek(); return t != null && t.kind() != 's' && t.text().equals(s); }
        Tok next() { if (pos >= toks.size()) throw new Unsupported("unexpected end"); return toks.get(pos++); }
        void expect(String s) { if (!at(s)) throw new Unsupported("expected " + s); pos++; }

        String ternary() {
            String c = binary(0);
            if (at("?")) {
                pos++;
                String a = ternary();
                expect(":");
                String b = ternary();
                return add(MosaicSlangGraph.OP, "?:", 0, map("a", c, "b", a, "c", b), null);
            }
            return c;
        }

        static final List<List<String>> LEVELS = List.of(List.of("||"), List.of("&&"), List.of("|"), List.of("^"), List.of("&"),
            List.of("==", "!="), List.of("<", ">", "<=", ">="), List.of("<<", ">>"), List.of("+", "-"), List.of("*", "/", "%"));

        String binary(int level) {
            if (level >= LEVELS.size()) return unary();
            String left = binary(level + 1);
            while (peek() != null && peek().kind() == 'p' && LEVELS.get(level).contains(peek().text())) {
                String op = next().text();
                String right = binary(level + 1);
                left = binary(op, left, right);
            }
            return left;
        }

        String unary() {
            Tok t = peek();
            if (t == null) throw new Unsupported("unexpected end");
            if (t.kind() == 'p' && (t.text().equals("-") || t.text().equals("!") || t.text().equals("~") || t.text().equals("+"))) {
                pos++;
                String a = unary();
                if (t.text().equals("+")) return a;
                return add(MosaicSlangGraph.OP, t.text() + "u", 0, map("a", a), null);
            }
            if (t.kind() == 'p' && (t.text().equals("++") || t.text().equals("--"))) throw new Unsupported("prefix " + t.text() + " in an expression");
            // cast: ( Type ) unary
            if (at("(") && peek(1) != null && peek(1).kind() == 'i' && peek(2) != null && peek(2).text().equals(")") && isType(peek(1).text())
                && peek(3) != null && (peek(3).kind() != 'p' || peek(3).text().equals("(") || peek(3).text().equals("-"))) {
                String type = peek(1).text();
                pos += 3;
                String a = unary();
                return add(MosaicSlangGraph.CALL, "(" + type + ")", 1, map("a0", a), null);
            }
            return postfix(primary());
        }

        boolean isType(String name) {
            if (name.matches("(float|half|int|uint|bool|double|min16float|int16_t|uint16_t|int64_t|uint64_t)([1-4](x[1-4])?)?")) return true;
            for (MosaicSlangLib.Struct s : lib.structs().values()) if (s.name().equals(name)) return true;
            return false;
        }

        /** A primary expression: its node ref, with the text it came from for lvalue use. */
        String primary() {
            Tok t = next();
            if (t.kind() == 'n') return literal(t.text());
            if (t.kind() == 's') throw new Unsupported("string literal");
            if (t.kind() == 'p') {
                if (t.text().equals("(")) {
                    String e = ternary();
                    expect(")");
                    return e;
                }
                if (t.text().equals("{")) {
                    int start = pos - 1, depth = 1;
                    StringBuilder sb = new StringBuilder("{");
                    while (depth > 0) {
                        Tok k = next();
                        if (k.text().equals("{")) depth++;
                        if (k.text().equals("}")) depth--;
                        if (k.kind() == 'i' && vars.containsKey(k.text())) throw new Unsupported("initializer list with locals");
                        sb.append(k.text()).append(k.text().equals(",") ? " " : "");
                    }
                    return literal(sb.toString());
                }
                throw new Unsupported("unexpected " + t.text());
            }
            String name = t.text();
            if (name.equals("true") || name.equals("false")) return literal(name);
            if (at("(")) return call(name);
            if (at("<") && isType(name)) throw new Unsupported("generic " + name);
            if (vars.containsKey(name)) return valueOf(name);
            // a static const of the pack: same module, an imported one, then any
            MosaicSlangLib.Constant c = constantNamed(name);
            if (c != null) return shared.computeIfAbsent("const:" + c.module() + "/" + c.name(),
                k -> add(MosaicSlangGraph.CONST + c.module() + "/" + c.name(), null, 0, Map.of(), null));
            // a global (buffer, enum, static) of the module: read as written
            StringBuilder text = new StringBuilder(name);
            while ((at(".") || at("::")) && peek(1) != null && peek(1).kind() == 'i' && !(peek(2) != null && peek(2).text().equals("("))) {
                text.append(next().text()).append(next().text());
            }
            if (at(".") && peek(1) != null && peek(2) != null && peek(2).text().equals("(")) {
                pos++;
                String method = next().text();
                if (OUT_BUILTINS.contains(method)) throw new Unsupported(method + " writes its arguments");
                return unknownCall(text + "." + method, null, !NO_WRITE_METHODS.contains(method));
            }
            return literal(text.toString());
        }

        MosaicSlangLib.Constant constantNamed(String name) {
            MosaicSlangLib.Constant best = null;
            for (MosaicSlangLib.Constant c : lib.constants().values()) {
                if (!c.name().equals(name)) continue;
                if (c.module().equals(fn.module())) return c;
                if (best == null || moduleImports.contains(c.module())) best = c;
            }
            return best;
        }

        String postfix(String base) {
            while (true) {
                if (at(".") && peek(1) != null && peek(1).kind() == 'i') {
                    pos++;
                    String member = next().text();
                    if (at("(")) {
                        base = methodCall(base, member);
                        continue;
                    }
                    base = add(MosaicSlangGraph.MEMBER, "." + member, 0, map("a", base), null);
                    continue;
                }
                if (at("[")) {
                    pos++;
                    String i = ternary();
                    expect("]");
                    base = add(MosaicSlangGraph.OP, "[]", 0, map("a", base, "b", i), null);
                    continue;
                }
                if (at("++") || at("--")) throw new Unsupported("postfix " + peek().text() + " in an expression");
                return base;
            }
        }

        /** The argument token runs of a call at "(". */
        List<List<Tok>> args() {
            expect("(");
            List<List<Tok>> out = new ArrayList<>();
            List<Tok> cur = new ArrayList<>();
            int depth = 0;
            while (true) {
                Tok t = next();
                if (depth == 0 && t.text().equals(")")) {
                    if (!cur.isEmpty() || !out.isEmpty()) out.add(cur);
                    return out;
                }
                if (depth == 0 && t.text().equals(",")) {
                    out.add(cur);
                    cur = new ArrayList<>();
                    continue;
                }
                if (t.text().equals("(") || t.text().equals("[") || t.text().equals("{")) depth++;
                if (t.text().equals(")") || t.text().equals("]") || t.text().equals("}")) depth--;
                cur.add(t);
            }
        }

        static String join(List<Tok> ts) {
            StringBuilder sb = new StringBuilder();
            for (Tok t : ts) {
                if (sb.length() > 0 && t.kind() != 'p' && sb.charAt(sb.length() - 1) != '(' && sb.charAt(sb.length() - 1) != '.') sb.append(' ');
                sb.append(t.text());
            }
            return sb.toString();
        }

        String call(String name) {
            if (OUT_BUILTINS.contains(name)) throw new Unsupported(name + " writes its arguments");
            int save = pos;
            List<List<Tok>> a = args();
            packCall = false;
            MosaicSlangLib.Function f = functionNamed(name, a.size());
            if (f == null) {
                boolean pack = packCall;
                pos = save;
                return unknownCall(name, null, pack);
            }
            return libCall(f, null, a, null);
        }

        /** The pack function a call means; null when none or several overloads fit (then Slang picks: a plain call node). */
        MosaicSlangLib.Function functionNamed(String name, int arity) {
            List<MosaicSlangLib.Function> fit = new ArrayList<>();
            boolean anyNamed = false;
            for (MosaicSlangLib.Function f : lib.functions().values()) {
                if (f.owner() != null || !f.name().equals(name)) continue;
                anyNamed = true;
                if (!f.isNode()) { fit.clear(); fit.add(null); break; }
                long required = f.params().stream().filter(p -> p.defaultValue() == null).count();
                if (arity < required || arity > f.params().size()) continue;
                fit.add(f);
            }
            if (fit.size() == 1) return fit.get(0);
            if (anyNamed) for (MosaicSlangLib.Function f : lib.functions().values())
                if (f.owner() == null && f.name().equals(name) && f.params().stream().anyMatch(MosaicSlangLib.Param::isOut)) packCall = true;
            if (fit.size() > 1) {
                // the same function seen in two modules (one re-exports the other) is still one function
                MosaicSlangLib.Function first = fit.get(0);
                boolean same = fit.stream().allMatch(f -> f != null && f.params().equals(first.params()) && f.returnType().equals(first.returnType()));
                if (same) {
                    for (MosaicSlangLib.Function f : fit) if (f.module().equals(fn.module()) || moduleImports.contains(f.module())) return f;
                    return first;
                }
            }
            return null;
        }

        boolean packCall;

        String libCall(MosaicSlangLib.Function f, String self, List<List<Tok>> a, String selfVar) {
            Map<String, String> in = new LinkedHashMap<>();
            if (self != null) in.put(MosaicSlangGraph.SELF, self);
            List<String[]> writeBack = new ArrayList<>();
            for (int i = 0; i < f.params().size(); i++) {
                MosaicSlangLib.Param p = f.params().get(i);
                if (i >= a.size()) break;
                List<Tok> arg = a.get(i);
                if (p.isOut()) {
                    if (arg.size() != 1 || arg.get(0).kind() != 'i' || !vars.containsKey(arg.get(0).text()))
                        throw new Unsupported("out argument " + join(arg) + " is not a local");
                    String v = arg.get(0).text();
                    if (p.direction().equals("inout")) in.put(p.name(), valueOf(v));
                    writeBack.add(new String[] {v, p.name()});
                } else {
                    in.put(p.name(), expr(join(arg)));
                }
            }
            boolean effectful = f.isVoid() || !writeBack.isEmpty() || f.mutating();
            String id = add(MosaicSlangGraph.FN + f.id(), null, 0, effectful ? after(in) : in, null);
            for (String[] wb : writeBack) vars.put(wb[0], id + "." + wb[1]);
            if (selfVar != null && f.mutating()) vars.put(selfVar, id + "." + MosaicSlangGraph.SELF);
            if (effectful) effect(id);
            return id;
        }

        String methodCall(String base, String member) {
            if (OUT_BUILTINS.contains(member)) throw new Unsupported(member + " writes its arguments");
            String baseId = MosaicGraph.split(base)[0];
            // the receiver's struct type: a local of a declared type, or a pack function's typed output
            String type = typeOfRef(base);
            String selfVar = null;
            for (Map.Entry<String, String> e : vars.entrySet()) if (base.equals(e.getValue())) { selfVar = e.getKey(); break; }
            MosaicSlangLib.Function f = null;
            if (type != null) for (MosaicSlangLib.Function c : lib.functions().values())
                if (type.equals(c.owner()) && c.name().equals(member) && c.isNode()) { f = c; break; }
            int save = pos;
            List<List<Tok>> a = args();
            if (f != null && a.size() <= f.params().size()) return libCall(f, base, a, selfVar);
            if (selfVar != null && !baseId.isEmpty() && OUT_BUILTINS.contains(member)) throw new Unsupported(member + " writes its arguments");
            pos = save;
            return unknownCall("." + member, base, !NO_WRITE_METHODS.contains(member));
        }

        String typeOfRef(String ref) {
            for (Map.Entry<String, String> e : vars.entrySet())
                if (ref.equals(e.getValue())) {
                    String t = types.get(e.getKey());
                    if (t != null && !t.equals("auto")) return t;
                }
            String[] parts = MosaicGraph.split(ref);
            for (JsonObject o : nodes) {
                if (!o.get("id").getAsString().equals(parts[0])) continue;
                String type = o.get("type").getAsString();
                if (type.equals(MosaicSlangGraph.LET) || type.equals(MosaicSlangGraph.PARAM)) {
                    String m = o.get("mask").getAsString();
                    return m.substring(0, m.lastIndexOf(' ')).trim();
                }
                MosaicSlangGraph.SPort p = MosaicSlangGraph.handles(type) ? MosaicSlangGraph.output(type, o.has("mask") ? o.get("mask").getAsString() : null, parts[1]) : null;
                return p == null || p.slangType().equals("auto") ? null : p.slangType();
            }
            return null;
        }

        /** A builtin or a call into something the library does not list: a call node with numbered arguments. */
        String unknownCall(String callee, String self, boolean mayWrite) {
            List<List<Tok>> a = args();
            Map<String, String> in = new LinkedHashMap<>();
            if (self != null) in.put(MosaicSlangGraph.SELF, self);
            for (int i = 0; i < a.size(); i++) {
                List<Tok> arg = a.get(i);
                boolean bareLocal = arg.size() == 1 && arg.get(0).kind() == 'i' && vars.containsKey(arg.get(0).text());
                // a local handed to something that may write it (an out parameter of an overloaded or generic pack function, a method)
                if (bareLocal && (mayWrite || vars.get(arg.get(0).text()) == null || OUT_BUILTINS.contains(callee.replace(".", ""))))
                    throw new Unsupported(callee + " may write " + arg.get(0).text());
                in.put("a" + i, expr(join(arg)));
            }
            return add(MosaicSlangGraph.CALL, callee, a.size(), in, null);
        }

        // ------------------------------------------------------------ result

        Result result(boolean whole) {
            layout();
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (JsonObject o : nodes) arr.add(o);
            root.add("nodes", arr);
            root.addProperty("function", fn.id());
            return new Result(root.toString(), nodes.size(), codeBlocks, whole && codeBlocks == 0);
        }

        /** Columns by dependency depth, rows in statement order. */
        void layout() {
            Map<String, Integer> col = new HashMap<>();
            for (JsonObject o : nodes) {
                String id = o.get("id").getAsString();
                int c = 0;
                for (String d : deps.getOrDefault(id, Set.of())) c = Math.max(c, col.getOrDefault(d, 0) + 1);
                col.put(id, c);
            }
            Map<Integer, Integer> rows = new HashMap<>();
            for (JsonObject o : nodes) {
                int c = col.get(o.get("id").getAsString());
                int r = rows.merge(c, 1, Integer::sum) - 1;
                o.addProperty("x", 60 + c * 250);
                o.addProperty("y", 60 + r * 120);
            }
        }
    }
}
