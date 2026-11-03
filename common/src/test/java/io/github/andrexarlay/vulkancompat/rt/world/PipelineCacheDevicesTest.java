package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PipelineCacheDevicesTest {
    @Test void oneOwnerPerActualDeviceAndNoResurrectionAfterDeviceClose() {
        var registry = new PipelineCacheDevices<Object>(); var device = new Object(); var creates = new AtomicInteger();
        var first = registry.forUse(device, 9, owner -> { creates.incrementAndGet(); owner.initialized(17); });
        assertSame(first, registry.forUse(device, 9, owner -> fail("duplicate")));
        assertSame(first, registry.forClose(device, 9));
        first.close(handle -> assertEquals(17, handle));
        var stale = registry.forUse(device, 9, owner -> fail("closed device reopened"));
        assertThrows(IllegalStateException.class, () -> stale.use(handle -> null));
        assertEquals(1, creates.get());
    }

    @Test void closeBeforeFirstRegistrationAlsoPreventsResurrection() {
        var registry = new PipelineCacheDevices<Object>(); var device = new Object();
        registry.forClose(device, 9).close(handle -> assertEquals(0, handle));
        assertThrows(IllegalStateException.class, () -> registry.forUse(device, 9, owner -> fail("closing device")).use(handle -> null));
    }

    @Test void numericalDeviceAddressCanBeReusedOnlyAfterOldOwnerRetires() {
        var registry = new PipelineCacheDevices<Object>(); var first = new Object(); var second = new Object();
        var old = registry.forUse(first, 9, owner -> owner.initialized(17));
        assertThrows(IllegalStateException.class, () -> registry.forUse(second, 9, owner -> fail("old device live")));
        old.close(handle -> {});
        var next = registry.forUse(second, 9, owner -> owner.initialized(29));
        assertNotSame(old, next); assertEquals(29L, next.use(handle -> handle).longValue());
    }

    @Test void initializationFailureAfterNativeAllocationPreservesTheOwnerForClose() {
        var registry = new PipelineCacheDevices<Object>(); var device = new Object();
        assertThrows(OutOfMemoryError.class, () -> registry.forUse(device, 9, owner -> {
            owner.initialized(17); throw new OutOfMemoryError("after native acceptance");
        }));
        var retained = registry.forClose(device, 9);
        assertThrows(IllegalStateException.class, () -> retained.use(handle -> null));
        retained.close(handle -> assertEquals(17, handle));
        assertTrue(retained.isClosed());
    }

    @Test void addressReuseCannotForgetAnyStillReachableRetiredDeviceIdentity() {
        var registry = new PipelineCacheDevices<Object>(); var first = new Object(); var second = new Object();
        var old = registry.forUse(first, 9, owner -> owner.initialized(17)); old.close(handle -> {});
        var next = registry.forUse(second, 9, owner -> owner.initialized(29)); next.close(handle -> {});
        assertSame(old, registry.forUse(first, 9, owner -> fail("retired first device resurrected")));
        assertThrows(IllegalStateException.class, () -> old.use(handle -> fail("destroyed device")));
        assertSame(next, registry.forUse(second, 9, owner -> fail("retired second device resurrected")));
    }

    @Test void staleCloseOfOldWrapperCannotReplaceTheNewDeviceTombstone() {
        var registry = new PipelineCacheDevices<Object>(); var first = new Object(); var second = new Object();
        var old = registry.forUse(first, 9, owner -> owner.initialized(17)); old.close(handle -> {});
        var next = registry.forUse(second, 9, owner -> owner.initialized(29));
        registry.forClose(first, 9).close(handle -> fail("old cache freed twice"));
        assertEquals(29L, next.use(handle -> handle).longValue());
        next.close(handle -> {});
        registry.forClose(first, 9).close(handle -> fail("stale close after second retirement"));
        assertSame(next, registry.forUse(second, 9, owner -> fail("stale close erased the second tombstone")));
        assertThrows(IllegalStateException.class, () -> next.use(handle -> fail("destroyed second device")));
    }
}
