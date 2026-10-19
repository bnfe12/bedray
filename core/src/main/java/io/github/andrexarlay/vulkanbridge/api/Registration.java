package io.github.andrexarlay.vulkanbridge.api;

/** Handle of a registered listener or modifier. */
@Experimental
public interface Registration extends AutoCloseable {
    /** Unregisters; takes effect from the next dispatch. Returns false when it was already removed. */
    boolean remove();

    /** Same as {@link #remove()}. */
    @Override
    default void close() {
        remove();
    }
}
