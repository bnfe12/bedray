package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reservoir rings fit their owner's budget at the extents players use. The owner was capped at 1 GiB, and at
 * 2560x1370 the DI ring (428 MiB) plus the PT ring (963 MiB) need 1.36 GiB: the first frame after maximising the window
 * failed "native buffer create failed: status=7" (BUDGET_EXCEEDED) and switched RT off for the session
 * (26.3(1)/latest.log, 2026-11-01 01:09:26). The budget is now a quarter of device memory, and a PT ring that still does
 * not fit is left out with a log line instead of taking the whole renderer down.
 */
class RtReservoirBudgetTest {
    private static final Path BACKEND = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "ReferencePathTraceBackend.java");
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private static long diBytes(int w, int h) {
        return (long) w * h * 2L * RtPathTraceSlangPass.RESERVOIR_BYTES;
    }

    @Test
    void theBudgetIsAQuarterOfDeviceMemory() {
        assertEquals(4L * GIB, ReferencePathTraceBackend.bufferBudget(16L * GIB), "RTX 5060 Ti 16 GB");
        assertEquals(2L * GIB, ReferencePathTraceBackend.bufferBudget(8L * GIB));
        assertEquals(512L * MIB, ReferencePathTraceBackend.bufferBudget(0L), "unknown device memory");
    }

    @Test
    void bothRingsFitAt1440pAndEvenAt4kOnSixteenGigabytes() {
        long budget = ReferencePathTraceBackend.bufferBudget(16L * GIB);
        for (int[] extent : new int[][] {{2560, 1370}, {2560, 1440}, {3840, 2160}}) {
            long pt = RtPathTraceSlangPass.ptReservoirBytes(extent[0], extent[1]);
            long di = diBytes(extent[0], extent[1]);
            assertTrue(ReferencePathTraceBackend.ptRingFits(pt, di, budget), extent[0] + "x" + extent[1]);
        }
        long pt = RtPathTraceSlangPass.ptReservoirBytes(2560, 1370);
        assertFalse(ReferencePathTraceBackend.ptRingFits(pt, diBytes(2560, 1370), GIB), "the old 1 GiB cap");
    }

    @Test
    void aRingThatDoesNotFitIsLoggedAndLeftOut() throws IOException {
        String backend = Files.readString(BACKEND).replace("\r\n", "\n");
        assertFalse(backend.contains("Math.min(1L << 30, bytes / 4)"), "no 1 GiB cap on the buffer owner");
        assertTrue(backend.contains("runtime.createBufferOwner(bufferBudget(bytes))"));
        int prepare = backend.indexOf("private void preparePathReservoirs(");
        int fits = backend.indexOf("if (!ptRingFits(need, diBytes, bufferBudget)) {", prepare);
        int create = backend.indexOf("nativeBuffers.createDeviceLocal(need,", prepare);
        assertTrue(prepare > 0 && fits > prepare && fits < create, "checked before the allocation that would fail");
        assertTrue(backend.contains("[VKB-RT] restir_pt=off reason=budget"), "the player's log says why PT is off");
    }
}
