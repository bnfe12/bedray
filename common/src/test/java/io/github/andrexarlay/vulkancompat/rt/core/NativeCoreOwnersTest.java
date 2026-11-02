package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeCoreOwnersTest {
    @Test void preNativeFailurePublishesNoHandleAndNeedsNoDestroy() {
        var owners = new NativeCoreOwners(); var reservation = owners.reserve();
        owners.failed(reservation);
        owners.destroyAll(handle -> fail("native creation never succeeded"));
        assertThrows(IllegalArgumentException.class, () -> owners.require(17));
    }

    @Test void postAcceptanceAllocationFailureCannotHideTheCoreFromLibraryClose() {
        var owners = new NativeCoreOwners(); var reservation = owners.reserve();
        var original = new OutOfMemoryError("post-native Java allocation");
        assertSame(original, assertThrows(OutOfMemoryError.class, () -> {
            reservation.accepted(17); throw original;
        }));
        owners.failed(reservation);
        assertSame(reservation, owners.require(17));
        var calls = new AtomicInteger();
        owners.destroyAll(handle -> { assertEquals(17, handle); calls.incrementAndGet(); owners.released(reservation); });
        owners.destroyAll(handle -> fail("core destroyed twice"));
        assertEquals(1, calls.get());
    }

    @Test void failedNativeDestroyRetainsTheOwnerAndNumericalAddressReuseGetsANewReservation() {
        var owners = new NativeCoreOwners(); var first = owners.reserve(); first.accepted(17);
        assertThrows(IllegalStateException.class, () -> owners.destroyAll(handle -> { throw new IllegalStateException("IN_USE"); }));
        assertSame(first, owners.require(17));
        owners.released(first);
        var second = owners.reserve(); second.accepted(17);
        assertSame(second, owners.require(17)); assertNotSame(first, second);
        owners.released(second);
    }

    @Test void aFailureAfterAcknowledgedDestructionCannotRepeatNativeDestroy() {
        var owners = new NativeCoreOwners(); var reservation = owners.reserve(); reservation.accepted(17);
        assertThrows(OutOfMemoryError.class, () -> {
            owners.released(reservation); throw new OutOfMemoryError("post-destroy Java work");
        });
        owners.destroyAll(handle -> fail("already destroyed"));
    }

    @Test void ffiReservesBeforeCreateAndAcknowledgesBothNativeMilestonesBeforePostCallWork() throws Exception {
        String ffi = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java"));
        int create = ffi.indexOf("public long create(");
        int reserve = ffi.indexOf("liveCores.reserve()", create);
        int call = ffi.indexOf("createHandle.invokeExact(", reserve);
        int accepted = ffi.indexOf("creation.accepted(out.get(JAVA_LONG, 0))", call);
        assertTrue(create >= 0 && reserve > create && call > reserve && accepted > call);
        assertTrue(accepted < ffi.indexOf("check(\"create\", status, error)", call));
        assertTrue(ffi.contains("liveCores.failed(creation);"));
        int destroy = ffi.indexOf("public void destroy(long core)");
        int owner = ffi.indexOf("liveCores.require(core)", destroy);
        int destroyCall = ffi.indexOf("destroyHandle.invokeExact(", destroy);
        assertTrue(owner > destroy && owner < destroyCall);
        int released = ffi.indexOf("liveCores.released(owner)", destroyCall);
        assertTrue(released > destroyCall && released < ffi.indexOf("check(\"destroy\", status", destroyCall));
        assertTrue(ffi.contains("liveCores.destroyAll(this::destroy)"));
    }
}
