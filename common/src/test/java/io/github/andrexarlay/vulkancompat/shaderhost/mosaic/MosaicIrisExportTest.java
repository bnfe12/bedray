package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MosaicIrisExportTest {

    @Test
    void aShaderGraphBecomesAnIrisCompositePack() {
        MosaicGraph g = MosaicGraph.analyze("""
            {"nodes": [{"id": "c", "type": "mosaic.sceneColor"}, {"id": "t", "type": "mosaic.time"},
              {"id": "n", "type": "mosaic.noise"}, {"id": "r", "type": "mosaic.rain"}, {"id": "hs", "type": "mosaic.hueSat", "inputs": {"color": "c", "hue": "r"}},
              {"id": "m", "type": "mosaic.mul", "inputs": {"a": "hs", "b": "n"}}, {"id": "o", "type": "mosaic.output", "inputs": {"color": "m"}}]}""").graph();
        MosaicIrisExport.Result r = MosaicIrisExport.export(g, "test");
        assertTrue(r.ok(), String.valueOf(r.problems()));
        String fsh = r.files().get("shaders/composite.fsh");
        assertNotNull(fsh);
        assertTrue(fsh.startsWith("#version 330 compatibility"), fsh);
        assertTrue(fsh.contains("mosaicFragColor = "), fsh);
        assertTrue(fsh.contains("uniform sampler2D colortex0;"));
        assertFalse(fsh.contains("gl_GlobalInvocationID"));
        assertFalse(fsh.contains("binding ="), "no Vulkan bindings left");
        assertTrue(r.files().containsKey("shaders/composite.vsh"));
    }

    @Test
    void whatIrisHasNothingForIsRefusedByName() {
        MosaicGraph g = MosaicGraph.analyze("""
            {"nodes": [{"id": "c", "type": "mosaic.sceneColor"}, {"id": "s", "type": "mosaic.shadow"},
              {"id": "m", "type": "mosaic.mul", "inputs": {"a": "c", "b": "s"}}, {"id": "o", "type": "mosaic.output", "inputs": {"color": "m"}}]}""").graph();
        MosaicIrisExport.Result r = MosaicIrisExport.export(g, "test");
        assertFalse(r.ok());
        assertTrue(r.problems().get(0).contains("node s"), String.valueOf(r.problems()));
    }
}
