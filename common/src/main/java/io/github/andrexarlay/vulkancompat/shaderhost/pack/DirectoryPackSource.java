package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * Unpacked shaderpack folder. Symbolic links and special files are skipped (never followed); every read re-checks that the
 * real path is still a regular file under the pack root, so a link swapped in after open cannot redirect a read.
 */
final class DirectoryPackSource implements PackSource {
    private final Path root;
    private final String name;
    private final String fingerprint;
    private final List<String> entries;
    private final List<String> skipped;
    private final ReadBudget budget;

    private DirectoryPackSource(Path root, String name, String fingerprint, List<String> entries, List<String> skipped, ReadBudget budget) {
        this.root = root;
        this.name = name;
        this.fingerprint = fingerprint;
        this.entries = entries;
        this.skipped = skipped;
        this.budget = budget;
    }

    static DirectoryPackSource open(Path dir, ScanLimits limits) throws IOException {
        Path root = dir.toRealPath();
        String name = dir.getFileName().toString();
        List<String> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        long[] sum = {0, 0};
        Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), limits.maxDepth() + 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    skipped.add(root.relativize(d).toString().replace('\\', '/'));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                String rel = root.relativize(f).toString();
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                    skipped.add(rel.replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
                entries.add(PackPaths.normalizeEntry(rel, limits));
                if (entries.size() > limits.maxEntries()) {
                    throw new PackScanException(name + ": more than " + limits.maxEntries() + " entries");
                }
                sum[0] += attrs.size();
                sum[1] = Math.max(sum[1], attrs.lastModifiedTime().toMillis());
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(entries);
        String fingerprint = "dir:" + entries.size() + ":" + sum[0] + ":" + sum[1];
        return new DirectoryPackSource(root, name, fingerprint, List.copyOf(entries), List.copyOf(skipped), new ReadBudget(limits, name));
    }

    /** Symlinks / special files that were not listed (reported as warnings). */
    List<String> skipped() {
        return skipped;
    }

    @Override
    public Kind kind() {
        return Kind.DIRECTORY;
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
        if (Collections.binarySearch(entries, entry) < 0) {
            throw new PackScanException(name + ": no entry " + entry);
        }
        Path p = root.resolve(entry).normalize();
        if (!p.startsWith(root) || Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
            || !p.toRealPath().startsWith(root)) {
            throw new PackScanException(name + ": entry " + entry + " is no longer a regular file inside the pack");
        }
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            return budget.readAll(in, entry);
        }
    }

    @Override
    public void close() {
        // nothing held open
    }
}
