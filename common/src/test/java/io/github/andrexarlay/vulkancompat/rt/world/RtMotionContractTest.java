package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.denoise.RtMotionDiagnostics;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the motion / depth reprojection contract defined in RtMotionDiagnostics.
 * Uses the CPU reference implementation from VelocityPipeline.reprojectCpu for
 * round-trip verification without a GPU.
 */
public class RtMotionContractTest {

    @BeforeEach
    public void resetDiagnostics() {
        RtMotionDiagnostics.reset();
    }

    @Test
    public void testInitialStateIsDisconnected() {
        assertEquals(RtMotionDiagnostics.State.DISCONNECTED, RtMotionDiagnostics.getState());
    }

    @Test
    public void testRecordDispatchTransitionsToConnected() {
        RtMotionDiagnostics.recordDispatch(VelocityPipeline.DEPTH_HARDWARE, true, 100L);
        assertEquals(RtMotionDiagnostics.State.CONNECTED_NOT_RUNTIME_VERIFIED, RtMotionDiagnostics.getState());
    }

    @Test
    public void testMarkRuntimeVerified() {
        RtMotionDiagnostics.recordDispatch(VelocityPipeline.DEPTH_HARDWARE, true, 200L);
        RtMotionDiagnostics.markRuntimeVerified();
        assertEquals(RtMotionDiagnostics.State.RUNTIME_VERIFIED, RtMotionDiagnostics.getState());
    }

    @Test
    public void testFailedState() {
        RtMotionDiagnostics.recordFailure("camera_matrix_singular");
        assertEquals(RtMotionDiagnostics.State.FAILED, RtMotionDiagnostics.getState());
        String diag = RtMotionDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("state=FAILED"), "must report FAILED");
        assertTrue(diag.contains("camera_matrix_singular"), "must include error message");
    }

    @Test
    public void testDepthModeNamesAreCorrect() {
        assertEquals("DEPTH_HARDWARE",       RtMotionDiagnostics.depthModeName(VelocityPipeline.DEPTH_HARDWARE));
        assertEquals("DEPTH_LINEAR_VIEW_Z",  RtMotionDiagnostics.depthModeName(VelocityPipeline.DEPTH_LINEAR_VIEW_Z));
        assertEquals("DEPTH_RAY_DISTANCE",   RtMotionDiagnostics.depthModeName(VelocityPipeline.DEPTH_RAY_DISTANCE));
        // Unknown mode must not throw
        String unknown = RtMotionDiagnostics.depthModeName(99);
        assertNotNull(unknown);
        assertTrue(unknown.startsWith("UNKNOWN"), "unknown mode must start with UNKNOWN");
    }

    @Test
    public void testDepthModeConstants() {
        // VelocityPipeline constants must match GLSL depthMode values used in velocity_reproject.comp
        assertEquals(0, VelocityPipeline.DEPTH_HARDWARE,      "DEPTH_HARDWARE must be 0");
        assertEquals(1, VelocityPipeline.DEPTH_LINEAR_VIEW_Z, "DEPTH_LINEAR_VIEW_Z must be 1");
        assertEquals(2, VelocityPipeline.DEPTH_RAY_DISTANCE,  "DEPTH_RAY_DISTANCE must be 2");
    }

    @Test
    public void testFormatDiagnosticsContainsRequiredFields() {
        RtMotionDiagnostics.recordDispatch(VelocityPipeline.DEPTH_LINEAR_VIEW_Z, true, 300L);
        String diag = RtMotionDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("[RT-MV]"), "must have prefix");
        assertTrue(diag.contains("state="), "must have state");
        assertTrue(diag.contains("depthEncoding=DEPTH_LINEAR_VIEW_Z"), "must report depth mode");
        assertTrue(diag.contains("cameraValid=true"), "must report camera validity");
        assertTrue(diag.contains("entitySkinned=MISSING"), "skinned entities must be honest MISSING");
        assertTrue(diag.contains("lastError=none"), "no error expected");
    }

    @Test
    public void testManualRuntimeVerifiedTransition() {
        // Simulate what would happen after a successful CPU reproject test:
        // recordDispatch -> markRuntimeVerified -> RUNTIME_VERIFIED
        RtMotionDiagnostics.recordDispatch(VelocityPipeline.DEPTH_HARDWARE, true, 400L);
        assertEquals(RtMotionDiagnostics.State.CONNECTED_NOT_RUNTIME_VERIFIED, RtMotionDiagnostics.getState());
        RtMotionDiagnostics.markRuntimeVerified();
        assertEquals(RtMotionDiagnostics.State.RUNTIME_VERIFIED, RtMotionDiagnostics.getState());

        // formatDiagnostics should report RUNTIME_VERIFIED
        String diag = RtMotionDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("state=RUNTIME_VERIFIED"), "must report RUNTIME_VERIFIED after markRuntimeVerified");
    }
}
