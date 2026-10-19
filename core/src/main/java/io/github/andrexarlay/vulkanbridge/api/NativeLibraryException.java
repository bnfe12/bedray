package io.github.andrexarlay.vulkanbridge.api;

/** A native library cannot be used or installed. The message always carries the reason. */
@Experimental
public final class NativeLibraryException extends RuntimeException {
    private final String libraryId;
    private final LibraryStatus status;

    public NativeLibraryException(String libraryId, LibraryStatus status) {
        super("native library '" + libraryId + "' is " + status.state() + ": " + status.detail());
        this.libraryId = libraryId;
        this.status = status;
    }

    public NativeLibraryException(String libraryId, String message) {
        super("native library '" + libraryId + "': " + message);
        this.libraryId = libraryId;
        this.status = LibraryStatus.rejected(message);
    }

    public NativeLibraryException(String libraryId, String message, Throwable cause) {
        super("native library '" + libraryId + "': " + message, cause);
        this.libraryId = libraryId;
        this.status = LibraryStatus.rejected(message);
    }

    public String libraryId() {
        return libraryId;
    }

    public LibraryStatus status() {
        return status;
    }
}
