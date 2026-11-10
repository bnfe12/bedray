package io.github.andrexarlay.vulkancompat.rt.scene;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BedraySceneLibraryTest {
    @Test void missingLibraryHasAnExplicitDiagnostic(@TempDir Path dir) {
        var error = assertThrows(BedraySceneException.class, () -> BedraySceneNative.load(dir.resolve("missing.dll")));
        assertTrue(error.getMessage().contains("not a regular file"));
    }

    @Test void currentCoreExportsAndReportsTheSceneLayout() {
        String path = System.getProperty("bedray.core.library");
        Assumptions.assumeTrue(path != null, "matching renderer DLL not configured");
        try (var scene = BedraySceneNative.load(Path.of(path))) {
            var info = scene.abiInfo();
            assertEquals(BedraySceneLayouts.ABI_VERSION, info.abiVersion());
            assertEquals(136, info.sizeofEntityInput());
            assertEquals(200, info.sizeofResources());
        }
    }

    @Test void previousCoreIsRejectedAtAbiCheckBeforeBindingNewExports() {
        String path = System.getProperty("bedray.scene.previousLibrary");
        Assumptions.assumeTrue(path != null, "previous renderer DLL not configured");
        var error = assertThrows(BedraySceneException.class, () -> BedraySceneNative.load(Path.of(path)));
        assertEquals(BedraySceneLayouts.STATUS_ABI_MISMATCH, error.status());
        assertTrue(error.getMessage().contains("DLL ABI 2 != Java ABI 3"), error::getMessage);
    }
}
