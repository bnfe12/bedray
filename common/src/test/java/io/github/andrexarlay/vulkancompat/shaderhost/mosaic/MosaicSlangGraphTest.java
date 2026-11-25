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
}
