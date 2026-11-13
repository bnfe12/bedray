package io.github.andrexarlay.bedray.compute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComputeDiagnosticsTest {
    @Test void percentileUsesNearestRankAndHandlesEmptyWindows() {
        long[] sorted = {10, 20, 30, 40};
        assertEquals(0, ComputeDiagnostics.percentile(sorted, 0, 0.50));
        assertEquals(20, ComputeDiagnostics.percentile(sorted, 4, 0.50));
        assertEquals(40, ComputeDiagnostics.percentile(sorted, 4, 0.95));
        assertEquals(40, ComputeDiagnostics.percentile(sorted, 4, 0.99));
        assertEquals(10, ComputeDiagnostics.percentile(sorted, 1, 0.99));
        assertEquals(40, ComputeDiagnostics.percentile(new long[]{40}, 1, 0.50));
    }

    @Test void snapshotAccumulatesThenResetsEveryField() {
        ComputeDiagnostics diagnostics = new ComputeDiagnostics();
        assertEquals("", diagnostics.summarizeAndReset(), "An empty window must not log");
        diagnostics.frame();
        diagnostics.frame();
        diagnostics.addUploadPrepNs(1_000_000);
        diagnostics.addUploadPrepNs(3_000_000);
        diagnostics.addRecordNs(2_000_000);
        diagnostics.addGpuSample(100_000, 800_000, 100_000);
        diagnostics.addGpuSample(300_000, 1_600_000, 100_000);
        diagnostics.addUpload(27, 100, 20, 300, 1_000_000);
        diagnostics.setScene(5, 4, "accumulating");
        diagnostics.addGpuCounters(new long[]{10, 1, 2, 3, 4, 5, 6, 7});
        diagnostics.addGpuCounters(new long[]{10, 1, 2, 3, 4, 5, 6, 7});
        ComputeDiagnostics.Snapshot snapshot = diagnostics.snapshotAndReset();
        assertEquals(2, snapshot.frames());
        assertEquals(4_000_000, snapshot.uploadPrepNs());
        assertEquals(2_000_000, snapshot.recordNs());
        assertEquals(2, snapshot.gpuSamples());
        assertEquals(400_000, snapshot.gpuUploadNs());
        assertEquals(2_400_000, snapshot.gpuDispatchNs());
        assertEquals(200_000, snapshot.gpuBlitNs());
        assertEquals(3_000_000, snapshot.gpuTotalNs());
        assertEquals(1_000_000, snapshot.p50Ns());
        assertEquals(2_000_000, snapshot.p95Ns());
        assertEquals(27, snapshot.sections());
        assertEquals(100, snapshot.cubes());
        assertEquals(20, snapshot.models());
        assertEquals(300, snapshot.quads());
        assertEquals(1_000_000, snapshot.bytes());
        assertEquals(5, snapshot.emitters());
        assertEquals(4, snapshot.emittersFound());
        assertEquals("accumulating", snapshot.history());
        assertArrayEquals(new long[]{20, 2, 4, 6, 8, 10, 12, 14}, snapshot.gpuCounters());
        assertEquals("", diagnostics.summarizeAndReset(), "Reset must clear frames, counters and history");
    }

    @Test void formatReportsUnavailableGpuAndOptionalCounters() {
        ComputeDiagnostics diagnostics = new ComputeDiagnostics();
        diagnostics.frame();
        diagnostics.addRecordNs(1_500_000);
        diagnostics.setScene(0, 0, "off");
        String withoutGpu = diagnostics.summarizeAndReset();
        assertTrue(withoutGpu.contains("frames=1"));
        assertTrue(withoutGpu.contains("record_ms=1.500"));
        assertTrue(withoutGpu.contains("gpu=unavailable"));
        assertFalse(withoutGpu.contains("hist_ok"));
        diagnostics.frame();
        diagnostics.addGpuSample(1_000_000, 2_000_000, 1_000_000);
        diagnostics.addGpuCounters(new long[]{7, 0, 0, 0, 0, 0, 0, 0});
        String withGpu = diagnostics.summarizeAndReset();
        assertTrue(withGpu.contains("dispatch_ms=2.000"));
        assertTrue(withGpu.contains("gpu_total_ms=4.000"));
        assertTrue(withGpu.contains("hist_ok=7"));
        assertFalse(withGpu.contains("gpu=unavailable"));
    }
}