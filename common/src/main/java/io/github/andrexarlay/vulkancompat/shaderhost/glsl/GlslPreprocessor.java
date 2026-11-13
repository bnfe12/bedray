package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL preprocessor for the raster-core translator: evaluates {@code #define}/{@code #undef}, {@code #ifdef}/{@code #ifndef}/
 * {@code #if}/{@code #elif}/{@code #else}/{@code #endif} (with {@code defined()}), and expands object-like and function-like
 * macros. The translator parses declarations per stage BEFORE compilation, so conditional branches must be resolved here
 * (shaderc's own preprocessor runs too late). {@code #version}/{@code #extension}/{@code #line} pass through.
 * <p>
 * Comments are stripped (preserving line count) except {@code DRAWBUFFERS}/{@code RENDERTARGETS} markers, which survive in
 * active branches so the translator can still read draw buffers. Inactive lines become blank to keep {@code #line} numbering.
 */
public final class GlslPreprocessor {

    private record Macro(List<String> params, String body) {
        boolean functionLike() {
            return params != null;
        }
    }

    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(\\w+)\\s*(.*)$");
    private static final Pattern DEFINE_OBJ = Pattern.compile("^(\\w+)\\s*(.*)$");
    private static final Pattern DEFINE_FN = Pattern.compile("^(\\w+)\\(([^)]*)\\)\\s*(.*)$");
    private static final Pattern TARGETS_COMMENT = Pattern.compile("/\\*\\s*(?:DRAWBUFFERS|RENDERTARGETS)\\s*:[0-9, ]*\\*/");
    private static final Pattern DEFINED = Pattern.compile("\\bdefined\\s*(?:\\(\\s*(\\w+)\\s*\\)|(\\w+))");

    private GlslPreprocessor() {}

    /**
     * Applies pack option values the way Iris does: by editing the pack's own declarations, NOT by injecting macros (which
     * would collide with {@code const} option declarations such as BSL's {@code const int shadowMapResolution}). Boolean
     * options comment/uncomment a {@code #define} toggle; valued options rewrite a {@code #define} value or a {@code const}
     * initializer, preserving the trailing {@code //[choices]} comment.
     */
    public static String applyOptions(String source, Map<String, String> options) {
        String s = source;
        for (Map.Entry<String, String> e : options.entrySet()) {
            String name = Pattern.quote(e.getKey());
            String value = e.getValue();
            if ("false".equalsIgnoreCase(value)) {
                s = s.replaceAll("(?m)^(\\s*)#define\\s+" + name + "\\b", "$1//#define " + e.getKey());
            } else if ("true".equalsIgnoreCase(value)) {
                s = s.replaceAll("(?m)^(\\s*)//\\s*#define\\s+" + name + "\\b", "$1#define " + e.getKey());
            } else {
                String v = Matcher.quoteReplacement(value);
                s = s.replaceAll("(?m)^(\\s*#define\\s+" + name + "\\s+)[^/\\n]*", "$1" + v + " ");
                s = s.replaceAll("(?m)^(\\s*const\\s+\\w+\\s+" + name + "\\s*=\\s*)[^;\\n]*;", "$1" + v + ";");
            }
        }
        return s;
    }

    public static String process(String source, Map<String, String> predefined) {
        String joined = joinContinuations(source);
        String uncommented = stripCommentsKeepTargets(joined);
        Map<String, Macro> macros = new LinkedHashMap<>();
        if (predefined != null) {
            predefined.forEach((k, v) -> macros.put(k, new Macro(null, v == null ? "" : v)));
        }
        Deque<boolean[]> stack = new ArrayDeque<>(); // {parentActive, taken, active}
        StringBuilder out = new StringBuilder();
        for (String line : uncommented.split("\n", -1)) {
            boolean parentActive = stack.isEmpty() || stack.peek()[2];
            Matcher d = DIRECTIVE.matcher(line);
            if (d.matches()) {
                String dir = d.group(1);
                String rest = d.group(2).trim();
                switch (dir) {
                    case "ifdef" -> push(stack, parentActive, macros.containsKey(rest.trim()));
                    case "ifndef" -> push(stack, parentActive, !macros.containsKey(rest.trim()));
                    case "if" -> push(stack, parentActive, parentActive && evalCondition(rest, macros) != 0);
                    case "elif" -> {
                        boolean[] f = stack.peek();
                        boolean a = f[0] && !f[1] && evalCondition(rest, macros) != 0;
                        f[2] = a;
                        f[1] = f[1] || a;
                    }
                    case "else" -> {
                        boolean[] f = stack.peek();
                        boolean a = f[0] && !f[1];
                        f[2] = a;
                        f[1] = f[1] || a;
                    }
                    case "endif" -> {
                        if (!stack.isEmpty()) stack.pop();
                    }
                    case "define" -> {
                        if (parentActive) define(macros, rest);
                    }
                    case "undef" -> {
                        if (parentActive) macros.remove(rest.trim());
                    }
                    default -> {
                        // #version / #extension / #line / #pragma / unknown: keep when active.
                        if (parentActive) out.append(line);
                    }
                }
                out.append('\n');
                continue;
            }
            if (parentActive) {
                out.append(expandMacros(line, macros));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void push(Deque<boolean[]> stack, boolean parentActive, boolean cond) {
        boolean active = parentActive && cond;
        stack.push(new boolean[] {parentActive, active, active});
    }

    private static void define(Map<String, Macro> macros, String rest) {
        Matcher fn = DEFINE_FN.matcher(rest);
        if (fn.matches()) {
            List<String> params = new ArrayList<>();
            for (String p : fn.group(2).split(",")) {
                if (!p.isBlank()) params.add(p.trim());
            }
            macros.put(fn.group(1), new Macro(params, fn.group(3).trim()));
            return;
        }
        Matcher obj = DEFINE_OBJ.matcher(rest);
        if (obj.matches()) {
            macros.put(obj.group(1), new Macro(null, obj.group(2).trim()));
        }
    }

    private static double evalCondition(String expr, Map<String, Macro> macros) {
        Matcher m = DEFINED.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            m.appendReplacement(sb, macros.containsKey(name) ? "1" : "0");
        }
        m.appendTail(sb);
        String resolved = sb.toString();
        try {
            return IrisUniformBlock.Expr.eval(resolved, name -> {
                Macro mac = macros.get(name);
                if (mac == null || mac.functionLike() || mac.body().isEmpty()) {
                    return new double[] {0};
                }
                try {
                    return new double[] {Double.parseDouble(mac.body().trim())};
                } catch (NumberFormatException e) {
                    return new double[] {0};
                }
            }, null);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Expands macros in one line (outside comments/strings), iterating to a fixed point with a recursion guard. */
    static String expandMacros(String line, Map<String, Macro> macros) {
        String s = line;
        for (int iter = 0; iter < 64; iter++) {
            String next = expandOnce(s, macros);
            if (next.equals(s)) {
                break;
            }
            s = next;
        }
        return s;
    }

    private static String expandOnce(String s, Map<String, Macro> macros) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = s.length();
        boolean changed = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(s, i, end);
                i = end;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\') j++;
                    j++;
                }
                j = Math.min(j + 1, n);
                out.append(s, i, j);
                i = j;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) j++;
                String name = s.substring(i, j);
                Macro mac = macros.get(name);
                if (mac != null && !mac.functionLike()) {
                    out.append(mac.body());
                    changed = true;
                    i = j;
                    continue;
                }
                if (mac != null && mac.functionLike()) {
                    int k = j;
                    while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                    if (k < n && s.charAt(k) == '(') {
                        int close = matchParen(s, k);
                        if (close > k) {
                            List<String> args = splitArgs(s.substring(k + 1, close));
                            out.append(substituteParams(mac, args));
                            changed = true;
                            i = close + 1;
                            continue;
                        }
                    }
                }
                out.append(name);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return changed ? out.toString() : s;
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> splitArgs(String s) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                args.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (!s.isBlank()) {
            args.add(s.substring(start).trim());
        }
        return args;
    }

    private static String substituteParams(Macro mac, List<String> args) {
        String body = mac.body();
        for (int p = 0; p < mac.params().size(); p++) {
            String arg = p < args.size() ? args.get(p) : "";
            body = body.replaceAll("\\b" + Pattern.quote(mac.params().get(p)) + "\\b", Matcher.quoteReplacement(arg));
        }
        return body;
    }

    private static String joinContinuations(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\\\\\\n", " ");
    }

    /** Strips C-style comments preserving newlines, but keeps DRAWBUFFERS/RENDERTARGETS marker comments verbatim. */
    static String stripCommentsKeepTargets(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int len = source.length();
        int i = 0;
        while (i < len) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < len) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    sb.append("  ");
                    i += 2;
                    while (i < len && source.charAt(i) != '\n') {
                        sb.append(' ');
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", i + 2);
                    end = end < 0 ? len : end + 2;
                    String comment = source.substring(i, end);
                    if (TARGETS_COMMENT.matcher(comment).matches()) {
                        sb.append(comment);
                    } else {
                        for (int k = i; k < end; k++) {
                            char b = source.charAt(k);
                            sb.append(b == '\n' ? '\n' : ' ');
                        }
                    }
                    i = end;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}