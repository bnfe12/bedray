package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 tests for {@link SpirvCompiler}. The large-source case guards against the LWJGL MemoryStack overflow
 * (OutOfMemoryError: "Out of stack space") that the CharSequence shaderc overload hits on big expanded packs.
 */
class SpirvCompilerTest {

    @Test
    void smallFragmentCompiles() throws SpirvCompiler.SpirvCompileException {
        String src = """
            #version 450
            layout(location=0) out vec4 fragColor;
            void main() { fragColor = vec4(1.0, 0.5, 0.25, 1.0); }
            """;
        ByteBuffer spirv = SpirvCompiler.compile(src, IrisGlslTranslator.Stage.FRAGMENT, "small.fsh");
        assertTrue(SpirvCompiler.isValidSpirv(spirv), "small source must yield valid SPIR-V");
    }

    @Test
    void largeSourceExceedingMemoryStackCompiles() throws SpirvCompiler.SpirvCompileException {
        StringBuilder sb = new StringBuilder();
        sb.append("#version 450\n");
        sb.append("layout(location=0) out vec4 fragColor;\n");
        sb.append("void main() {\n");
        sb.append("  vec4 c = vec4(0.0);\n");
        for (int i = 0; i < 6000; i++) {
            sb.append("  c += vec4(").append(i).append(".0 * 0.000001);\n");
        }
        sb.append("  fragColor = c;\n");
        sb.append("}\n");
        String src = sb.toString();
        assertTrue(src.length() > 64 * 1024, "precondition: source must exceed the default 64KB MemoryStack, was " + src.length());

        ByteBuffer spirv = SpirvCompiler.compile(src, IrisGlslTranslator.Stage.FRAGMENT, "large.fsh");
        assertTrue(SpirvCompiler.isValidSpirv(spirv), "large source must yield valid SPIR-V without MemoryStack overflow");
    }
}