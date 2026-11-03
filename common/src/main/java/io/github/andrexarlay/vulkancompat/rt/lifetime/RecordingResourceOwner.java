package io.github.andrexarlay.vulkancompat.rt.lifetime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Render-thread resource generations and exclusive writable slots, retired only after exact recording proof. */
public final class RecordingResourceOwner<T> implements AutoCloseable {
    public static final class Generation<T> {
        private final RecordingResourceOwner<T> owner;
        private final T value;
        private final Object[] slots;
        private int pins;
        private boolean retired;
        private Generation(RecordingResourceOwner<T> owner, T value, int slots) {
            this.owner = owner;
            this.value = value;
            this.slots = new Object[slots];
        }
        public T value() { return value; }
    }
    public static final class Recording<T> {
        private final RecordingResourceOwner<T> owner;
        private final long command;
        private final List<Use<T>> uses = new ArrayList<>();
        private boolean proven;
        private Recording(RecordingResourceOwner<T> owner, long command) {
            this.owner = owner;
            this.command = command;
        }
    }
    private static final class Use<T> {
        final Generation<T> generation;
        final int slot;
        boolean released;
        Use(Generation<T> generation, int slot) { this.generation = generation; this.slot = slot; }
    }

    private final Consumer<T> destroy;
    private final List<Generation<T>> generations = new ArrayList<>();
    private final Map<Long, Recording<T>> recordings = new LinkedHashMap<>();
    private boolean closing;
    private boolean closed;

    /** The destructor must preserve enough state to retry after a partial destruction failure. */
    public RecordingResourceOwner(Consumer<T> destroy) { this.destroy = Objects.requireNonNull(destroy); }

    public Generation<T> create(T value, int slots) {
        requireOpen();
        if (slots < 0) throw new IllegalArgumentException("negative slot count");
        Generation<T> generation = new Generation<>(this, Objects.requireNonNull(value), slots);
        generations.add(generation);
        return generation;
    }

    public Recording<T> begin(long command) {
        requireOpen();
        if (command == 0 || recordings.containsKey(command))
            throw new IllegalStateException("recording requires a nonzero, completed/discarded prior handle");
        Recording<T> recording = new Recording<>(this, command);
        recordings.put(command, recording);
        return recording;
    }

    public Recording<T> recording(long command) { return recordings.get(command); }
    public int pendingCount() { return recordings.size(); }
    public long retiredCount() {
        long count = 0;
        for (Generation<T> generation : generations) if (generation.retired) count++;
        return count;
    }

    public int freeSlot(Generation<T> generation) {
        requireGeneration(generation);
        if (generation.retired) return -1;
        for (int i = 0; i < generation.slots.length; i++) if (generation.slots[i] == null) return i;
        return -1;
    }

    /** Returns -1 when all writable slots remain pinned. Slot pressure is not completion proof. */
    public int lease(Recording<T> recording, Generation<T> generation) {
        requireRecording(recording);
        int slot = freeSlot(generation);
        if (slot < 0) return -1;
        addUse(recording, generation, slot);
        generation.slots[slot] = recording;
        return slot;
    }

    /** Shared immutable data, such as an atlas generation, needs a pin rather than an exclusive write slot. */
    public void pin(Recording<T> recording, Generation<T> generation) {
        requireRecording(recording);
        requireGeneration(generation);
        if (generation.retired) throw new IllegalStateException("cannot pin a retired generation");
        addUse(recording, generation, -1);
    }

    private void addUse(Recording<T> recording, Generation<T> generation, int slot) {
        // Allocate bookkeeping before changing reference counts or slot availability.
        recording.uses.add(new Use<>(generation, slot));
        generation.pins++;
    }

    public void retire(Generation<T> generation) {
        requireGeneration(generation);
        generation.retired = true;
        collect();
    }

    /** Caller proved completion or deliberate discard/exclusion of resubmission for this exact ticket. */
    public boolean releaseAfterProof(Recording<T> recording) {
        if (recording == null || recording.owner != this || recordings.get(recording.command) != recording) return false;
        recording.proven = true;
        for (Use<T> use : recording.uses) {
            if (use.released) continue;
            if (use.slot >= 0) use.generation.slots[use.slot] = null;
            use.generation.pins--;
            use.released = true;
        }
        // A failed destructor keeps both the retired generation and this proven ticket for a retry.
        collect();
        recordings.remove(recording.command);
        return true;
    }

    private void collect() {
        for (Iterator<Generation<T>> it = generations.iterator(); it.hasNext(); ) {
            Generation<T> generation = it.next();
            if (!generation.retired || generation.pins != 0) continue;
            destroy.accept(generation.value);
            it.remove();
        }
    }

    @Override public void close() {
        if (closed) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("resource owner still has pending recordings");
        closing = true;
        for (Generation<T> generation : generations) generation.retired = true;
        collect();
        closed = true;
    }

    private void requireOpen() {
        if (closing || closed) throw new IllegalStateException("resource owner is closing/closed");
    }
    private void requireRecording(Recording<T> recording) {
        requireOpen();
        if (recording == null || recording.owner != this || recordings.get(recording.command) != recording || recording.proven)
            throw new IllegalStateException("stale or completed recording");
    }
    private void requireGeneration(Generation<T> generation) {
        if (generation == null || generation.owner != this) throw new IllegalArgumentException("foreign generation");
    }
}
