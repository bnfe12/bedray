package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transparent texel of a cutout face is skipped inside the ray query. Each leaf hole used to commit, end the query and
 * start a new one from the hole; with the step budget at 32 a forest doubled the trace cost (gpu_rt 23 -> 48 ms at the
 * rt_look spawn view, 2026-11-02). The legacy traversal now tests the albedo alpha of a cutout-pass candidate at its
 * barycentrics before committing, in the closest-hit and the visibility loop, and both passes that build it assign the
 * atlas and the cutoff (an unassigned resource field crashes the NVIDIA pipeline compile, P0-9).
 */
class RtSlangInQueryAlphaTest {
    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");

    private static String read(String relative) throws IOException {
        return Files.readString(SHADERS.resolve(relative)).replace("\r\n", "\n");
    }

    @Test
    void bothQueryLoopsSkipHoles() throws IOException {
        String traversal = read("traversal/legacy_section_traversal.slang");
        int legacy = traversal.indexOf("struct LegacySectionTraversal : ISceneTracer {");
        int world = traversal.indexOf("struct LegacySectionWorldTraversal : ISceneTracer {");
        String body = traversal.substring(legacy, world);
        int skips = body.split("if \\(candidateIsHole\\(query.CandidateInstanceID\\(\\), query.CandidateGeometryIndex\\(\\),", -1).length - 1;
        assertEquals(2, skips, "closest hit and visibility");
        assertTrue(body.contains("if (((uint(section.origin.w) >> (2u * geometryIndex)) & 3u) != 1u) return false;"),
            "only a cutout-pass face can be a hole, as in the surface layer");
        assertTrue(body.contains("return alphaAtlas.SampleLevel(uv, 0.0).a < alphaCutoff;"),
            "the rule of materialAcceptsAlpha, on the same texel");
    }

    @Test
    void everyBuilderAssignsTheAtlas() throws IOException {
        for (String pass : new String[] {"passes/path_trace.slang", "passes/restir_pt_spatial.slang"}) {
            String source = read(pass);
            assertTrue(source.contains("    traversal.alphaAtlas = albedoAtlas;\n    traversal.alphaCutoff = pc.alphaCutoff;"),
                pass);
        }
    }
}
