package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedraySodiumNativeTest {
    @Test void frameMatchesAdditiveLayoutAndKeepsOptionalFlagsSeparate() {
        try (Arena arena = Arena.ofConfined()) {
            var frame = BedraySodiumNative.frame(arena, 11, 17, 0x1000, 0x10000, 0x20000, 129);
            assertEquals(56, frame.byteSize()); assertEquals(2, frame.get(JAVA_INT, 0)); assertEquals(56, frame.get(JAVA_INT, 4));
            assertEquals(11, frame.get(JAVA_LONG, 8)); assertEquals(17, frame.get(JAVA_LONG, 16));
            assertEquals(0x1000, frame.get(JAVA_LONG, 24)); assertEquals(0x10000, frame.get(JAVA_LONG, 32));
            assertEquals(0x20000, frame.get(JAVA_LONG, 40)); assertEquals(129, frame.get(JAVA_INT, 48));
            assertEquals(0, frame.get(JAVA_INT, 52));
            assertEquals(0, BedraySodiumNative.frame(arena, 11, 17, 0x1000, 0x10000, 0, 1).get(JAVA_LONG, 40));
        }
    }
    @Test void rangesAreUnsignedAlignedNonwrappingAndDisjointBeforeAnyDowncall() {
        try (Arena arena = Arena.ofConfined()) {
            for (long[] addresses : new long[][]{{0,0x10000,0},{0x1000,0,0},{0x1001,0x10000,0},
                {0x1000,0x10000,0x20001},{0x1000,0x1000,0},{0x1000,0x1040,0},
                {0x1000,0x10000,0x1000},{0x1000,0x10000,0x10040},
                {-4L,0x10000,0},{0x1000,-4L,0},{0x1000,0x10000,-4L}})
                assertThrows(IllegalArgumentException.class,
                    () -> BedraySodiumNative.frame(arena, 11, 17, addresses[0], addresses[1], addresses[2], 2));
            // Adjacent input/flag ranges are the actual staging layout and must remain valid.
            assertDoesNotThrow(() -> BedraySodiumNative.frame(arena, 11, 17, 0x1000, 0x10000, 0x10a0, 2));
            // High-bit addresses are valid uint64 device addresses, not negative Java offsets.
            assertDoesNotThrow(() -> BedraySodiumNative.frame(arena, 11, 17, Long.MIN_VALUE, 0x10000, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> BedraySodiumNative.frame(arena, 0, 17, 0x1000, 0x10000, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> BedraySodiumNative.frame(arena, 11, 0, 0x1000, 0x10000, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> BedraySodiumNative.frame(arena, 11, 17, 0x1000, 0x10000, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> BedraySodiumNative.frame(arena, 11, 17, 0x1000, 0x10000, 0, Integer.MAX_VALUE / 28 + 1));
        }
    }
}
