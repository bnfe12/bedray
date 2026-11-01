package io.github.andrexarlay.vulkancompat.rt.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Reserves host tracking before the native scene can accept a frame. Render-thread only. */
public final class SceneFrameRecordings {
    public static final class Frame {
        private final long scene;
        private final long index;
        private final Long command;
        private boolean prepared;
        private boolean released;

        private Frame(long scene, long index, long command) {
            this.scene = scene;
            this.index = index;
            this.command = command;
        }
        public long scene() { return scene; }
        public long index() { return index; }
        public long command() { return command; }
        public boolean prepared() { return prepared; }

        /** FFM calls this immediately after native success, before decoding or allocating its result. */
        void markPrepared() { prepared = true; }
        void markReleased() { released = true; }
        boolean released() { return released; }
    }

    private final long scene;
    private final Map<Long, Frame> recordings = new LinkedHashMap<>();

    public SceneFrameRecordings(long scene) {
        if (scene == 0) throw new IllegalArgumentException("scene handle must be nonzero");
        this.scene = scene;
    }

    public void prepare(long command, long index, Consumer<Frame> operation) {
        Objects.requireNonNull(operation);
        if (command == 0 || index <= 0)
            throw new IllegalArgumentException("scene recording needs a nonzero command and positive frame index");
        Frame pending = recordings.get(command);
        if (pending != null)
            throw new IllegalStateException("native scene preparation blocked: scene=0x" + Long.toUnsignedString(scene, 16)
                + " requestedFrame=" + index + " pendingFrame=" + pending.index
                + " command=0x" + Long.toUnsignedString(command, 16) + " requires completion/discard");
        Frame frame = new Frame(scene, index, command);
        recordings.put(frame.command, frame); // All tracking allocation precedes the downcall.
        try {
            operation.accept(frame);
            if (!frame.prepared) throw new IllegalStateException("native scene prepare returned without acceptance");
        } catch (RuntimeException | Error failure) {
            if (!frame.prepared) recordings.remove(frame.command);
            throw failed("prepare_frame", frame, failure);
        }
    }

    /** Exact completion/discard proof; native cleanup failure preserves the accepted token for retry. */
    public boolean release(String operation, long command, Consumer<Frame> releaseNative) {
        Objects.requireNonNull(releaseNative);
        Frame frame = recordings.get(command);
        if (frame == null) return false;
        try {
            releaseNative(frame, releaseNative);
            recordings.remove(frame.command);
            return true;
        } catch (RuntimeException | Error failure) {
            throw failed(operation, frame, failure);
        }
    }

    /** Only after the host closed/discarded open recordings and drained submissions. */
    public void releaseAll(Consumer<Frame> releaseNative) {
        Objects.requireNonNull(releaseNative);
        IllegalStateException failures = null;
        for (var it = recordings.values().iterator(); it.hasNext();) {
            Frame frame = it.next();
            try {
                releaseNative(frame, releaseNative);
                it.remove();
            } catch (RuntimeException | Error failure) {
                IllegalStateException contextual = failed("release_frame_after_host_drain", frame, failure);
                if (failures == null) failures = contextual;
                else failures.addSuppressed(contextual);
            }
        }
        if (failures != null) throw failures;
    }

    public int pendingCount() { return recordings.size(); }

    private static void releaseNative(Frame frame, Consumer<Frame> release) {
        if (!frame.released) {
            release.accept(frame);
            if (!frame.released) throw new IllegalStateException("native scene release returned without acknowledgement");
        }
    }

    private IllegalStateException failed(String stage, Frame frame, Throwable cause) {
        return new IllegalStateException("native scene stage=" + stage + " scene=0x" + Long.toUnsignedString(scene, 16)
            + " frame=" + frame.index + " command=0x" + Long.toUnsignedString(frame.command, 16)
            + " accepted=" + frame.prepared + " nativeReleased=" + frame.released
            + "; recording retained=" + frame.prepared, cause);
    }
}
