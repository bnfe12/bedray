package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Read-only view of one shaderpack (zip or folder). Entries are normalized by {@link PackPaths} at open time; reads are bounded
 * by {@link ScanLimits}. Nothing is ever extracted or written.
 */
public interface PackSource extends Closeable {
    enum Kind { ZIP, DIRECTORY }

    Kind kind();

    /** File name of the zip or folder inside shaderpacks/. */
    String name();

    /** Normalized paths of all regular files, sorted. */
    List<String> entries();

    /** Cheap identity of the on-disk state (sizes + modification times), used to skip re-hashing an unchanged pack. */
    String fingerprint();

    /** Reads one entry fully; throws {@link PackScanException} when a limit is exceeded. */
    byte[] read(String entry) throws IOException;

    /** Opens a zip or a folder; anything else (symlink, device, missing) is refused. */
    static PackSource open(Path pack, ScanLimits limits) throws IOException {
        if (Files.isSymbolicLink(pack)) {
            throw new PackScanException("symbolic link refused: " + pack.getFileName());
        }
        if (Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) {
            return DirectoryPackSource.open(pack, limits);
        }
        if (Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)) {
            return ZipPackSource.open(pack, limits);
        }
        throw new PackScanException("not a zip file or folder: " + pack.getFileName());
    }
}
