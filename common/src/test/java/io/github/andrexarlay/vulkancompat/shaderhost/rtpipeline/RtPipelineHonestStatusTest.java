package io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RtPipelineHonestStatusTest {

    @Test
    public void failureWithoutRealFallbackReportsNoPipeline() {
        RtPipelineStatus.resetState();
        RtPipelineStatus.setNativeRtFallbackPipeline(0L);
        RtPipelineStatus.updateState(RtPipelineStatus.State.COMPILING, "RT Pipeline Test Pack", "compiling ray stages");
        RtPipelineStatus.onFailure("compile failed: test", 0L);
        assertEquals(RtPipelineStatus.State.FAILED, RtPipelineStatus.getState());
        assertEquals(0L, RtPipelineStatus.getCurrentPipelineHandle(),
            "no invented fallback handle may be reported as a pipeline");
        String f3 = RtPipelineStatus.getF3Telemetry();
        assertTrue(f3.contains("state=FAILED"), f3);
        assertTrue(f3.contains("pipeline=N/A"), f3);
        assertTrue(f3.contains("detail=compile failed: test"), f3);
    }

    @Test
    public void defaultFallbackHandleIsZero() {
        // The scaffold used to start with 0xCAFEBABE0001 and print it in F3 as if it were a live pipeline.
        RtPipelineStatus.resetState();
        assertEquals(0L, RtPipelineStatus.getCurrentPipelineHandle());
        RtPipelineStatus.failEntityCompatibility("test");
        String f3 = RtPipelineStatus.getF3Telemetry();
        assertFalse(f3.toLowerCase().contains("cafebabe"), f3);
    }
}
