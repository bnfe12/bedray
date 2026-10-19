package io.github.andrexarlay.vulkanbridge.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Result of {@link NativeLibraries#resolve(String)}.
 *
 * @param descriptor the library
 * @param status     status; paths are present only when {@code status.usable()}
 * @param files      file name (as in the descriptor) to absolute path, only for usable libraries
 * @param versions   file name to PE file version {@code a.b.c.d}; a file without a version resource has no entry
 * @param legacy     true if any file came from the legacy {@code vulkan_rtx/natives} directory
 */
@Experimental
public record LibraryResolution(LibraryDescriptor descriptor, LibraryStatus status, Map<String, Path> files,
                                Map<String, String> versions, boolean legacy) {
    public LibraryResolution {
        files = status.usable() ? Map.copyOf(files) : Map.of();
        versions = status.usable() ? Map.copyOf(versions) : Map.of();
    }

    public String id() {
        return descriptor.id();
    }

    public boolean usable() {
        return status.usable();
    }

    /** Path of the first required file, or empty when not usable. */
    public Optional<Path> primary() {
        return usable() ? Optional.ofNullable(files.get(descriptor.requiredFiles().get(0))) : Optional.empty();
    }

    /** Path of a file of this library, or empty when not usable or that (optional) file is not installed. */
    public Optional<Path> file(String fileName) {
        return Optional.ofNullable(files.get(fileName));
    }

    /** The paths, or an exception that carries the reason; use when the caller cannot continue without the library. */
    public Map<String, Path> requireFiles() {
        if (!usable()) {
            throw new NativeLibraryException(descriptor.id(), status);
        }
        return files;
    }
}
