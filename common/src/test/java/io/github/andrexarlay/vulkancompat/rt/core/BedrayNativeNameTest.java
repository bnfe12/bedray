package io.github.andrexarlay.vulkancompat.rt.core;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLocator;
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
 * P2-3: the Bedray locators hardcoded {@code bedray_core.dll} / {@code bedray_scene.dll}.
 * On Linux and macOS that name is wrong before the file is even looked for. The
 * allowlist also rejected every non-{@code .dll} name, so a platform-correct lookup
 * could not have been described.
 */
class BedrayNativeNameTest {
    private static final Path CORE_SOURCE =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLocator.java");
    private static final Path SCENE_SOURCE =
        Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/scene/BedraySceneLocator.java");

    @Test
    void theCoreNameFollowsTheJvmNativeConvention() {
        assertEquals(System.mapLibraryName("bedray_core"), BedrayCoreLocator.FILE);
    }

    @Test
    void theSceneNameFollowsTheJvmNativeConvention() {
        assertEquals(System.mapLibraryName("bedray_scene"), BedraySceneLocator.FILE);
    }

    @Test
    void bothLocatorsDeriveTheNameInsteadOfHardcodingAnExtension() throws IOException {
        assertTrue(Files.readString(CORE_SOURCE).contains("System.mapLibraryName(LIBRARY_ID)"),
            "the core locator must map the id at runtime, not spell out .dll");
        assertTrue(Files.readString(SCENE_SOURCE).contains("System.mapLibraryName(LIBRARY_ID)"),
            "the scene locator must map the id at runtime, not spell out .dll");
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
