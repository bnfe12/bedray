package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

/** Host/controller contract exercised through a recording backend, without Vulkan objects or a driver. */
class PathTraceBackendTest {
    private static final class Backend implements PathTraceBackend {
        Frame frame;
        boolean failRecord;
        int closes;
        long released, discarded;
        public void prepare(int width, int height) {}
        public void record(Frame value) {
            frame = value;
            if (failRecord) throw new IllegalStateException("record failure");
        }
        public boolean spatialAvailable() { return true; }
        public String reservoirState() { return "native"; }
        public void releaseRecording(long command) { released = command; }
        public boolean discardRecording(long command) { discarded = command; return command == 17; }
        public void releaseRecordingsAfterHostDrain() {}
        public void close() { if (++closes == 1) throw new IllegalStateException("pending recording"); }
    }

    @Test void failedRecordingCannotPublishHistoryOrConsumeReset() {
        Backend backend = new Backend();
        RtSlangPathPass host = new RtSlangPathPass(backend, false);
        host.bind(new RtPipeline.Views(1, null, 2, 3, 4, 5, 0, null, 6));
        RtFrameParams params = new RtFrameParams();
        params.bounces = 3;
        params.rayDistance = 72;
        host.prepare(null, null, 16, 8);
        backend.failRecord = true;
        assertThrows(IllegalStateException.class, () -> host.dispatch(null, params, 16, 8));
        backend.failRecord = false;
        host.dispatch(null, params, 16, 8);
        assertTrue((backend.frame.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY) != 0);
        assertTrue((backend.frame.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_DI) != 0);
        assertEquals(16, backend.frame.constants().width);
        assertEquals(8, backend.frame.constants().height);
        assertEquals(3, backend.frame.dispatch().maxDepth());
        assertEquals(0, backend.frame.dispatch().diParity(), "the host supplies no backend parity");
        host.dispatch(null, params, 16, 8);
        assertEquals(0, backend.frame.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY);
        assertEquals(0, backend.frame.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_DI);
        host.discardRecording(99);
        host.dispatch(null, params, 16, 8);
        assertEquals(0, backend.frame.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY);
        host.prepare(null, null, 8, 8);
        host.dispatch(null, params, 8, 8);
        assertTrue((backend.frame.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY) != 0);
        host.discardRecording(17);
        assertEquals(17, backend.discarded);
        host.dispatch(null, params, 8, 8);
        assertTrue((backend.frame.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY) != 0);
        host.releaseRecording(19);
        assertEquals(19, backend.released);
        assertThrows(IllegalStateException.class, host::destroy);
        host.destroy();
        host.destroy();
        assertEquals(2, backend.closes, "failed close is retryable; successful close is idempotent");
    }

    @Test void backendDiStatePreservesEveryOtherPushByte() {
        var original = new RtPathTraceSlangPass.Dispatch(3, 8, 7, 5, 9, 0.01f, 0.5f, 72f, 64f, 0.5f,
            2, 0.25f, 1f, 2f, 3f, 8, 4f, 5f, 6f, 1, 0, 256, 10, 20);
        ByteBuffer before = ByteBuffer.allocate(RtPathTraceSlangPass.PUSH_BYTES);
        ByteBuffer after = ByteBuffer.allocate(RtPathTraceSlangPass.PUSH_BYTES);
        original.writeTo(before);
        original.withDiState(3, 1).writeTo(after);
        assertEquals(3, after.getInt(80));
        assertEquals(1, after.getInt(84));
        for (int i = 0; i < before.capacity(); i++) {
            if (i < 80 || i >= 88) assertEquals(before.get(i), after.get(i), "push byte " + i);
        }
        assertEquals(1, original.diFlags());
        assertEquals(0, original.diParity());
    }

    @Test void displayFlagsTranslateHostIntentWithoutCopyingHostOnlyBits() {
        int input = BedrayCoreLayouts.PATH_TRACE_FRAME_ACCUMULATE
            | BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_STATIC | BedrayCoreLayouts.PATH_TRACE_FRAME_FLIP_Y
            | BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_SCENE | BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_DI;
        assertEquals(31, ReferencePathTraceBackend.displayFlags(input));
        assertEquals(27, ReferencePathTraceBackend.displayFlags(input | BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY));
        assertEquals(2, ReferencePathTraceBackend.displayFlags(BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY));
    }
}
