package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "found in this folder" detail is rendered in the natives screen and the log, so it must not carry the profile directory. */
class NativeScannerPrivacyTest {
    @TempDir
    Path tmp;

    @Test
    void foundSlotDetailNamesTheFolderAsGameDir() throws IOException {
        NativeCatalog.Slot slot = NativeCatalog.GROUPS.stream()
            .flatMap(g -> g.slots().stream())
            .filter(s -> !s.bundled())
            .findFirst()
            .orElseThrow();
        Path natives = Files.createDirectories(tmp.resolve("vulkan_bridge").resolve("natives"));
        Path folder = Files.createDirectories(slot.folder().resolve(natives));
        Files.write(folder.resolve(slot.file()), PeFixtures.x64("1.0.0.0"));

        LogPaths.setGameDir(natives);
        try {
            NativeScanner.SlotState state = NativeScanner.scanSlot(slot, natives, List.of());

            assertEquals(NativeScanner.State.FOUND, state.state(), state.detail());
            assertFalse(state.detail().contains(tmp.toString()), state.detail());
            assertTrue(state.detail().startsWith("<gameDir>"), state.detail());
        } finally {
            // Back to the library default: gameDir is process-global static state, and outside the game it is empty.
        }
    }
}
