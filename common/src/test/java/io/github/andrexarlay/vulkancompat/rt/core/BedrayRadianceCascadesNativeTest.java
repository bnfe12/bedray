package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrayRadianceCascadesNativeTest {
    @Test void descriptorMatchesEveryAdditiveNativeOffsetForAllSupportedAxesAndBudgets() {
        try (Arena arena = Arena.ofConfined()) {
            int descriptors = 0;
            for (int axis : new int[] {8, 16, 32}) for (long budget : new long[] {0, 1, 192L << 20, Long.MAX_VALUE}) {
                var desc = BedrayRadianceCascadesNative.descriptor(arena, 17, axis, budget);
                assertEquals(32, desc.byteSize());
                assertEquals(BedrayCoreLayouts.ABI_VERSION, desc.get(JAVA_INT, 0));
                assertEquals(32, desc.get(JAVA_INT, 4));
                assertEquals(17, desc.get(JAVA_LONG, 8));
                assertEquals(axis, desc.get(JAVA_INT, 16));
                assertEquals(0, desc.get(JAVA_INT, 20), "reserved field is always zero");
                assertEquals(budget, desc.get(JAVA_LONG, 24));
                descriptors++;
            }
            assertEquals(12, descriptors);
            var highHandle = BedrayRadianceCascadesNative.descriptor(arena, Long.MIN_VALUE, 8, 0);
            assertEquals(Long.MIN_VALUE, highHandle.get(JAVA_LONG, 8), "nonzero native handles are uint64 identities");
        }
    }

    @Test void descriptorCopiesConfigurationRatherThanSharingMutablePacketStorage() {
        try (Arena arena = Arena.ofConfined()) {
            var first = BedrayRadianceCascadesNative.descriptor(arena, 17, 8, 0);
            var second = BedrayRadianceCascadesNative.descriptor(arena, 23, 32, 192L << 20);
            assertNotEquals(first.address(), second.address());
            assertEquals(17, first.get(JAVA_LONG, 8)); assertEquals(8, first.get(JAVA_INT, 16));
            assertEquals(0, first.get(JAVA_LONG, 24));
            assertEquals(23, second.get(JAVA_LONG, 8)); assertEquals(32, second.get(JAVA_INT, 16));
        }
    }

    @Test void invalidAxisBuilderAndBudgetFailBeforeAnyNativeLookupOrDowncall() {
        try (Arena arena = Arena.ofConfined()) {
            for (int axis : new int[] {Integer.MIN_VALUE, -32, -1, 0, 4, 7, 9, 15, 24, 31, 33, 64, Integer.MAX_VALUE})
                assertThrows(IllegalArgumentException.class, () -> BedrayRadianceCascadesNative.descriptor(arena, 17, axis, 0));
            assertThrows(IllegalArgumentException.class, () -> BedrayRadianceCascadesNative.descriptor(arena, 0, 8, 0));
            assertThrows(IllegalArgumentException.class, () -> BedrayRadianceCascadesNative.descriptor(arena, 17, 8, -1));
            assertThrows(IllegalArgumentException.class, () -> BedrayRadianceCascadesNative.descriptor(arena, 17, 8, Long.MIN_VALUE));
            assertThrows(IllegalArgumentException.class, () -> new BedrayRadianceCascadesNative(null, 11, 17, 8));
        }
    }
}
