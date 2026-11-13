package io.github.andrexarlay.vulkancompat.rt.particles;

import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native particle/weather layer belongs to the ray traced frame. While the Iris raster host owns the frame it must not
 * replay native draws into the shared world target (never mix native + iris in one frame), exactly as
 * {@code RtFrameRenderer.render} returns early while {@link RtGeometryGate#irisActive()}. The replay is issued from
 * {@code MixinGameRenderer} before that early return, so the suppression has to live in {@link RtParticleLayer#capturing()}.
 */
class RtParticleLayerIrisGateTest {

    @AfterEach
    void reset() {
        RtGeometryGate.setForTests(false);
        RtGeometryGate.setIrisForTests(false);
        VulkanRtxConfig.rtMode = RtMode.OFF;
        VulkanRtxConfig.enableHardwareRayTracing = false;
        RtSettings.rtParticles = true;
    }

    @Test
    void irisFrameSuppressesNativeParticleCapture() {
        // Native conditions that capture particles in a ray traced frame: RT gate on, setting on, an active RT mode so the
        // RT image is displayed.
        RtGeometryGate.setForTests(true);
        RtSettings.rtParticles = true;
        VulkanRtxConfig.applyRtModeOverride("path");
        RtGeometryGate.setIrisForTests(false);
        assertTrue(RtParticleLayer.capturing(), "precondition: a native RT frame captures particles");

        // Iris owns the frame: the native particle layer must stay off so no native draw mixes with the host's passes.
        RtGeometryGate.setIrisForTests(true);
        assertFalse(RtParticleLayer.capturing(), "iris frame must not replay native particles into the world target");
    }
}