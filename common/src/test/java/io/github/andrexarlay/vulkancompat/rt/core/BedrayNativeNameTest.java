package io.github.andrexarlay.vulkancompat.rt.core;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core locator and the native file allowlist use the JVM's platform naming convention.
 */
class BedrayNativeNameTest {
    private static final Path CORE_SOURCE =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLocator.java");

    @Test
    void theCoreNameFollowsTheJvmNativeConvention() {
        assertEquals(System.mapLibraryName("bedray_core"), BedrayCoreLocator.FILE);
    }

    @Test
    void theCoreLocatorDerivesTheNameAtRuntime() throws IOException {
        assertTrue(Files.readString(CORE_SOURCE).contains("System.mapLibraryName(LIBRARY_ID)"),
            "the core locator must map the id at runtime");
    }

    @Test
    void theAllowlistAcceptsTheThreeNativeExtensions() {
        for (String name : List.of("bedray_core.dll", "libbedray_core.so", "libbedray_core.dylib")) {
            LibraryDescriptor descriptor = new LibraryDescriptor("probe", "Probe", "T", List.of(name), List.of(),
                "T", "https://example.invalid", Map.of(), null, LibraryDescriptor.DEFAULT_MAX_FILE_BYTES);
            assertEquals(List.of(name), descriptor.requiredFiles());
        }
    }

    @Test
    void theAllowlistStillRejectsPathsAndUnknownExtensions() {
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("probe", "Probe", "T",
            List.of("../libbedray_core.so"), List.of(), "T", "u", Map.of(), null, 10));
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("probe", "Probe", "T",
            List.of("bedray_core.so.1"), List.of(), "T", "u", Map.of(), null, 10));
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("probe", "Probe", "T",
            List.of("bedray_core.exe"), List.of(), "T", "u", Map.of(), null, 10));
    }
}
