package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-2: both native projects failed every non-Windows configure with a hard
 * {@code FATAL_ERROR}. That is honest but leaves portability work blocked. The
 * gate is now an explicit opt-in: the default still refuses unsupported
 * platforms, while an attempt produces a warning and claims no support.
 * <p>
 * The opt-in also has to keep the output names aligned with P2-3:
 * {@code System.mapLibraryName} expects {@code libbedray_scene.so/.dylib}, so
 * the empty Windows prefix must not be applied to every platform.
 */
class NativePlatformGateTest {
    private static final Path CORE = Path.of("../native/bedray_core/CMakeLists.txt");
    private static final Path SCENE = Path.of("../native/bedray_scene/CMakeLists.txt");

    @Test
    void aNonWindowsConfigureIsAnOptInWithAWarning() throws IOException {
        for (Path path : new Path[] {CORE, SCENE}) {
            String cmake = Files.readString(path);
            assertTrue(cmake.contains("option(BEDRAY_ALLOW_UNSUPPORTED_PLATFORM"),
                path + ": the unsupported platform must be an explicit option");
            assertTrue(cmake.contains("if(NOT BEDRAY_ALLOW_UNSUPPORTED_PLATFORM)"),
                path + ": the default must still refuse the untested platform");
            assertTrue(cmake.contains("message(WARNING"),
                path + ": an opted-in build must say that it is untested and unsupported");
            assertFalse(cmake.matches("(?s)if\\(NOT WIN32\\)\\s*message\\(FATAL_ERROR.*?endif\\(\\)"),
                path + ": no unconditional fatal platform gate may remain");
        }
    }

    @Test
    void theSceneLibraryUsesThePlatformPrefix() throws IOException {
        String cmake = Files.readString(SCENE);
        assertTrue(cmake.matches("(?s).*if\\(WIN32\\)\\s*set_target_properties\\(bedray_scene PROPERTIES PREFIX \"\"\\)\\s*endif\\(\\).*"),
            "an empty prefix on Linux/macOS would produce bedray_scene.so while the JVM asks for libbedray_scene.so; "
                + "the empty prefix belongs to the Windows branch only");
    }
}
