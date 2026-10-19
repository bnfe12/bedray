package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.BufferUtils;
import org.lwjgl.util.shaderc.Shaderc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Runtime Shaderc compilation of the RT compute shaders (Vulkan 1.2 / SPIR-V 1.4, enough for GL_EXT_ray_query).
 * A compile error is an {@link RtFailure} carrying the compiler message; there is no silent fallback (rule 4).
 * An on-disk SPIR-V cache keyed by the source hash can be added later; compile times are logged to decide.
 */
public final class RtShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    private RtShaderCompiler() {
    }

    public static ByteBuffer compileResource(String resourcePath, String name) {
        return compileResource(resourcePath, name, "");
    }

    /** As {@link #compileResource(String, String)}; {@code defines} (e.g. {@code "#define VKB_GBUFFER 1
"}) go right after the {@code #version} line. */
    public static ByteBuffer compileResource(String resourcePath, String name, String defines) {
        String source = resolveIncludes(withDefines(loadSource(resourcePath), defines), resourcePath);
        long t0 = System.nanoTime();
        ByteBuffer spirv = compile(source, name);
        LOGGER.info(String.format(java.util.Locale.ROOT, "[VKB-RT] shader=%s compile_ms=%.1f spv_bytes=%d cached=false",
            name, (System.nanoTime() - t0) / 1.0e6, spirv.remaining()));
        return spirv;
    }

    /** Inserts {@code defines} after the first line (which must be the {@code #version} directive). */
    public static String withDefines(String source, String defines) {
        if (defines == null || defines.isEmpty()) {
            return source;
        }
        if (!source.startsWith("#version")) {
            throw new RtFailure("shader_load", "shader source does not start with #version, cannot insert defines");
        }
        int eol = source.indexOf((char) 10);
        if (eol < 0) {
            throw new RtFailure("shader_load", "shader source has only one line");
        }
        return source.substring(0, eol + 1) + defines + source.substring(eol + 1);
    }


    private static final java.util.regex.Pattern INCLUDE = java.util.regex.Pattern.compile("^[ \\t]*#include[ \\t]+\"([^\"]+)\"[ \\t]*\\r?$", java.util.regex.Pattern.MULTILINE);

    /**
     * Textual {@code #include "file.glsl"} (resolved next to {@code resourcePath} in the jar). Each file is inserted once
     * (include-once semantics); nested includes are resolved too. shaderc is given the expanded source, so error line numbers
     * refer to the expanded text. A missing include is an {@link RtFailure}.
     */
    public static String resolveIncludes(String source, String resourcePath) {
        String dir = resourcePath.substring(0, resourcePath.lastIndexOf('/') + 1);
        java.util.Set<String> done = new java.util.HashSet<>();
        return expand(source, dir, done, 0);
    }

    private static String expand(String source, String dir, java.util.Set<String> done, int depth) {
        if (depth > 8) {
            throw new RtFailure("shader_load", "include depth > 8 (cycle?)");
        }
        java.util.regex.Matcher m = INCLUDE.matcher(source);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            String name = m.group(1);
            if (done.add(name)) {
                out.append(expand(loadSource(dir + name), dir, done, depth + 1));
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
            }
            last = m.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }
    public static String loadSource(String resourcePath) {
        try (InputStream in = RtShaderCompiler.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RtFailure("shader_load", "resource_not_found " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RtFailure("shader_load", "io_error " + resourcePath, e);
        }
    }

    static ByteBuffer compile(String source, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new RtFailure("shader_compile", "shaderc_compiler_initialize returned 0");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_4);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            // The CharSequence overload encodes the text on the thread's MemoryStack (LWJGL default 64 KiB): the expanded
            // rt_rays/rt_path sources (~66 KiB) overflowed it ("OutOfMemoryError: Out of stack space" in
            // shaderc_compile_into_spv, render thread hung). Heap-encoded buffers, ByteBuffer overload (javap lwjgl-shaderc 3.4.3:
            // shaderc_compile_into_spv(long, ByteBuffer, int, ByteBuffer, ByteBuffer, long); names null-terminated).
            java.nio.ByteBuffer src = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
            java.nio.ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memUTF8(name, true);
            java.nio.ByteBuffer entry = org.lwjgl.system.MemoryUtil.memUTF8("main", true);
            long result;
            try {
                result = Shaderc.shaderc_compile_into_spv(compiler, src, Shaderc.shaderc_glsl_compute_shader, fileName, entry, options);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(src);
                org.lwjgl.system.MemoryUtil.memFree(fileName);
                org.lwjgl.system.MemoryUtil.memFree(entry);
            }
            if (result == 0L) {
                throw new RtFailure("shader_compile", name + ": null result handle");
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    throw new RtFailure("shader_compile", name + " status=" + status + ": "
                        + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = BufferUtils.createByteBuffer(bytes.remaining());
                copy.put(bytes);
                copy.flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
