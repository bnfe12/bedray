package io.github.andrexarlay.vulkancompat.rt.lifetime;

import java.util.ArrayList;
import java.util.List;

/** Standalone no-GPU checks: javac/java can run these without game or loader jars. */
public final class RecordingCompletionChecks {
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
    private static void refuses(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("transition must fail closed");
    }
    public static void main(String[] args) {
        List<Throwable> failures = new ArrayList<>();
        RecordingCompletion owner = new RecordingCompletion(failures::add);
        List<Runnable> queue = new ArrayList<>();
        int[] releases = {0, 0};
        var first = owner.begin(7, () -> releases[0]++);
        refuses(() -> owner.begin(7));
        owner.afterHostDrain(); // Idle GPU cannot discard an open recording.
        check(owner.pendingCount() == 1 && releases[0] == 0);
        owner.submissionAttempted(first);
        refuses(() -> owner.discard(first));
        owner.submitted(first, queue::add);
        check(releases[0] == 0);
        queue.getFirst().run();
        check(releases[0] == 1 && owner.pendingCount() == 0);
        var second = owner.begin(7, () -> releases[1]++);
        queue.getFirst().run(); // An old generation cannot release second.
        check(releases[1] == 0 && owner.pendingCount() == 1);
        owner.discard(second);
        check(releases[1] == 1);
        refuses(() -> owner.submissionAttempted(second));

        var unknown = owner.begin(8, () -> releases[0]++);
        owner.submissionAttempted(unknown); // execute threw: no callback exists.
        check(owner.pendingCount() == 1);
        owner.afterHostDrain();
        check(releases[0] == 2 && owner.pendingCount() == 0);

        int[] retry = {0, 0};
        var failed = owner.begin(9, () -> {
            if (retry[0]++ == 0) throw new IllegalStateException("injected release failure");
        }, () -> retry[1]++);
        owner.submissionAttempted(failed);
        owner.submitted(failed, queue::add);
        queue.getLast().run();
        check(failures.size() == 1 && retry[1] == 1 && owner.pendingCount() == 1);
        owner.afterHostDrain();
        check(retry[0] == 2 && retry[1] == 1 && owner.pendingCount() == 0);

        var enqueueFailed = owner.begin(10, () -> releases[0]++);
        owner.submissionAttempted(enqueueFailed);
        refuses(() -> owner.submitted(enqueueFailed, action -> { throw new IllegalStateException("enqueue"); }));
        refuses(() -> owner.discard(enqueueFailed));
        owner.afterHostDrain();
        check(releases[0] == 3 && owner.pendingCount() == 0);
        int[] discardRetry = {0};
        var discarded = owner.begin(11, () -> {
            if (discardRetry[0]++ == 0) throw new IllegalStateException("discard cleanup");
        });
        refuses(() -> owner.discard(discarded));
        check(owner.pendingCount() == 1);
        owner.afterHostDrain();
        check(discardRetry[0] == 2 && owner.pendingCount() == 0);
        System.out.println("Recording completion transitions passed (no GPU)");
    }
}
