package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native backend runs ReSTIR PT and measures the F3 split. Until 2026-11-01 the core bound a one-record stand-in at
 * 36 and a one-word stand-in at 37, so -Dvkbridge.rt.nativePathTrace=true traced without path reuse and printed the
 * reject split as n/a. The core now owns the PT ring (NATIVE_PT, its own history and parity, the lent spatial stage)
 * and takes the frame's StatsBuf slot through render_path_trace_ext; this host sends the bits and the slot.
 */
class NativePathReuseTest {
    private static final Path HEADER = Path.of("../native/bedray_core/include/bedray_core.h");
    private static final Path BACKEND = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "NativePathTraceBackend.java");

    @Test
    void theStatsSlotIsTheWholeFrameSlot() {
        try (Arena arena = Arena.ofConfined()) {
            var out = arena.allocate(BedrayCoreLayouts.PT_BUFFER);
            BedrayPathTraceNative.fillStatsSlot(out, new RtPathTraceSlangPass.BufferView(77, 1152, 4096));
            assertEquals(77, out.get(JAVA_LONG, 0));
            assertEquals(1152, out.get(JAVA_LONG, 8));
            assertEquals(RtStatsLayout.SLOT_STRIDE, out.get(JAVA_LONG, 16), "exactly one slot, not the rest of the ring");
            assertEquals(0, out.get(JAVA_INT, 24));
            assertEquals(0, out.get(JAVA_INT, 28));
            assertThrows(IllegalArgumentException.class, () -> BedrayPathTraceNative.fillStatsSlot(out,
                new RtPathTraceSlangPass.BufferView(77, 0, RtStatsLayout.SLOT_STRIDE - 1)));
            assertThrows(IllegalArgumentException.class, () -> BedrayPathTraceNative.fillStatsSlot(out,
                new RtPathTraceSlangPass.BufferView(0, 0, RtStatsLayout.SLOT_STRIDE)));
        }
    }

    @Test
    void theHostBitsAreTheHeaderBits() throws IOException {
        String header = Files.readString(HEADER);
        assertEquals(define(header, "BEDRAY_PATH_TRACE_FRAME_NATIVE_PT"), BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_PT);
        assertEquals(define(header, "BEDRAY_PATH_TRACE_FRAME_RESET_PT"), BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_PT);
        assertEquals(define(header, "BEDRAY_PATH_TRACE_FRAME_PT_SPATIAL"), BedrayCoreLayouts.PATH_TRACE_FRAME_PT_SPATIAL);
        assertEquals(define(header, "BEDRAY_PATH_TRACE_FEATURE_NATIVE_PT"), BedrayCoreLayouts.PATH_TRACE_FEATURE_NATIVE_PT);
        assertEquals(define(header, "BEDRAY_PATH_TRACE_FEATURE_STATS_SLOT"), BedrayCoreLayouts.PATH_TRACE_FEATURE_STATS_SLOT);
    }

    @Test
    void theBackendLeavesHistoryAndParityToTheCore() throws IOException {
        String backend = Files.readString(BACKEND).replace("\r\n", "\n");
        assertTrue(backend.contains("& ~(RtPathTraceSlangPass.PT_HISTORY | RtPathTraceSlangPass.PT_PARITY)"),
            "a stale host mirror never reaches the push of a core-owned ring");
        assertTrue(backend.contains("int flags = BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_PT;"));
        assertTrue(backend.contains("if (frame.ptReset()) flags |= BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_PT;"));
        assertTrue(backend.contains("&& pass.ptSpatialFor(nativeScene))"),
            "the spatial stage only for a table the core has it for, or the core refuses the frame");
        assertTrue(backend.contains("@Override public boolean pathReuseAvailable() { return pass.nativePt(); }"),
            "the settings switch reaches this backend once the core owns a ring");
    }

    private static int define(String header, String name) {
        Matcher m = Pattern.compile("#define " + name + "\\s+\\(1u << (\\d+)\\)").matcher(header);
        assertTrue(m.find(), name);
        return 1 << Integer.parseInt(m.group(1));
    }
}
