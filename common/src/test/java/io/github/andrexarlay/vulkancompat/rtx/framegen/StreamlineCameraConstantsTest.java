package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rtx.frame.ReconstructionCamera;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamlineCameraConstantsTest {
    private static ReconstructionCamera camera(double x, boolean reset) {
        var p = new Matrix4f().perspective(1.2f, 16f / 9, .05f, 512, true);
        var view = new Matrix4f().rotateY(.2f).translate(.1f, -.3f, .2f);
        return new ReconstructionCamera(p, view, x, 70, -15, .25f, -.125f, reset);
    }

    @Test void packsExactRrProjectionAndEffectiveEyeWithTracePixelScale() {
        var camera = camera(5, false);
        var packed = StreamlineCameraConstants.pack(camera, camera(4.9, false), 1280, 720, 1920, 1080);
        assertTrue(new Matrix4f().set(packed.matrices()).equals(camera.projection()));
        var values = packed.values();
        assertEquals(.25f, values[0]); assertEquals(-.125f, values[1]);
        assertEquals(1f / 1280, values[2]); assertEquals(1f / 720, values[3]);
        assertEquals((float) camera.eye().x, values[4]);
        assertEquals((float) camera.eye().y, values[5]);
        assertEquals((float) camera.eye().z, values[6]);
        assertEquals(camera.up().x, values[7]);
        assertEquals(camera.right().x, values[10]);
        assertEquals(camera.forward().x, values[13]);
        assertEquals(1.2f, values[18], 1e-6f);
        assertArrayEquals(new int[]{1, 1, 0, 0, 0, 0}, packed.flags());
        var toPrevious = new Matrix4f().set(packed.matrices(), 32);
        var toCurrent = new Matrix4f().set(packed.matrices(), 48);
        assertTrue(toPrevious.equals(camera.clipToPrevious(camera(4.9, false)), 1e-6f));
        assertTrue(toPrevious.mul(toCurrent).equals(new Matrix4f(), 1e-4f));
    }

    @Test void firstFrameExplicitResetAndTeleportDiscardHistory() {
        for (var packed : new StreamlineCameraConstants.Packed[]{
                StreamlineCameraConstants.pack(camera(0, false), null, 100, 100, 200, 200),
                StreamlineCameraConstants.pack(camera(1, true), camera(0, false), 100, 100, 200, 200),
                StreamlineCameraConstants.pack(camera(100, false), camera(0, false), 100, 100, 200, 200)}) {
            assertEquals(1, packed.flags()[3]);
            assertTrue(new Matrix4f().set(packed.matrices(), 32).equals(new Matrix4f(), 1e-4f));
        }
    }

    @Test void refusesZeroExtentBeforeDividingMotionScale() {
        assertThrows(IllegalArgumentException.class,
            () -> StreamlineCameraConstants.pack(camera(0, false), null, 0, 100, 100, 100));
    }
}
