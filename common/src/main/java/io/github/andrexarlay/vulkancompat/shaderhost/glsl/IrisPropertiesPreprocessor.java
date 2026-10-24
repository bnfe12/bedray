package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackProperties;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conditional properties use the same native expression evaluator as GLSL, without tokenizing property values as GLSL. */
public final class IrisPropertiesPreprocessor {
    private static final Pattern ACTIVE = Pattern.compile("(?m)^\\s*#\\s*pragma\\s+bedray_property_(\\d+)\\s*$");
    private static final Pattern DIRECTIVE = Pattern.compile("^#\\s*(\\w+)\\b.*");
    private static final Set<String> CONDITIONS = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif", "define", "undef", "error");

    private IrisPropertiesPreprocessor() {}

    public static PackProperties read(String text, Map<String, String> macros)
        throws IOException, SpirvCompiler.SpirvCompileException {
        if (text.length() > (1 << 20)) throw new PackScanException("shaders.properties exceeds 1 MiB character budget");
        var lines = new ArrayList<String>();
        StringBuilder encoded = new StringBuilder("#version 450\n");
        StringBuilder logical = new StringBuilder();
        for (String physical : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = physical.strip();
            if (line.startsWith("\uFEFF")) line = line.substring(1).strip();
            if (line.endsWith("\\")) { logical.append(line, 0, line.length() - 1).append(' '); continue; }
            logical.append(line);
            line = logical.toString().strip();
            logical.setLength(0);
            if (line.isEmpty() || line.startsWith("!")) continue;
            if (line.startsWith("#")) {
                var directive = DIRECTIVE.matcher(line);
                if (directive.matches()) {
                    String word = directive.group(1);
                    if (word.equals("include")) throw new PackScanException("includes in shaders.properties are unsupported");
                    if (CONDITIONS.contains(word)) encoded.append(line).append('\n');
                }
            } else {
                // The opaque ID preserves option names in profiles/screens and literal backslashes.
                // The native preprocessor decides only which property lines are active.
                encoded.append("#pragma bedray_property_").append(lines.size()).append('\n');
                lines.add(line);
            }
        }
        if (!logical.isEmpty()) throw new PackScanException("unterminated continuation in shaders.properties");
        String preprocessed = run(encoded.toString(), macros);
        StringBuilder active = new StringBuilder();
        var matches = ACTIVE.matcher(preprocessed);
        while (matches.find()) active.append(lines.get(Integer.parseInt(matches.group(1)))).append('\n');
        return PackProperties.parse(active.toString());
    }

    /** Evaluate enabled expressions in one compiler call. A malformed expression refuses the pack by name. */
    public static Map<String, Boolean> conditions(Map<String, String> expressions, Map<String, String> macros)
        throws IOException, SpirvCompiler.SpirvCompileException {
        var names = new ArrayList<>(expressions.keySet());
        StringBuilder source = new StringBuilder("#version 450\n");
        for (int i = 0; i < names.size(); i++) {
            String expression = expressions.get(names.get(i));
            if (expression.isBlank() || !expression.matches("[A-Za-z0-9_!&|<>=()+*/%~^?:. \\t+-]+"))
                throw new PackScanException("unsupported enabled expression for " + names.get(i) + ": " + expression);
            source.append("#if ").append(expression).append("\n#pragma bedray_property_").append(i).append("\n#endif\n");
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        names.forEach(name -> result.put(name, false));
        if (!names.isEmpty()) {
            var matches = ACTIVE.matcher(run(source.toString(), macros));
            while (matches.find()) result.put(names.get(Integer.parseInt(matches.group(1))), true);
        }
        return result;
    }

    private static String run(String text, Map<String, String> macros) throws IOException, SpirvCompiler.SpirvCompileException {
        return IrisSourcePreprocessor.preprocess("", "shaders.properties", name -> {
            if (!name.equals("shaders.properties")) throw new PackScanException("properties include refused: " + name);
            return text;
        }, IrisGlslTranslator.Stage.VERTEX, macros);
    }
}
