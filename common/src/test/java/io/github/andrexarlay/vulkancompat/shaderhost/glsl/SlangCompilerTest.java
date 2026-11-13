package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2 tests for the Slang compiler bridge. Compile tests are skipped when no slangc toolchain is present
 * (run with -Dbedray.slangc=&lt;path&gt;); the authored host shaders are validated whenever it is.
 */
class SlangCompilerTest {

    private static final String VERTEX_SLANG = """
        [shader("vertex")]
        void main(float3 pos : POSITION, out float4 o : SV_Position) { o = float4(pos, 1); }
        """;

    private static final String VERTEX_GLSL = """
        #version 450
        layout(location = 0) in vec3 p;
        void main() { gl_Position = vec4(p, 1); }
        """;

    @Test
    void locateHonorsSystemProperty() {
        String prev = System.getProperty("bedray.slangc");
        try {
            System.setProperty("bedray.slangc", "/nonexistent/slangc");
            Path p = SlangCompiler.locate();
            assertNotNull(p);
            assertEquals("/nonexistent/slangc", p.toString().replace('\\', '/'));
        } finally {
            if (prev == null) System.clearProperty("bedray.slangc"); else System.setProperty("bedray.slangc", prev);
        }
    }

    @Test
    void compileSlangProducesSpirv() throws Exception {
        assumeTrue(SlangCompiler.available(), "slangc not available");
        ByteBuffer spv = SlangCompiler.compileSlang(VERTEX_SLANG, "vertex", "main", "test.slang");
        assertTrue(SpirvCompiler.isValidSpirv(spv));
    }

    @Test
    void compileGlsl450ProducesSpirv() throws Exception {
        assumeTrue(SlangCompiler.available(), "slangc not available");
        ByteBuffer spv = SlangCompiler.compileGlsl450(VERTEX_GLSL, "vertex", "main", "test.vert");
        assertTrue(SpirvCompiler.isValidSpirv(spv));
    }

    @Test
    void invalidSlangThrowsWithLog() {
        assumeTrue(SlangCompiler.available(), "slangc not available");
        SlangCompiler.SlangCompileException e = assertThrows(SlangCompiler.SlangCompileException.class,
            () -> SlangCompiler.compileSlang("this is not slang", "vertex", "main", "bad.slang"));
        assertNotNull(e.errorLog());
    }

    @Test
    void authoredFullscreenAndBlitCompile() throws Exception {
        assumeTrue(SlangCompiler.available(), "slangc not available");
        ByteBuffer vs = SlangCompiler.compileSlang(resource("shaders/iris/fullscreen.slang"), "vertex", "main", "fullscreen.slang");
        ByteBuffer fs = SlangCompiler.compileSlang(resource("shaders/iris/blit.slang"), "fragment", "main", "blit.slang");
        assertTrue(SpirvCompiler.isValidSpirv(vs));
        assertTrue(SpirvCompiler.isValidSpirv(fs));
    }

    private static String resource(String name) throws Exception {
        try (var in = SlangCompilerTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}