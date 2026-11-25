package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The preview world's G-buffer compiles with default and custom graph settings and look. */
class PreviewWorldShaderTest {

    @Test
    void gbufferCompilesWithSettingsAndLook() {
        var snap = PreviewWorld.demo();
        for (var view : List.of(PreviewWorld.View.of(snap).with(Map.of(), Map.of()),
                PreviewWorld.View.of(snap).with(Map.of("skyZenith", new double[] {0.1, 0.1, 0.5}),
                    Map.of("faceTop", new double[] {0.9}, "blockColor", new double[] {1, 0.5, 0.2})))) {
            String src = GpuPreview.gbufferSource(view);
            assertFalse(src.contains("%CONSTANTS%"));
            MosaicShaderCheck.Result r = MosaicShaderCheck.compile(new MosaicCodegen.Generated(src, List.of(), null));
            assertTrue(r.ok(), String.valueOf(r.errors()));
        }
    }

    @Test
    void demoWorldIsLitLikeTheGame() {
        var s = PreviewWorld.demo();
        int sky = 0, lamp = 0;
        for (short c : s.cells()) {
            if (((c >> 12) & 15) == 15) sky++;
            if (((c >> 8) & 15) > 0) lamp++;
        }
        assertTrue(sky > 1000, "open air has full sky light");
        assertTrue(lamp > 10, "torches and lava light their surroundings");
    }
}
