package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for the GLSL translation and SPIR-V compilation pipeline:
 * IncludeExpander -> IrisGlslTranslator -> SpirvCompiler.
 */
class GlslPipelineTest {

    @Test
    void includeExpansionWithLineDirectives() throws IOException {
        Map<String, String> files = new HashMap<>();
        files.put("shaders/composite.fsh", "#version 120\n#include \"lib/math.glsl\"\nvoid main() {}\n");
        files.put("shaders/lib/math.glsl", "float sq(float x) { return x * x; }\n");

        IncludeExpander expander = new IncludeExpander("shaders/", files::get);
        String expanded = expander.expand("shaders/composite.fsh");

        assertTrue(expanded.contains("#line 1 \"lib/math.glsl\""), expanded);
        assertTrue(expanded.contains("float sq(float x)"), expanded);
        assertTrue(expanded.contains("#line 3 \"composite.fsh\""), expanded);
    }

    @Test
    void includeCycleThrows() {
        Map<String, String> files = new HashMap<>();
        files.put("shaders/a.glsl", "#include \"b.glsl\"\n");
        files.put("shaders/b.glsl", "#include \"a.glsl\"\n");

        IncludeExpander expander = new IncludeExpander("shaders/", files::get);
        assertThrows(PackScanException.class, () -> expander.expand("shaders/a.glsl"));
    }

    @Test
    void uniformsPushConstantBlockOffsets() {
        String fsh = """
            #version 120
            uniform float viewWidth;
            uniform float viewHeight;
            uniform float frameTimeCounter;
            uniform int frameCounter;
            void main() {
                gl_FragColor = vec4(viewWidth, viewHeight, frameTimeCounter, float(frameCounter));
            }
            """;
        IrisGlslTranslator.Result res = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            Map.of(), "test.fsh");

        assertTrue(res.unsupported().isEmpty(), "unsupported: " + res.unsupported());
        assertTrue(res.pushUsed().contains("viewWidth"));
        assertTrue(res.pushUsed().contains("viewHeight"));
        assertTrue(res.pushUsed().contains("frameTimeCounter"));
        assertTrue(res.pushUsed().contains("frameCounter"));

        String src = res.source();
        assertTrue(src.contains("layout(push_constant) uniform VkbIrisUniforms"), src);
        assertTrue(src.contains("layout(offset = 0) float viewWidth;"), src);
        assertTrue(src.contains("layout(offset = 4) float viewHeight;"), src);
        assertTrue(src.contains("layout(offset = 12) float frameTimeCounter;"), src);
        assertTrue(src.contains("layout(offset = 16) int frameCounter;"), src);
    }

    @Test
    void samplersBindingAssignment() {
        String fsh = """
            #version 120
            uniform sampler2D colortex0;
            uniform sampler2D depthtex0;
            void main() {
                gl_FragColor = texture2D(colortex0, vec2(0.5)) + texture2D(depthtex0, vec2(0.5));
            }
            """;
        IrisGlslTranslator.Result res = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            Map.of(), "test.fsh");

        assertTrue(res.unsupported().isEmpty(), "unsupported: " + res.unsupported());
        assertTrue(res.samplerBindings().contains(0));
        assertTrue(res.samplerBindings().contains(1));

        String src = res.source();
        assertTrue(src.contains("layout(set = 0, binding = 0) uniform sampler2D colortex0;"), src);
        assertTrue(src.contains("layout(set = 0, binding = 1) uniform sampler2D depthtex0;"), src);
        assertTrue(src.contains("texture(colortex0, vec2(0.5))"), src);
    }

    @Test
    void samplerAliasesGcolorAndGdepthtex() throws SpirvCompiler.SpirvCompileException {
        String fsh = """
            #version 120
            uniform sampler2D gcolor;
            uniform sampler2D gdepthtex;
            void main() {
                gl_FragColor = texture2D(gcolor, vec2(0.5)) + texture2D(gdepthtex, vec2(0.5));
            }
            """;
        IrisGlslTranslator.Result res = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            Map.of(), "aliases.fsh");

        assertTrue(res.unsupported().isEmpty(), "unsupported: " + res.unsupported());
        assertTrue(res.samplerBindings().contains(0));
        assertTrue(res.samplerBindings().contains(1));

        String src = res.source();
        assertTrue(src.contains("layout(set = 0, binding = 0) uniform sampler2D gcolor;"), src);
        assertTrue(src.contains("layout(set = 0, binding = 1) uniform sampler2D gdepthtex;"), src);

        ByteBuffer spv = SpirvCompiler.compile(src, IrisGlslTranslator.Stage.FRAGMENT, "aliases.fsh");
        assertNotNull(spv);
        assertTrue(SpirvCompiler.isValidSpirv(spv));
    }

    @Test
    void varyingMatchingBetweenStages() {
        String vsh = """
            #version 120
            varying vec4 texcoord;
            varying vec3 tint;
            void main() {
                gl_Position = ftransform();
                texcoord = gl_MultiTexCoord0;
                tint = vec3(1.0, 0.5, 0.0);
            }
            """;
        Map<String, Integer> varyings = new LinkedHashMap<>();
        IrisGlslTranslator.Result vRes = IrisGlslTranslator.translate(vsh, IrisGlslTranslator.Stage.VERTEX,
            varyings, "composite.vsh");

        assertTrue(vRes.unsupported().isEmpty(), "vertex unsupported: " + vRes.unsupported());
        assertEquals(0, vRes.varyings().get("texcoord"));
        assertEquals(1, vRes.varyings().get("tint"));

        String fsh = """
            #version 120
            varying vec4 texcoord;
            varying vec3 tint;
            void main() {
                gl_FragColor = vec4(tint, 1.0);
            }
            """;
        IrisGlslTranslator.Result fRes = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            vRes.varyings(), "composite.fsh");

        assertTrue(fRes.unsupported().isEmpty(), "fragment unsupported: " + fRes.unsupported());
        String fSrc = fRes.source();
        assertTrue(fSrc.contains("layout(location = 0) in vec4 texcoord;"), fSrc);
        assertTrue(fSrc.contains("layout(location = 1) in vec3 tint;"), fSrc);
    }

    @Test
    void builtinsRewriteFtransformAndTexCoord() {
        String vsh = """
            #version 120
            varying vec4 texcoord;
            void main() {
                gl_Position = ftransform();
                texcoord = gl_MultiTexCoord0;
            }
            """;
        IrisGlslTranslator.Result vRes = IrisGlslTranslator.translate(vsh, IrisGlslTranslator.Stage.VERTEX,
            new LinkedHashMap<>(), "test.vsh");

        assertTrue(vRes.unsupported().isEmpty());
        String src = vRes.source();
        assertTrue(src.contains("vkb_ortho * vec4(vkb_corner(), 0.0, 1.0)"), src);
        assertTrue(src.contains("vec4(vkb_corner(), 0.0, 1.0)"), src);
    }

    @Test
    void unsupportedBuiltinRejected() {
        String fsh = """
            #version 120
            void main() {
                vec4 n = vec4(gl_Normal, 1.0);
                gl_FragColor = n;
            }
            """;
        IrisGlslTranslator.Result res = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            Map.of(), "test.fsh");

        assertFalse(res.unsupported().isEmpty());
        assertTrue(res.unsupported().stream().anyMatch(s -> s.contains("gl_Normal")));
    }

    @Test
    void unsupportedSamplerRejected() {
        String fsh = """
            #version 120
            uniform sampler2D shadowtex0;
            void main() {
                gl_FragColor = texture2D(shadowtex0, vec2(0.5));
            }
            """;
        IrisGlslTranslator.Result res = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            Map.of(), "test.fsh");

        assertFalse(res.unsupported().isEmpty());
        assertTrue(res.unsupported().stream().anyMatch(s -> s.contains("shadowtex0")));
    }

    @Test
    void compileVertexAndFragmentToValidSpirv() throws SpirvCompiler.SpirvCompileException {
        String vsh = """
            #version 120
            varying vec4 texcoord;
            void main() {
                gl_Position = ftransform();
                texcoord = gl_MultiTexCoord0;
            }
            """;
        Map<String, Integer> varyings = new LinkedHashMap<>();
        IrisGlslTranslator.Result vRes = IrisGlslTranslator.translate(vsh, IrisGlslTranslator.Stage.VERTEX,
            varyings, "composite.vsh");
        assertTrue(vRes.unsupported().isEmpty());

        ByteBuffer vSpv = SpirvCompiler.compile(vRes.source(), IrisGlslTranslator.Stage.VERTEX, "composite.vsh");
        assertNotNull(vSpv);
        assertTrue(vSpv.remaining() > 0);
        assertTrue(SpirvCompiler.isValidSpirv(vSpv), "Vertex bytecode must be valid SPIR-V");

        String fsh = """
            #version 120
            /* RENDERTARGETS: 0 */
            uniform sampler2D colortex0;
            uniform float viewWidth;
            uniform float viewHeight;
            uniform float frameTimeCounter;
            varying vec4 texcoord;
            void main() {
                vec4 c = texture2D(colortex0, texcoord.st);
                float f = sin(frameTimeCounter) * 0.1;
                gl_FragData[0] = vec4(c.rgb + f, c.a);
            }
            """;
        IrisGlslTranslator.Result fRes = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            vRes.varyings(), "composite.fsh");
        assertTrue(fRes.unsupported().isEmpty(), "fragment unsupported: " + fRes.unsupported());

        ByteBuffer fSpv = SpirvCompiler.compile(fRes.source(), IrisGlslTranslator.Stage.FRAGMENT, "composite.fsh");
        assertNotNull(fSpv);
        assertTrue(fSpv.remaining() > 0);
        assertTrue(SpirvCompiler.isValidSpirv(fSpv), "Fragment bytecode must be valid SPIR-V");
    }

    @Test
    void compileSyntaxErrorThrowsExceptionWithDetails() {
        String brokenGlsl = "#version 450\nvoid main() { this is not valid glsl; }\n";
        SpirvCompiler.SpirvCompileException ex = assertThrows(SpirvCompiler.SpirvCompileException.class,
            () -> SpirvCompiler.compile(brokenGlsl, IrisGlslTranslator.Stage.FRAGMENT, "broken.fsh"));

        assertNotNull(ex.errorLog());
        assertTrue(ex.errorLog().contains("syntax error") || ex.getMessage().contains("compilation failed"), ex.getMessage());
    }

    @Test
    void spirvMagicAndAlignment() {
        ByteBuffer invalidShort = ByteBuffer.allocate(16);
        assertFalse(SpirvCompiler.isValidSpirv(invalidShort));

        ByteBuffer invalidMagic = ByteBuffer.allocate(24);
        invalidMagic.putInt(0x12345678);
        invalidMagic.flip();
        assertFalse(SpirvCompiler.isValidSpirv(invalidMagic));

        ByteBuffer validMagic = ByteBuffer.allocate(24);
        validMagic.putInt(SpirvCompiler.SPIRV_MAGIC);
        validMagic.putInt(0x00010400); // version 1.4
        validMagic.putInt(0);          // generator
        validMagic.putInt(1);          // bound
        validMagic.putInt(0);          // schema
        validMagic.flip();
        assertTrue(SpirvCompiler.isValidSpirv(validMagic));
    }

    @Test
    void outOfOrderUniformDeclarationsCompileToSpirv() throws SpirvCompiler.SpirvCompileException {
        // Uniforms declared in reverse/random offset order: frameTime (offset 28) before viewWidth (offset 0)
        String fsh = """
            #version 120
            uniform float frameTime;
            uniform float viewHeight;
            uniform float viewWidth;
            uniform int frameCounter;
            void main() {
                gl_FragColor = vec4(viewWidth, viewHeight, frameTime, float(frameCounter));
            }
            """;
        IrisGlslTranslator.Result res = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            Map.of(), "reverse_uniforms.fsh");
        assertTrue(res.unsupported().isEmpty(), "unsupported: " + res.unsupported());

        ByteBuffer spv = SpirvCompiler.compile(res.source(), IrisGlslTranslator.Stage.FRAGMENT, "reverse_uniforms.fsh");
        assertNotNull(spv);
        assertTrue(SpirvCompiler.isValidSpirv(spv));
    }

    @Test
    void commentsAroundDeclarationsDoNotBreakTranslation() throws SpirvCompiler.SpirvCompileException {
        // Block comment around unsupported uniform, inline block comment on sampler, and comment mentioning gl_FragColor
        String fsh = """
            #version 120
            /*
            uniform vec3 sunPosition;
            */
            uniform sampler2D colortex0; /* primary scene color */
            // Note: gl_FragData[1] is not used in this pass
            out vec4 fragColor; // replaced gl_FragColor
            void main() {
                fragColor = texture2D(colortex0, vec2(0.5));
            }
            """;
        IrisGlslTranslator.Result res = IrisGlslTranslator.translate(fsh, IrisGlslTranslator.Stage.FRAGMENT,
            Map.of(), "comments_test.fsh");
        assertTrue(res.unsupported().isEmpty(), "unsupported: " + res.unsupported());

        ByteBuffer spv = SpirvCompiler.compile(res.source(), IrisGlslTranslator.Stage.FRAGMENT, "comments_test.fsh");
        assertNotNull(spv);
        assertTrue(SpirvCompiler.isValidSpirv(spv));
    }

    @Test
    void spirvMagicWithBigEndianByteBuffer() {
        // Standard little-endian SPIR-V header in a default (Big Endian) ByteBuffer
        byte[] spvHeader = new byte[] {
            0x03, 0x02, 0x23, 0x07, // magic in little-endian byte stream
            0x00, 0x04, 0x01, 0x00, // version 1.4
            0x00, 0x00, 0x00, 0x00, // generator
            0x01, 0x00, 0x00, 0x00, // bound
            0x00, 0x00, 0x00, 0x00  // schema
        };
        ByteBuffer wrapped = ByteBuffer.wrap(spvHeader); // default BIG_ENDIAN in Java
        assertTrue(SpirvCompiler.isValidSpirv(wrapped), "Should recognize little-endian SPIR-V in big-endian ByteBuffer");
    }
}
