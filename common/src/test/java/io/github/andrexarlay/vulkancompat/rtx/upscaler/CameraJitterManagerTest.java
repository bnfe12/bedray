package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraJitterManagerTest {
    @Test void skippedFramesExposeZeroAndRestartTheSequence() {
        CameraJitterManager.reset();
        try {
            CameraJitterManager.beginFrame();
            CameraJitterManager.advanceFrame(960, 540, 32);
            float firstY = CameraJitterManager.getCurrentPixelJitterY();
            assertNotEquals(0.0f, firstY);
            assertEquals(firstY, CameraJitterManager.getPrevPixelJitterY());
            assertEquals(32, CameraJitterManager.getPhaseCount());

            CameraJitterManager.beginFrame();
            assertFalse(CameraJitterManager.isActive());
            assertEquals(0.0f, CameraJitterManager.getCurrentPixelJitterY());
            assertEquals(0.0f, CameraJitterManager.getPrevPixelJitterY());
            CameraJitterManager.advanceFrame(960, 540, 32);
            assertEquals(1, CameraJitterManager.getPhaseIndex());
            assertEquals(firstY, CameraJitterManager.getPrevPixelJitterY());

            CameraJitterManager.beginFrame();
            CameraJitterManager.beginFrame();
            CameraJitterManager.advanceFrame(1921, 1081, 16);
            assertEquals(0, CameraJitterManager.getPhaseIndex());
            assertEquals(16, CameraJitterManager.getPhaseCount());
            assertEquals(firstY, CameraJitterManager.getCurrentPixelJitterY());
        } finally {
            CameraJitterManager.reset();
        }
    }
}
