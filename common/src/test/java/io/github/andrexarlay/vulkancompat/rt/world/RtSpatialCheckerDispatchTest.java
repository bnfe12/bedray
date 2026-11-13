package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DI spatial pass runs once per checker colour, and each run may only write its own colour (the other colour is what
 * it reads).  Since 2026-11-08 the host dispatches half the columns and the shader folds the colour into the thread to
 * pixel mapping, instead of launching every pixel and exiting half the lanes.  These guards prove the folded mapping
 * still reaches every pixel of the colour exactly once - including the last column of an odd width - so the image is
 * the same as before, only cheaper.
 */
class RtSpatialCheckerDispatchTest {

    private static final String PASS = "src/main/resources/shaders/slang/passes/restir_spatial.slang";
    private static final String HOST = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSpatialReuseSlangPass.java";
    /** {@code [numthreads(8, 8, 1)]} of the entry; pinned by {@link #theShaderFoldsThePhaseIntoTheColumn()}. */
    private static final int GROUP = 8;

    @Test
    void everyPixelOfTheColourGetsExactlyOneThread() {
        // 1707 is the odd render width of 2560 at 1/1.5 scale: the column x = 1706 belongs to phase 0 on even rows and
        // to phase 1 on odd rows, so a floor(width / 2) dispatch would silently drop it for one colour.
        for (int width : new int[] {1707, 1708, 1, 2, 3}) {
            for (int phase = 0; phase < RtSpatialReuseSlangPass.PHASES; phase++) {
                assertCovers(width, 960, phase);
                assertCovers(width, 7, phase);
            }
        }
    }

    @Test
    void theDispatchIsHalfTheRow() {
        assertEquals(854, RtSpatialReuseSlangPass.checkerColumns(1707));
        assertEquals(854, RtSpatialReuseSlangPass.checkerColumns(1708));
        assertEquals(1, RtSpatialReuseSlangPass.checkerColumns(1));
        assertThrows(IllegalArgumentException.class, () -> RtSpatialReuseSlangPass.checkerColumns(0));
    }

    @Test
    void theShaderFoldsThePhaseIntoTheColumn() {
        String pass = read(PASS);
        assertTrue(pass.contains("[numthreads(8, 8, 1)]"), "the Java mirror assumes 8x8 groups");
        assertTrue(pass.contains("int2 p = int2(int(2u * tid.x + ((tid.y + phase) & 1u)), int(tid.y));"),
            "thread (tx, ty) owns pixel (2 tx + ((ty + phase) & 1), ty)");
        assertFalse(pass.contains("int2 p = int2(tid.xy);"), "the identity mapping launched both colours");
        assertTrue(pass.contains("if (p.x >= int(size.x) || p.y >= int(size.y)) {"),
            "the bounds test stays: an odd width leaves one column without a pixel on alternate rows");
        assertTrue(pass.contains("reuse.pixel = p;"), "the seed and the slot still come from the pixel, not the thread");
    }

    @Test
    void theHostDispatchesTheHalvedWidthForEveryPhase() {
        String host = read(HOST);
        assertTrue(host.contains("program.groups(checkerColumns(width), 0), program.groups(height, 1), 1"),
            "both phases share the one native dispatch, so the halved width covers each of them");
        assertFalse(host.contains("program.groups(width, 0)"), "no full-width dispatch is left");
    }

    /** Mirrors the dispatch (groups rounded up to 8) and the shader's mapping plus bounds test, for one colour. */
    private static void assertCovers(int width, int height, int phase) {
        int columns = RtSpatialReuseSlangPass.checkerColumns(width);
        int threadsX = ceilDiv(columns, GROUP) * GROUP;
        int threadsY = ceilDiv(height, GROUP) * GROUP;
        Set<Long> produced = new HashSet<>();
        for (int ty = 0; ty < threadsY; ty++) {
            for (int tx = 0; tx < threadsX; tx++) {
                int x = 2 * tx + ((ty + phase) & 1);
                int y = ty;
                if (x >= width || y >= height) {
                    continue;
                }
                assertTrue(((x + y) & 1) == phase,
                    "thread (" + tx + "," + ty + ") left its colour at " + width + "x" + height + " phase " + phase);
                assertTrue(produced.add(key(x, y)),
                    "pixel (" + x + "," + y + ") reached twice at " + width + "x" + height + " phase " + phase);
            }
        }
        Set<Long> expected = new HashSet<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((x + y) & 1) == phase) {
                    expected.add(key(x, y));
                }
            }
        }
        assertEquals(expected, produced, "phase " + phase + " at " + width + "x" + height);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static long key(int x, int y) {
        return ((long) y << 32) | (x & 0xffffffffL);
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + relative, e);
        }
    }
}
