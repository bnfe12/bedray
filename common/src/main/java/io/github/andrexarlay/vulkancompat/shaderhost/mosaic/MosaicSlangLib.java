package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedray's Slang pack as a node library: every function (and struct method) of every module with its parameters
 * ({@code in} / {@code out} / {@code inout}, types), every struct with its fields, every enum and every {@code static const},
 * and who calls whom. Function nodes are {@code bedray.fn:<module>/<name>} (methods {@code <Struct>.<name>}); structs get
 * Make / Break nodes. A graph of these nodes compiles to Slang that imports the real modules and calls them.
 */
public final class MosaicSlangLib {

    private MosaicSlangLib() {}

    public record Param(String name, String type, String direction, String defaultValue) {
        public boolean isOut() { return direction.equals("out") || direction.equals("inout"); }
        public boolean isIn() { return !direction.equals("out"); }
    }

    /** A function: module path, owning struct (null for free functions), name, return type, parameters, callees, source. */
    public record Function(String module, String owner, String name, String returnType, List<Param> params, Set<String> calls,
                           String comment, boolean generic, boolean mutating, boolean entry, String body, int start, int bodyStart, int end,
                           String overload) {
        /** Parameter types, for telling overloads apart. */
        public String signature() { return String.join(",", params.stream().map(Param::type).toList()); }
        public boolean isVoid() { return returnType.equals("void"); }

        Function withOverload(String sig) {
            return new Function(module, owner, name, returnType, params, calls, comment, generic, mutating, entry, body, start, bodyStart, end, sig);
        }
        /** Offered as a node: not generic, not an entry point. */
        public boolean isNode() { return !generic && !entry; }
        public String id() { return module + "/" + (owner == null ? "" : owner + ".") + name + (overload == null ? "" : "(" + overload + ")"); }
        public String type() { return "bedray.fn:" + id(); }
        public String label() { return (owner == null ? "" : owner + ".") + name; }
    }

    public record Field(String name, String type, String comment) {}

    public record Struct(String module, String name, String base, List<Field> fields, boolean isInterface, String comment) {}

    public record Constant(String module, String name, String type, String value, String comment) {}

    public record Enum(String module, String name, List<String> values) {}

    /** Everything parsed from a set of modules. */
    public record Library(Map<String, Function> functions, Map<String, Struct> structs, Map<String, Constant> constants,
                          Map<String, Enum> enums, Map<String, String> sources) {
        public Function function(String type) {
            return type.startsWith("bedray.fn:") ? functions.get(type.substring("bedray.fn:".length())) : null;
        }

        public List<Function> ofModule(String module) {
            return functions.values().stream().filter(f -> f.module().equals(module)).toList();
        }

        public List<Constant> constantsOf(String module) {
            return constants.values().stream().filter(c -> c.module().equals(module)).toList();
        }
    }

    // ------------------------------------------------------------------ parsing

    /** Comments become spaces (positions kept), the last comment before a declaration is kept as its doc. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) { out.append(s.charAt(i) == '\n' ? '\n' : ' '); i++; }
                if (i < n) { out.append("  "); i += 2; }
            } else if (c == '"') {
                out.append(c);
                i++;
                while (i < n && s.charAt(i) != '"') { out.append(s.charAt(i)); i++; }
                if (i < n) { out.append('"'); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The comment lines right above position {@code at} in the original source. */
    static String docBefore(String src, int at) {
        int lineStart = src.lastIndexOf('\n', Math.max(0, at - 1));
        List<String> lines = new ArrayList<>();
        int end = lineStart;
        while (end > 0) {
            int prev = src.lastIndexOf('\n', end - 1);
            String line = src.substring(prev + 1, end).trim();
            if (!line.startsWith("//")) break;
            lines.add(0, line.replaceFirst("^//+\\s?", ""));
            end = prev;
        }
        return lines.isEmpty() ? null : String.join(" ", lines);
    }

    private static final Pattern FN_HEAD = Pattern.compile(
        "(?:\\[[^\\]]*\\]\\s*)*(?:(?:public|internal|private|static|inline|override|\\[mutating\\]|mutating|__generic|export)\\s+)*"
            + "([A-Za-z_][\\w.]*(?:<[^<>()]*>)?(?:\\[\\d*\\])?)\\s+([A-Za-z_]\\w*)\\s*(<[^<>()]*>)?\\s*\\(");
    private static final Pattern CONST = Pattern.compile("static\\s+const\\s+([A-Za-z_][\\w<>]*)\\s+([A-Z_a-z]\\w*)(\\[[^\\]]*\\])?\\s*=\\s*([^;]+);");
    private static final Pattern STRUCT_HEAD = Pattern.compile("(?:public\\s+)?(struct|interface|enum)\\s+([A-Za-z_]\\w*)(\\s*<[^>]*>)?\\s*(?::\\s*([A-Za-z_][\\w, ]*))?\\s*\\{");
    private static final Set<String> GENERIC_STRUCTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> overloaded = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Methods of generic structs (and of interfaces) are not nodes: they have no concrete self type. */
    private static boolean generic(String owner, Map<String, Struct> structs, String module) {
        if (owner == null) return false;
        Struct s = structs.get(module + "/" + owner);
        return GENERIC_STRUCTS.contains(module + "/" + owner) || s != null && s.isInterface();
    }
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "return", "else", "do", "case", "sizeof", "struct",
        "interface", "enum", "import", "module", "namespace", "typedef", "using");

    /** "shading/microfacet.slang" -> "shading/microfacet". */
    public static String moduleId(String path) {
        String p = path.replace('\\', '/');
        return p.endsWith(".slang") ? p.substring(0, p.length() - ".slang".length()) : p;
    }

    /** The import line of a module id. */
    public static String importOf(String module) { return "import \"" + module + ".slang\";"; }

    public static Library parse(Map<String, String> sources) {
        Map<String, Function> fns = new TreeMap<>();
        Map<String, Struct> structs = new TreeMap<>();
        Map<String, Constant> consts = new TreeMap<>();
        Map<String, Enum> enums = new TreeMap<>();
        Map<String, String> bodies = new LinkedHashMap<>();
        overloaded.clear();
        for (Map.Entry<String, String> e : sources.entrySet()) parseModule(moduleId(e.getKey()), e.getValue(), fns, structs, consts, enums, bodies);
        // calls: identifiers followed by "(" that name a known function (free functions by name, methods by name)
        Set<String> known = new LinkedHashSet<>();
        for (Function f : fns.values()) known.add(f.name());
        Pattern call = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
        Map<String, Function> out = new TreeMap<>();
        for (Function f : fns.values()) {
            Set<String> calls = new LinkedHashSet<>();
            Matcher m = call.matcher(bodies.getOrDefault(f.id(), ""));
            while (m.find()) if (known.contains(m.group(1)) && !m.group(1).equals(f.name())) calls.add(m.group(1));
            out.put(f.id(), new Function(f.module(), f.owner(), f.name(), f.returnType(), f.params(), calls, f.comment(), f.generic(),
                f.mutating(), f.entry(), f.body(), f.start(), f.bodyStart(), f.end(), f.overload()));
        }
        return new Library(out, structs, consts, enums, sources);
    }

    private static void parseModule(String module, String original, Map<String, Function> fns, Map<String, Struct> structs,
                                     Map<String, Constant> consts, Map<String, Enum> enums, Map<String, String> bodies) {
        String s = stripComments(original);
        // constants anywhere at file level
        Matcher cm = CONST.matcher(s);
        while (cm.find())
            consts.put(module + "/" + cm.group(2), new Constant(module, cm.group(2), cm.group(1) + (cm.group(3) == null ? "" : cm.group(3)),
                cm.group(4).trim(), docBefore(original, cm.start())));
        walk(module, original, s, 0, s.length(), null, fns, structs, enums, bodies);
    }

    /** Scans [from, to) for struct/interface/enum blocks and functions; recurses into struct bodies for methods. */
    private static void walk(String module, String original, String s, int from, int to, String owner, Map<String, Function> fns,
                             Map<String, Struct> structs, Map<String, Enum> enums, Map<String, String> bodies) {
        int i = from;
        while (i < to) {
            Matcher sh = STRUCT_HEAD.matcher(s);
            sh.region(i, to);
            Matcher fh = FN_HEAD.matcher(s);
            fh.region(i, to);
            int sAt = sh.find() ? sh.start() : -1;
            int fAt = fh.find() ? fh.start() : -1;
            if (sAt < 0 && fAt < 0) return;
            if (sAt >= 0 && (fAt < 0 || sAt <= fAt)) {
                int open = sh.end() - 1, close = match(s, open);
                if (close < 0) return;
                String kind = sh.group(1), name = sh.group(2);
                if (kind.equals("enum")) {
                    List<String> values = new ArrayList<>();
                    for (String part : s.substring(open + 1, close).split(",")) {
                        String v = part.trim().replaceAll("\\s*=.*", "");
                        if (!v.isEmpty()) values.add(v);
                    }
                    enums.put(module + "/" + name, new Enum(module, name, values));
                } else {
                    List<Field> fields = new ArrayList<>();
                    String body = s.substring(open + 1, close);
                    // fields: "type name;" at depth 0 of the body
                    int depth = 0;
                    StringBuilder stmt = new StringBuilder();
                    for (int k = 0; k < body.length(); k++) {
                        char c = body.charAt(k);
                        if (c == '{') depth++;
                        if (c == '}') { depth--; stmt.setLength(0); continue; }
                        if (depth > 0) continue;
                        if (c == ';') {
                            String st = stmt.toString().trim();
                            Matcher fm = Pattern.compile("^(?:(?:static|const|public)\\s+)*([A-Za-z_][\\w<>,]*)\\s+([A-Za-z_]\\w*)(\\[[^\\]]*\\])?$").matcher(st);
                            if (fm.matches() && !st.contains("(") && !st.startsWith("static")) fields.add(new Field(fm.group(2), fm.group(1)
                                + (fm.group(3) == null ? "" : fm.group(3)), null));
                            stmt.setLength(0);
                        } else stmt.append(c);
                    }
                    if (sh.group(3) != null) GENERIC_STRUCTS.add(module + "/" + name);
                    structs.put(module + "/" + name, new Struct(module, name, sh.group(4) == null ? null : sh.group(4).trim(), fields,
                        kind.equals("interface"), docBefore(original, sh.start())));
                    if (kind.equals("struct")) walk(module, original, s, open + 1, close, name, fns, structs, enums, bodies);
                }
                i = close + 1;
                continue;
            }
            // a function head: params up to the matching ")", then "{" (definition) or ";" (declaration, skipped)
            String ret = fh.group(1), name = fh.group(2);
            int paren = fh.end() - 1, closeParen = matchParen(s, paren);
            if (closeParen < 0) return;
            int k = closeParen + 1;
            while (k < to && Character.isWhitespace(s.charAt(k))) k++;
            if (KEYWORDS.contains(ret) || KEYWORDS.contains(name) || ret.equals("return") || ret.equals("new") || ret.equals("else")) {
                i = fh.end();
                continue;
            }
            if (k < to && s.charAt(k) == '{') {
                int close = match(s, k);
                if (close < 0) return;
                String head = s.substring(fh.start(), fh.end());
                boolean entry = head.contains("[shader(") || head.contains("[numthreads(");
                List<Param> params = params(s.substring(paren + 1, closeParen));
                if (params != null) {
                    Function f = new Function(module, owner, name, ret, params, Set.of(), docBefore(original, fh.start()), fh.group(3) != null,
                        head.contains("mutating"), entry || generic(owner, structs, module), original.substring(k, close + 1), fh.start(), k, close + 1,
                        null);
                    if (fns.containsKey(f.id()) || overloaded.contains(f.id())) {
                        // overloads: every one of them is named by its parameter types
                        Function first = fns.remove(f.id());
                        if (first != null) {
                            Function renamed = first.withOverload(first.signature());
                            fns.put(renamed.id(), renamed);
                            bodies.put(renamed.id(), bodies.remove(first.id()));
                        }
                        overloaded.add(f.id());
                        f = f.withOverload(f.signature());
                    }
                    fns.put(f.id(), f);
                    bodies.put(f.id(), s.substring(k, close + 1));
                }
                i = close + 1;
            } else {
                i = closeParen + 1;
            }
        }
    }

    /** Parameters, or null when they cannot be read (then the function is not offered as a node). */
    static List<Param> params(String text) {
        List<Param> out = new ArrayList<>();
        if (text.isBlank()) return out;
        for (String raw : MosaicFunction.splitTopLevel(text)) {
            String p = raw.trim().replaceAll("\\s+", " ");
            String dir = "in";
            String[] mods = {"inout ", "out ", "in ", "const ", "uniform "};
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String m : mods)
                    if (p.startsWith(m)) {
                        if (!m.equals("const ") && !m.equals("uniform ")) dir = m.trim();
                        p = p.substring(m.length()).trim();
                        changed = true;
                    }
            }
            p = p.replaceAll("\\s*:\\s*[A-Za-z_]\\w*\\s*$", "");
            String def = null;
            int eq = p.indexOf('=');
            if (eq > 0) {
                def = p.substring(eq + 1).trim();
                p = p.substring(0, eq).trim();
            }
            Matcher m = Pattern.compile("^([A-Za-z_][\\w.]*(?:<[^<>]*>)?)\\s+([A-Za-z_]\\w*)(\\[[^\\]]*\\])?$").matcher(p);
            if (!m.matches()) return null;
            out.add(new Param(m.group(2), m.group(1) + (m.group(3) == null ? "" : m.group(3)), dir, def));
        }
        return out;
    }

    private static int match(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }
}
