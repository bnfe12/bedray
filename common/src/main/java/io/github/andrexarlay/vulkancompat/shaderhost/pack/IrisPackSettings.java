package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a shaderpack's own configuration (shaders.properties) into concrete option values and program enablement.
 * Profiles (profile.*) are expanded recursively into option tokens; {@code program.<dim>/<prog>.enabled} boolean expressions
 * are evaluated over those options. This selects which of the pack's programs run; it never invents support for a program.
 */
public final class IrisPackSettings {

    private final PackProperties props;
    private final Set<String> dimensionFolders;
    private final String profile;
    private final Map<String, String> options;

    private IrisPackSettings(PackProperties props, Set<String> dimensionFolders, String profile, Map<String, String> options) {
        this.props = props;
        this.dimensionFolders = dimensionFolders;
        this.profile = profile;
        this.options = options;
    }

    public static IrisPackSettings parse(PackProperties props, Set<String> dimensionFolders) {
        return parse(props, dimensionFolders, Map.of());
    }

    /** Base option defaults (the pack's source {@code #define}/{@code const} declarations) overlaid by the active profile. */
    static IrisPackSettings parse(PackProperties props, Set<String> dimensionFolders, Map<String, String> baseDefaults) {
        String profile = System.getProperty("vkrtx.shaderhost.profile");
        if (profile == null || profile.isBlank()) {
            profile = props.all("profile.DEFAULT").isEmpty() ? "" : "DEFAULT";
        }
        Map<String, String> options = new LinkedHashMap<>(baseDefaults);
        applyProfile(props, profile, options, 0);
        return new IrisPackSettings(props, Set.copyOf(dimensionFolders), profile, Map.copyOf(options));
    }

    public static IrisPackSettings parse(PackSource src, PackReport report) throws IOException {
        String entry = report.shadersRoot() + "shaders.properties";
        PackProperties props = src.entries().contains(entry)
            ? PackProperties.parse(new String(src.read(entry), StandardCharsets.UTF_8))
            : PackProperties.empty();
        return parse(props, report.dimensions().keySet(), scanOptionDefaults(src, optionNames(props)));
    }

    private static void applyProfile(PackProperties props, String name, Map<String, String> options, int depth) {
        if (name == null || name.isBlank() || depth > 8) return;
        List<String> vals = props.all("profile." + name);
        if (vals.isEmpty()) return;
        for (String token : vals.get(vals.size() - 1).trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.startsWith("profile.")) {
                applyProfile(props, token.substring("profile.".length()), options, depth + 1);
            } else if (token.startsWith("!")) {
                options.put(token.substring(1), "false");
            } else if (token.contains("=")) {
                int eq = token.indexOf('=');
                options.put(token.substring(0, eq), token.substring(eq + 1));
            } else {
                options.put(token, "true");
            }
        }
    }

    private static final Pattern DEFINE_DECL = Pattern.compile("^\\s*(//\\s*)?#define\\s+([A-Za-z_]\\w*)\\b[ \\t]*(.*)$");
    private static final Pattern CONST_DECL = Pattern.compile("^\\s*const\\s+\\w+\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    /** Option names the pack exposes: {@code screen.*}/{@code sliders.*} tokens plus {@code program.*.enabled} operands. */
    private static Set<String> optionNames(PackProperties props) {
        Set<String> names = new LinkedHashSet<>();
        for (String key : props.keys()) {
            if (key.startsWith("screen.") || key.startsWith("sliders.")) {
                for (String val : props.all(key)) {
                    for (String tok : val.split("\\s+")) {
                        if (IDENT.matcher(tok).matches()) names.add(tok);
                    }
                }
            } else if (key.startsWith("program.") && key.endsWith(".enabled")) {
                for (String val : props.all(key)) {
                    for (String tok : val.split("[\\s()!&|]+")) {
                        if (IDENT.matcher(tok).matches() && !tok.equalsIgnoreCase("true") && !tok.equalsIgnoreCase("false")) {
                            names.add(tok);
                        }
                    }
                }
            }
        }
        return names;
    }

    /**
     * The pack's own default option values, read from its GLSL source the way Iris does: an uncommented {@code #define NAME}
     * is on, a commented {@code //#define NAME} is off, and a valued {@code #define}/{@code const} carries its initializer.
     * Only names the pack lists as options are considered, so include guards and internal macros never become options.
     */
    static Map<String, String> scanOptionDefaults(PackSource src, Set<String> optionNames) throws IOException {
        Map<String, String> defaults = new LinkedHashMap<>();
        if (optionNames.isEmpty()) return defaults;
        for (String entry : src.entries()) {
            if (!(entry.endsWith(".glsl") || entry.endsWith(".fsh") || entry.endsWith(".vsh"))) continue;
            String text = new String(src.read(entry), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            for (String line : text.split("\n", -1)) {
                Matcher d = DEFINE_DECL.matcher(line);
                if (d.matches()) {
                    String name = d.group(2);
                    if (optionNames.contains(name)) {
                        String val = d.group(3).trim();
                        defaults.putIfAbsent(name, d.group(1) != null ? "false" : (val.isEmpty() ? "true" : val));
                    }
                    continue;
                }
                Matcher c = CONST_DECL.matcher(line);
                if (c.matches() && optionNames.contains(c.group(1))) {
                    defaults.putIfAbsent(c.group(1), c.group(2).trim());
                }
            }
        }
        return defaults;
    }

    public String profile() {
        return profile;
    }

    public Map<String, String> options() {
        return options;
    }

    public boolean optionBool(String name) {
        return "true".equalsIgnoreCase(options.get(name));
    }

    public boolean isProgramEnabled(String dimension, String program) {
        List<String> vals = props.all("program." + dimension + "/" + program + ".enabled");
        if (vals.isEmpty()) return true;
        return evalBool(vals.get(vals.size() - 1));
    }

    /** Dimension-less form {@code program.<name>.enabled} (the common pack spelling). */
    public boolean isProgramEnabled(String program) {
        List<String> vals = props.all("program." + program + ".enabled");
        if (vals.isEmpty()) return true;
        return evalBool(vals.get(vals.size() - 1));
    }

    public String dimensionFolder(String worldDimension) {
        String candidate = switch (worldDimension == null ? "" : worldDimension) {
            case "minecraft:the_nether" -> "world-1";
            case "minecraft:the_end" -> "world1";
            default -> "world0";
        };
        return dimensionFolders.contains(candidate) ? candidate : "";
    }

    public String customTexture(String sampler) {
        List<String> v = props.all("texture." + sampler);
        if (!v.isEmpty()) return v.get(v.size() - 1);
        v = props.all("texture.composite." + sampler);
        return v.isEmpty() ? null : v.get(v.size() - 1);
    }

    /** Sampler names the pack binds to custom texture files (texture.* / texture.composite.* keys), sorted. */
    public java.util.Set<String> customTextureNames() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String key : props.keys()) {
            if (key.startsWith("texture.composite.")) {
                names.add(key.substring("texture.composite.".length()));
            } else if (key.startsWith("texture.")) {
                names.add(key.substring("texture.".length()));
            }
        }
        return names;
    }

    public String colortexFormat(int index) {
        List<String> v = props.all("colortex" + index + ".format");
        return v.isEmpty() ? "RGBA8" : v.get(v.size() - 1);
    }

    public Map<String, String> defineMacros() {
        Map<String, String> macros = new LinkedHashMap<>();
        options.forEach((k, v) -> {
            if ("false".equalsIgnoreCase(v)) return;
            macros.put(k, "true".equalsIgnoreCase(v) ? "" : v);
        });
        return macros;
    }

    public String uniformExpr(String name) {
        List<String> v = props.all("uniform.float." + name);
        if (!v.isEmpty()) return v.get(v.size() - 1);
        v = props.all("uniform." + name);
        return v.isEmpty() ? null : v.get(v.size() - 1);
    }

    /** Pack-declared {@code uniform.<type>.<name>} expressions, name -> GLSL type, in file order. */
    public Map<String, String> expressionUniforms() {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> types = Set.of("float", "int", "bool", "uint", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "mat3", "mat4");
        for (String key : props.keys()) {
            if (!key.startsWith("uniform.")) continue;
            String rest = key.substring("uniform.".length());
            int dot = rest.indexOf('.');
            if (dot > 0 && types.contains(rest.substring(0, dot))) {
                out.putIfAbsent(rest.substring(dot + 1), rest.substring(0, dot));
            } else {
                out.putIfAbsent(rest, "float");
            }
        }
        return out;
    }

    /** Pack-declared {@code variable.<type>.<name>} intermediate expression, or null. */
    public String variableExpr(String name) {
        List<String> v = props.all("variable.float." + name);
        if (!v.isEmpty()) return v.get(v.size() - 1);
        v = props.all("variable." + name);
        return v.isEmpty() ? null : v.get(v.size() - 1);
    }

    // ---- boolean expression evaluator over option truthiness ----

    boolean evalBool(String expr) {
        return new BoolParser(tokenize(expr)).parseOr();
    }

    private static List<String> tokenize(String expr) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')' || c == '!') {
                tokens.add(String.valueOf(c));
                i++;
            } else if (c == '&' || c == '|') {
                tokens.add(expr.substring(i, i + 2));
                i += 2;
            } else {
                int j = i;
                while (j < expr.length() && !Character.isWhitespace(expr.charAt(j))
                    && "()!&|".indexOf(expr.charAt(j)) < 0) {
                    j++;
                }
                tokens.add(expr.substring(i, j));
                i = j;
            }
        }
        return tokens;
    }

    private final class BoolParser {
        private final List<String> tokens;
        private int pos;

        BoolParser(List<String> tokens) {
            this.tokens = tokens;
        }

        private String peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        private String next() {
            return tokens.get(pos++);
        }

        boolean parseOr() {
            boolean v = parseAnd();
            while ("||".equals(peek())) {
                next();
                boolean r = parseAnd();
                v = v || r;
            }
            return v;
        }

        boolean parseAnd() {
            boolean v = parseNot();
            while ("&&".equals(peek())) {
                next();
                boolean r = parseNot();
                v = v && r;
            }
            return v;
        }

        boolean parseNot() {
            if ("!".equals(peek())) {
                next();
                return !parseNot();
            }
            return parsePrimary();
        }

        boolean parsePrimary() {
            String t = next();
            if (t == null) return false;
            if ("(".equals(t)) {
                boolean v = parseOr();
                if (")".equals(peek())) next();
                return v;
            }
            if ("true".equalsIgnoreCase(t)) return true;
            if ("false".equalsIgnoreCase(t)) return false;
            return optionBool(t);
        }
    }
}