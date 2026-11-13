package io.github.andrexarlay.vulkancompat.rtx;

import io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native world phase (isolated low-res world target, camera jitter, DLSS-RR / temporal upscale and the deferred hand)
 * belongs to the ray traced frame. While the Iris raster host owns the frame it must not run alongside the host's own
 * gbuffers/composite/final passes over the same engine colour image (never mix native + iris in one frame): doing so lost
 * the Vulkan device (VK_ERROR_DEVICE_LOST). {@link WorldTargetManager#isScalingActive()} drives {@code beginWorldPhase},
 * so it must report false while {@link RtGeometryGate#irisActive()}, even though the native RT gate is still on.
 */
class WorldTargetManagerIrisGateTest {

    @AfterEach
    void reset() {
        RtGeometryGate.setForTests(false);
        RtGeometryGate.setIrisForTests(false);
        VulkanRtxConfig.enableHardwareRayTracing = false;
        VulkanRtxConfig.upscalerType = VulkanRtxConfig.UpscalerType.OFF;
        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
        VulkanBackendDetector.reset();
    }

    @Test
    void irisFrameSuppressesNativeWorldPhase() {
        VulkanBackendDetector.setVulkanActive(true);
        // Isolate the decision: no resolution scaling, no upscaler. The only reason to isolate is the native RT gate.
        VulkanRtxConfig.upscalerType = VulkanRtxConfig.UpscalerType.OFF;
        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
        VulkanRtxConfig.enableHardwareRayTracing = true;
        RtGeometryGate.setForTests(true);

        // Precondition: a native RT frame (iris off) isolates the world target.
        RtGeometryGate.setIrisForTests(false);
        assertTrue(WorldTargetManager.isScalingActive(), "precondition: a native RT frame activates scaling/isolation");
        assertTrue(WorldTargetManager.isWorldPhaseActive(), "precondition: a native RT frame activates the world phase");

        // Iris owns the frame: the native world target / jitter / temporal upscale must stay off so nothing native mixes
        // with the host's passes over the shared engine colour image.
        RtGeometryGate.setIrisForTests(true);
        assertFalse(WorldTargetManager.isScalingActive(), "iris frame must not activate native scaling/isolation");
        assertFalse(WorldTargetManager.isWorldPhaseActive(), "iris frame must not activate the native world phase");
    }
}