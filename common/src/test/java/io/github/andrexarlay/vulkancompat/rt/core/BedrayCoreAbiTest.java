package io.github.andrexarlay.vulkancompat.rt.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary test for the Java FFM mirror of ABI v2. It needs the built
 * {@code bedray_core.dll} but no Vulkan device: only argument validation and the
 * reported ABI sizes are exercised.
 */
class BedrayCoreAbiTest {
    private static Path dll() {
        String configured = System.getProperty("bedray.core.library");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("..", "native", "bedray_core", "build", "Release", "bedray_core.dll");
    }

    @Test
    void reportsTheSameLayoutAsTheJavaMirror() {
        Path dll = dll().toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(dll), "bedray_core.dll is not built at " + dll);

        try (BedrayCoreNative core = BedrayCoreNative.load(dll)) {
            BedrayCoreNative.AbiInfo info = core.abiInfo();
            assertEquals(BedrayCoreLayouts.ABI_VERSION, info.abiVersion());
            assertEquals(72, info.sizeofCoreDesc());
            assertEquals(56, info.sizeofCapabilities());
            assertEquals(56, info.sizeofProgramDesc());
            assertEquals(64, info.sizeofFrameDesc());
            assertEquals(48, info.sizeofResource());
            assertEquals(1028, info.sizeofError());
        }
    }

    @Test
    void nativeErrorsCarryTheStatusAndMessage() {
        Path dll = dll().toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(dll), "bedray_core.dll is not built at " + dll);

        try (BedrayCoreNative core = BedrayCoreNative.load(dll)) {
            BedrayCoreException error = assertThrows(BedrayCoreException.class,
                () -> core.create(1, 1, 1, 1, 0, 0x00401000, 0, 0, 0));
            assertEquals(BedrayCoreLayouts.STATUS_INVALID_ARGUMENT, error.status());
            assertTrue(error.getMessage().contains("vkGetInstanceProcAddr"),
                () -> "message should name the missing function pointers: " + error.getMessage());
        }
    }

    @Test
    void javaRejectsZeroHandlesBeforeTheNativeCall() {
        Path dll = dll().toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(dll), "bedray_core.dll is not built at " + dll);

        try (BedrayCoreNative core = BedrayCoreNative.load(dll)) {
            assertThrows(IllegalArgumentException.class,
                () -> core.queryCapabilities(0));
            assertThrows(IllegalArgumentException.class,
                () -> core.renderFrame(0, 1, 1, 1, 1, 1,
                    java.lang.foreign.MemorySegment.NULL, 0, java.lang.foreign.MemorySegment.NULL, 0));
        }
    }
}
