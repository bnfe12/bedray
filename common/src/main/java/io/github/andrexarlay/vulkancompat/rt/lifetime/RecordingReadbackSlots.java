package io.github.andrexarlay.vulkancompat.rt.lifetime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Render-thread leases for writable readback slots. Only exact completion proof
 * publishes a written slot; neither frame age nor slot pressure proves completion.
 * No GPU objects, submissions or waits are owned here.
 */
public final class RecordingReadbackSlots<T> {
    private enum State { PENDING, READY, FREE }

    public static final class Lease<T> {
        private final RecordingReadbackSlots<T> owner;
        private final int slot;
        private final long commandBuffer;
        private State state = State.PENDING;
        private boolean written;
        private T metadata;

        private Lease(RecordingReadbackSlots<T> owner, int slot, long commandBuffer) {
            this.owner = owner;
            this.slot = slot;
            this.commandBuffer = commandBuffer;
        }

        public int slot() { return slot; }
        public long commandBuffer() { return commandBuffer; }

        /** Metadata is readable only while this exact completed lease remains unconsumed. */
        public T metadata() {
            if (!owner.current(this, State.READY)) throw new IllegalStateException("readback lease is not ready");
            return metadata;
        }
    }

    private final List<Lease<T>> slots;

    public RecordingReadbackSlots(int count) {
        if (count <= 0) throw new IllegalArgumentException("readback slot count must be positive");
        slots = new ArrayList<>(Collections.nCopies(count, null));
    }

    /** Slot pressure can be handled by the host before allocating/opening another command buffer. */
    public boolean hasFreeSlot() { return slots.contains(null); }

    /** Reserve before recording any commands or writing slot memory. Null means all slots are retained. */
    public Lease<T> reserve(long commandBuffer) {
        if (commandBuffer == 0) throw new IllegalArgumentException("command buffer must be nonzero");
        int free = -1;
        for (int i = 0; i < slots.size(); i++) {
            Lease<T> lease = slots.get(i);
            if (lease == null) {
                if (free < 0) free = i;
            } else if (lease.commandBuffer == commandBuffer && lease.state == State.PENDING) {
                throw new IllegalStateException("command buffer already has a pending readback lease");
            }
        }
        if (free < 0) return null;
        Lease<T> lease = new Lease<>(this, free, commandBuffer);
        slots.set(free, lease);
        return lease;
    }

    /** Attach immutable per-frame metadata after the complete producer recording succeeds. */
    public void written(Lease<T> lease, T metadata) {
        Objects.requireNonNull(metadata);
        if (!current(lease, State.PENDING) || lease.written)
            throw new IllegalStateException("readback lease is stale or already written");
        lease.metadata = metadata;
        lease.written = true;
    }

    /** Exact completion/exclusion of resubmission; an unwritten recording publishes nothing. */
    public boolean completed(Lease<T> lease) {
        if (!current(lease, State.PENDING)) return false;
        if (lease.written) lease.state = State.READY;
        else free(lease);
        return true;
    }

    /** Exact deliberate discard/exclusion of resubmission. A completion already proved cannot be revoked. */
    public boolean discarded(Lease<T> lease) {
        if (!current(lease, State.PENDING)) return false;
        free(lease);
        return true;
    }

    /** Immutable snapshot. A failed read leaves the lease READY for a later retry until consume succeeds. */
    public List<Lease<T>> ready() {
        List<Lease<T>> ready = new ArrayList<>();
        for (Lease<T> lease : slots) if (lease != null && lease.state == State.READY) ready.add(lease);
        return List.copyOf(ready);
    }

    /** Release storage only after the host has finished reading this exact completed lease. */
    public boolean consume(Lease<T> lease) {
        if (!current(lease, State.READY)) return false;
        free(lease);
        return true;
    }

    private boolean current(Lease<T> lease, State state) {
        return lease != null && lease.owner == this && slots.get(lease.slot) == lease && lease.state == state;
    }

    private void free(Lease<T> lease) {
        slots.set(lease.slot, null);
        lease.state = State.FREE;
        lease.metadata = null;
    }
}
