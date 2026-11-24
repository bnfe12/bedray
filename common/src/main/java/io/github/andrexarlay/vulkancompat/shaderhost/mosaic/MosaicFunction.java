package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Kind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A user-written node: a GLSL function body behind a typed signature, used in graphs as node type {@code fn.<name>}.
 * Definitions travel inside {@code mosaic.json} ({@code "functions": [...]}), so a pack stays self-contained; the
 * editor also keeps a personal library of them. The signature reads like GLSL:
 * {@code vec3 toon(vec3 color, float steps = 4)} - parameters with a default are optional ports.
 * <p>
 * The body is compiled as written inside {@code <out> mosaic_fn_<name>(<params>) { <body> }}; it may not contain
 * preprocessor lines. An optional Slang body serves the Slang mirror; without it the mirror returns zero.
 */
public record MosaicFunction(String name, String label, String category, String help, List<Param> params, Kind output,
                             String glsl, String slang) {

    public static final int MAX_PARAMS = 8, MAX_BODY = 20_000;

    /** One parameter: a port of the node; a default value makes it optional. */
    public record Param(String name, Kind kind, double[] defaultValue) {
        public boolean required() { return defaultValue == null; }
    }

    private static final Set<String> RESERVED = Set.of(
        "main", "return", "if", "else", "for", "while", "do", "break", "continue", "discard", "switch", "case",
        "default", "in", "out", "inout", "const", "uniform", "buffer", "shared", "struct", "void", "bool", "int",
        "uint", "float", "double", "true", "false", "vec2", "vec3", "vec4", "mat2", "mat3", "mat4", "layout",
        "float2", "float3", "float4", "precise", "highp", "mediump", "lowp", "frame");

    public MosaicFunction {
        params = List.copyOf(params);
        label = label == null || label.isBlank() ? name : label;
        category = category == null || category.isBlank() ? "My nodes" : category;
        help = help == null ? "" : help;
        slang = slang == null || slang.isBlank() ? null : slang;
    }

    public String type() { return "fn." + name; }

    public static boolean isFunctionType(String type) { return type != null && type.startsWith("fn."); }

    public static String nameOf(String type) { return type.substring(3); }

    /** The GLSL-like signature the editor shows and parses back. */
    public String signature() {
        StringBuilder sb = new StringBuilder(output.glsl()).append(' ').append(name).append('(');
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            if (i > 0) sb.append(", ");
            sb.append(p.kind().glsl()).append(' ').append(p.name());
            if (p.defaultValue() != null) {
                double[] d = p.defaultValue();
                sb.append(" = ").append(d.length == 1 ? numbers(d) : p.kind().glsl() + "(" + numbers(d) + ")");
            }
        }
        return sb.append(')').toString();
    }

    public MosaicFunction withBody(String body) { return new MosaicFunction(name, label, category, help, params, output, body, slang); }

    public MosaicFunction withSlang(String body) { return new MosaicFunction(name, label, category, help, params, output, glsl, body); }

    public MosaicFunction withSignature(String signature) {
        MosaicFunction s = parseSignature(signature, glsl);
        return new MosaicFunction(s.name, label.equals(name) ? s.name : label, category, help, s.params, s.output, glsl, slang);
    }

    // ---------------------------------------------------------------- signature

    private static final Pattern SIGNATURE = Pattern.compile("\\s*(\\w+)\\s+(\\w+)\\s*\\((.*)\\)\\s*;?\\s*");
    private static final Pattern PARAM = Pattern.compile("\\s*(?:in\\s+)?(\\w+)\\s+(\\w+)\\s*(?:=\\s*(.+?))?\\s*");

    /** Parses {@code vec3 name(vec3 a, float b = 2)}; {@link IllegalArgumentException} names what is wrong. */
    public static MosaicFunction parseSignature(String signature, String body) {
        Matcher m = SIGNATURE.matcher(signature == null ? "" : signature);
        if (!m.matches())
            throw new IllegalArgumentException("signature must look like: vec3 name(vec3 color, float amount = 1)");
        Kind out = kindNamed(m.group(1), "return type");
        String name = m.group(2);
        List<Param> params = new ArrayList<>();
        String list = m.group(3).trim();
        if (!list.isEmpty() && !list.equals("void")) {
            for (String part : splitTopLevel(list)) {
                Matcher pm = PARAM.matcher(part);
                if (!pm.matches()) throw new IllegalArgumentException("parameter '" + part.trim() + "' must be: <type> <name> [= value]");
                Kind k = kindNamed(pm.group(1), "parameter " + pm.group(2));
                double[] def = pm.group(3) == null ? null : parseDefault(pm.group(3), pm.group(2));
                params.add(new Param(pm.group(2), k, def));
            }
        }
        MosaicFunction f = new MosaicFunction(name, name, null, null, params, out, body, null);
        f.validate();
        return f;
    }

    /** Splits on commas outside parentheses: {@code vec3 c = vec3(1, 2, 3), float k} has two parts. */
    static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(list.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(list.substring(start));
        return parts;
    }

    private static double[] parseDefault(String text, String param) {
        String t = text.trim().replaceAll("^(?:vec[234]|float[234]?)\\s*\\((.*)\\)$", "$1");
        String[] parts = t.split("\\s*,\\s*");
        if (parts.length > 4) throw new IllegalArgumentException("default of " + param + " has more than four numbers");
        double[] v = new double[parts.length];
        for (int i = 0; i < v.length; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].replaceAll("[fF]$", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("default of " + param + " must be numbers, found '" + text.trim() + "'");
            }
            if (!Double.isFinite(v[i])) throw new IllegalArgumentException("default of " + param + " must be finite");
        }
        return v;
    }

    static Kind kindNamed(String text, String what) {
        Kind k = Kind.named(text);
        if (k == null) throw new IllegalArgumentException(what + " must be float, vec2, vec3 or vec4, found '" + text + "'");
        return k;
    }

    /** Throws {@link IllegalArgumentException} when the definition cannot be compiled safely. */
    public void validate() {
        if (name == null || !name.matches("[A-Za-z_]\\w{0,39}"))
            throw new IllegalArgumentException("function name must be a word of up to 40 letters, digits or _");
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT)) || name.startsWith("gl_") || name.startsWith("mosaic"))
            throw new IllegalArgumentException("function name " + name + " is reserved");
        if (params.size() > MAX_PARAMS) throw new IllegalArgumentException("function " + name + " has more than " + MAX_PARAMS + " inputs");
        Set<String> seen = new HashSet<>();
        for (Param p : params) {
            if (!p.name().matches("[A-Za-z_]\\w{0,31}") || RESERVED.contains(p.name()) || p.name().startsWith("gl_")
                || p.name().startsWith("g_") || p.name().startsWith("m_"))
                throw new IllegalArgumentException("function " + name + ": input name " + p.name() + " is not allowed");
            if (!seen.add(p.name())) throw new IllegalArgumentException("function " + name + ": two inputs are called " + p.name());
            if (p.defaultValue() != null && p.defaultValue().length != 1 && p.defaultValue().length != p.kind().size())
                throw new IllegalArgumentException("function " + name + ": default of " + p.name() + " needs 1 or "
                    + p.kind().size() + " numbers");
        }
        checkBody(glsl, "GLSL");
        if (slang != null) checkBody(slang, "Slang");
    }

    private void checkBody(String body, String what) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("function " + name + " has no " + what + " code");
        if (body.length() > MAX_BODY) throw new IllegalArgumentException("function " + name + ": " + what + " code is too long");
        for (String line : body.split("\n", -1))
            if (line.trim().startsWith("#"))
                throw new IllegalArgumentException("function " + name + ": preprocessor lines (#...) are not allowed in node code");
        if (!body.contains("return")) throw new IllegalArgumentException("function " + name + ": the code must return a "
            + output.glsl());
    }

    // ---------------------------------------------------------------- json

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        if (!label.equals(name)) o.addProperty("label", label);
        if (!category.equals("My nodes")) o.addProperty("category", category);
        if (!help.isEmpty()) o.addProperty("help", help);
        o.addProperty("signature", signature());
        o.addProperty("glsl", glsl);
        if (slang != null) o.addProperty("slang", slang);
        return o;
    }

    /** Reads a definition: the {@code signature} text form (or {@code inputs}/{@code output}) plus the code. */
    public static MosaicFunction fromJson(JsonObject o) {
        String glsl = str(o, "glsl");
        MosaicFunction f;
        if (o.has("signature")) {
            f = parseSignature(str(o, "signature"), glsl);
        } else {
            String name = str(o, "name");
            Kind out = kindNamed(str(o, "output"), "output");
            List<Param> params = new ArrayList<>();
            if (o.has("inputs") && o.get("inputs").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("inputs")) {
                    JsonObject p = e.getAsJsonObject();
                    double[] def = null;
                    if (p.has("default")) {
                        JsonElement d = p.get("default");
                        if (d.isJsonArray()) {
                            JsonArray a = d.getAsJsonArray();
                            def = new double[a.size()];
                            for (int i = 0; i < def.length; i++) def[i] = a.get(i).getAsDouble();
                        } else {
                            def = new double[] {d.getAsDouble()};
                        }
                    }
                    params.add(new Param(str(p, "name"), kindNamed(str(p, "kind"), "input kind"), def));
                }
            }
            f = new MosaicFunction(name, null, null, null, params, out, glsl, null);
        }
        if (o.has("name") && !str(o, "name").equals(f.name))
            throw new IllegalArgumentException("function " + str(o, "name") + ": the signature names it " + f.name);
        MosaicFunction full = new MosaicFunction(f.name, opt(o, "label"), opt(o, "category"), opt(o, "help"), f.params,
            f.output, glsl, opt(o, "slang"));
        full.validate();
        return full;
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw new IllegalArgumentException("function needs a text field " + key);
        return o.get(key).getAsString();
    }

    private static String opt(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    static String numbers(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (double d : v) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d));
        }
        return sb.toString();
    }
}
