package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCatalogTest {
    @TempDir
    Path tmp;

    @Test
    void dlssNeuralRenderingFilesAreDiagnosticOnly() throws IOException {
        String model = "nvngx_dlssnr.dll";
        String plugin = "sl.dlss_nr.dll";
        byte[] dll = PeFixtures.x64("1.0.0.0");
        Path natives = tmp.resolve("natives");
        Path droppedModel = Files.write(tmp.resolve(model), dll);
        Path archive = Files.write(tmp.resolve("vendor.zip"), PeFixtures.zip(
            "bin/" + model, dll, "bin/" + plugin, dll, "bin/" + LibraryDescriptor.DLSS_RR_DLL, dll));

        NativeInstaller.Report report = NativeInstaller.install(natives, List.of(droppedModel, archive));

        assertEquals(List.of(), report.errors());
        assertEquals(List.of(LibraryDescriptor.DLSS_RR_DLL), report.libraries());
        assertFalse(Files.exists(natives.resolve(model)));
        Path streamline = NativeCatalog.Folder.STREAMLINE.resolve(natives);
        assertFalse(Files.exists(streamline.resolve(plugin)));
        assertTrue(Files.isRegularFile(natives.resolve(LibraryDescriptor.DLSS_RR_DLL)));
        assertTrue(Files.isRegularFile(streamline.resolve(LibraryDescriptor.DLSS_RR_DLL)));

        Files.write(natives.resolve(model), dll);
        Files.write(streamline.resolve(plugin), dll);
        NativeScanner.GroupState neuralRendering = NativeScanner.scan(natives, List.of()).stream()
            .filter(group -> group.slots().stream().anyMatch(slot -> slot.slot().file().equals(model)))
            .findFirst()
            .orElseThrow();

        assertEquals(List.of(model, plugin), neuralRendering.slots().stream().map(slot -> slot.slot().file()).toList());
        assertTrue(neuralRendering.slots().stream().allMatch(slot -> slot.state() == NativeScanner.State.FOUND));
        assertFalse(neuralRendering.complete());
    }
}
