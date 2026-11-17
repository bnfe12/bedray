package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayVolumetricNativeTest {
    @Test void configurationPacksNativeProgramsAndKeepsTheRetiredSlotZero() {
        try (Arena arena = Arena.ofConfined()) {
            var desc = BedrayVolumetricNative.configuration(arena, 12, 13, 1024, 2048);
            assertEquals(48, desc.byteSize()); assertEquals(2, desc.get(JAVA_INT, 0)); assertEquals(48, desc.get(JAVA_INT, 4));
            assertEquals(0, desc.get(JAVA_LONG, 8)); assertEquals(12, desc.get(JAVA_LONG, 16));
            assertEquals(13, desc.get(JAVA_LONG, 24)); assertEquals(1024, desc.get(JAVA_LONG, 32));
            assertEquals(2048, desc.get(JAVA_LONG, 40));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.configuration(arena, 0, 13, 1, 1));
        }
    }
    @Test void frameBorrowsExactSlicesAndPacksAllOffsetsWithoutMovingPositions() {
        var constants = ByteBuffer.allocateDirect(624).position(8).limit(616);
        var march = ByteBuffer.allocateDirect(128).position(8).limit(120);
        var apply = ByteBuffer.allocateDirect(48).position(8).limit(40);
        constants.put(8, (byte)71); march.put(8, (byte)72); apply.put(8, (byte)73);
        try (Arena arena = Arena.ofConfined()) {
            var desc = BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 64, 128, 51, 52, 53, 61, 256, 336,
                19, 9, 3, 17, constants, march, apply);
            assertEquals(168, desc.byteSize()); assertEquals(168, desc.get(JAVA_INT, 4));
            assertEquals(51, desc.get(JAVA_LONG, 120)); assertEquals(52, desc.get(JAVA_LONG, 128)); assertEquals(53, desc.get(JAVA_LONG, 136));
            assertEquals(61, desc.get(JAVA_LONG, 144)); assertEquals(256, desc.get(JAVA_LONG, 152)); assertEquals(336, desc.get(JAVA_LONG, 160));
            assertEquals(11, desc.get(JAVA_LONG, 8)); assertEquals(21, desc.get(JAVA_LONG, 16));
            assertEquals(22, desc.get(JAVA_LONG, 24)); assertEquals(31, desc.get(JAVA_LONG, 32));
            assertEquals(41, desc.get(JAVA_LONG, 40)); assertEquals(64, desc.get(JAVA_LONG, 48)); assertEquals(128, desc.get(JAVA_LONG, 56));
            assertEquals(19, desc.get(JAVA_INT, 64)); assertEquals(9, desc.get(JAVA_INT, 68));
            assertEquals(3, desc.get(JAVA_INT, 72)); assertEquals(17, desc.get(JAVA_INT, 76));
            assertEquals(608, desc.get(JAVA_INT, 88)); assertEquals(112, desc.get(JAVA_INT, 92));
            assertEquals(32, desc.get(JAVA_INT, 112)); assertEquals(0, desc.get(JAVA_INT, 116));
            assertEquals(71, desc.get(ADDRESS, 80).reinterpret(608).get(JAVA_BYTE, 0));
            assertEquals(72, desc.get(ADDRESS, 96).reinterpret(112).get(JAVA_BYTE, 0));
            assertEquals(73, desc.get(ADDRESS, 104).reinterpret(32).get(JAVA_BYTE, 0));
            assertEquals(8, constants.position()); assertEquals(8, march.position()); assertEquals(8, apply.position());
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 21, 31, 41, 0, 64, 5, 6, 7, 0, 0, 0, 1, 1, 1, 0, constants, march, apply));
            // the atlases are required, an emitter list is optional but must be a whole number of 16-byte records
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 64, 0, 6, 7, 0, 0, 0, 1, 1, 1, 0, constants, march, apply));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 64, 5, 0, 7, 0, 0, 0, 1, 1, 1, 0, constants, march, apply));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 64, 5, 6, 0, 0, 0, 0, 1, 1, 1, 0, constants, march, apply));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 64, 5, 6, 7, 9, 16, 0, 1, 1, 1, 0, constants, march, apply));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 64, 5, 6, 7, 9, 16, 24, 1, 1, 1, 0, constants, march, apply));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 64, 5, 6, 7, 0, 16, 32, 1, 1, 1, 0, constants, march, apply));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 63, 5, 6, 7, 0, 0, 0, 1, 1, 1, 0, constants, march, apply));
            assertThrows(IllegalArgumentException.class, () -> BedrayVolumetricNative.frame(arena, 11, 21, 22, 31, 41, 0, 64, 5, 6, 7, 0, 0, 0, 1, 1, 1, 0, ByteBuffer.allocate(608), march, apply));
        }
    }
}
