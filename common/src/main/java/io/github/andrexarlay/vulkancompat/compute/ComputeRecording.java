package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One whole host command recording; false and failures never submit its prefix. */
final class ComputeRecording {
    enum Submission { NEVER_EXECUTED, UNKNOWN, SUBMITTED }
    private final RecordingCompletion completion;
    private final RecordingCompletion.Ticket ticket;
    private Submission submission = Submission.NEVER_EXECUTED;
    private boolean used, discardAttempted;

    ComputeRecording(RecordingCompletion completion, long command, RecordingCompletion.Retirement proof) {
        this.completion = completion;
        ticket = completion.beginWithDiscard(command, proof);
    }

    Submission submission() { return submission; }

    boolean record(BooleanSupplier work, Runnable finish, Runnable execute, Consumer<Runnable> afterCompletion) {
        if (used) throw new IllegalStateException("NoRT command recording was already consumed");
        used = true;
        try {
            if (!work.getAsBoolean()) {
                discardAttempted = true;
                completion.discard(ticket);
                return false;
            }
            finish.run();
            completion.submissionAttempted(ticket);
            submission = Submission.UNKNOWN;
            execute.run();
            completion.submitted(ticket, afterCompletion);
            submission = Submission.SUBMITTED;
            return true;
        } catch (RuntimeException | Error failure) {
            if (submission == Submission.NEVER_EXECUTED && !discardAttempted) {
                discardAttempted = true;
                try { completion.discard(ticket); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            // Execute/enqueue exceptions remain pinned until host drain; never guess their outcome.
            throw failure;
        }
    }
}
