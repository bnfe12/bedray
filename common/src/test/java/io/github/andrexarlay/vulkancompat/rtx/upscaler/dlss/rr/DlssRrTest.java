package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * JUnit runner for DLSS Ray Reconstruction checks.
 */
class DlssRrTest {
    @Test
    void runDlssRrChecks() {
        assertDoesNotThrow(() -> {
            // Also test native wrapper directly if the built DLL exists on disk
            Path defaultWrapper = Path.of("..", "native", "dlss_bridge", "build", "vkb_dlss.dll");
            if (Files.isRegularFile(defaultWrapper)) {
                System.setProperty("vkbridge.test.dlssWrapper", defaultWrapper.toAbsolutePath().toString());
            }
            try {
                DlssRrChecks.runAll();
            } finally {
                System.clearProperty("vkbridge.test.dlssWrapper");
            }
        });
    }

    @Test
    void testDlssRrGeometricNormalsSettingAndPacking() {
        assertDoesNotThrow(DlssRrChecks::geometricNormalsAndRoughnessPacking);
    }

    @Test
    void testHorizonBendingAndReflectionContinuity() {
        assertDoesNotThrow(DlssRrChecks::horizonBendingAndReflectionContinuity);
    }

    @Test
    void testVelocityMotionVectorIntegration() {
        assertDoesNotThrow(DlssRrChecks::velocityMotionVectorIntegration);
    }
}
