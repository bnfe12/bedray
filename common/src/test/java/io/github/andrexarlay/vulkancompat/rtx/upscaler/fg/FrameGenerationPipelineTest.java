package io.github.andrexarlay.vulkancompat.rtx.upscaler.fg;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FrameGenerationPipelineTest {

    @Test
    public void testHonestUnsupportedScaffold() {
        FrameGenerationPipeline pipeline = new FrameGenerationPipeline();
        assertEquals(FrameGenerationPipeline.State.UNSUPPORTED, pipeline.getState());
        assertFalse(pipeline.isSupported());

        pipeline.setRequested(true);
        assertEquals(FrameGenerationPipeline.State.UNSUPPORTED, pipeline.getState());

        pipeline.onFrameRendered(16.67);
        assertEquals(1, pipeline.getRealFrames());
        assertEquals(0, pipeline.getGeneratedFrames());
        assertEquals(1, pipeline.getPresentedFrames());

        String telemetry = pipeline.getF3TelemetryString();
        assertTrue(telemetry.contains("[FG]"));
        assertTrue(telemetry.contains("supported=false"));
        assertTrue(telemetry.contains("state=UNSUPPORTED"));
        assertTrue(telemetry.contains("reason=ScaffoldOnly_NoPresentHook"));
        assertTrue(telemetry.contains("hudMode=SEPARATE_PLANNED"));
    }
}
