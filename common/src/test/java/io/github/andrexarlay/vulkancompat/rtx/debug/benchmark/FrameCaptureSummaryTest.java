package io.github.andrexarlay.vulkancompat.rtx.debug.benchmark;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FrameCaptureSummaryTest {
    private static FrameCapture.Facts facts(int flags) {
        return new FrameCapture.Facts(flags, 1280, 720, 1920, 1080, "raster", "", 0, 0, 0, 0,
            "DLSS", "DLSS", "ACTIVE", "NRD");
    }

    @Test void computesWeightedFpsAndSlowestOnePercentWithoutGeneratedFrames() {
        var rows = new ArrayList<FrameCapture.Row>();
        for (int i = 0; i < 100; ++i)
            rows.add(new FrameCapture.Row(i, i == 99 ? 100 : 10, facts(FrameCapture.NATIVE_REQUIRED),
                new double[0], new double[0], false));
        rows.add(new FrameCapture.Row(101, 10000, facts(0), new double[0], new double[0], false));
        var summary = FrameCaptureSummary.summarize(new FrameCapture.Snapshot(true, "duration_elapsed", 0, 0,
            new String[0], rows));
        assertEquals(100 * 1000.0 / 1090, summary.get("average_fps").getAsDouble(), 0.00001);
        assertEquals(10, summary.get("one_percent_low_fps").getAsDouble());
        assertEquals(10, summary.get("p99_frame_ms").getAsDouble());
        assertEquals(100, summary.get("worst_frame_ms").getAsDouble());
        assertEquals(1, summary.get("excluded_frames").getAsInt());
    }

    @Test void emptyAndIncompleteCapturesDoNotFabricateFps() {
        var summary = FrameCaptureSummary.summarize(new FrameCapture.Snapshot(false, "world_unloaded", 0, 0,
            new String[0], List.of()));
        assertFalse(summary.has("average_fps"));
        assertFalse(summary.get("capture_complete").getAsBoolean());
        assertTrue(FrameCaptureSummary.describe(summary).contains("No eligible"));
    }

    @Test void gameplayWarmupWorksInRasterAndLegacyAcceptanceStillRequiresPath() {
        int flags = FrameCapture.WORLD | FrameCapture.SCREEN_CLOSED | FrameCapture.FOCUSED;
        var gameplay = new FrameCapture(new FrameCapture.Config(0, 10, 10, 5, flags), new String[]{"total"});
        gameplay.begin(0, 100); gameplay.end(0, facts(flags), new double[]{2});
        assertEquals(1, gameplay.size());
        var nativeTest = new FrameCapture(new FrameCapture.Config(0, 10, 10, 5), new String[]{"total"});
        nativeTest.begin(0, 100); nativeTest.end(0, facts(flags), new double[]{2});
        assertEquals(0, nativeTest.size());
    }
}
