package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackPaths;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage I2, first half: textual {@code #include} expansion the way Iris does it (every include is inserted every time it
 * appears; packs guard with {@code #ifndef}). Paths are resolved by {@link PackPaths#resolveWithin}, so an include can never
 * leave the shaders root. Each inserted file is wrapped in {@code #line} directives with the pack path (needs
 * {@code GL_GOOGLE_cpp_style_line_directive}, added by the translator) so compiler errors name the real file and line.
 * Conditional compilation ({@code #ifdef}/{@code #if}) and macros are left to shaderc's preprocessor, which is complete.
 */
public final class IncludeExpander {
    /** Reads one pack file (normalized pack path) as text. */
    @FunctionalInterface
    public interface Reader {
        String read(String packPath) throws IOException;
    }

    public static final int MAX_DEPTH = 32;
    public static final int MAX_OUTPUT_CHARS = 8 << 20;
    private static final Pattern INCLUDE = Pattern.compile("^[ \\t]*#[ \\t]*include[ \\t]+[\"<]([^\">]+)[\">][ \\t]*$");

    private final String shadersRoot;
    private final Reader reader;
    private int outChars;

    public IncludeExpander(String shadersRoot, Reader reader) {
        this.shadersRoot = shadersRoot;
        this.reader = reader;
    }

    /** Expands {@code file} (a normalized pack path under the shaders root). */
    public String expand(String file) throws IOException {
        outChars = 0;
        StringBuilder out = new StringBuilder();
        expandInto(file, out, new ArrayDeque<>());
        return out.toString();
    }

    private void expandInto(String file, StringBuilder out, Deque<String> stack) throws IOException {
        if (stack.contains(file)) {
            throw new PackScanException("#include cycle: " + String.join(" -> ", stack) + " -> " + file);
        }
        if (stack.size() >= MAX_DEPTH) {
            throw new PackScanException("#include deeper than " + MAX_DEPTH + " at " + file);
        }
        stack.addLast(file);
        String text = reader.read(file).replace("\r\n", "\n").replace('\r', '\n');
        String display = file.substring(shadersRoot.length());
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = INCLUDE.matcher(lines[i]);
            if (m.matches()) {
                String target = PackPaths.resolveWithin(shadersRoot, file, m.group(1).trim());
                append(out, "#line 1 \"" + target.substring(shadersRoot.length()) + "\"\n");
                expandInto(target, out, stack);
                append(out, "#line " + (i + 2) + " \"" + display + "\"\n");
            } else {
                append(out, lines[i]);
                if (i < lines.length - 1) {
                    append(out, "\n");
                }
            }
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            append(out, "\n");
        }
        stack.removeLast();
    }

    private void append(StringBuilder out, String s) throws PackScanException {
        outChars += s.length();
        if (outChars > MAX_OUTPUT_CHARS) {
            throw new PackScanException("expanded source larger than " + MAX_OUTPUT_CHARS + " chars");
        }
        out.append(s);
    }
}
