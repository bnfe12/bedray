package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayBloomNativeTest {
    @Test void configurationMatchesTheAdditiveNativeLayoutAndCopiesProgramHandles() {
        try (Arena arena = Arena.ofConfined()) {
            long[] programs = {11,12,13,14};
            var desc = BedrayBloomNative.configuration(arena, programs, 21, 22, 4096);
            programs[0] = 99;
            assertEquals(64, desc.byteSize());
            assertEquals(2, desc.get(JAVA_INT, 0)); assertEquals(64, desc.get(JAVA_INT, 4));
            for (int i = 0; i < 4; i++) assertEquals(11 + i, desc.get(JAVA_LONG, 8 + 8L * i));
            assertEquals(21, desc.get(JAVA_LONG, 40)); assertEquals(22, desc.get(JAVA_LONG, 48));
            assertEquals(4096, desc.get(JAVA_LONG, 56));
        }
    }

    @Test void frameBorrowsExactlyTheDirectPushSliceWithoutChangingItsPosition() {
        ByteBuffer push = ByteBuffer.allocateDirect(144);
        for (int i = 0; i < push.capacity(); i++) push.put(i, (byte)i);
        push.position(8).limit(136);
        try (Arena arena = Arena.ofConfined()) {
            var desc = BedrayBloomNative.frame(arena, 100, 19, 11, 200, 201, push, true);
            assertEquals(56, desc.byteSize());
            assertEquals(2, desc.get(JAVA_INT, 0)); assertEquals(56, desc.get(JAVA_INT, 4));
            assertEquals(100, desc.get(JAVA_LONG, 8));
            assertEquals(19, desc.get(JAVA_INT, 16)); assertEquals(11, desc.get(JAVA_INT, 20));
            assertEquals(200, desc.get(JAVA_LONG, 24)); assertEquals(201, desc.get(JAVA_LONG, 32));
            assertEquals(1, desc.get(JAVA_INT, 40)); assertEquals(128, desc.get(JAVA_INT, 44));
            MemorySegment bytes = desc.get(ADDRESS, 48).reinterpret(128);
            for (int i = 0; i < 128; i++) assertEquals((byte)(i + 8), bytes.get(JAVA_BYTE, i));
            assertEquals(8, push.position()); assertEquals(136, push.limit());
            var glare = BedrayBloomNative.frame(arena, 101, 19, 11, 200, 201, push, false);
            assertEquals(0, glare.get(JAVA_INT, 40));
        }
    }

    @Test void invalidInputsFailBeforeAnyDowncall() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(IllegalArgumentException.class, () -> BedrayBloomNative.configuration(arena, new long[4], 1, 2, 1024));
            assertThrows(IllegalArgumentException.class, () -> BedrayBloomNative.configuration(arena, new long[]{1,2,3,4}, 1, 2, 0));
            assertThrows(IllegalArgumentException.class, () -> BedrayBloomNative.frame(arena, 1, 1, 1, 2, 3, ByteBuffer.allocate(128), true));
            assertThrows(IllegalArgumentException.class, () -> BedrayBloomNative.frame(arena, 1, 1, 1, 2, 3, ByteBuffer.allocateDirect(127), true));
            assertThrows(IllegalArgumentException.class, () -> BedrayBloomNative.frame(arena, 1, 0, 1, 2, 3, ByteBuffer.allocateDirect(128), true));
        }
    }
}
