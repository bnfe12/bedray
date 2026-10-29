package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayVelocityNativeTest {
    @Test void configurationBorrowsOneAotProgramAndNearestSampler() {
        try (Arena arena = Arena.ofConfined()) {
            var desc = BedrayVelocityNative.configuration(arena, 11, 12, 1024);
            assertEquals(32, desc.byteSize()); assertEquals(2, desc.get(JAVA_INT, 0));
            assertEquals(32, desc.get(JAVA_INT, 4)); assertEquals(11, desc.get(JAVA_LONG, 8));
            assertEquals(12, desc.get(JAVA_LONG, 16)); assertEquals(1024, desc.get(JAVA_LONG, 24));
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.configuration(arena, 0, 12, 1024));
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.configuration(arena, 11, 0, 1024));
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.configuration(arena, 11, 12, 0));
        }
    }
    @Test void frameBorrowsTheExactPayloadSliceAndPacksOffsets() {
        var uniforms = ByteBuffer.allocateDirect(384).position(8).limit(376);
        uniforms.put(8, (byte)71);
        try (Arena arena = Arena.ofConfined()) {
            var desc = BedrayVelocityNative.frame(arena, 11, 21, 22, 19, 9, uniforms);
            assertEquals(56, desc.byteSize()); assertEquals(56, desc.get(JAVA_INT, 4));
            assertEquals(11, desc.get(JAVA_LONG, 8)); assertEquals(21, desc.get(JAVA_LONG, 16));
            assertEquals(22, desc.get(JAVA_LONG, 24)); assertEquals(19, desc.get(JAVA_INT, 32));
            assertEquals(9, desc.get(JAVA_INT, 36)); assertEquals(368, desc.get(JAVA_INT, 48));
            assertEquals(0, desc.get(JAVA_INT, 52));
            assertEquals(71, desc.get(ADDRESS, 40).reinterpret(368).get(JAVA_BYTE, 0));
            assertEquals(8, uniforms.position()); assertEquals(376, uniforms.limit());
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.frame(arena, 0, 21, 22, 1, 1, uniforms));
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.frame(arena, 11, 21, 21, 1, 1, uniforms));
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.frame(arena, 11, 21, 22, 65535, 65535, uniforms));
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.frame(arena, 11, 21, 22, 1, 1, ByteBuffer.allocate(368)));
            assertThrows(IllegalArgumentException.class, () -> BedrayVelocityNative.frame(arena, 11, 21, 22, 1, 1, ByteBuffer.allocateDirect(367)));
        }
    }
}
