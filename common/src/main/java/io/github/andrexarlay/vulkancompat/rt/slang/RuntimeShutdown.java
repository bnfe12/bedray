package io.github.andrexarlay.vulkancompat.rt.slang;

/** Retryable native-core/library teardown. A refused core close leaves cached programs usable. */
final class RuntimeShutdown {
    private enum State { OPEN, CORE_DESTROYED, CLOSED }
    private State state = State.OPEN;
    boolean isOpen() { return state == State.OPEN; }
    boolean isClosed() { return state == State.CLOSED; }
    void close(Runnable destroyCore, Runnable invalidatePrograms, Runnable unloadLibrary) {
        if (isClosed()) return;
        if (isOpen()) {
            destroyCore.run();
            state = State.CORE_DESTROYED;
        }
        invalidatePrograms.run();
        unloadLibrary.run();
        state = State.CLOSED;
    }
}
