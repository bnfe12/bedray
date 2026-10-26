package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.NativeLibraryException;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs dropped {@code .dll} / {@code .zip} files into the natives folders using the {@link NativeCatalog} (what goes where).
 * Used by the manager screen's drag and drop. Pure Java.
 * <p>
 * Safety, in the order it is applied:
 * <ul>
 *   <li>only {@code .dll} and {@code .zip} sources; a source that is a symbolic link, junction or other special file is refused;</li>
 *   <li>a zip is read with {@link ZipFile}; entry names are checked fail-closed (absolute path, drive letter, {@code ..}, NUL abort the
 *       whole archive, backslashes count as separators) and are NEVER used to build a target path: only the base name, and only
 *       if it is in the catalog, is extracted, to a private staging folder;</li>
 *   <li>limits: entry count, candidate count, bytes per file (counted while copying, the declared size is not trusted) and bytes per
 *       archive;</li>
 *   <li>every candidate must be a regular x64 PE DLL ({@link NativeFiles#problem}); of several candidates with one name the
 *       non-"development" one is taken, else the archive is rejected as ambiguous;</li>
 *   <li>nothing is written to the natives folders until every candidate of the source is validated; an existing target that is a
 *       link is refused; files are moved into place atomically.</li>
 * </ul>
 */
public final class NativeInstaller {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/NativeInstaller");

    public static final int MAX_ENTRIES = 20_000;
    public static final int MAX_CANDIDATES = 64;
    public static final long MAX_ARCHIVE_BYTES = 1536L * 1024 * 1024;
    private static final String ID = "natives";

    private NativeInstaller() {}

    /**
     * @param libraries distinct catalog file names installed (what the user is told: "N libraries")
     * @param files     every file written (a name with two slots counts twice here)
     * @param skipped   sources or entries that were ignored, with the reason
     * @param errors    sources that were rejected, with the reason; nothing of such a source was written
     */
    public record Report(List<String> libraries, List<Path> files, List<String> skipped, List<String> errors) {
        public int count() {
            return libraries.size();
        }
    }

    private record Staged(String canonical, String origin, Path file) {}

    public static Report install(Path nativesDir, List<Path> sources) {
        Set<String> libs = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            NativeFolders.ensureLayout(nativesDir);
        } catch (IOException e) {
            errors.add("cannot prepare " + LogPaths.show(nativesDir) + ": " + LogPaths.show(e.getMessage()));
            LOGGER.warn("[VKB-NATIVES] install aborted: cannot prepare {}\n{}", LogPaths.show(nativesDir), LogPaths.stack(e));
            return new Report(List.of(), List.of(), skipped, errors);
        }
        for (Path src : sources) {
            String name = src.getFileName() == null ? LogPaths.show(src) : src.getFileName().toString();
            try {
                installOne(nativesDir, src, name, libs, files, skipped);
            } catch (NativeLibraryException | IOException e) {
                errors.add(name + ": " + LogPaths.show(e.getMessage()));
                LOGGER.warn("[VKB-NATIVES] source rejected: {}: {}", name, LogPaths.show(e.getMessage()));
            }
        }
        LOGGER.info("[VKB-NATIVES] install_done libraries={} files={} skipped={} errors={}", libs.size(), files.size(), skipped.size(), errors.size());
        return new Report(List.copyOf(libs), List.copyOf(files), skipped, errors);
    }

    private static void installOne(Path nativesDir, Path src, String name, Set<String> libs, List<Path> files, List<String> skipped) throws IOException {
        BasicFileAttributes a;
        try {
            a = Files.readAttributes(src, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IOException("cannot read the dropped file: " + LogPaths.show(e.getMessage()), e);
        }
        if (a.isSymbolicLink() || a.isOther()) {
            throw new IOException("is a symbolic link or special file; refused");
        }
        if (a.isDirectory()) {
            skipped.add(name + ": folders are not supported, drop the .zip or the .dll files");
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Path staging = Files.createTempDirectory(nativesDir, ".staging-");
        try {
            List<Staged> staged;
            if (lower.endsWith(".dll")) {
                String canonical = NativeCatalog.canonicalName(name);
                if (canonical == null) {
                    skipped.add(name + ": not a library Bedray uses");
                    return;
                }
                Path tmp = staging.resolve(canonical);
                try (InputStream in = Files.newInputStream(src)) {
                    NativeLibrariesImpl.copyLimited(ID, in, tmp, NativeFiles.MAX_BYTES, name);
                }
                String problem = NativeFiles.problem(tmp);
                if (problem != null) {
                    throw new IOException(canonical + " " + problem);
                }
                staged = List.of(new Staged(canonical, name, tmp));
            } else if (lower.endsWith(".zip")) {
                staged = extractZip(src, staging, skipped);
            } else {
                skipped.add(name + ": only .dll and .zip are accepted");
                return;
            }
            for (Staged s : staged) {
                place(nativesDir, s, files);
                libs.add(s.canonical());
            }
        } finally {
            try {
                NativeLibrariesImpl.deleteTree(staging);
            } catch (NativeLibraryException e) {
                LOGGER.warn("[VKB-NATIVES] {}", e.getMessage());
            }
        }
    }

    private static List<Staged> extractZip(Path zip, Path staging, List<String> skipped) throws IOException {
        Map<String, List<Staged>> byName = new LinkedHashMap<>();
        int count = 0;
        int candidates = 0;
        long total = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new NativeLibraryException(ID, "archive has more than " + MAX_ENTRIES + " entries");
                }
                String base = NativeLibrariesImpl.checkEntryName(ID, e.getName()); // throws for traversal / absolute names
                if (e.isDirectory() || base == null) {
                    continue;
                }
                String canonical = NativeCatalog.canonicalName(base);
                if (canonical == null) {
                    continue; // not in the catalog: never extracted
                }
                if (++candidates > MAX_CANDIDATES) {
                    throw new NativeLibraryException(ID, "archive has more than " + MAX_CANDIDATES + " candidate files");
                }
                if (e.getSize() > NativeFiles.MAX_BYTES) {
                    throw new NativeLibraryException(ID, e.getName() + " declares " + e.getSize() + " bytes, over the limit of " + NativeFiles.MAX_BYTES);
                }
                Path tmp = Files.createTempFile(staging, "cand-", ".bin");
                try (InputStream in = zf.getInputStream(e)) {
                    NativeLibrariesImpl.copyLimited(ID, in, tmp, NativeFiles.MAX_BYTES, e.getName());
                }
                total += Files.size(tmp);
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new NativeLibraryException(ID, "the files in the archive exceed " + MAX_ARCHIVE_BYTES + " bytes together");
                }
                byName.computeIfAbsent(canonical, k -> new ArrayList<>()).add(new Staged(canonical, e.getName(), tmp));
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("not a readable zip archive: " + e.getMessage(), e);
        }
        if (byName.isEmpty()) {
            throw new NativeLibraryException(ID, "the archive contains none of the libraries Bedray uses");
        }
        List<Staged> chosen = new ArrayList<>();
        for (Map.Entry<String, List<Staged>> en : byName.entrySet()) {
            List<Staged> ok = new ArrayList<>();
            List<String> why = new ArrayList<>();
            for (Staged s : en.getValue()) {
                String p = NativeFiles.problem(s.file());
                if (p == null) {
                    ok.add(s);
                } else {
                    why.add(LogPaths.show(s.origin()) + ": " + p);
                }
            }
            if (ok.isEmpty()) {
                throw new NativeLibraryException(ID, "no usable " + en.getKey() + " in the archive: " + why);
            }
            Staged pick = pickOne(en.getKey(), ok);
            if (!why.isEmpty()) {
                skipped.add(en.getKey() + ": ignored other copies in the archive (" + why.size() + " unusable)");
            }
            Path named = staging.resolve(en.getKey());
            Files.move(pick.file(), named);
            chosen.add(new Staged(en.getKey(), pick.origin(), named));
        }
        return chosen;
    }

    /** One valid copy: the only one, else the only non-development one; otherwise the archive is ambiguous and rejected. */
    static Staged pickOne(String name, List<Staged> ok) {
        if (ok.size() == 1) {
            return ok.get(0);
        }
        List<Staged> release = new ArrayList<>();
        for (Staged s : ok) {
            String o = s.origin().toLowerCase(Locale.ROOT).replace('\\', '/');
            boolean dev = false;
            for (String seg : o.split("/")) {
                if (seg.equals("development") || seg.equals("dev") || seg.equals("debug")) {
                    dev = true;
                }
            }
            if (!dev) {
                release.add(s);
            }
        }
        if (release.size() == 1) {
            return release.get(0);
        }
        List<String> names = new ArrayList<>();
        for (Staged s : ok) {
            names.add(LogPaths.show(s.origin()));
        }
        throw new NativeLibraryException(ID, "ambiguous: " + name + " occurs " + ok.size() + " times as a valid x64 DLL " + names
            + "; extract the one you want and drop that file");
    }

    private static void place(Path nativesDir, Staged s, List<Path> files) throws IOException {
        List<NativeCatalog.Slot> slots = NativeCatalog.slotsFor(s.canonical());
        // validate every destination first so a refusal leaves nothing half written
        for (NativeCatalog.Slot slot : slots) {
            Path dir = slot.folder().resolve(nativesDir);
            if (Files.isSymbolicLink(dir)) {
                throw new IOException(LogPaths.show(dir) + " is a symbolic link; refusing to write into it");
            }
            Path existing = NativeFiles.find(dir, s.canonical());
            if (existing != null && Files.isSymbolicLink(existing)) {
                throw new IOException(LogPaths.show(existing) + " is a symbolic link; refusing to overwrite it");
            }
        }
        for (NativeCatalog.Slot slot : slots) {
            Path dir = slot.folder().resolve(nativesDir);
            Files.createDirectories(dir);
            Path existing = NativeFiles.find(dir, s.canonical());
            if (existing != null && !existing.getFileName().toString().equals(s.canonical())) {
                Files.delete(existing); // same file with different letter case; avoid two spellings
            }
            Path target = dir.resolve(s.canonical());
            Path tmp = Files.createTempFile(dir, ".install-", ".tmp");
            try {
                try (InputStream in = Files.newInputStream(s.file())) {
                    Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    NativeLibrariesImpl.moveInto(tmp, target);
                } catch (IOException e) {
                    throw new IOException(LogPaths.show("cannot replace " + target + " (is it loaded by the running game? close the game and try again): "
                        + e.getMessage()), e);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            files.add(target);
            LOGGER.info("[VKB-NATIVES] native_installed file={} to={} bytes={} sha256={} from={}", LogPaths.show(s.canonical()),
                LogPaths.show(target), Files.size(target), NativeFiles.sha256(target), LogPaths.show(s.origin()));
        }
    }
}
