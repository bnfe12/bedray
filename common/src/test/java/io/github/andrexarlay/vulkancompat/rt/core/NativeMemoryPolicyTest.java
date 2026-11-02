package io.github.andrexarlay.vulkancompat.rt.core;
import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import static org.junit.jupiter.api.Assertions.*;
class NativeMemoryPolicyTest {
    @Test void budgetUsesOnlyAThirdOfKnownLocalMemoryWithBoundedDefaults() {
        long mib = 1L << 20;
        assertEquals(2730 * mib, NativeMemoryPolicy.defaults(8L << 30).limitBytes());
        assertEquals(3072 * mib, NativeMemoryPolicy.defaults(24L << 30).limitBytes());
        assertEquals(256 * mib, NativeMemoryPolicy.defaults(0).limitBytes());
        assertEquals(32 * mib, NativeMemoryPolicy.defaults(8L << 30).blockBytes());
    }
    @Test void invalidBlockAndUnboundedBudgetsFailBeforeNativeCalls() {
        long mib = 1L << 20;
        assertThrows(IllegalArgumentException.class, () -> new NativeMemoryPolicy(32 * mib, mib));
        assertThrows(IllegalArgumentException.class, () -> new NativeMemoryPolicy(256 * mib, 3 * mib));
        assertThrows(IllegalArgumentException.class, () -> new NativeMemoryPolicy(128 * mib, 256 * mib));
        assertThrows(IllegalArgumentException.class, () -> new NativeMemoryPolicy(Long.MAX_VALUE, mib));
    }
    @Test void statsLayoutKeepsCountersAndUnavailableBudgetDistinct() {
        try (Arena arena = Arena.ofConfined()) {
            var data = arena.allocate(72, 8);
            for (int i = 0; i < 8; ++i) data.set(ValueLayout.JAVA_LONG, i * 8L, i + 10);
            var stats = BedrayMemoryNative.decode(data);
            assertEquals(10, stats.limit()); assertEquals(11, stats.reserved()); assertEquals(12, stats.used());
            assertEquals(14, stats.allocations()); assertEquals(15, stats.blocks());
            assertFalse(stats.budgetReported()); assertTrue(stats.logValues().contains("heap_budget_bytes=unavailable"));
            data.set(ValueLayout.JAVA_LONG, 64, 3);
            stats = BedrayMemoryNative.decode(data);
            assertTrue(stats.budgetReported()); assertTrue(stats.deviceLost());
            assertEquals(16, stats.heapBudget()); assertEquals(17, stats.heapUsage());
        }
    }
}
