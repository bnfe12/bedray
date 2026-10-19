package io.github.andrexarlay.vulkanbridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** File level checks shared by the natives scanner, installer and bundle extractor. Pure Java. */
public final class NativeFiles {
    /** A DLL smaller than this cannot be a real vendor library (rejects empty and placeholder files). */
    public static final long MIN_BYTES = NativeLibrariesImpl.MIN_FILE_BYTES;
    /** Largest single DLL accepted (the biggest vendor file, libxess, is 78 MB). */
    public static final long MAX_BYTES = 256L * 1024 * 1024;

    private NativeFiles() {}

    /**
     * Why {@code file} cannot be used as a vendor library, or null when it is a regular, non-link x64 DLL of a sane size.
     * Never follows links: a symbolic link, junction or other reparse point is refused.
     */
    public static String problem(Path file) {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther()) {
                return "is a link or special file; only regular files are accepted";
            }
            if (!a.isRegularFile()) {
                return "is not a regular file";
            }
            if (a.size() < MIN_BYTES) {
                return "too small (" + a.size() + " bytes) to be a real library";
            }
            if (a.size() > MAX_BYTES) {
                return "too large (" + a.size() + " bytes, limit " + MAX_BYTES + ")";
            }
            PeInspector.Info info = PeInspector.inspect(file);
            if (!info.dll()) {
                return "PE image is not a DLL";
            }
            if (!info.isX64Dll()) {
                return "wrong architecture: " + info.machineName() + (info.pe32Plus() ? "" : ", 32-bit image") + "; the game needs an x64 DLL";
            }
            return null;
        } catch (PeInspector.PeFormatException e) {
            return e.getMessage();
        } catch (IOException e) {
            return "cannot be read: " + e;
        }
    }

    /** PE file version of {@code file} or null when it has none or cannot be read (display only). */
    public static String version(Path file) {
        try {
            return PeInspector.inspect(file).version();
        } catch (IOException e) {
            return null;
        }
    }

    /** Lower-case hex SHA-256 of a file. */
    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256(in);
        }
    }

    public static String sha256(InputStream in) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Finds {@code name} in {@code dir} ignoring case (Windows semantics); returns the real path or null. */
    public static Path find(Path dir, String name) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path direct = dir.resolve(name);
        if (Files.exists(direct, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(direct, LinkOption.NOFOLLOW_LINKS)) {
            return direct;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path e : ds) {
                if (e.getFileName().toString().equalsIgnoreCase(name) && !Files.isDirectory(e, LinkOption.NOFOLLOW_LINKS)) {
                    return e;
                }
            }
        }
        return null;
    }
}
