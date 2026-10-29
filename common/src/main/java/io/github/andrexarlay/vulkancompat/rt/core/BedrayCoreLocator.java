package io.github.andrexarlay.vulkancompat.rt.core;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Finds the platform's Bedray core library through Bedray Core's {@link NativeLibraries} instead of guessing a path
 * (rule 1 of the native-library security model): the file is checked structurally and must sit in
 * {@code <gameDir>/bedray/natives}.
 *
 * <p>The file name follows the JVM's native-library convention: {@code bedray_core.dll} on Windows,
 * {@code libbedray_core.so} on Linux and {@code libbedray_core.dylib} on macOS. Non-Windows builds are not supported
 * yet; the name is still platform-correct so the lookup does not fail on a hardcoded extension.
 *
 * <p>The descriptor is registered on demand, so the renderer core is not part of the vendor library list shown by the
 * natives manager, and no file is downloaded or extracted.
 */
public final class BedrayCoreLocator {
    public static final String LIBRARY_ID = "bedray_core";
    public static final String FILE = System.mapLibraryName(LIBRARY_ID);

    private static final LibraryDescriptor DESCRIPTOR = new LibraryDescriptor(
        LIBRARY_ID, "Bedray renderer core", "Bedray", List.of(FILE), List.of(),
        "LGPL-3.0-or-later", null, Map.of(), null, LibraryDescriptor.DEFAULT_MAX_FILE_BYTES);

    private BedrayCoreLocator() {
    }

    /** Outcome of a lookup: the checked path, or {@code null} and the reason it is not usable. */
    public record Location(Path file, String detail) {
        public boolean usable() {
            return file != null;
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
        return resolution.file(FILE)
            .map(path -> new Location(path, "ok"))
            .orElseGet(() -> new Location(null, "resolved, but " + FILE + " is not in the file list"));
    }
}
