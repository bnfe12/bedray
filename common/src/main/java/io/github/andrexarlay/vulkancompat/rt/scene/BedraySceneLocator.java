package io.github.andrexarlay.vulkancompat.rt.scene;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Finds {@code bedray_scene.dll} through Bedray Core's {@link NativeLibraries}
 * instead of guessing a path (rule 1 of the native-library security model): the
 * file is checked structurally and must sit in {@code <gameDir>/bedray/natives}.
 *
 * <p>The GPU scene is a separate DLL from {@code bedray_core} today. It is
 * optional: a build without it keeps the Java scene, so a failed lookup is
 * reported, not thrown.
 */
public final class BedraySceneLocator {
    public static final String LIBRARY_ID = "bedray_scene";
    public static final String DLL = "bedray_scene.dll";

    private static final LibraryDescriptor DESCRIPTOR = new LibraryDescriptor(
        LIBRARY_ID, "Bedray GPU scene", "Bedray", List.of(DLL), List.of(),
        "LGPL-3.0-or-later", null, Map.of(), null, LibraryDescriptor.DEFAULT_MAX_FILE_BYTES);

    private BedraySceneLocator() {
    }

    /** Outcome of a lookup: the checked path, or {@code null} and the reason it is not usable. */
    public record Location(Path dll, String detail) {
        public boolean usable() {
            return dll != null;
        }
    }

    public static Location locate(NativeLibraries libraries) {
        boolean known = libraries.descriptors().stream().anyMatch(d -> d.id().equals(LIBRARY_ID));
        if (!known) {
            libraries.register(DESCRIPTOR);
        }
        LibraryResolution resolution = libraries.resolve(LIBRARY_ID);
        if (!resolution.usable()) {
            return new Location(null, resolution.status().state() + ": " + resolution.status().detail());
        }
        return resolution.file(DLL)
            .map(path -> new Location(path, "ok"))
            .orElseGet(() -> new Location(null, "resolved, but " + DLL + " is not in the file list"));
    }
}
