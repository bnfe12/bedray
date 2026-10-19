package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;

import java.util.function.BooleanSupplier;

/** Time based waits on top of {@link AutotestContext#waitFrames} (the harness only offers frame counts). */
final class ScenarioTime {
    private ScenarioTime() {}

    /** Runs {@code then} after at least {@code seconds} of wall time (checked once per frame). */
    static void waitSeconds(AutotestContext ctx, double seconds, Runnable then) {
        long until = System.nanoTime() + (long) (seconds * 1e9);
        poll(ctx, () -> System.nanoTime() >= until, Long.MAX_VALUE, then, null);
    }

    /** Runs {@code then} once {@code condition} is true, or {@code onTimeout} after {@code timeoutSec} (checked every frame). */
    static void waitUntil(AutotestContext ctx, BooleanSupplier condition, double timeoutSec, Runnable then, Runnable onTimeout) {
        poll(ctx, condition, System.nanoTime() + (long) (timeoutSec * 1e9), then, onTimeout);
    }

    private static void poll(AutotestContext ctx, BooleanSupplier condition, long deadline, Runnable then, Runnable onTimeout) {
        if (condition.getAsBoolean()) {
            then.run();
            return;
        }
        if (System.nanoTime() >= deadline) {
            onTimeout.run();
            return;
        }
        ctx.waitFrames(1, () -> poll(ctx, condition, deadline, then, onTimeout));
    }
}
