package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the extraction of the operator-facing RT log out of RtFrameRenderer: the once-per-second status / BLAS / rt_pass
 * window, the change-triggered pass and shading lines and the sky-display packet line live in {@link RtRenderLog}, which
 * borrows the live frame state through its {@code Context}; the facade only delegates. Re-inlining the wording into the
 * renderer, or dropping main's trace/stats telemetry from the rt_pass line, breaks this guard.
 */
class RtRenderLogTest {
    private static final Path OWNER =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtRenderLog.java");
    private static final Path FACADE =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }

    @Test
    void theLogWindowLivesInTheOwnerAndTheFacadeDelegates() {
        String owner = read(OWNER);
        assertTrue(owner.contains("final class RtRenderLog"), "the log owner must exist");
        assertTrue(owner.contains("void logWindow(RtWorld.FrameStats s, RenderTarget target)"),
            "logWindow moved with its exact signature");
        assertTrue(owner.contains("interface Context"), "the live frame state is borrowed through Context");
        assertTrue(owner.contains("[VKB-RT] rt_pass ok"), "the rt_pass window line is the owner's");
        assertTrue(owner.contains("[VKB-RT] status mode="), "the status line is the owner's");
        assertTrue(owner.contains("[VKB-SKY] display="), "the sky-display packet line is the owner's");
        // main's newest telemetry must survive the move verbatim
        assertTrue(owner.contains("RtTraceTelemetry submitted = lastTraceTelemetry;"), "trace telemetry kept");
        assertTrue(owner.contains("StatsMetadata metered = lastStatsMetadata;"), "stats metadata kept");
        assertTrue(owner.contains("RtStatsMetering.meteredPixels("), "metered pixels kept");
        assertTrue(owner.contains("scene_vram_mb="), "main's renamed status field kept");
        assertFalse(owner.contains("failedSections"), "main deleted failedSections from the log");
        assertFalse(owner.contains("ommCache"), "main deleted the micromap stats from the log");

        String facade = read(FACADE);
        assertTrue(facade.contains("renderLog.logWindow(stats, target);"), "the facade delegates the window");
        assertTrue(facade.contains("renderLog.logPass("), "the facade delegates the pass line");
        assertTrue(facade.contains("renderLog.logShading("), "the facade delegates the shading line");
        assertTrue(facade.contains("renderLog.logSkyDisplay("), "the facade delegates the sky-display line");
        assertTrue(facade.contains("renderLog.addTracedFrame("), "the facade feeds the window accumulator");
        assertFalse(facade.contains("private static void logWindow("), "the window body must not stay in the facade");
        assertFalse(facade.contains("private static void logShading("), "the shading body must not stay in the facade");
        assertFalse(facade.contains("private static void logPass("), "the pass body must not stay in the facade");
    }
}