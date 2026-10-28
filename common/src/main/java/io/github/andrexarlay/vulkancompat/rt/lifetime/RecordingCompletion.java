package io.github.andrexarlay.vulkancompat.rt.lifetime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Render-thread bridge from host completion evidence to native recording retirement. */
public final class RecordingCompletion {
    private enum State { RECORDING, UNKNOWN, SUBMITTED, DISCARD_PROVEN }
    public record Retirement(Runnable completed, Runnable discarded) {
        public Retirement { Objects.requireNonNull(completed); Objects.requireNonNull(discarded); }
    }

    public static final class Ticket {
        private final long commandBuffer;
        private final Retirement[] release;
        private final boolean[] released;
        private Boolean discardedProof;
        private State state = State.RECORDING;

        private Ticket(long commandBuffer, Retirement[] release) {
            this.commandBuffer = commandBuffer;
            this.release = release.clone();
            this.released = new boolean[release.length];
        }
    }

    private final Map<Long, Ticket> pending = new LinkedHashMap<>();
    private final Consumer<Throwable> reportFailure;

    public RecordingCompletion(Consumer<Throwable> reportFailure) {
        this.reportFailure = Objects.requireNonNull(reportFailure);
    }

    /** A reused handle cannot replace an unreleased recording generation. */
    public Ticket begin(long commandBuffer, Runnable... release) {
        Retirement[] actions = new Retirement[release.length];
        for (int i = 0; i < release.length; ++i) actions[i] = new Retirement(release[i], release[i]);
        return beginWithDiscard(commandBuffer, actions);
    }

    public Ticket beginWithDiscard(long commandBuffer, Retirement... release) {
        if (commandBuffer == 0 || pending.containsKey(commandBuffer)) {
            throw new IllegalStateException("command buffer has no completed/discarded prior recording");
        }
        for (Retirement action : release) Objects.requireNonNull(action);
        Ticket ticket = new Ticket(commandBuffer, release);
        pending.put(commandBuffer, ticket);
        return ticket;
    }

    /** Call immediately before execute: an exception from execute is an unknown outcome. */
    public void submissionAttempted(Ticket ticket) {
        require(ticket, State.RECORDING);
        ticket.state = State.UNKNOWN;
    }

    /** Call after execute attached the recording, before the same encoder submits it. */
    public void submitted(Ticket ticket, Consumer<Runnable> queueAfterCompletion) {
        require(ticket, State.UNKNOWN);
        // If enqueue throws, retain UNKNOWN and its strong owner references.
        queueAfterCompletion.accept(() -> {
            try {
                releaseProven(ticket, false);
            } catch (RuntimeException | Error failure) {
                // A failed native release must not abort the host destruction queue.
                reportFailure.accept(failure);
            }
        });
        if (pending.get(ticket.commandBuffer) == ticket) ticket.state = State.SUBMITTED;
    }

    /** The host deliberately abandons this never-executed command buffer. */
    public void discard(Ticket ticket) {
        require(ticket, State.RECORDING);
        ticket.state = State.DISCARD_PROVEN;
        releaseProven(ticket, true);
    }

    /** Whole-device completion proof, excluding resubmission; open recordings stay pinned. */
    public void afterHostDrain() {
        RuntimeException failures = null;
        for (Ticket ticket : List.copyOf(pending.values())) {
            if (ticket.state == State.RECORDING) continue;
            try {
                releaseProven(ticket, ticket.state != State.SUBMITTED);
            } catch (RuntimeException | Error failure) {
                if (failures == null) failures = new IllegalStateException("native recording retirement failed");
                failures.addSuppressed(failure);
            }
        }
        if (failures != null) throw failures;
    }

    public int pendingCount() { return pending.size(); }

    private void require(Ticket ticket, State state) {
        if (ticket == null || pending.get(ticket.commandBuffer) != ticket || ticket.state != state) {
            throw new IllegalStateException("stale recording ticket or invalid submission transition");
        }
    }

    private void releaseProven(Ticket ticket, boolean discarded) {
        // A late callback names its ticket, never a newer recording of the same address.
        if (pending.get(ticket.commandBuffer) != ticket) return;
        if (ticket.discardedProof == null) ticket.discardedProof = discarded;
        RuntimeException failures = null;
        for (int i = 0; i < ticket.release.length; ++i) {
            if (ticket.released[i]) continue;
            try {
                Retirement action = ticket.release[i];
                (ticket.discardedProof ? action.discarded() : action.completed()).run();
                ticket.released[i] = true;
            } catch (RuntimeException | Error failure) {
                if (failures == null) failures = new IllegalStateException("recording owner release failed");
                failures.addSuppressed(failure);
            }
        }
        if (failures != null) throw failures;
        pending.remove(ticket.commandBuffer);
    }
}
