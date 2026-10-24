package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris/OptiFine {@code const} directives ({@code const int colortex2Format = RGBA16F;}, {@code const bool colortex5Clear = false;},
 * {@code const ivec3 workGroups = ivec3(4, 4, 1);} ...), read the way Iris's ConstDirectiveParser reads them: a line whose
 * trimmed text starts with {@code const}, then a type, a name, {@code =} and a value up to {@code ;}. The input is the
 * preprocessed source, so only directives of live conditional branches count; directives that were inside a block comment
 * come back from {@link IrisSourcePreprocessor} prefixed with {@link IrisSourcePreprocessor#CONST_MARKER}.
 */
public final class IrisConstDirectives {
    public enum Type { INT, FLOAT, VEC2, IVEC3, VEC4, BOOL }

    /** One directive; {@code value} is the raw text between {@code =} and {@code ;}, trimmed. */
    public record ConstDirective(Type type, String name, String value) {}

    private static final Pattern LINE = Pattern.compile(
        "^const\\s+(int|float|vec2|ivec3|vec4|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+?)\\s*;.*$");
    private static final String NUMBER = "[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?[fF]?";

    private IrisConstDirectives() {}

    public static List<ConstDirective> parse(String preprocessed) {
        List<ConstDirective> out = new ArrayList<>();
        for (String raw : preprocessed.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith(IrisSourcePreprocessor.CONST_MARKER.trim()))
                line = line.substring(IrisSourcePreprocessor.CONST_MARKER.trim().length()).trim();
            if (!line.startsWith("const")) continue;
            Matcher m = LINE.matcher(line);
            if (m.matches()) out.add(new ConstDirective(Type.valueOf(m.group(1).toUpperCase(Locale.ROOT)), m.group(2), m.group(3).trim()));
        }
        return List.copyOf(out);
    }

    /** {@code true}/{@code false}; anything else is null. */
    public static Boolean bool(String value) {
        return switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** A float literal, or null. */
    public static Float number(String value) {
        if (!value.matches(NUMBER)) return null;
        String v = value.endsWith("f") || value.endsWith("F") ? value.substring(0, value.length() - 1) : value;
        try { return Float.parseFloat(v); }
        catch (NumberFormatException e) { return null; }
    }

    /** {@code vecN(a, b, ...)} with exactly {@code n} literal components (one component is splatted like GLSL), or null. */
    public static float[] vector(String value, String constructor, int n) {
        Matcher m = Pattern.compile("^" + constructor + "\\s*\\((.*)\\)$").matcher(value.trim());
        if (!m.matches()) return null;
        String[] parts = m.group(1).split(",");
        if (parts.length != n && parts.length != 1) return null;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            Float f = number(parts[parts.length == 1 ? 0 : i].trim());
            if (f == null) return null;
            out[i] = f;
        }
        return out;
    }
}
