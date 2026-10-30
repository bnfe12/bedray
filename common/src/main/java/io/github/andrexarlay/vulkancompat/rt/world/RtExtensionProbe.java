package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.util.shaderc.Shaderc;

/**
 * Capability probe for {@code GL_EXT_opacity_micromap}, kept because {@link OpacityMicromapCache} decides from it
 * whether an acceleration structure may reference micromaps at all.
 *
 * <p>This is a query about the driver, not a renderer: it compiles eight lines of ray-query GLSL and keeps the answer.
 * The legacy path used the same Shaderc compiler to build every RT shader from source at runtime; that compiler and
 * those shaders are gone, and only this probe outlived them.
 */
public final class RtExtensionProbe {
    private static final Object LOCK = new Object();
    private static volatile Boolean micromapSupported;

    private RtExtensionProbe() {
    }

    /** False when Shaderc or the driver rejects the extension, which disables opacity micromaps for the session. */
    public static boolean supportsOpacityMicromap() {
        Boolean answer = micromapSupported;
        if (answer != null) {
            return answer;
        }
        synchronized (LOCK) {
            if (micromapSupported != null) {
                return micromapSupported;
            }
            int supportedWith = Shaderc.shaderc_spirv_version_1_4;
            try {
                compile(ProbeSource.RAY_QUERY_WITH_MICROMAP, supportedWith);
            } catch (Throwable rejected14) {
                try {
                    supportedWith = Shaderc.shaderc_spirv_version_1_5;
                    compile(ProbeSource.RAY_QUERY_WITH_MICROMAP, supportedWith);
                } catch (Throwable rejected15) {
                    micromapSupported = false;
                    return false;
                }
            }
            micromapSupported = true;
            return true;
        }
    }

    /** Compile one compute source to SPIR-V, or throw with the compiler's own message. */
    private static void compile(String source, int spirvVersion) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new RtFailure("shader_compile", "shaderc_compiler_initialize returned 0");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new RtFailure("shader_compile", "shaderc_compile_options_initialize returned 0");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_target_spirv(options, spirvVersion);
            Shaderc.shaderc_compile_options_set_optimization_level(options,
                Shaderc.shaderc_optimization_level_zero);
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_glsl_compute_shader,
                "bedray_extension_probe", "main", options);
            if (result == 0L) {
                throw new RtFailure("shader_compile", "probe: null result handle");
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    throw new RtFailure("shader_compile", "probe status=" + status + ": "
                        + Shaderc.shaderc_result_get_error_message(result));
                }
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /** A micromap declaration is only legal in a shader that also does ray traversal (raytraversal.adoc). */
    private static final class ProbeSource {
        static final String RAY_QUERY_WITH_MICROMAP = "#version 460\n"
            + "#extension GL_EXT_ray_query : require\n"
            + "#extension GL_EXT_opacity_micromap : require\n"
            + "layout(set = 0, binding = 0) uniform accelerationStructureEXT as;\n"
            + "layout(local_size_x = 1) in;\n"
            + "void main() {\n"
            + "    rayQueryEXT q;\n"
            + "    rayQueryInitializeEXT(q, as, 0, 0u, vec3(0.0), 0.0, vec3(1.0), 0.0);\n"
            + "}\n";

        private ProbeSource() {
        }
    }
}
