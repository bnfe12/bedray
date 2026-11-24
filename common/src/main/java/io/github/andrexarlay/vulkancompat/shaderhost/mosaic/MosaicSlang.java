package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Slang side of Mosaic: turns GLSL node code into Slang (so every function and effect also has a Slang body), finds
 * slangc, and compiles the generated Slang shader to SPIR-V with errors mapped back to nodes and node-code lines, like
 * {@link MosaicShaderCheck} does for GLSL. The studio uses it to hand the game ready SPIR-V.
 */
public final class MosaicSlang {

    private MosaicSlang() {}

    // ---------------------------------------------------------------- GLSL -> Slang node code

    private static final String[][] WORDS = {
        {"vec2", "float2"}, {"vec3", "float3"}, {"vec4", "float4"}, {"ivec2", "int2"}, {"ivec3", "int3"}, {"ivec4", "int4"},
        {"uvec2", "uint2"}, {"uvec3", "uint3"}, {"uvec4", "uint4"}, {"bvec2", "bool2"}, {"bvec3", "bool3"}, {"bvec4", "bool4"},
        {"mat2", "float2x2"}, {"mat3", "float3x3"}, {"mat4", "float4x4"},
        {"mix", "lerp"}, {"fract", "frac"}, {"mod", "fmod"}, {"inversesqrt", "rsqrt"}, {"dFdx", "ddx"}, {"dFdy", "ddy"}};

    /**
     * Translates the GLSL subset node code uses into Slang: type names, renamed intrinsics and two-argument {@code atan}.
     * Matrix products and GLSL vector relational functions are not translated: such code should carry its own Slang body.
     */
    public static String fromGlsl(String glsl) {
        String s = glsl;
        for (String[] w : WORDS) s = s.replaceAll("\\b" + w[0] + "\\b", w[1]);
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\\batan\\s*\\(").matcher(s);
        int last = 0;
        while (m.find()) {
            int open = m.end() - 1, depth = 0, commas = 0, i = open;
            for (; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
                else if (c == ',' && depth == 1) commas++;
            }
            out.append(s, last, m.start()).append(commas == 1 ? "atan2(" : "atan(");
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    // ---------------------------------------------------------------- slangc

    /** slangc from a configured path, -Dbedray.slangc / BEDRAY_SLANGC / PATH, or the usual SDK folders; null when absent. */
    public static Path locate(String configured) {
        if (configured != null && !configured.isBlank() && Files.isRegularFile(Path.of(configured))) return Path.of(configured);
        Path found = io.github.andrexarlay.vulkancompat.shaderhost.glsl.SlangCompiler.locate();
        if (found != null && Files.isRegularFile(found)) return found;
        List<Path> roots = new ArrayList<>();
        String env = System.getenv("SLANG_DIR");
        if (env != null) roots.add(Path.of(env));
        roots.add(Path.of(System.getProperty("user.home"), ".bedray", "slang"));
        try (var dirs = Files.list(Path.of("C:/Temp"))) {
            dirs.filter(d -> d.getFileName().toString().startsWith("bedray-slang")).forEach(roots::add);
        } catch (Exception ignored) {
            // no such folder
        }
        for (Path r : roots)
            for (String exe : new String[] {"bin/slangc.exe", "bin/slangc"})
                if (Files.isRegularFile(r.resolve(exe))) return r.resolve(exe);
        return null;
    }

    private static final Pattern ERROR = Pattern.compile("(?m)^error\\[E\\d+\\]: (.*)\\R\\s*-->\\s*.*?:(\\d+):(\\d+)");

    /** Compiles generated Slang (see {@link MosaicCodegen#generate} with slang = true) to SPIR-V. */
    public static MosaicShaderCheck.Result compile(MosaicCodegen.Generated gen, Path slangc) {
        if (slangc == null) return new MosaicShaderCheck.Result(false, 0, 0,
            List.of(new MosaicShaderCheck.Error(0, "slangc not found (Settings > Compiler)", null, null, 0)), "");
        long start = System.nanoTime();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("mosaic-slang");
            Path src = dir.resolve("mosaic.generated.slang"), out = dir.resolve("mosaic.spv");
            Files.writeString(src, gen.source(), StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(slangc.toString(), src.toString(), "-target", "spirv", "-profile", "cs_6_5",
                "-entry", "main", "-emit-spirv-directly", "-matrix-layout-column-major", "-o", out.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            long micros = (System.nanoTime() - start) / 1000;
            if (exit == 0 && Files.isRegularFile(out)) {
                byte[] spv = Files.readAllBytes(out);
                return new MosaicShaderCheck.Result(true, spv.length, micros, List.of(), log, spv);
            }
            return new MosaicShaderCheck.Result(false, 0, micros, map(log, gen), log);
        } catch (Exception e) {
            return new MosaicShaderCheck.Result(false, 0, 0, List.of(new MosaicShaderCheck.Error(0, "slangc failed: " + e, null, null, 0)), "");
        } finally {
            if (dir != null) try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (Exception ignored) {
                // temp folder
            }
        }
    }

    static List<MosaicShaderCheck.Error> map(String log, MosaicCodegen.Generated gen) {
        List<MosaicShaderCheck.Error> out = new ArrayList<>();
        Matcher m = ERROR.matcher(log);
        while (m.find()) {
            int line = Integer.parseInt(m.group(2));
            MosaicCodegen.Span s = gen.at(line);
            String msg = m.group(1).trim();
            if (s == null) out.add(new MosaicShaderCheck.Error(line, msg, null, null, 0));
            else out.add(new MosaicShaderCheck.Error(line, msg, s.node(), s.function(),
                s.function() != null && line >= s.bodyFirst() ? line - s.bodyFirst() + 1 : 0));
        }
        if (out.isEmpty()) out.add(new MosaicShaderCheck.Error(0, log.length() > 300 ? log.substring(0, 300) + "..." : log, null, null, 0));
        return out;
    }

    /** Hash that ties a prebuilt SPIR-V to the graph it came from (the generated Slang source). */
    public static String sourceHash(String source) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return Integer.toHexString(source.hashCode());
        }
    }
}
