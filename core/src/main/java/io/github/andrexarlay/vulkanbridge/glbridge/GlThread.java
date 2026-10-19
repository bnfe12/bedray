package io.github.andrexarlay.vulkanbridge.glbridge;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

/**
 * The one thread that owns the hidden GL context for the whole life of the bridge. Work is submitted from the render
 * thread with {@link #call}; the call returns when the GL commands were issued (and flushed), not when the GPU finished.
 * Exceptions of a task are rethrown on the caller (never swallowed).
 */
final class GlThread {
    private volatile Thread thread;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VKB GL Bridge");
        t.setDaemon(true);
        thread = t;
        return t;
    });

    boolean isGlThread() {
        return Thread.currentThread() == thread;
    }

    <T> T call(Callable<T> task) {
        return join(executor.submit(task));
    }

    /**
     * Queues a task without waiting (pipelined GL frames). The returned future must be passed to {@link #join} later; that is
     * where an exception of the task surfaces.
     */
    Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    /** Waits for a task queued with {@link #submit}/{@link #call} and rethrows its exception on the caller (never swallowed). */
    static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the GL bridge thread", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("GL bridge thread task failed: " + c, c);
        }
    }

    /**
     * Stops the thread after the queued tasks ran. Returns whether it terminated within the timeout (the thread is a daemon, so a stuck driver call
     * cannot keep the JVM alive; the caller logs the result).
     */
    boolean shutdown(long timeoutMillis) {
        executor.shutdown();
        try {
            return executor.awaitTermination(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }
}
