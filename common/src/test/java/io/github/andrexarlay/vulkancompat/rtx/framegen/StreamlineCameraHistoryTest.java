package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rtx.frame.ReconstructionCamera;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class StreamlineCameraHistoryTest {
    private static final StreamlineCameraHistory.Extent EXTENT = new StreamlineCameraHistory.Extent(1280, 720, 1920, 1080);

    private static ReconstructionCamera camera(double x) {
        return new ReconstructionCamera(new Matrix4f().perspective(1.2f, 16f / 9, .05f, 512, true),
            new Matrix4f().rotateY((float) x * .02f).translate(0, -.04f, 0), x, 70, 0, .25f, -.125f, false);
    }

    @Test void rrHistoryContinuesWithoutAnyFgConsumerOrReadyTargets() {
        var history = new StreamlineCameraHistory();
        var sent = new ArrayList<StreamlineCameraConstants.Packed>();
        history.publish(40, 3, camera(0), EXTENT, sent::add);
        // FG has no usable targets: no publication. Its warmup/resize is outside the common-camera owner.
        history.publish(41, 3, camera(.3), EXTENT, sent::add);
        assertEquals(1, sent.get(0).flags()[3]);
        assertEquals(0, sent.get(1).flags()[3]);
        assertTrue(new Matrix4f().set(sent.get(1).matrices(), 32)
            .equals(camera(.3).clipToPrevious(camera(0)), 1e-6f));
    }

    @Test void acceptedConstantsAreImmutableForEveryConsumerOfTheFrame() {
        var history = new StreamlineCameraHistory();
        var sent = new ArrayList<StreamlineCameraConstants.Packed>();
        history.publish(1, 1, camera(0), EXTENT, sent::add);
        history.publish(1, 1, camera(0), EXTENT, sent::add);
        assertEquals(1, sent.size());
        assertThrows(IllegalStateException.class, () -> history.publish(1, 1, camera(.1), EXTENT, sent::add));
        assertThrows(IllegalStateException.class, () -> history.publish(1, 2, camera(0), EXTENT, sent::add));
        assertThrows(IllegalStateException.class, () -> history.publish(1, 1, camera(0),
            new StreamlineCameraHistory.Extent(1920, 1080, 1920, 1080), sent::add));
        assertEquals(1, sent.size());
    }

    @Test void sdkFailureCannotCommitAFrameOrReplaceTheLastAcceptedCamera() {
        var history = new StreamlineCameraHistory();
        var sent = new ArrayList<StreamlineCameraConstants.Packed>();
        history.publish(1, 1, camera(0), EXTENT, sent::add);
        assertThrows(IllegalStateException.class, () -> history.publish(2, 1, camera(1), EXTENT,
            packed -> { throw new IllegalStateException("slSetConstants failed"); }));
        assertFalse(history.published(2));
        history.publish(2, 1, camera(.2), EXTENT, sent::add);
        assertEquals(0, sent.get(1).flags()[3]);
        assertTrue(new Matrix4f().set(sent.get(1).matrices(), 32)
            .equals(camera(.2).clipToPrevious(camera(0)), 1e-6f));
    }

    @Test void cameraGapsWorldChangesAndExtentChangesResetWithoutFgIntervention() {
        var history = new StreamlineCameraHistory();
        var sent = new ArrayList<StreamlineCameraConstants.Packed>();
        history.publish(1, 1, camera(0), EXTENT, sent::add);
        history.publish(3, 1, camera(.1), EXTENT, sent::add);
        history.publish(4, 2, camera(.2), EXTENT, sent::add);
        history.publish(5, 2, camera(.3), new StreamlineCameraHistory.Extent(1920, 1080, 1920, 1080), sent::add);
        for (var packed : sent) assertEquals(1, packed.flags()[3]);
    }

    @Test void surfaceTeardownEndsContinuityAndStaleFramesAreRejected() {
        var history = new StreamlineCameraHistory();
        var sent = new ArrayList<StreamlineCameraConstants.Packed>();
        history.publish(5, 1, camera(0), EXTENT, sent::add);
        assertThrows(IllegalArgumentException.class, () -> history.publish(4, 1, camera(0), EXTENT, sent::add));
        history.clear();
        assertFalse(history.published(5));
        history.publish(6, 1, camera(.1), EXTENT, sent::add);
        assertEquals(1, sent.get(1).flags()[3]);
    }

    @Test void explicitResetSurvivesContinuousFramesWorldAndExtent() {
        var history = new StreamlineCameraHistory();
        var sent = new ArrayList<StreamlineCameraConstants.Packed>();
        var first = camera(0);
        var reset = new ReconstructionCamera(first.projection(), first.view(), 0, 70, 0, .25f, -.125f, true);
        history.publish(1, 1, first, EXTENT, sent::add);
        history.publish(2, 1, reset, EXTENT, sent::add);
        history.publish(3, 1, camera(.1), EXTENT, sent::add);
        assertEquals(1, sent.get(1).flags()[3]);
        assertEquals(0, sent.get(2).flags()[3]);
    }
}
