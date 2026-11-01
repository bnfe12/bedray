package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;

import java.util.Objects;
import java.util.function.Consumer;

/** Exact graphics-producer proofs. Vendor interpolation consumers have a separate lifetime. */
public final class FrameGenProducerRecordings {
    public interface Submission {
        long commandBuffer();
        void end();
        void execute();
        void afterCompletion(Runnable action);
    }

    private final RecordingCompletion completion;
    private boolean recording, draining;

    public FrameGenProducerRecordings(Consumer<Throwable> reportFailure) {
        completion = new RecordingCompletion(reportFailure);
    }

    /** Captures the output owner before the first transition or vendor prepare command. */
    public void record(Submission submission, RecordingCompletion.Retirement owner, Runnable commands) {
        Objects.requireNonNull(submission);
        Objects.requireNonNull(commands);
        if (recording || draining) throw new IllegalStateException("nested FG producer recording/drain");
        var ticket = completion.beginWithDiscard(submission.commandBuffer(), owner);
        boolean executeAttempted = false;
        String stage = "commands";
        recording = true;
        try {
            commands.run();
            stage = "end";
            submission.end();
            completion.submissionAttempted(ticket);
            executeAttempted = true;
            stage = "execute";
            submission.execute();
            stage = "enqueue_completion";
            completion.submitted(ticket, submission::afterCompletion);
        } catch (RuntimeException | Error failure) {
            if (!executeAttempted) {
                // The host permanently abandons this recording without passing it to execute.
                try { completion.discard(ticket); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            String context = "FG producer stage=" + stage + " command=0x" + Long.toHexString(submission.commandBuffer())
                + " outcome=" + (executeAttempted ? "unknown" : "unsubmitted") + " pending=" + completion.pendingCount();
            if (failure instanceof Error error) {
                error.addSuppressed(new IllegalStateException(context));
                throw error;
            }
            throw new IllegalStateException(context, failure);
        } finally {
            recording = false;
        }
    }

    /** The port submits queued host work and proves its fence before any owner is released. */
    public void drain(Runnable submitAndAwait) {
        Objects.requireNonNull(submitAndAwait);
        if (recording || draining) throw new IllegalStateException("FG producer drain during recording/drain");
        if (completion.pendingCount() == 0) return;
        draining = true;
        try {
            submitAndAwait.run();
            completion.afterHostDrain();
            if (completion.pendingCount() != 0)
                throw new IllegalStateException("FG producer completion proof unavailable; resources retained");
        } finally {
            draining = false;
        }
    }

    public int pendingCount() { return completion.pendingCount(); }
}
