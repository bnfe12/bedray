package io.github.andrexarlay.vulkancompat.rtx.framegen;

import java.util.Objects;

/** Attempts every vendor shutdown action without losing the error that stopped frame production. */
public final class FrameGenFailureCleanup {
    private FrameGenFailureCleanup() {}

    public static Throwable run(Throwable failure, Runnable... actions) {
        Objects.requireNonNull(failure);
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }
}
