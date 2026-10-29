package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-1: the audit described four helper modules as isolated from the game. Two of them are now compiled into
 * {@code bedray_core} and used from Java; this test pins that wiring so a build edit cannot quietly drop it again.
 * <p>
 * The remaining gap is documented in the audit, not asserted here: the history C API is compiled into the core but
 * has no Java binding, and {@code bedray_frame_lifetime} is still a standalone library.
 */
class NativeModuleWiringTest {
    private static final Path CORE_CMAKE = Path.of("../native/bedray_core/CMakeLists.txt");
    private static final Path SLANG_RUNTIME =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java");
    private static final Path GPU_BUFFERS =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayGpuBuffersNative.java");

    @Test
    void theCoreCompilesTheResourceAndGraphModules() throws IOException {
        String cmake = Files.readString(CORE_CMAKE);
        for (String source : new String[] {
            "../bedray_history/src/history_c.cpp",
            "../bedray_gpu_resources/src/gpu_buffers_c.cpp",
            "../bedray_gpu_resources/src/gpu_images_c.cpp",
            "src/frame_graph_owner.cpp"
        }) {
            assertTrue(cmake.contains(source), CORE_CMAKE + ": the core must compile " + source);
        }
        for (String target : new String[] {"bedray_graph_frame", "bedray_scene_objects", "bedray_gpu_resources"}) {
            assertTrue(cmake.contains(target), CORE_CMAKE + ": the core must link " + target);
        }
    }

    @Test
    void theJavaSideUsesTheGpuResourceOwners() throws IOException {
        String runtime = Files.readString(SLANG_RUNTIME);
        assertTrue(runtime.contains("createImageOwner"), "SlangRuntime must expose the native image owner");
        assertTrue(runtime.contains("createBufferOwner"), "SlangRuntime must expose the native buffer owner");
        assertTrue(Files.readString(GPU_BUFFERS).contains("bedray_gpu_buffer_create"),
            "the buffer owner must call the native gpu-resource export, not reimplement it");
    }
}
