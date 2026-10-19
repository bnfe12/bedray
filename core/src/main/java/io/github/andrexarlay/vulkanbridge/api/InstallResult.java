package io.github.andrexarlay.vulkanbridge.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of an installation. An install either succeeds (every file was checked, then moved into the natives
 * directory) or throws {@link NativeLibraryException} having written nothing.
 *
 * @param libraryId library
 * @param installed absolute paths of the files that were written
 * @param status    status of the library after the install; it may still be MISSING when only part of a
 *                  multi-file library was supplied
 */
@Experimental
public record InstallResult(String libraryId, List<Path> installed, LibraryStatus status) {
    public InstallResult {
        installed = List.copyOf(installed);
    }
}
