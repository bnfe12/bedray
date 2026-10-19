package io.github.andrexarlay.vulkanbridge.api;

/**
 * Notification delivered to listeners of {@link NativeLibraries#addListener}. Delivered on the thread that
 * performed the change; listeners must not block. An exception thrown by a listener propagates to the caller of
 * the operation (it is not swallowed).
 *
 * @param libraryId id of the library
 * @param type      what happened
 * @param status    status after the change
 */
@Experimental
public record LibraryEvent(String libraryId, Type type, LibraryStatus status) {
    public enum Type { INSTALLED, REMOVED }
}
