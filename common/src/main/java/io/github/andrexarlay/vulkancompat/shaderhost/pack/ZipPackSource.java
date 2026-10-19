package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Shaderpack zip opened read-only through {@link ZipFile}; entries are validated once, at open time. */
final class ZipPackSource implements PackSource {
    private final ZipFile zip;
    private final String name;
    private final String fingerprint;
    private final Map<String, ZipEntry> byPath;
    private final List<String> entries;
    private final ReadBudget budget;

    private ZipPackSource(ZipFile zip, String name, String fingerprint, Map<String, ZipEntry> byPath, ReadBudget budget) {
        this.zip = zip;
        this.name = name;
        this.fingerprint = fingerprint;
        this.byPath = byPath;
        List<String> sorted = new ArrayList<>(byPath.keySet());
        Collections.sort(sorted);
        this.entries = List.copyOf(sorted);
        this.budget = budget;
    }

    static ZipPackSource open(Path file, ScanLimits limits) throws IOException {
        String name = file.getFileName().toString();
        String fingerprint = "zip:" + Files.size(file) + ":" + Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (ZipException e) {
            throw new PackScanException(name + ": not a readable zip (" + e.getMessage() + ")", e);
        }
        try {
            Map<String, ZipEntry> byPath = new HashMap<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    // still validated: a directory entry named "../x/" is as hostile as a file entry
                    PackPaths.normalizeEntry(e.getName(), limits);
                    continue;
                }
                String path = PackPaths.normalizeEntry(e.getName(), limits);
                if (byPath.putIfAbsent(path, e) != null) {
                    throw new PackScanException(name + ": duplicate entry after normalization: " + path);
                }
                if (byPath.size() > limits.maxEntries()) {
                    throw new PackScanException(name + ": more than " + limits.maxEntries() + " entries");
                }
            }
            return new ZipPackSource(zip, name, fingerprint, byPath, new ReadBudget(limits, name));
        } catch (IllegalArgumentException e) {
            // ZipFile throws IAE for malformed entry names (bad UTF-8)
            zip.close();
            throw new PackScanException(name + ": malformed zip entry name (" + e.getMessage() + ")", e);
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    @Override
    public Kind kind() {
        return Kind.ZIP;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> entries() {
        return entries;
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public byte[] read(String entry) throws IOException {
        ZipEntry e = byPath.get(entry);
        if (e == null) {
            throw new PackScanException(name + ": no entry " + entry);
        }
        try (InputStream in = zip.getInputStream(e)) {
            return budget.readAll(in, entry);
        } catch (ZipException ex) {
            throw new PackScanException(name + ": corrupt entry " + entry + " (" + ex.getMessage() + ")", ex);
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
