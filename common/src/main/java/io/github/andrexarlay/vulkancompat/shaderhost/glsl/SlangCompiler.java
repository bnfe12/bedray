package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiles Slang (author-host shaders) and Vulkan GLSL 450 (translated pack shaders) to SPIR-V via the external slangc
 * toolchain. Preferred over shaderc per owner directive; callers fall back to {@link SpirvCompiler} when {@link #available()}
 * is false. Toolchain discovery: -Dbedray.slangc, then env BEDRAY_SLANGC, then PATH.
 */
public final class SlangCompiler {

    public static class SlangCompileException extends Exception {
        private final String errorLog;

        public SlangCompileException(String message, String errorLog) {
            super(message);
            this.errorLog = errorLog;
        }

        public String errorLog() {
            return errorLog;
        }
    }

    private SlangCompiler() {}

    public static Path locate() {
        String prop = System.getProperty("bedray.slangc");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("BEDRAY_SLANGC");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (String name : new String[] {"slangc", "slangc.exe"}) {
                    Path cand = Path.of(dir, name);
                    if (Files.isRegularFile(cand)) {
                        return cand;
                    }
                }
            }
        }
        return null;
    }

    public static boolean available() {
        Path p = locate();
        return p != null && Files.isRegularFile(p);
    }

    public static ByteBuffer compileSlang(String source, String stage, String entry, String name) throws SlangCompileException {
        return compile(source, stage, entry, name, false);
    }

    public static ByteBuffer compileGlsl450(String source, String stage, String entry, String name) throws SlangCompileException {
        return compile(source, stage, entry, name, true);
    }

    private static ByteBuffer compile(String source, String stage, String entry, String name, boolean glsl) throws SlangCompileException {
        Objects.requireNonNull(source, "source");
        Path tool = locate();
        if (tool == null || !Files.isRegularFile(tool)) {
            throw new SlangCompileException(name + ": slangc not available", "slangc not found (set -Dbedray.slangc)");
        }
        String displayName = (name == null || name.isBlank()) ? "<unnamed>" : name;
        String ext = glsl ? ("fragment".equals(stage) ? ".frag" : ".vert") : ".slang";
        Path dir = null;
        try {
            dir = Files.createTempDirectory("bedray-slang");
            Path src = dir.resolve("shader" + ext);
            Path out = dir.resolve("shader.spv");
            Files.writeString(src, source, StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of(tool.toString(), src.toString(),
                "-profile", "spirv_1_5", "-stage", stage, "-entry", entry == null ? "main" : entry, "-o", out.toString()));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String log = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = proc.waitFor();
            if (exit != 0 || !Files.isRegularFile(out)) {
                throw new SlangCompileException(displayName + ": slangc exit=" + exit, log);
            }
            byte[] bytes = Files.readAllBytes(out);
            ByteBuffer buf = BufferUtils.createByteBuffer(bytes.length);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.put(bytes);
            buf.flip();
            return buf;
        } catch (IOException e) {
            throw new SlangCompileException(displayName + ": " + e, String.valueOf(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlangCompileException(displayName + ": interrupted", String.valueOf(e));
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException ignored) {
                }
            }
        }
    }
}