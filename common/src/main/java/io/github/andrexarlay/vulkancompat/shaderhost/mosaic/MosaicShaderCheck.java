package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles generated Mosaic GLSL exactly like the host does (same entry name, macros and shaderc path) and maps every
 * compiler error back to the node or the user function that produced the line. The editor runs it in the background
 * after each change, so a typo in node code is reported at the node before anything reaches the game.
 */
public final class MosaicShaderCheck {

    private MosaicShaderCheck() {}

    public static final String ROOT = "shaders/";
    public static final String ENTRY = ROOT + "bedray/mosaic.generated.comp";
    public static final Map<String, String> MACROS = Map.of("BEDRAY_CUSTOM_RT", "1", "BEDRAY_MOSAIC", "1");

    /** One compiler message; {@code bodyLine} is the 1-based line inside a user function's code, else 0. */
    public record Error(int line, String message, String node, String function, int bodyLine) {
        public String where() {
            if (function != null) return "node code of " + function + (bodyLine > 0 ? ", line " + bodyLine : "");
            if (node != null) return "node " + node;
            return line > 0 ? "generated line " + line : "shader";
        }
    }

    public record Result(boolean ok, int spirvBytes, long micros, List<Error> errors, String raw, byte[] spirv) {
        public Result(boolean ok, int spirvBytes, long micros, List<Error> errors, String raw) {
            this(ok, spirvBytes, micros, errors, raw, null);
        }
    }

    private static final Pattern LINE = Pattern.compile("(?m)^[^\\n]*?(?::|\\()(\\d+)\\)?: (?:error|fatal error): (.*)$");

    public static Result compile(MosaicCodegen.Generated gen) {
        long start = System.nanoTime();
        try {
            VulkanGlslPackCompiler.Compiled c = VulkanGlslPackCompiler.compile(ROOT, ENTRY,
                e -> {
                    if (e.equals(ENTRY)) return gen.source();
                    throw new java.io.IOException("node code cannot include files: " + e);
                },
                VulkanGlslPackCompiler.Kind.COMPUTE, MACROS);
            java.nio.ByteBuffer buf = c.spirv().duplicate();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return new Result(true, bytes.length, (System.nanoTime() - start) / 1000, List.of(), "", bytes);
        } catch (SpirvCompiler.SpirvCompileException | java.io.IOException | RuntimeException e) {
            String raw = String.valueOf(e.getMessage());
            return new Result(false, 0, (System.nanoTime() - start) / 1000, map(raw, gen), raw);
        } catch (LinkageError e) {
            // shaderc natives are missing (unit tests without natives): report, do not crash the editor
            return new Result(false, 0, 0, List.of(new Error(0, "shader compiler unavailable: " + e, null, null, 0)), "");
        }
    }

    static List<Error> map(String raw, MosaicCodegen.Generated gen) {
        List<Error> out = new ArrayList<>();
        Matcher m = LINE.matcher(raw);
        while (m.find()) {
            int line = Integer.parseInt(m.group(1));
            MosaicCodegen.Span s = gen.at(line);
            String msg = m.group(2).trim();
            if (s == null) out.add(new Error(line, msg, null, null, 0));
            else out.add(new Error(line, msg, s.node(), s.function(), s.function() != null && line >= s.bodyFirst()
                ? line - s.bodyFirst() + 1 : 0));
        }
        if (out.isEmpty()) out.add(new Error(0, raw.length() > 300 ? raw.substring(0, 300) + "..." : raw, null, null, 0));
        return out;
    }
}
