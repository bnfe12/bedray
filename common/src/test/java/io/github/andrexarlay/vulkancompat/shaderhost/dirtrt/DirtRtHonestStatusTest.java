package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DirtRtHonestStatusTest {

    @Test
    public void testDirtRtHonestUnavailableStatus() {
        DirtRtPipeline.resetState();
        boolean accepted = DirtRtPipeline.handleShaderpackReload("Dirt RT Test Pack");
        assertTrue(accepted, "Dirt RT pack reload must be accepted");
        assertEquals(DirtRtPipeline.State.UNAVAILABLE, DirtRtPipeline.getState(),
            "Dirt RT state must be UNAVAILABLE when runtime loader / pipeline is not implemented (scaffold only)");
        assertEquals(0L, DirtRtPipeline.getCurrentPipelineHandle(),
            "Dirt RT pipeline handle must be 0L when scaffold only");
        assertTrue(DirtRtPipeline.getLastError().contains("scaffold only"),
            "LastError must indicate scaffold only");

        String f3 = DirtRtPipeline.getF3Telemetry();
        assertTrue(f3.contains("state=UNAVAILABLE"), "F3 telemetry must report UNAVAILABLE state");
    }
}
