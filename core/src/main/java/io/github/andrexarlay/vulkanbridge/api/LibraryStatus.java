package io.github.andrexarlay.vulkanbridge.api;

/**
 * State of one library as seen by the core.
 *
 * @param state  see {@link State}
 * @param detail human readable reason or note, never null; it says why a file is missing, rejected or not verified
 */
@Experimental
public record LibraryStatus(State state, String detail) {
    public enum State {
        /** At least one required file is not in any searched directory. */
        MISSING,
        /** Files exist and pass name, size and PE checks (x64 DLL, version) but their SHA-256 is not in the known list. */
        PRESENT_UNVERIFIED,
        /** As above and every installed file's SHA-256 matches a known-good value of the descriptor. */
        VERIFIED,
        /** A file failed a check (wrong architecture, not a PE, too small/large, version too old). Not loadable. */
        REJECTED
    }

    public LibraryStatus {
        if (state == null || detail == null) {
            throw new IllegalArgumentException("state and detail are required");
        }
    }

    /** True when {@link NativeLibraries#resolve} hands out paths for this status. */
    public boolean usable() {
        return state == State.PRESENT_UNVERIFIED || state == State.VERIFIED;
    }

    public static LibraryStatus missing(String detail) {
        return new LibraryStatus(State.MISSING, detail);
    }

    public static LibraryStatus presentUnverified(String detail) {
        return new LibraryStatus(State.PRESENT_UNVERIFIED, detail);
    }

    public static LibraryStatus verified(String detail) {
        return new LibraryStatus(State.VERIFIED, detail);
    }

    public static LibraryStatus rejected(String reason) {
        return new LibraryStatus(State.REJECTED, reason);
    }
}
