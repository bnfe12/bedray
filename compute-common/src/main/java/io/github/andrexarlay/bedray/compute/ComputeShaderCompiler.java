package io.github.andrexarlay.bedray.compute;

import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/** Compiles portable Vulkan 1.0 compute; no ray tracing or device address extensions. */
public final class ComputeShaderCompiler {
    private ComputeShaderCompiler() {}

    public static ByteBuffer compile(String source, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("Cannot create shaderc compiler");
        long options = 0;
        long result = 0;
        ByteBuffer utf8 = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            options = Shaderc.shaderc_compile_options_initialize();
            if (options == 0) throw new IllegalStateException("Cannot create shaderc options");
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_0);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            utf8 = MemoryUtil.memUTF8(source, false);
            result = Shaderc.shaderc_compile_into_spv(compiler, utf8, Shaderc.shaderc_glsl_compute_shader,
                    stack.UTF8(name), stack.UTF8("main"), options);
            if (result == 0 || Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalArgumentException(name + ": " + (result == 0 ? "no compiler result" : Shaderc.shaderc_result_get_error_message(result)));
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            if (bytes == null) throw new IllegalStateException("Empty SPIR-V");
            ByteBuffer copy = BufferUtils.createByteBuffer(bytes.remaining());
            return copy.put(bytes).flip();
        } finally {
            if (utf8 != null) MemoryUtil.memFree(utf8);
            if (result != 0) Shaderc.shaderc_result_release(result);
            if (options != 0) Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
