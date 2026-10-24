package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisConstDirectives;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisGlslTranslator;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisSourcePreprocessor;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Target formats, clear/persistence, mipmaps and the resource plan built on them. */
class IrisRenderTargetsTest {

    private static List<IrisConstDirectives.ConstDirective> d(String source) {
        return IrisConstDirectives.parse(source);
    }

    private static IrisRenderTargets.ProgramDirectives program(String name, String source) {
        return new IrisRenderTargets.ProgramDirectives(name, d(source), false, null);
    }

    @Test
    void directiveParserFollowsIrisLineRule() {
        var parsed = d("""
            const int colortex2Format = RGBA16F;
              const bool colortex5Clear = false; // trailing comment
            //@bedray-const const vec4 colortex4ClearColor = vec4(0.1, 0.2, 0.3, 1.0);
            // const int colortex3Format = R8;
            /* const int colortex6Format = R8; */
            uniform float x; const int notAtLineStart = 1;
            """);
        assertEquals(3, parsed.size(), parsed.toString());
        assertEquals(new IrisConstDirectives.ConstDirective(IrisConstDirectives.Type.INT, "colortex2Format", "RGBA16F"), parsed.get(0));
        assertEquals("false", parsed.get(1).value());
        assertEquals(IrisConstDirectives.Type.VEC4, parsed.get(2).type());
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f, 1f}, IrisConstDirectives.vector(parsed.get(2).value(), "vec4", 4), 1e-6f);
    }

    @Test
    void preprocessorKeepsOnlyLiveCommentDirectives() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("shaders/composite.fsh", """
            #version 120
            #define WIDE
            #ifdef WIDE
            /*
            const int colortex2Format = RGBA16F;
            */
            #else
            /*
            const int colortex2Format = R8;
            */
            #endif
            /* const int colortex3Format = R8; */
            void main() { gl_FragColor = vec4(1.0); }
            """);
        String out = IrisSourcePreprocessor.preprocess("shaders/", "shaders/composite.fsh", files::get, IrisGlslTranslator.Stage.FRAGMENT);
        var parsed = IrisConstDirectives.parse(out);
        assertEquals(1, parsed.size(), out);
        assertEquals("RGBA16F", parsed.get(0).value());
    }

    @Test
    void formatsMapAndRefuseByName() {
        var rgb8 = IrisRenderTargets.format("RGB8");
        assertEquals(VK10.VK_FORMAT_R8G8B8A8_UNORM, rgb8.vkFormat());
        assertTrue(rgb8.alphaLocked(), "GL RGB8 has no alpha: stored as RGBA with alpha masked");
        assertEquals(VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32, IrisRenderTargets.format("R11F_G11F_B10F").vkFormat());
        assertEquals(VK10.VK_FORMAT_R16G16B16A16_SFLOAT, IrisRenderTargets.format("RGBA16F").vkFormat());
        assertNull(IrisRenderTargets.format("R32UI"));
        assertTrue(IrisRenderTargets.refusal("R32UI").contains("integer"));
        assertTrue(IrisRenderTargets.refusal("RGB9_E5").contains("shared-exponent"));

        var res = IrisRenderTargets.resolve(List.of(program("composite", "const int colortex2Format = R32UI;\nconst int colortex3Format = BOGUS;")));
        assertEquals(1, res.problems().size(), res.problems().toString());
        assertTrue(res.problems().get(0).contains("colortex2Format = R32UI"), res.problems().toString());
        assertTrue(res.problems().get(0).contains("colortex3Format = BOGUS"), res.problems().toString());
    }

    @Test
    void defaultsLegacyNamesAndConflicts() {
        var defaults = IrisRenderTargets.Resolution.defaults();
        assertEquals("RGBA", defaults.target(3).format().glName());
        assertTrue(defaults.target(3).clear());
        assertArrayEquals(new float[] {1, 1, 1, 1}, defaults.target(1).clearColor());
        assertArrayEquals(new float[] {0, 0, 0, 0}, defaults.target(2).clearColor());

        var legacy = IrisRenderTargets.resolve(List.of(program("composite",
            "const int gaux2Format = RGBA16F;\nconst bool gnormalClear = false;\nconst int colortex3Format = RGB16F;\nconst vec4 colortex3ClearColor = vec4(0.5);")));
        assertTrue(legacy.problems().isEmpty(), legacy.problems().toString());
        assertEquals("RGBA16F", legacy.target(5).format().glName(), "gaux2 = colortex5");
        assertTrue(legacy.target(2).persistent(), "gnormal = colortex2, Clear=false keeps history");
        assertArrayEquals(new float[] {0.5f, 0.5f, 0.5f, 1f}, legacy.target(3).clearColor(), "alpha clears to 1 for an RGB format");

        var conflict = IrisRenderTargets.resolve(List.of(program("composite", "const int colortex4Format = R8;"),
            program("composite1", "const int colortex4Format = RG8;")));
        assertEquals(1, conflict.problems().size());
        assertTrue(conflict.problems().get(0).contains("conflicting colortex4"), conflict.problems().toString());

        var gdepth = IrisRenderTargets.resolve(List.of(new IrisRenderTargets.ProgramDirectives("composite", List.of(), true, null)));
        assertEquals("RGBA32F", gdepth.target(1).format().glName(), "OptiFine legacy: sampled gdepth defaults to RGBA32F");
        var gaux4 = IrisRenderTargets.resolve(List.of(new IrisRenderTargets.ProgramDirectives("composite", List.of(), false, "RGB16")));
        assertEquals("RGB16", gaux4.target(7).format().glName());
        var badGaux4 = IrisRenderTargets.resolve(List.of(new IrisRenderTargets.ProgramDirectives("composite", List.of(), false, "R8")));
        assertFalse(badGaux4.problems().isEmpty());
    }

    @Test
    void planReadsDefinedValuesPersistsAndMipmaps() {
        // composite reads colortex5 (persistent) before writing it, and colortex4 (cleared, never written).
        IrisPassGraph.Pass composite = pass("composite", ProgramName.Family.COMPOSITE, List.of(5, 2),
            Set.of(IrisUniforms.colorBinding(0), IrisUniforms.colorBinding(5), IrisUniforms.colorBinding(4)),
            "const bool colortex5Clear = false;\nconst int colortex5Format = RGBA16F;");
        IrisPassGraph.Pass composite1 = pass("composite1", ProgramName.Family.COMPOSITE, List.of(0),
            Set.of(IrisUniforms.colorBinding(2), IrisUniforms.colorBinding(0)),
            "const bool colortex2MipmapEnabled = true;\nconst bool colortex3MipmapEnabled = true;");
        IrisPassGraph graph = new IrisPassGraph("t", "shaders/", List.of(composite, composite1));
        IrisResourcePlan plan = IrisResourcePlan.build(graph);
        var step0 = plan.steps().get(0);
        assertEquals(0, step0.readBanks().get(5), "persistent history is read from bank 0 at frame start");
        assertEquals(0, step0.readBanks().get(4), "an unwritten cleared target reads its clear color");
        assertEquals(List.of(new IrisResourcePlan.Write(5, 1, 0), new IrisResourcePlan.Write(2, 1, 0)), step0.writes());
        assertEquals(List.of(5), plan.persistCopies(), "history written into bank 1 is copied back to bank 0");
        assertEquals(List.of(2, 4), plan.frameClears(), "colortex0 is the world image; colortex5 keeps history");
        var step1 = plan.steps().get(1);
        assertEquals(Set.of(2), step1.mipmapped(), "mipmaps only for buffers the program samples");
        assertEquals(Set.of(2), plan.mipmappedTargets());
        assertEquals(Set.of(0, 2, 4, 5), plan.targets());
    }

    @Test
    void eightOutputsAllowedNineRefused() {
        String eight = "#version 120\n/* RENDERTARGETS:0,1,2,3,4,5,6,15 */\nvoid main(){ for(int i=0;i<1;i++){} "
            + "gl_FragData[0]=vec4(0);gl_FragData[1]=vec4(0);gl_FragData[2]=vec4(0);gl_FragData[3]=vec4(0);"
            + "gl_FragData[4]=vec4(0);gl_FragData[5]=vec4(0);gl_FragData[6]=vec4(0);gl_FragData[7]=vec4(0);}\n";
        var ok = IrisGlslTranslator.translate(eight, IrisGlslTranslator.Stage.FRAGMENT, Map.of(), "composite.fsh");
        assertTrue(ok.unsupported().isEmpty(), ok.unsupported().toString());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 15), ok.drawBuffers());
        var nine = IrisGlslTranslator.translate("#version 120\n/* DRAWBUFFERS:012345678 */\nvoid main(){gl_FragColor=vec4(0);}\n",
            IrisGlslTranslator.Stage.FRAGMENT, Map.of(), "composite.fsh");
        assertTrue(nine.unsupported().stream().anyMatch(u -> u.contains("draw buffers need 1..8")), nine.unsupported().toString());
        var sixteen = IrisGlslTranslator.translate("#version 120\n/* RENDERTARGETS:16 */\nvoid main(){gl_FragColor=vec4(0);}\n",
            IrisGlslTranslator.Stage.FRAGMENT, Map.of(), "composite.fsh");
        assertTrue(sixteen.unsupported().stream().anyMatch(u -> u.contains("colortex16")), sixteen.unsupported().toString());
    }

    @Test
    void legacySamplerAliasesBindTheirTargets() {
        var r = IrisGlslTranslator.translate("#version 120\nuniform sampler2D gdepth;\nuniform sampler2D gaux3;\nvarying vec2 texcoord;\n"
            + "void main(){gl_FragColor=texture2D(gdepth,texcoord)+texture2D(gaux3,texcoord);}\n",
            IrisGlslTranslator.Stage.FRAGMENT, Map.of("texcoord", 0), "composite.fsh");
        assertTrue(r.unsupported().isEmpty(), r.unsupported().toString());
        assertEquals(Set.of(IrisUniforms.colorBinding(1), IrisUniforms.colorBinding(6)), r.samplerBindings());
        assertTrue(r.samplesGdepth());
    }

    private static IrisPassGraph.Pass pass(String name, ProgramName.Family family, List<Integer> outputs, Set<Integer> samplers,
                                           String directives) {
        ByteBuffer spv = ByteBuffer.allocate(16);
        var vertex = new IrisGlslTranslator.Result("", Set.of(), List.of(), Map.of(), Set.of(), Set.of(), Map.of(), Set.of());
        var fragment = new IrisGlslTranslator.Result("", Set.of(), outputs, Map.of(), Set.of(), samplers, Map.of(), Set.of(),
            IrisConstDirectives.parse(directives), null, false);
        return new IrisPassGraph.Pass(name, family, 0, name + ".vsh", name + ".fsh", "", "", vertex, fragment, spv, spv);
    }
}
