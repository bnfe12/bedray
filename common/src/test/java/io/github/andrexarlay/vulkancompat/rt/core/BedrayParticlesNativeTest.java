package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayParticlesNativeTest {
    @Test void packetMatchesTheAdditiveAbiWithoutAJavaDescriptorOrPushArray() {
        try (Arena arena = Arena.ofConfined()) {
            var frame = BedrayParticlesNative.frame(arena, 11, 17, 21, 22, 19, 9, 0.75f);
            assertEquals(56, frame.byteSize());
            assertEquals(2, frame.get(JAVA_INT, 0)); assertEquals(56, frame.get(JAVA_INT, 4));
            assertEquals(11, frame.get(JAVA_LONG, 8)); assertEquals(17, frame.get(JAVA_LONG, 16));
            assertEquals(21, frame.get(JAVA_LONG, 24)); assertEquals(22, frame.get(JAVA_LONG, 32));
            assertEquals(19, frame.get(JAVA_INT, 40)); assertEquals(9, frame.get(JAVA_INT, 44));
            assertEquals(0.75f, frame.get(JAVA_FLOAT, 48)); assertEquals(0, frame.get(JAVA_INT, 52));
            assertEquals(0f, BedrayParticlesNative.frame(arena, 11, 17, 21, 22, 1, 1, 0).get(JAVA_FLOAT, 48));
        }
    }
    @Test void invalidInputsFailBeforeAnyDowncall() {
        try (Arena arena = Arena.ofConfined()) {
            for (long[] handles : new long[][]{{0,17,21,22},{11,0,21,22},{11,17,0,22},{11,17,21,0},{11,17,21,21}})
                assertThrows(IllegalArgumentException.class,
                    () -> BedrayParticlesNative.frame(arena, handles[0], handles[1], handles[2], handles[3], 19, 9, 1));
            for (float strength : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
                assertThrows(IllegalArgumentException.class, () -> BedrayParticlesNative.frame(arena, 11, 17, 21, 22, 19, 9, strength));
            assertThrows(IllegalArgumentException.class, () -> BedrayParticlesNative.frame(arena, 11, 17, 21, 22, 0, 9, 1));
            assertThrows(IllegalArgumentException.class, () -> BedrayParticlesNative.frame(arena, 11, 17, 21, 22, 19, -1, 1));
        }
    }
}
