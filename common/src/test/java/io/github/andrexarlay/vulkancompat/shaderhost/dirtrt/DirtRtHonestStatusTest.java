package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DirtRtHonestStatusTest {

    @Test
    public void failureWithoutRealFallbackReportsNoPipeline() {
        DirtRtPipeline.resetState();
        DirtRtPipeline.setNativeRtFallbackPipeline(0L);
        DirtRtPipeline.updateState(DirtRtPipeline.State.COMPILING, "Dirt RT Test Pack", "compiling ray stages");
        DirtRtPipeline.onFailure("compile failed: test", 0L);
        assertEquals(DirtRtPipeline.State.FAILED, DirtRtPipeline.getState());
        assertEquals(0L, DirtRtPipeline.getCurrentPipelineHandle(),
            "no invented fallback handle may be reported as a pipeline");
        String f3 = DirtRtPipeline.getF3Telemetry();
        assertTrue(f3.contains("state=FAILED"), f3);
        assertTrue(f3.contains("pipeline=N/A"), f3);
        assertTrue(f3.contains("detail=compile failed: test"), f3);
    }

    @Test
    public void defaultFallbackHandleIsZero() {
        // The scaffold used to start with 0xCAFEBABE0001 and print it in F3 as if it were a live pipeline.
        DirtRtPipeline.resetState();
        assertEquals(0L, DirtRtPipeline.getCurrentPipelineHandle());
        DirtRtPipeline.failEntityCompatibility("test");
        String f3 = DirtRtPipeline.getF3Telemetry();
        assertFalse(f3.toLowerCase().contains("cafebabe"), f3);
    }
}
