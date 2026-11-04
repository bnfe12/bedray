package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rtx.frame.ReconstructionCamera;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameGenGuidesTest {
    private static ReconstructionCamera camera(double x) {
        return new ReconstructionCamera(new Matrix4f().perspective(1.2f, 16f / 9, .05f, 512, true),
            new Matrix4f().rotateY(.2f + (float) x).translate(.02f, -.05f, 0), x, 70, 0, -.25f, -.375f, false);
    }
    private static FrameGenGuides guides(Object device) {
        var reads = new FrameGenGuideReads(); reads.replace(7);
        return new FrameGenGuides(new WorldFrame.Stamp(3, 40), device, 7,
            new WorldFrame.Image(1, 2, 1280, 720, 100, WorldFrame.Origin.BOTTOM_LEFT),
            new WorldFrame.Image(3, 4, 1280, 720, 83, WorldFrame.Origin.BOTTOM_LEFT),
            1920, 1080, camera(0), .25f, -.375f, reads.source());
    }

    @Test void acceptsMixedInputOutputExtentsButRefusesStaleForeignOrResizedFrames() {
        var device = new Object(); var guides = guides(device);
        assertDoesNotThrow(() -> guides.requireMatches(new WorldFrame.Stamp(3, 40), device, 1920, 1080));
        assertThrows(IllegalStateException.class, () -> guides.requireMatches(new WorldFrame.Stamp(3, 41), device, 1920, 1080));
        assertThrows(IllegalStateException.class, () -> guides.requireMatches(new WorldFrame.Stamp(4, 40), device, 1920, 1080));
        assertThrows(IllegalStateException.class, () -> guides.requireMatches(guides.stamp(), new Object(), 1920, 1080));
        assertThrows(IllegalStateException.class, () -> guides.requireMatches(guides.stamp(), device, 2560, 1440));
    }

    @Test void slAndFfxReceiveTheirOwnJitterConventionsAfterTheSingleRowFlip() {
        var guides = guides(new Object());
        assertEquals(-.25f, guides.preparedCamera().jitterX());
        assertEquals(.375f, guides.preparedCamera().jitterY());
        assertEquals(-.25f, guides.ffxJitterX());
        assertEquals(-.375f, guides.ffxJitterY());
        assertTrue(guides.preparedCamera().view().equals(guides.camera().view()));
        assertEquals(guides.camera().eye(), guides.preparedCamera().eye());
    }

    @Test void preparedClipReprojectionIsConjugatedByTheSameYFlipAsTheImages() {
        var current = camera(.05); var previous = camera(0);
        var y = new Matrix4f().scaling(1, -1, 1);
        var expected = new Matrix4f(y).mul(current.clipToPrevious(previous)).mul(y);
        assertTrue(current.flipClipY().clipToPrevious(previous.flipClipY()).equals(expected, 1e-5f));
        assertTrue(current.flipClipY().flipClipY().sameConstants(current));
    }

    @Test void reconstructionAndFgCanPublishDifferentCamerasInOneToken() {
        var reconstruction = new StreamlineCameraHistory(); var fg = new StreamlineCameraHistory();
        var extent = new StreamlineCameraHistory.Extent(1280, 720, 1920, 1080);
        var camera = camera(0);
        reconstruction.publish(40, 3, camera, extent, packed -> {});
        fg.publish(40, 3, camera.flipClipY(), extent, packed -> {});
        assertTrue(reconstruction.published(40)); assertTrue(fg.published(40));
        assertThrows(IllegalStateException.class,
            () -> fg.publish(40, 3, camera, extent, packed -> fail("incompatible FG constants must not be sent")));
    }
}
