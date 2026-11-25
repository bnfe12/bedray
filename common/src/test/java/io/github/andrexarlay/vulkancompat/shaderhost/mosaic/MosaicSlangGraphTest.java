package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MosaicSlangGraphTest {

    private static MosaicSlangLib.Library lib() {
        MosaicSlangLib.Library lib = MosaicSlangGraph.library();
        assumeTrue(lib != null, "Bedray's Slang pack is not in this checkout");
        return lib;
    }

    @Test
    void everyFunctionOfThePackIsANode() {
        MosaicSlangLib.Library lib = lib();
        assertTrue(lib.functions().size() > 500, "functions: " + lib.functions().size());
        MosaicSlangLib.Function f = lib.functions().get("shading/microfacet/fresnelSchlick");
        assertNotNull(f);
        assertEquals("float3", f.returnType());
        assertEquals(2, f.params().size());
        assertTrue(MosaicGraph.isKnown("bedray.fn:shading/microfacet/fresnelSchlick"));
        assertTrue(MosaicGraph.isKnown("bedray.make:contracts/hit/SurfaceHit"));
        MosaicSlangLib.Function sample = lib.functions().get("light/sun/SunMoonSampler.sample");
        assertNotNull(sample, "struct methods are nodes too");
        assertEquals("SunMoonSampler", sample.owner());
    }

    private static final String FRESNEL = """
        {"nodes": [
          {"id": "c", "type": "slang.param", "mask": "float c"},
          {"id": "f0", "type": "slang.param", "mask": "float3 f0"},
          {"id": "fr", "type": "bedray.fn:shading/microfacet/fresnelSchlick", "inputs": {"cosTheta": "c", "f0": "f0"}},
          {"id": "d", "type": "bedray.fn:shading/microfacet/ggxNDF", "inputs": {"NdotH": "c"}, "defaults": {"alpha": [0.5]}},
          {"id": "m", "type": "mosaic.mul", "inputs": {"a": "d", "b": "fr"}},
          {"id": "r", "type": "slang.result", "mask": "float3", "inputs": {"value": "m"}}
        ]}""";

    @Test
    void aFunctionGraphCompilesAgainstThePack() {
        lib();
        MosaicGraph.Analysis a = MosaicGraph.analyze(FRESNEL);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        assertTrue(a.graph().isFunctionGraph());
        MosaicSlangGraph.Code code = MosaicSlangGraph.function(a.graph(), "myFresnel");
        assertTrue(code.problems().isEmpty(), String.valueOf(code.problems()));
        assertTrue(code.text().startsWith("float3 myFresnel(float c, float3 f0)"), code.text());
        assertTrue(code.text().contains("fresnelSchlick(c, f0)"), code.text());
        assertTrue(code.text().contains("ggxNDF(c, 0.5)"), code.text());
        assertTrue(code.imports().contains("shading/microfacet"));
        Path slangc = MosaicSlang.locate(null);
        assumeTrue(slangc != null, "no slangc");
        MosaicSlangGraph.Check check = MosaicSlangGraph.check(MosaicSlangGraph.checkUnit(code), slangc, MosaicSlangGraph.packRoot());
        assertTrue(check.ok(), check.log() + "\n" + MosaicSlangGraph.checkUnit(code));
    }

    @Test
    void outParametersMethodsAndStructsCompile() {
        lib();
        String json = """
            {"nodes": [
              {"id": "v", "type": "slang.param", "mask": "float3 V"},
              {"id": "n", "type": "slang.param", "mask": "float3 N"},
              {"id": "seed", "type": "slang.param", "mask": "uint seed"},
              {"id": "s", "type": "bedray.fn:shading/microfacet/sampleReflection",
               "inputs": {"V": "v", "N": "n", "seed": "seed"}, "defaults": {"roughness": [0.3], "f0": [0.04]}},
              {"id": "hit", "type": "bedray.make:contracts/hit/SurfaceHit", "inputs": {"shadingNormal": "n"}},
              {"id": "b", "type": "bedray.break:contracts/hit/SurfaceHit", "inputs": {"value": "hit"}},
              {"id": "add", "type": "mosaic.add", "inputs": {"a": "s.weight", "b": "b.shadingNormal"}},
              {"id": "w", "type": "slang.out", "mask": "float3 dir", "inputs": {"value": "s"}},
              {"id": "r", "type": "slang.result", "mask": "float3", "inputs": {"value": "add"}}
            ]}""";
        MosaicGraph.Analysis a = MosaicGraph.analyze(json);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        MosaicSlangGraph.Code code = MosaicSlangGraph.function(a.graph(), "probe");
        assertTrue(code.text().contains("uint n_s_seed = seed;"), code.text());
        assertTrue(code.text().contains("float3 n_s_weight;"), code.text());
        Path slangc = MosaicSlang.locate(null);
        assumeTrue(slangc != null, "no slangc");
        MosaicSlangGraph.Check check = MosaicSlangGraph.check(MosaicSlangGraph.checkUnit(code), slangc, MosaicSlangGraph.packRoot());
        assertTrue(check.ok(), check.log() + "\n" + MosaicSlangGraph.checkUnit(code));
    }

    @Test
    void aStructOnlyGoesWhereItsTypeIsWanted() {
        lib();
        MosaicGraph.Analysis a = MosaicGraph.analyze("""
            {"nodes": [
              {"id": "hit", "type": "bedray.make:contracts/hit/SurfaceHit"},
              {"id": "fr", "type": "bedray.fn:shading/microfacet/fresnelSchlick", "inputs": {"f0": "hit"}},
              {"id": "r", "type": "slang.result", "mask": "float3", "inputs": {"value": "fr"}}
            ]}""");
        assertNotNull(a.firstError());
        assertTrue(a.firstError().contains("SurfaceHit"), a.firstError());
    }

    @Test
    void aFunctionOfThePackOpensAsNodes() {
        MosaicSlangLib.Library lib = lib();
        MosaicSlangLib.Function f = lib.functions().get("contracts/math/orientNormal");
        assertNotNull(f);
        MosaicSlangDecompile.Result r = MosaicSlangDecompile.decompile(f, lib);
        assertTrue(r.whole(), r.json());
        MosaicGraph.Analysis a = MosaicGraph.analyze(r.json());
        assertNull(a.firstError(), String.valueOf(a.problems()));
        String body = MosaicSlangGraph.function(a.graph(), f.name()).body();
        assertTrue(body.contains("dot(normal, geometricNormal)"), body);
        assertTrue(body.contains("?"), body);
        // overloads are told apart by their parameter types
        assertNotNull(lib.functions().get("contracts/math/safeNormalize(float3,float3)"));
        assertNotNull(lib.functions().get("contracts/math/safeNormalize(float2,float2)"));
    }

    @Test
    void controlFlowStaysEditableAsACodeNodeAndCommentsBecomeNotes() {
        MosaicSlangLib.Library lib = lib();
        MosaicSlangLib.Function f = lib.functions().get("light/sun/SunMoonSampler.sample");
        MosaicSlangDecompile.Result r = MosaicSlangDecompile.decompile(f, lib);
        assertTrue(r.codeBlocks() > 0, "an if / for stays a code node");
        assertNull(MosaicGraph.analyze(r.json()).firstError());
        long withNotes = lib.ofModule("restir/restir_di").stream().filter(MosaicSlangLib.Function::isNode)
            .filter(fn -> MosaicSlangDecompile.decompile(fn, lib).json().contains("\"note\"")).count();
        assertTrue(withNotes > 0, "the developer's comments ride along as notes");
    }

    @Test
    void wholeModulesRoundTripThroughNodesAndCompile() {
        MosaicSlangLib.Library lib = lib();
        Path slangc = MosaicSlang.locate(null);
        assumeTrue(slangc != null, "no slangc");
        for (String module : java.util.List.of("shading/microfacet", "light/sun", "restir/restir_di")) {
            java.util.Map<MosaicSlangLib.Function, MosaicSlangGraph.Code> codes = new java.util.LinkedHashMap<>();
            for (MosaicSlangLib.Function f : lib.ofModule(module)) {
                if (!f.isNode()) continue;
                MosaicGraph.Analysis a = MosaicGraph.analyze(MosaicSlangDecompile.decompile(f, lib).json());
                assertNull(a.firstError(), f.id() + ": " + a.problems());
                codes.put(f, MosaicSlangGraph.function(a.graph(), f.name()));
            }
            String src = lib.sources().get(module + ".slang");
            String rebuilt = MosaicSlangGraph.overrideAll(src, module, codes);
            MosaicSlangGraph.Check check = MosaicSlangGraph.checkModules(java.util.Map.of(module, rebuilt), slangc, MosaicSlangGraph.packRoot());
            assertTrue(check.ok(), module + ": " + check.log());
        }
    }

    @Test
    void aStageOpensIntoItsModulesFunctionsAndSettings() {
        MosaicSlangLib.Library lib = lib();
        java.util.List<String> sky = MosaicStageMap.modules("bedray.sky", lib);
        assertTrue(sky.contains("light/sun"), String.valueOf(sky));
        assertTrue(sky.contains("light/sky"), String.valueOf(sky));
        assertTrue(MosaicStageMap.modules("bedray.rays", lib).contains("passes/path_trace_scene"), "Hybrid ray effects run the path trace passes");
        String json = MosaicStageMap.graph("bedray.sky", lib, java.util.Map.of("sun_size_tenths", 12.0));
        MosaicGraph.Analysis a = MosaicGraph.analyze(json);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        assertTrue(a.graph().isStageMap());
        assertTrue(json.contains("bedray.fn:light/sun/SunMoonSampler.sample"), "its functions are nodes");
        assertTrue(json.contains("\"bedray.setting\""), "its settings are public parameters");
        assertTrue(json.contains("12.0"), "with the current value");
        // every stage has something inside
        for (MosaicRenderer.Stage s : MosaicRenderer.stages())
            if (!s.type().equals("bedray.output"))
                assertTrue(!MosaicStageMap.modules(s.type(), lib).isEmpty() || !s.settings().isEmpty(), s.type());
    }

    @Test
    void yourPostStageIsAGraphOfBedraysPostHook() {
        MosaicSlangLib.Library lib = lib();
        MosaicSlangLib.Function hook = lib.functions().get("post_cinema/postStudio");
        assertNotNull(hook, "Bedray's pack has the post hook");
        // a stage of your own: Bedray's vignette, stronger, then a warm tint
        String json = """
            {"function": "post_cinema/postStudio", "nodes": [
              {"id": "c", "type": "slang.param", "mask": "float3 color"},
              {"id": "uv", "type": "slang.param", "mask": "float2 uv"},
              {"id": "px", "type": "slang.param", "mask": "int2 pixel"},
              {"id": "fi", "type": "slang.param", "mask": "uint frameIndex"},
              {"id": "v", "type": "bedray.fn:post_cinema/postVignette", "inputs": {"color": "c", "uv": "uv"}, "defaults": {"strength": [0.6]}},
              {"id": "t", "type": "mosaic.mul", "inputs": {"a": "v"}, "defaults": {"b": [1.1, 1.0, 0.9]}},
              {"id": "r", "type": "slang.result", "mask": "float3", "inputs": {"value": "t"}}
            ]}""";
        MosaicGraph.Analysis a = MosaicGraph.analyze(json);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        MosaicSlangGraph.Code code = MosaicSlangGraph.function(a.graph(), "postStudio");
        assertTrue(code.body().contains("postVignette(color, uv, 0.6)"), code.body());
        Path slangc = MosaicSlang.locate(null);
        assumeTrue(slangc != null, "no slangc");
        String module = MosaicSlangGraph.overrideAll(lib.sources().get("post_cinema.slang"), "post_cinema", java.util.Map.of(hook, code));
        MosaicSlangGraph.Check check = MosaicSlangGraph.checkModules(java.util.Map.of("post_cinema", module), slangc, MosaicSlangGraph.packRoot());
        assertTrue(check.ok(), check.log() + "\n" + module);
    }
}
