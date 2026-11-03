package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeShutdownTest {
    @Test void pendingRecordingRefusalLeavesProgramsAndLibraryUntouchedUntilRetry() {
        var shutdown = new RuntimeShutdown();
        List<String> actions = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> shutdown.close(
            () -> { throw new IllegalStateException("pending recording"); },
            () -> actions.add("programs"), () -> actions.add("library")));
        assertTrue(shutdown.isOpen()); assertFalse(shutdown.isClosed()); assertTrue(actions.isEmpty());
        shutdown.close(() -> actions.add("core"), () -> actions.add("programs"), () -> actions.add("library"));
        assertEquals(List.of("core", "programs", "library"), actions);
        assertTrue(shutdown.isClosed()); assertFalse(shutdown.isOpen());
        shutdown.close(() -> fail("core destroyed twice"), () -> fail("programs invalidated twice"), () -> fail("unloaded twice"));
    }
    @Test void failedLibraryUnloadForbidsUseAndRetryNeverDestroysTheCoreTwice() {
        var shutdown = new RuntimeShutdown();
        int[] destroys = {0}, invalidations = {0};
        assertThrows(IllegalStateException.class, () -> shutdown.close(() -> destroys[0]++, () -> invalidations[0]++,
            () -> { throw new IllegalStateException("unload retry"); }));
        assertFalse(shutdown.isOpen()); assertFalse(shutdown.isClosed());
        shutdown.close(() -> fail("core destroyed twice"), () -> invalidations[0]++, () -> {});
        assertEquals(1, destroys[0]); assertEquals(2, invalidations[0]); assertTrue(shutdown.isClosed());
    }
}
