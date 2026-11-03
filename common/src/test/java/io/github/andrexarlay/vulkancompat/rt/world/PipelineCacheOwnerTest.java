package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PipelineCacheOwnerTest {
    @Test void capturedBorrowIsRefusedAfterCloseAndCloseIsIdempotent() {
        var owner = new PipelineCacheOwner(17, null); var retires = new AtomicInteger();
        assertEquals(18L, owner.use(handle -> handle + 1).longValue());
        owner.close(handle -> { assertEquals(17, handle); retires.incrementAndGet(); });
        owner.close(handle -> fail("double retirement"));
        assertEquals(1, retires.get());
        assertThrows(IllegalStateException.class, () -> owner.use(handle -> fail("retired cache")));
    }

    @Test void failedRegistrationReleasesItsLockAndKeepsCacheUsable() {
        var owner = new PipelineCacheOwner(17, null);
        var original = new IllegalStateException("driver registration failed");
        assertSame(original, assertThrows(IllegalStateException.class, () -> owner.use(handle -> { throw original; })));
        assertEquals(17L, owner.use(handle -> handle).longValue());
        owner.close(handle -> assertEquals(17, handle));
    }

    @Test void failedRetirementRetainsHandleButBlocksNewBorrowUntilRetry() {
        var owner = new PipelineCacheOwner(17, null);
        assertThrows(IllegalStateException.class, () -> owner.close(handle -> { throw new IllegalStateException("save failed"); }));
        assertFalse(owner.isClosed());
        assertThrows(IllegalStateException.class, () -> owner.use(handle -> fail("closing")));
        owner.close(handle -> assertEquals(17, handle));
        assertTrue(owner.isClosed());
    }

    @Test void reentrantCloseCannotDestroyAHandleInsideItsNativeCall() {
        var owner = new PipelineCacheOwner(17, null);
        owner.use(handle -> {
            assertThrows(IllegalStateException.class, () -> owner.close(cache -> fail("inside registration")));
            return null;
        });
        owner.close(handle -> {});
    }

    @Test void closeWaitsForTheWholeNativeBorrowWithoutGpuSubmission() throws Exception {
        var owner = new PipelineCacheOwner(17, null);
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1); var retires = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var registration = workers.submit(() -> owner.use(handle -> {
                entered.countDown();
                try { assertTrue(finish.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                assertEquals(0, retires.get());
                return handle;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = workers.submit(() -> { closeStarted.countDown(); owner.close(handle -> retires.incrementAndGet()); });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            finish.countDown();
            assertEquals(17L, registration.get(5, TimeUnit.SECONDS).longValue());
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, retires.get());
            assertThrows(IllegalStateException.class, () -> owner.use(handle -> null));
        }
    }
}
