package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import org.lwjgl.BufferUtils;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Stage I2: compiles Vulkan GLSL 450 produced by {@link IrisGlslTranslator} into valid SPIR-V bytecode using LWJGL's
 * bundled Shaderc compiler. Supports vertex and fragment shader stages.
 */
public final class SpirvCompiler {

    public static final int SPIRV_MAGIC = 0x07230203;
    public static final int SPIRV_MAGIC_REVERSED = 0x03022307;

    public static class SpirvCompileException extends Exception {
        private final int status;
        private final String errorLog;

        public SpirvCompileException(String message) {
            super(message);
            this.status = -1;
            this.errorLog = message;
        }

        public SpirvCompileException(String name, int status, String errorLog) {
            super(name + " compilation failed (status=" + status + "): " + errorLog);
            this.status = status;
            this.errorLog = errorLog;
        }

        public int status() {
            return status;
        }

        public String errorLog() {
            return errorLog;
        }
    }

    private SpirvCompiler() {}

    /**
     * Maps an {@link IrisGlslTranslator.Stage} to its corresponding Shaderc shader kind constant.
     */
    public static int toShadercKind(IrisGlslTranslator.Stage stage) {
        Objects.requireNonNull(stage, "stage");
        return switch (stage) {
            case VERTEX -> Shaderc.shaderc_glsl_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_glsl_fragment_shader;
        };
    }

    /**
     * Compiles the given GLSL source for the specified stage into a direct {@link ByteBuffer} containing SPIR-V bytecode.
     *
     * @param source GLSL source code (e.g. Vulkan GLSL 450)
     * @param stage  vertex or fragment stage
     * @param name   display name or identifier used for diagnostics
     * @return direct {@link ByteBuffer} with SPIR-V bytecode (position 0, limit = byte count)
     * @throws SpirvCompileException if compilation fails or compiler cannot be initialized
     */
    public static ByteBuffer compile(String source, IrisGlslTranslator.Stage stage, String name) throws SpirvCompileException {
        return compile(source, toShadercKind(stage), name);
    }

    /**
     * Compiles the given GLSL source using a raw Shaderc shader kind constant into SPIR-V bytecode.
     */
    public static ByteBuffer compile(String source, int shadercKind, String name) throws SpirvCompileException {
        Objects.requireNonNull(source, "source");
        String displayName = (name == null || name.isBlank()) ? "<unnamed>" : name;

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new SpirvCompileException(displayName + ": shaderc_compiler_initialize returned 0");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new SpirvCompileException(displayName + ": shaderc_compile_options_initialize returned 0");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_4);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);

            long result = Shaderc.shaderc_compile_into_spv(compiler, source, shadercKind, displayName, "main", options);
            if (result == 0L) {
                throw new SpirvCompileException(displayName + ": null shaderc result handle");
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String error = Shaderc.shaderc_result_get_error_message(result);
                    throw new SpirvCompileException(displayName, status, error);
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                if (bytes == null) {
                    throw new SpirvCompileException(displayName + ": shaderc returned null bytecode buffer");
                }
                ByteBuffer copy = BufferUtils.createByteBuffer(bytes.remaining());
                copy.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                copy.put(bytes);
                copy.flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            if (options != 0L) {
                Shaderc.shaderc_compile_options_release(options);
            }
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /**
     * Convenience method returning byte array.
     */
    public static byte[] compileToBytes(String source, IrisGlslTranslator.Stage stage, String name) throws SpirvCompileException {
        ByteBuffer buf = compile(source, stage, name);
        byte[] arr = new byte[buf.remaining()];
        buf.get(arr);
        return arr;
    }

    /**
     * Checks if the buffer contains valid SPIR-V bytecode by inspecting magic number and word alignment.
     * Supports both host-order (0x07230203) and byte-swapped (0x03022307) representations per SPIR-V Specification §3.1.
     */
    public static boolean isValidSpirv(ByteBuffer spirv) {
        if (spirv == null || spirv.remaining() < 20 || (spirv.remaining() % 4) != 0) {
            return false;
        }
        int magic = spirv.getInt(spirv.position());
        return magic == SPIRV_MAGIC || magic == SPIRV_MAGIC_REVERSED;
    }
}
