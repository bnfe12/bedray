package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.shaderc.Shaderc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime Shaderc compilation of the RT compute shaders (Vulkan 1.2 / SPIR-V 1.4, enough for GL_EXT_ray_query).
 * A compile error is an {@link RtFailure} carrying the compiler message; there is no silent fallback (rule 4). Expanded source
 * variants are cached as SPIR-V on disk and can be compiled in the background before ray tracing is enabled.
 */
public final class RtShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final ConcurrentHashMap<String, CompletableFuture<byte[]>> COMPILED = new ConcurrentHashMap<>();
    private static final AtomicBoolean PREWARM_STARTED = new AtomicBoolean();
    private static volatile RtShaderDiskCache diskCache;
    private static volatile boolean diskCacheUnavailable;

    private RtShaderCompiler() {
    }

    /** GLSL injected after the {@code #version} line of every compile when the opacity micromap probe passed. */
    private static volatile String extensionPrefix = "";
    /** SPIR-V target of every compile; the opacity micromap probe may move it from 1.4 to 1.5. */
    private static volatile int targetSpirv = Shaderc.shaderc_spirv_version_1_4;
    private static volatile Boolean ommProbeResult;

    public static ByteBuffer compileResource(String resourcePath, String name) {
        return compileResource(resourcePath, name, "");
    }

    /** As {@link #compileResource(String, String)}; {@code defines} (e.g. {@code "#define VKB_GBUFFER 1
"}) go right after the {@code #version} line. */
    public static ByteBuffer compileResource(String resourcePath, String name, String defines) {
        String source = resolveIncludes(withDefines(loadSource(resourcePath), extensionPrefix + defines), resourcePath);
        RtShaderDiskCache cache = diskCache();
        String key = RtShaderDiskCache.key(source, targetSpirv);
        long t0 = System.nanoTime();
        if (cache != null) {
            ByteBuffer cached = cache.read(key);
            if (cached != null) {
                byte[] bytes = bytes(cached);
                COMPILED.putIfAbsent(key, CompletableFuture.completedFuture(bytes));
                logCache(name, (System.nanoTime() - t0) / 1.0e6, bytes.length, "disk");
                return direct(bytes);
            }
        }

        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = COMPILED.putIfAbsent(key, mine);
        if (existing != null) {
            try {
                byte[] bytes = existing.join();
                logCache(name, (System.nanoTime() - t0) / 1.0e6, bytes.length, "memory");
                return direct(bytes);
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new RtFailure("shader_compile", name + ": asynchronous compilation failed", cause);
            }
        }

        try {
            ByteBuffer spirv = compile(source, name);
            byte[] bytes = bytes(spirv);
            if (cache != null) {
                try {
                    cache.write(key, spirv);
                } catch (IOException e) {
                    LOGGER.debug("[VKB-RT] shader={} disk_cache_write_failed={}", name, e.toString());
                }
            }
            mine.complete(bytes);
            logCache(name, (System.nanoTime() - t0) / 1.0e6, bytes.length, "miss");
            return direct(bytes);
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            COMPILED.remove(key, mine);
            throw e;
        }
    }

    /** Starts one daemon worker that compiles known Bedray shaders before the player first enables ray tracing. */
    public static void prewarmAsync() {
        if (!Boolean.parseBoolean(System.getProperty("bedray.shaderCache.prewarm", "true"))
            || !PREWARM_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            LOGGER.info("[Bedray/ShaderCache] asynchronous SPIR-V warmup started");
            try {
                List<ShaderVariant> variants = warmupVariants();
                for (ShaderVariant variant : variants) {
                    compileResource(variant.path(), variant.name(), variant.defines());
                }
                LOGGER.info("[Bedray/ShaderCache] asynchronous SPIR-V warmup complete variants={}", variants.size());
            } catch (Throwable e) {
                LOGGER.warn("[Bedray/ShaderCache] asynchronous SPIR-V warmup stopped: {}", e.toString());
            }
        }, "Bedray-SPIR-V-Warmup");
        worker.setDaemon(true);
        worker.start();
    }

    private static List<ShaderVariant> warmupVariants() {
        String accum = RtRayEffectsPass.ACCUM_DEFINE;
        String gbufferAccum = RtPipeline.GBUFFER_DEFINE + accum;
        return List.of(
            new ShaderVariant(RtPipeline.SHADER_PATH, "rt_rays.comp+warmup", accum),
            new ShaderVariant(RtPipeline.SHADER_PATH, "rt_rays.comp+gbuffer+warmup", gbufferAccum),
            new ShaderVariant(RtPipeline.PATH_SHADER_PATH, "rt_path.comp+warmup", accum),
            new ShaderVariant(RtPipeline.PATH_SHADER_PATH, "rt_path.comp+gbuffer+warmup", gbufferAccum),
            new ShaderVariant("/shaders/rt_atrous.comp", "rt_atrous.comp+warmup", accum),
            new ShaderVariant("/shaders/rt_volumetric.comp", "rt_volumetric.comp+march+warmup",
                "#define VKB_POST 1" + (char) 10 + accum + "#define VOL_STAGE 0" + (char) 10),
            new ShaderVariant("/shaders/rt_volumetric.comp", "rt_volumetric.comp+apply+warmup",
                "#define VKB_POST 1" + (char) 10 + accum + "#define VOL_STAGE 1" + (char) 10),
            new ShaderVariant("/shaders/rt_bloom.comp", "rt_bloom.comp+prefilter_down+warmup", "#define BLOOM_STAGE 0" + (char) 10),
            new ShaderVariant("/shaders/rt_bloom.comp", "rt_bloom.comp+down+warmup", "#define BLOOM_STAGE 1" + (char) 10),
            new ShaderVariant("/shaders/rt_bloom.comp", "rt_bloom.comp+up+warmup", "#define BLOOM_STAGE 2" + (char) 10),
            new ShaderVariant("/shaders/rt_bloom.comp", "rt_bloom.comp+apply+warmup", "#define BLOOM_STAGE 3" + (char) 10),
            new ShaderVariant(RtCompositePipeline.SHADER_PATH, "rt_composite.comp+warmup", ""),
            new ShaderVariant("/shaders/rt_pack_nrd.comp", "rt_pack_nrd.comp+pack+warmup", "#define VKB_PASS_PACK 1" + (char) 10),
            new ShaderVariant("/shaders/rt_pack_nrd.comp", "rt_pack_nrd.comp+compose+warmup", "#define VKB_PASS_COMPOSE 1" + (char) 10),
            new ShaderVariant("/shaders/rt_particles_composite.comp", "rt_particles_composite.comp+warmup", "")
        );
    }

    private static RtShaderDiskCache diskCache() {
        RtShaderDiskCache current = diskCache;
        if (current != null || diskCacheUnavailable) {
            return current;
        }
        synchronized (RtShaderCompiler.class) {
            if (diskCache != null || diskCacheUnavailable) {
                return diskCache;
            }
            try {
                Path root = Platforms.get().configDir().resolve("bedray").resolve("cache").resolve("spirv");
                diskCache = new RtShaderDiskCache(root);
                return diskCache;
            } catch (RuntimeException e) {
                diskCacheUnavailable = true;
                LOGGER.debug("[Bedray/ShaderCache] persistent cache unavailable: {}", e.toString());
                return null;
            }
        }
    }

    private static void logCache(String name, double elapsedMs, int bytes, String source) {
        LOGGER.info(String.format(java.util.Locale.ROOT,
            "[VKB-RT] shader=%s ready_ms=%.1f spv_bytes=%d source=%s", name, elapsedMs, bytes, source));
    }

    private static byte[] bytes(ByteBuffer data) {
        ByteBuffer copy = data.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer copy = BufferUtils.createByteBuffer(bytes.length);
        copy.put(bytes).flip();
        return copy;
    }

    private record ShaderVariant(String path, String name, String defines) {
    }

    /**
     * Compile probe for GL_EXT_opacity_micromap on a minimal ray-query compute shader. An acceleration structure
     * that references opacity micromaps must be declared in the shader (raytraversal.adoc), so on success the
     * extension is injected into every later RT shader compile; if SPIR-V 1.4 rejects it, 1.5 is probed and becomes
     * the target. A failure disables opacity micromaps for the session and leaves all shaders unchanged.
     */
    public static boolean probeOpacityMicromapExtension() {
        Boolean r = ommProbeResult;
        if (r != null) {
            return r;
        }
        synchronized (RtShaderCompiler.class) {
            if (ommProbeResult != null) {
                return ommProbeResult;
            }
            String probe = "#version 460\n"
                + "#extension GL_EXT_ray_query : require\n"
                + "#extension GL_EXT_opacity_micromap : require\n"
                + "layout(set = 0, binding = 0) uniform accelerationStructureEXT as;\n"
                + "layout(local_size_x = 1) in;\n"
                + "void main() {\n"
                + "    rayQueryEXT q;\n"
                + "    rayQueryInitializeEXT(q, as, 0, 0u, vec3(0.0), 0.0, vec3(1.0), 0.0);\n"
                + "}\n";
            boolean ok = false;
            try {
                compileSpirv(probe, "omm_probe", Shaderc.shaderc_spirv_version_1_4);
                ok = true;
            } catch (Throwable t14) {
                try {
                    compileSpirv(probe, "omm_probe", Shaderc.shaderc_spirv_version_1_5);
                    targetSpirv = Shaderc.shaderc_spirv_version_1_5;
                    ok = true;
                    LOGGER.info("[VKB-RT] opacity micromap probe: SPIR-V 1.4 rejected ({}), compiling with 1.5", brief(t14));
                } catch (Throwable t15) {
                    LOGGER.info("[VKB-RT] opacity micromap probe: shaderc rejects the extension ({} / {})",
                        brief(t14), brief(t15));
                }
            }
            if (ok) {
                extensionPrefix = "#extension GL_EXT_opacity_micromap : require\n";
                LOGGER.info("[VKB-RT] opacity micromap probe: shaderc accepts the extension, GLSL prefix injected");
            }
            ommProbeResult = ok;
            return ok;
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        String s = (m == null ? t.toString() : m).replace('\n', ' ');
        return s.length() > 120 ? s.substring(0, 120) : s;
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
        return compileSpirv(source, name, targetSpirv);
    }

    static ByteBuffer compileSpirv(String source, String name, int spirvVersion) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new RtFailure("shader_compile", "shaderc_compiler_initialize returned 0");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_target_spirv(options, spirvVersion);
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
