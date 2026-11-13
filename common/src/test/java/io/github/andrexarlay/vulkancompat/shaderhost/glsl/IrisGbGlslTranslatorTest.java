package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisGbGlslTranslatorTest {

    @Test
    void vertexAttributesMapToLocationsAndMatricesToUbo() {
        String vs = """
            #version 120
            attribute vec3 vaPosition;
            attribute vec4 vaColor;
            attribute vec2 vaUV0;
            uniform mat4 modelViewMatrix;
            uniform mat4 projectionMatrix;
            varying vec2 texcoord;
            void main() {
                gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
                texcoord = vaUV0;
            }
            """;
        IrisGbGlslTranslator.Result r = IrisGbGlslTranslator.translate(vs, IrisGlslTranslator.Stage.VERTEX, new LinkedHashMap<>(), "gbuffers_terrain.vsh");
        assertTrue(r.unsupported().isEmpty(), String.join(",", r.unsupported()));
        assertTrue(r.source().contains("layout(location = 0) in vec3 vaPosition"));
        assertTrue(r.source().contains("layout(location = 1) in vec4 vaColor"));
        assertTrue(r.source().contains("layout(location = 2) in vec2 vaUV0"));
        assertTrue(r.source().contains("uniform VkbIrisUbo"));
        assertTrue(r.source().contains("mat4 modelViewMatrix"));
        assertTrue(r.source().contains("mat4 projectionMatrix"));
    }

    @Test
    void fragmentMultipleDrawBuffersAndSamplers() {
        String fs = """
            #version 120
            /* DRAWBUFFERS:01 */
            uniform sampler2D colortex0;
            uniform sampler2D depthtex0;
            varying vec2 texcoord;
            void main() {
                gl_FragData[0] = texture2D(colortex0, texcoord);
                gl_FragData[1] = vec4(texture2D(depthtex0, texcoord).r);
            }
            """;
        Map<String, Integer> varyings = new LinkedHashMap<>();
        varyings.put("texcoord", 0);
        IrisGbGlslTranslator.Result r = IrisGbGlslTranslator.translate(fs, IrisGlslTranslator.Stage.FRAGMENT, varyings, "gbuffers_terrain.fsh");
        assertTrue(r.unsupported().isEmpty(), String.join(",", r.unsupported()));
        assertEquals(List.of(0, 1), r.drawBuffers());
        assertTrue(r.source().contains("layout(location = 0) out vec4"));
        assertTrue(r.source().contains("layout(location = 1) out vec4"));
        assertTrue(r.source().contains("binding = " + IrisUniforms.BINDING_COLORTEX0));
    }

    @Test
    void unknownUniformNamedUnsupported() {
        String fs = """
            #version 120
            uniform float someBespokeUniform;
            void main() { gl_FragData[0] = vec4(someBespokeUniform); }
            """;
        IrisGbGlslTranslator.Result r = IrisGbGlslTranslator.translate(fs, IrisGlslTranslator.Stage.FRAGMENT, new LinkedHashMap<>(), "x.fsh");
        assertFalse(r.unsupported().isEmpty());
        assertTrue(String.join(",", r.unsupported()).contains("someBespokeUniform"));
    }

    @Test
    void shadowSamplerAndLegacyBuiltinsRewritten() {
        String vs = """
            #version 120
            attribute vec3 vaPosition;
            attribute vec3 vaNormal;
            uniform mat4 shadowModelView;
            uniform mat4 shadowProjection;
            void main() {
                gl_Position = shadowProjection * shadowModelView * vec4(vaPosition, 1.0);
                gl_FrontColor = gl_Color;
            }
            """;
        IrisGbGlslTranslator.Result r = IrisGbGlslTranslator.translate(vs, IrisGlslTranslator.Stage.VERTEX, new LinkedHashMap<>(), "shadow.vsh");
        assertTrue(r.unsupported().isEmpty(), String.join(",", r.unsupported()));
        assertTrue(r.source().contains("layout(location = 4) in vec3 vaNormal"));
        assertTrue(r.source().contains("mat4 shadowModelView"));
    }

    @Test
    void shadow2DSwizzleCompilesAsVec4() throws SpirvCompiler.SpirvCompileException {
        String fs = """
            #version 120
            uniform sampler2DShadow shadowtex0;
            varying vec2 texcoord;
            void main() {
                float s = shadow2D(shadowtex0, vec3(texcoord, 0.5)).z;
                gl_FragData[0] = vec4(s);
            }
            """;
        Map<String, Integer> varyings = new LinkedHashMap<>();
        varyings.put("texcoord", 0);
        IrisGbGlslTranslator.Result r = IrisGbGlslTranslator.translate(fs, IrisGlslTranslator.Stage.FRAGMENT, varyings, "composite.fsh");
        assertTrue(r.unsupported().isEmpty(), String.join(",", r.unsupported()));
        assertTrue(r.source().contains("vec4(texture(shadowtex0"), r.source());
        ByteBuffer spirv = SpirvCompiler.compile(r.source(), IrisGlslTranslator.Stage.FRAGMENT, "shadow.fsh");
        assertTrue(SpirvCompiler.isValidSpirv(spirv), "shadow2D(...).z must compile to valid SPIR-V");
    }
}