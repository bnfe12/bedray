package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native scene and Radiance Cascades entries share integration, material decode, ReSTIR and the output contract in
 * {@code pathtrace/path_trace_entry.slang}.
 */
class PathTraceEntryDedupTest {
    private static final Path PASSES = Path.of("src/main/resources/shaders/slang/passes");
    private static final Path SHARED = Path.of("src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang");

    @Test
    void bothEntriesCallTheSharedBody() throws IOException {
        String trace = Files.readString(PASSES.resolve("path_trace_scene.slang"));
        String field = Files.readString(PASSES.resolve("rc_path_trace_scene.slang"));
        String shared = Files.readString(SHARED);
        for (String entry : new String[] {trace, field}) {
            assertTrue(entry.contains("import \"pathtrace/path_trace_entry.slang\";"));
            assertTrue(entry.contains("#define BEDRAY_PATH_TRAVERSAL traversal"));
            assertFalse(entry.contains("tracePath("), "the entry must share the integrator call");
        }
        assertTrue(shared.contains("public void pathTraceEntry"));
        assertTrue(trace.contains("pathTraceEntry(BEDRAY_PATH_TRAVERSAL,"));
        assertTrue(field.contains("pathTraceEntryWithField(BEDRAY_PATH_TRAVERSAL,"));
    }
}
