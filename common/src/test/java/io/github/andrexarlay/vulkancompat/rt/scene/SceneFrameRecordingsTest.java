package io.github.andrexarlay.vulkancompat.rt.scene;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SceneFrameRecordingsTest {
    @Test void postAcceptanceAllocationFailureKeepsTheFrameReachableUntilExactDiscard() {
        var recordings = new SceneFrameRecordings(0xabc);
        var allocation = new OutOfMemoryError("FrameInfo allocation");
        var failure = assertThrows(IllegalStateException.class, () -> recordings.prepare(0xdef, 17, frame -> {
            assertEquals(1, recordings.pendingCount(), "host tracking already exists when the downcall starts");
            frame.markPrepared();
            throw allocation;
        }));
        assertSame(allocation, failure.getCause());
        assertTrue(failure.getMessage().contains("stage=prepare_frame scene=0xabc frame=17 command=0xdef"));
        assertTrue(failure.getMessage().contains("accepted=true nativeReleased=false; recording retained=true"));
        assertEquals(1, recordings.pendingCount());
        List<Long> discarded = new ArrayList<>();
        assertTrue(recordings.release("discard_frame", 0xdef, frame -> {
            discarded.add(frame.index());
            frame.markReleased();
        }));
        assertEquals(List.of(17L), discarded);
        assertEquals(0, recordings.pendingCount());
    }

    @Test void rejectionBeforeAcceptanceNeedsNoNativeCleanupAndDoesNotConsumeTheHandle() {
        var recordings = new SceneFrameRecordings(1);
        var rejection = new IllegalArgumentException("native validation rejected the frame");
        var failure = assertThrows(IllegalStateException.class, () -> recordings.prepare(19, 1, frame -> {
            throw rejection;
        }));
        assertSame(rejection, failure.getCause());
        assertTrue(failure.getMessage().contains("accepted=false"));
        assertEquals(0, recordings.pendingCount());
        assertFalse(recordings.release("discard_frame", 19, frame -> fail("no accepted native frame exists")));
        recordings.prepare(19, 2, SceneFrameRecordings.Frame::markPrepared);
        recordings.releaseAll(SceneFrameRecordings.Frame::markReleased);
        assertEquals(0, recordings.pendingCount());
    }

    @Test void failedNativeCleanupRetainsTheExactFrameForRetry() {
        var recordings = new SceneFrameRecordings(0x12);
        recordings.prepare(0x34, 9, SceneFrameRecordings.Frame::markPrepared);
        var cleanup = new IllegalStateException("native cleanup refused");
        var failure = assertThrows(IllegalStateException.class,
            () -> recordings.release("discard_frame", 0x34, frame -> { throw cleanup; }));
        assertSame(cleanup, failure.getCause());
        assertTrue(failure.getMessage().contains("stage=discard_frame scene=0x12 frame=9 command=0x34"));
        assertEquals(1, recordings.pendingCount());
        recordings.release("discard_frame", 0x34, frame -> {
            assertEquals(9, frame.index());
            frame.markReleased();
        });
        assertEquals(0, recordings.pendingCount());
    }

    @Test void aJavaFailureAfterNativeCleanupDoesNotRepeatTheNativeRelease() {
        var recordings = new SceneFrameRecordings(1);
        recordings.prepare(19, 1, SceneFrameRecordings.Frame::markPrepared);
        var postCall = new OutOfMemoryError("post-call cleanup");
        var failure = assertThrows(IllegalStateException.class, () -> recordings.release("release_frame", 19, frame -> {
            frame.markReleased();
            throw postCall;
        }));
        assertSame(postCall, failure.getCause());
        assertTrue(failure.getMessage().contains("nativeReleased=true"));
        assertEquals(1, recordings.pendingCount());
        assertTrue(recordings.release("release_frame", 19, frame -> fail("native frame was already released")));
        assertEquals(0, recordings.pendingCount());
    }

    @Test void aHostDrainAttemptsAllFramesAndKeepsOnlyFailedReleases() {
        var recordings = new SceneFrameRecordings(1);
        for (long index = 1; index <= 3; index++)
            recordings.prepare(100 + index, index, SceneFrameRecordings.Frame::markPrepared);
        List<Long> attempted = new ArrayList<>();
        var first = new IllegalStateException("frame one refused");
        var third = new OutOfMemoryError("frame three cleanup");
        var failure = assertThrows(IllegalStateException.class, () -> recordings.releaseAll(frame -> {
            attempted.add(frame.index());
            if (frame.index() == 1) throw first;
            if (frame.index() == 3) throw third;
            frame.markReleased();
        }));
        assertSame(first, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(third, failure.getSuppressed()[0].getCause());
        assertEquals(List.of(1L, 2L, 3L), attempted);
        assertEquals(2, recordings.pendingCount());
        recordings.releaseAll(frame -> { attempted.add(frame.index()); frame.markReleased(); });
        assertEquals(List.of(1L, 2L, 3L, 1L, 3L), attempted);
        assertEquals(0, recordings.pendingCount());
    }

    @Test void aDuplicateCommandCannotReplaceAnAcceptedFrame() {
        var recordings = new SceneFrameRecordings(1);
        recordings.prepare(0x123, 7, SceneFrameRecordings.Frame::markPrepared);
        var called = new AtomicBoolean();
        var failure = assertThrows(IllegalStateException.class, () -> recordings.prepare(0x123, 8, frame -> called.set(true)));
        assertFalse(called.get());
        assertTrue(failure.getMessage().contains("requestedFrame=8 pendingFrame=7 command=0x123"));
        assertFalse(recordings.release("release_frame", 0x456, frame -> fail("unknown command")));
        recordings.release("release_frame", 0x123, frame -> {
            assertEquals(7, frame.index());
            frame.markReleased();
        });
        assertEquals(0, recordings.pendingCount());
    }

    @Test void producerCompletionKeepsTheGenerationUntilItsSeparateConsumerCompletes() {
        var recordings = new SceneFrameRecordings(1);
        recordings.prepare(19, 1, SceneFrameRecordings.Frame::markPrepared);
        List<Long> released = new ArrayList<>();
        var read = recordings.retainRead(19, frame -> {
            released.add(frame.index());
            frame.markReleased();
        });
        assertNotNull(read);
        recordings.completeProducer(19, frame -> assertEquals(1, frame.index()),
            frame -> fail("the custom RT submission still reads the scene"));
        assertEquals(1, recordings.pendingCount());
        recordings.prepare(19, 2, SceneFrameRecordings.Frame::markPrepared);
        assertEquals(2, recordings.pendingCount());
        read.run();
        assertEquals(List.of(1L), released);
        read.run();
        assertEquals(1, recordings.pendingCount(), "a stale callback cannot retire the reused handle");
        recordings.releaseAll(SceneFrameRecordings.Frame::markReleased);
    }

    @Test void ffmAcknowledgesNativeSuccessBeforeResultAllocationOrArenaCleanup() throws Exception {
        Path sourceRoot = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt");
        String ffm = Files.readString(sourceRoot.resolve("scene/BedraySceneNative.java"));
        String prepare = body(ffm, "public FrameInfo prepareFrame(");
        int accepted = prepare.indexOf("recording.markPrepared()");
        assertTrue(accepted > prepare.indexOf("prepareFrameHandle.invokeExact("));
        assertTrue(accepted < prepare.indexOf("check(\"prepare_frame\""));
        assertTrue(accepted < prepare.indexOf("return new FrameInfo("));
        for (String operation : new String[]{"releaseFrame", "discardFrame"}) {
            String release = body(ffm, "public void " + operation + "(");
            assertTrue(release.indexOf("recording.markReleased()") > release.indexOf("Handle.invokeExact("));
            assertTrue(release.indexOf("recording.markReleased()") < release.indexOf("check("));
        }
        String bridge = Files.readString(sourceRoot.resolve("world/NativeSceneBridge.java"));
        String frame = body(bridge, "void prepareFrame(");
        assertTrue(frame.indexOf("recordings.prepare(commandBuffer, frameIndex")
            < frame.indexOf("library.prepareFrame(scene, recording)"));
        assertTrue(frame.indexOf("library.prepareFrame(scene, recording)") < frame.indexOf("library.queryResources(scene)"));
        assertFalse(bridge.contains("recordingFrames"), "one token map is the frame lifetime authority");
    }

    private static String body(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int open = source.indexOf('{', start), end = open + 1, depth = 1;
        while (end < source.length() && depth != 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        assertTrue(open >= 0 && depth == 0, marker);
        return source.substring(open + 1, end - 1);
    }
}
