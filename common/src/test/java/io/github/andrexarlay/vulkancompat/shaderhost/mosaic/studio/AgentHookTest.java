package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlangGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** mosaic hook: a path tracer stage from Slang text is a graph (parameters, one Slang block, ends) that compiles in its module. */
class AgentHookTest {

    @Test
    void everyPathTracerStageTakesSlangText() {
        var lib = MosaicSlangGraph.library();
        assumeTrue(lib != null, "no Slang pack");
        var slangc = MosaicSlang.locate(null);
        assumeTrue(slangc != null, "no slangc");
        String[][] cases = {
            {"user.material", "float wet = smoothstep(0.6, 0.9, normal.y);\nroughness = lerp(roughness, roughness * 0.25, wet);"},
            {"user.light", "if (lightKind == 1.0) radiance *= float3(1.0, 0.9, 0.8);"},
            {"user.skyLight", "if (bounce > 0.0) radiance *= 1.1;"},
            {"user.medium", "float k = 1.0 - exp(-segmentLength * 0.002);\ninScatter += float3(0.6, 0.7, 0.8) * k * 0.05;\ntransmittance *= 1.0 - k;"}};
        for (String[] c : cases) {
            var fn = lib.functions().get(StudioModel.USER_STAGES.get(c[0]));
            assertNotNull(fn, c[0]);
            MosaicGraph.Analysis a = MosaicGraph.analyze(AgentCli.hookGraph(fn, c[1]));
            assertNull(a.firstError(), c[0] + ": " + a.problems());
            MosaicSlangGraph.Code code = MosaicSlangGraph.function(a.graph(), fn.name());
            MosaicSlangGraph.Check check = MosaicSlangGraph.check(MosaicSlangGraph.checkUnit(code), slangc, MosaicSlangGraph.packRoot());
            assertTrue(check.ok(), c[0] + ": " + check.log() + "\n" + MosaicSlangGraph.checkUnit(code));
        }
    }
}
