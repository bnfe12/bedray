package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the runtime shaderc configuration. The expanded {@code rt_rays}/{@code rt_path} sources are ~261 KB; with
 * lwjgl-shaderc 3.3.3 and 3.4.3 both {@code optimization_level_performance} and {@code optimization_level_size} spin in
 * glslang for minutes instead of producing SPIR-V, while {@code level_zero} finishes in about a second. See
 * {@link RtShaderCompiler#compileSpirv(String, String, int)}.
 */
class RtShaderCompilerOptionsTest {
    @Test
    void runtimeCompilerKeepsSpirvOptimizerOff() {
        assertEquals(Shaderc.shaderc_optimization_level_zero, RtShaderCompiler.RUNTIME_OPTIMIZATION_LEVEL,
            "a non-zero shaderc optimisation level hangs on the large expanded RT sources");
    }
}
