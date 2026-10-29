package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the two path-tracing entry points from becoming copies again. The entry files own only their binding 2 and
 * traversal backend; integration, material decode, ReSTIR and the output contract live in
 * {@code pathtrace/path_trace_entry.slang}.
 */
class PathTraceEntryDedupTest {
    private static final Path PASSES = Path.of("src/main/resources/shaders/slang/passes");
    private static final Path SHARED = Path.of("src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang");

    @Test
    void bothEntriesCallTheSharedBody() throws IOException {
        String legacy = Files.readString(PASSES.resolve("path_trace.slang"));
        String scene = Files.readString(PASSES.resolve("path_trace_scene.slang"));
        String shared = Files.readString(SHARED);

        assertTrue(legacy.contains("import \"pathtrace/path_trace_entry.slang\";"),
            "path_trace.slang must import the shared entry body");
        assertTrue(scene.contains("import \"pathtrace/path_trace_entry.slang\";"),
            "path_trace_scene.slang must import the shared entry body");
        assertTrue(shared.contains("public void pathTraceEntry"), "shared module must expose pathTraceEntry");
        assertTrue(legacy.contains("pathTraceEntry(traversal"), "path_trace.slang must call the shared body");
        assertTrue(scene.contains("pathTraceEntry(traversal"), "path_trace_scene.slang must call the shared body");

        // A copied tracePath call means the integration moved back into one entry point and can drift.
        assertFalse(legacy.contains("tracePath("), "path_trace.slang must not duplicate the integrator call");
        assertFalse(scene.contains("tracePath("), "path_trace_scene.slang must not duplicate the integrator call");
    }
}
