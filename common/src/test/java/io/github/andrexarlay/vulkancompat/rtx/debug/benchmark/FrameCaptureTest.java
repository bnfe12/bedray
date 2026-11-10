package io.github.andrexarlay.vulkancompat.rtx.debug.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

class FrameCaptureTest {
    private static final FrameCapture.Facts NATIVE = facts(FrameCapture.NATIVE_REQUIRED, "path_trace_scene.ser");
    private static FrameCapture.Facts facts(int flags, String program) {
        return new FrameCapture.Facts(flags, 1920, 1080, 1920, 1080, "path", program, 1, 3, 72, 0,
            "BILINEAR", "none", "UNAVAILABLE", "OFF");
    }
    private static FrameCapture capture(long warmup, int capacity) {
        return new FrameCapture(new FrameCapture.Config(warmup, 10, capacity, 5), new String[]{"total", "rt"});
    }
    private static void frame(FrameCapture c, long id, long now, FrameCapture.Facts facts) {
        c.begin(id, now); c.end(id, facts, new double[]{2, 1});
    }

    @Test void lateGpuMatchesOriginalFrameAndFinalSlowFrameIsRetained() {
        var c = capture(0, 10);
        frame(c, 100, 100, NATIVE);
        frame(c, 101, 104, NATIVE);
        c.gpu(101, new double[]{20, 12}); // out of order, not assigned by arrival
        c.gpu(99, new double[]{900, 900}); // before capture
        c.gpu(100, new double[]{3, 2});
        c.gpu(100, new double[]{999, 999}); // duplicate ignored
        c.begin(102, 144);
        var snapshot = c.poll(144);
        assertTrue(snapshot.complete());
        assertEquals(2, snapshot.rows().size());
        assertEquals(40e-6, snapshot.rows().get(1).intervalMs(), 1e-15);
        assertArrayEquals(new double[]{3, 2}, snapshot.rows().getFirst().gpuMs());
        assertArrayEquals(new double[]{20, 12}, snapshot.rows().getLast().gpuMs());
        assertEquals(44, snapshot.endNs() - snapshot.startNs());
        assertNull(c.poll(145)); // exactly one export
    }

    @Test void warmupRestartsAfterFocusLossAndDoesNotCountMenus() {
        var c = capture(10, 10);
        frame(c, 0, 100, facts(0, ""));
        frame(c, 1, 101, NATIVE);
        frame(c, 2, 109, facts(FrameCapture.NATIVE_REQUIRED & ~FrameCapture.FOCUSED, ""));
        frame(c, 3, 110, NATIVE);
        frame(c, 4, 119, NATIVE);
        assertEquals(0, c.size());
        frame(c, 5, 120, NATIVE);
        assertEquals(1, c.size());
    }

    @Test void invalidNativeStateAfterWarmupIsRetainedForOfflineRejection() {
        var c = capture(0, 10);
        frame(c, 0, 100, NATIVE);
        frame(c, 1, 102, facts(0, ""));
        c.begin(2, 110);
        var snapshot = c.poll(115);
        assertTrue(snapshot.complete());
        assertEquals(0, snapshot.rows().getLast().facts().flags());
        assertFalse(snapshot.rows().getLast().gpuResolved());
        assertTrue(Double.isNaN(snapshot.rows().getLast().gpuMs()[0]));
        assertTrue(snapshot.reason().contains("gpu_results_missing"));
    }

    @Test void capacityFailureExportsPartialDataWithoutPretendingCompletion() {
        var c = capture(0, 1);
        frame(c, 0, 100, NATIVE);
        frame(c, 1, 101, NATIVE);
        var snapshot = c.poll(102);
        assertFalse(snapshot.complete());
        assertEquals(1, snapshot.rows().size());
        assertTrue(snapshot.reason().startsWith("sample_capacity_exceeded"));
    }

    @Test void droppedOrDuplicateApplicationFramesInvalidateCapture() {
        for (int next : new int[]{0, 2}) {
            var c = capture(0, 10);
            frame(c, 0, 100, NATIVE);
            c.begin(next, 101);
            var snapshot = c.poll(101);
            assertFalse(snapshot.complete());
            assertTrue(snapshot.reason().contains("invalid_frame_interval_or_sequence"));
        }
    }

    @Test void abortKeepsUnclosedLastFrameAsMissingInsteadOfDroppingIt() {
        var c = capture(0, 10);
        frame(c, 9, 100, NATIVE);
        c.abort("world_unloaded", 101);
        var snapshot = c.poll(101);
        assertFalse(snapshot.complete());
        assertTrue(Double.isNaN(snapshot.rows().getFirst().intervalMs()));
        assertTrue(snapshot.reason().contains("last_interval_missing"));
    }

    @Test void backwardsClockAndUnfinishedFrameAreFailures() {
        var c = capture(0, 10);
        c.begin(0, 100); c.begin(1, 101);
        assertFalse(c.poll(101).complete());
        var other = capture(0, 10);
        frame(other, 0, 100, NATIVE);
        other.begin(1, 99);
        assertFalse(other.poll(99).complete());
    }

    @Test void writerPreservesMissingValuesAndCannotOverwriteEvidence(@TempDir Path directory) throws Exception {
        var c = capture(0, 10);
        frame(c, 0, 100, facts(FrameCapture.NATIVE_REQUIRED, "quote,\"line\nnext"));
        c.begin(1, 110);
        var snapshot = c.poll(115);
        var config = new FrameCapture.Config(0, 10, 10, 5);
        FrameCaptureWriter.write(directory, config, snapshot, Map.of("revision_hint", "unverified"));
        String csv = Files.readString(directory.resolve("frames.csv"));
        assertTrue(csv.contains("\"quote,\"\"line\nnext\""));
        assertTrue(csv.contains("2.0,1.0,,"));
        var manifest = JsonParser.parseString(Files.readString(directory.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(10, manifest.get("measured_interval_ns").getAsLong());
        assertEquals(127, manifest.get("native_required_flags").getAsInt());
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> FrameCaptureWriter.write(directory, config, snapshot, Map.of()));
        assertEquals(csv, Files.readString(directory.resolve("frames.csv")));
    }
}
