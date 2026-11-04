package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingResourceOwner;

/** Pins borrowed RR guide generations until the host's FG copy recording completes or is discarded. */
public final class FrameGenGuideReads {
    private final RecordingResourceOwner<Long> owner = new RecordingResourceOwner<>(generation -> {});
    private RecordingResourceOwner.Generation<Long> generation;

    public void replace(long id) {
        invalidate();
        generation = owner.create(id, 0);
    }

    public void requireUnreferenced() {
        if (owner.pendingCount() != 0)
            throw new IllegalStateException("RR guide replacement requires FG producer completion; pending=" + owner.pendingCount());
    }

    public void invalidate() {
        requireUnreferenced();
        if (generation != null) owner.retire(generation);
        generation = null;
    }

    public Source source() {
        if (generation == null) throw new IllegalStateException("RR guide generation is unavailable");
        return new Source(owner, generation);
    }

    public static final class Source {
        private final RecordingResourceOwner<Long> owner;
        private final RecordingResourceOwner.Generation<Long> generation;
        private Source(RecordingResourceOwner<Long> owner, RecordingResourceOwner.Generation<Long> generation) {
            this.owner = owner;
            this.generation = generation;
        }
        public Read read() { return new Read(owner, generation); }
    }

    public static final class Read {
        private final RecordingResourceOwner<Long> owner;
        private final RecordingResourceOwner.Generation<Long> generation;
        private RecordingResourceOwner.Recording<Long> recording;
        private boolean ended;
        private Read(RecordingResourceOwner<Long> owner, RecordingResourceOwner.Generation<Long> generation) {
            this.owner = owner;
            this.generation = generation;
        }
        /** Called only after the FG producer registered both source/output retirements, before any commands. */
        public void retain(long command) {
            if (ended || recording != null) throw new IllegalStateException("FG source read was already used");
            recording = owner.begin(command);
            owner.pin(recording, generation);
        }
        public RecordingCompletion.Retirement retirement() {
            return new RecordingCompletion.Retirement(this::release, this::release);
        }
        private void release() {
            if (recording != null) owner.releaseAfterProof(recording);
            ended = true;
        }
    }
}
