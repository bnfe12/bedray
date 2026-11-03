package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.InstallResult;
import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.LibraryEvent;
import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.LibraryStatus;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraryException;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * File based implementation of {@link NativeLibraries}. Internal; obtain it through {@code VulkanBridge.libraries()}.
 * <p>
 * Directories: {@code <gameDir>/<root>/natives} (read and write; {@code <root>} is {@code bedray} unless the system property
 * {@link #ROOT_PROPERTY} says otherwise) and the legacy folders of earlier test builds (read only, for migration of existing
 * instances). No network access.
 */
public final class NativeLibrariesImpl implements NativeLibraries {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/Natives");

    /** System property that overrides the folder name under the game directory (default {@value #DEFAULT_ROOT}). */
    public static final String ROOT_PROPERTY = "bedray.nativesRoot";
    public static final String DEFAULT_ROOT = "bedray";
    /** Folder names of earlier test builds, searched read-only after the primary folder so that existing instances keep working. */
    public static final List<String> LEGACY_ROOTS = List.of("vulkan_rtx", "vulkan_" + "bridge"); // second name: the separate library of pre-0.1 test builds
    public static final String NATIVES_LEAF = "natives";
    /** A DLL smaller than this cannot be a real vendor library (rejects empty and placeholder files). */
    static final long MIN_FILE_BYTES = 1024;
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final int MAX_CANDIDATES = 64;

    /** Loads a library by absolute path; {@code System::load} in production, replaced in tests. */
    @FunctionalInterface
    public interface NativeLoader {
        void load(String absolutePath);
    }

    private final Supplier<Path> gameDir;
    private final NativeLoader loader;
    private final Map<String, LibraryDescriptor> descriptors = new LinkedHashMap<>();
    private final List<Consumer<LibraryEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> hashCache = new ConcurrentHashMap<>();

    public NativeLibrariesImpl(Supplier<Path> gameDir, NativeLoader loader) {
        this.gameDir = gameDir;
        this.loader = loader;
        for (LibraryDescriptor d : LibraryDescriptor.builtIn()) {
            descriptors.put(d.id(), d);
        }
    }

    // ---- directories

    @Override
    public Path nativesDirectory() {
        return gameDir.get().toAbsolutePath().normalize().resolve(root()).resolve(NATIVES_LEAF);
    }

    /** Configured root folder name (a plain single path segment); falls back to {@value #DEFAULT_ROOT} if the property is blank or unsafe. */
    static String root() {
        String v = System.getProperty(ROOT_PROPERTY);
        if (v == null || v.isBlank() || v.contains("/") || v.contains("\\") || v.contains("..") || v.contains(":")) {
            return DEFAULT_ROOT;
        }
        return v.strip();
    }

    /** Read-only fallback folders, in search order (never the primary folder itself). */
    List<Path> legacyDirectories() {
        Path base = gameDir.get().toAbsolutePath().normalize();
        Path primary = nativesDirectory();
        List<Path> out = new ArrayList<>();
        for (String r : LEGACY_ROOTS) {
            Path p = base.resolve(r).resolve(NATIVES_LEAF);
            if (!p.equals(primary)) {
                out.add(p);
            }
        }
        return out;
    }

    /** The legacy folders as a status message may name them (no absolute home/profile prefix in a shared log). */
    private static String shownDirs(List<Path> dirs) {
        StringBuilder sb = new StringBuilder();
        for (Path d : dirs) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(LogPaths.show(d));
        }
        return sb.toString();
    }

    // ---- registry

    @Override
    public synchronized List<LibraryDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    @Override
    public synchronized void register(LibraryDescriptor descriptor) {
        if (descriptors.putIfAbsent(descriptor.id(), descriptor) != null) {
            throw new IllegalArgumentException("library id already registered: " + descriptor.id());
        }
    }

    private synchronized LibraryDescriptor descriptor(String id) {
        LibraryDescriptor d = descriptors.get(id);
        if (d == null) {
            throw new IllegalArgumentException("unknown library id: " + id);
        }
        return d;
    }

    @Override
    public Runnable addListener(Consumer<LibraryEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void fire(LibraryEvent e) {
        for (Consumer<LibraryEvent> l : listeners) {
            l.accept(e);
        }
    }

    // ---- resolve

    @Override
    public List<LibraryResolution> scan() {
        List<LibraryResolution> out = new ArrayList<>();
        for (LibraryDescriptor d : descriptors()) {
            out.add(resolve(d.id()));
        }
        return out;
    }

    @Override
    public LibraryResolution resolve(String id) {
        LibraryDescriptor d = descriptor(id);
        Path primary = nativesDirectory();
        List<Path> legacyDirs = legacyDirectories();
        Path legacyHit = null;

        Map<String, Path> found = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean legacyUsed = false;
        boolean allKnownHashes = true;

        for (String file : d.allFiles()) {
            boolean required = d.requiredFiles().contains(file);
            Path p = locate(primary, file);
            boolean fromLegacy = false;
            if (p == null) {
                for (Path dir : legacyDirs) {
                    p = locate(dir, file);
                    if (p != null) {
                        fromLegacy = true;
                        legacyHit = dir;
                        break;
                    }
                }
            }
            if (p == null) {
                if (required) {
                    missing.add(file);
                }
                continue;
            }
            FileCheck check = checkFile(d, file, p);
            if (check.rejection != null) {
                LibraryStatus st = LibraryStatus.rejected(file + " (" + LogPaths.show(p) + "): " + check.rejection);
                return new LibraryResolution(d, st, Map.of(), Map.of(), false);
            }
            found.put(file, p);
            legacyUsed |= fromLegacy;
            if (check.version != null) {
                versions.put(file, check.version);
            }
            boolean known = d.knownSha256().getOrDefault(file.toLowerCase(Locale.ROOT), Set.of()).contains(check.sha256);
            allKnownHashes &= known;
            notes.add(file + " sha256=" + check.sha256.substring(0, 16) + "... "
                    + (known ? "known" : "not in the known list")
                    + ", signature " + (check.signed ? "present (not validated)" : "absent"));
        }
        if (!missing.isEmpty()) {
            LibraryStatus st = LibraryStatus.missing("missing " + missing + " in " + LogPaths.show(primary)
                + " (also searched " + shownDirs(legacyDirs) + ")");
            return new LibraryResolution(d, st, Map.of(), Map.of(), false);
        }
        String detail = String.join("; ", notes) + (legacyUsed ? "; loaded from the legacy directory " + LogPaths.show(legacyHit) : "");
        LibraryStatus st = allKnownHashes ? LibraryStatus.verified(detail) : LibraryStatus.presentUnverified(detail);
        return new LibraryResolution(d, st, found, versions, legacyUsed);
    }

    private record FileCheck(String rejection, String sha256, String version, boolean signed) {
        static FileCheck reject(String reason) {
            return new FileCheck(reason, null, null, false);
        }
    }

    private FileCheck checkFile(LibraryDescriptor d, String name, Path p) {
        try {
            if (Files.isSymbolicLink(p)) {
                return FileCheck.reject("is a symbolic link; only regular files are accepted");
            }
            long size = Files.size(p);
            FileCheck structural = checkStructure(d, name, p, size);
            if (structural.rejection != null) {
                return structural;
            }
            return new FileCheck(null, sha256Cached(p, size), structural.version, structural.signed);
        } catch (IOException e) {
            return FileCheck.reject("cannot be read: " + LogPaths.show(e.toString()));
        }
    }

    /** Size, PE and version checks shared by resolve and install. Returns a FileCheck without a hash. */
    private static FileCheck checkStructure(LibraryDescriptor d, String name, Path p, long size) throws IOException {
        if (size < MIN_FILE_BYTES) {
            return FileCheck.reject("too small (" + size + " bytes) to be a real library");
        }
        if (size > d.maxFileBytes()) {
            return FileCheck.reject("too large (" + size + " bytes, limit " + d.maxFileBytes() + ")");
        }
        PeInspector.Info info;
        try {
            info = PeInspector.inspect(p);
        } catch (PeInspector.PeFormatException e) {
            return FileCheck.reject(e.getMessage());
        }
        if (!info.dll()) {
            return FileCheck.reject("PE image is not a DLL");
        }
        if (!info.isX64Dll()) {
            return FileCheck.reject("wrong architecture: " + info.machineName() + (info.pe32Plus() ? "" : ", 32-bit image")
                    + "; the game needs an x64 DLL");
        }
        if (d.minVersion() != null) {
            if (info.version() == null) {
                return FileCheck.reject("has no version resource but the library requires at least " + d.minVersion());
            }
            if (PeInspector.compareVersions(info.version(), d.minVersion()) < 0) {
                return FileCheck.reject("version " + info.version() + " is older than the required " + d.minVersion());
            }
        }
        return new FileCheck(null, null, info.version(), info.hasEmbeddedSignature());
    }

    /** Finds {@code file} in {@code dir} ignoring case (Windows semantics); returns the real path or null. */
    static Path locate(Path dir, String file) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path e : ds) {
                if (e.getFileName().toString().equalsIgnoreCase(file) && !Files.isDirectory(e, LinkOption.NOFOLLOW_LINKS)) {
                    return e.toAbsolutePath().normalize();
                }
            }
        } catch (IOException e) {
            throw new NativeLibraryException("-", LogPaths.show("cannot list " + dir + ": " + e), e);
        }
        return null;
    }

    private String sha256Cached(Path p, long size) throws IOException {
        String key = p + "|" + size + "|" + Files.getLastModifiedTime(p).toMillis();
        String h = hashCache.get(key);
        if (h == null) {
            h = sha256(p);
            hashCache.put(key, h);
        }
        return h;
    }

    static String sha256(Path p) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }

    // ---- load

    @Override
    public Path load(String id) {
        LibraryResolution r = resolve(id);
        if (!r.usable()) {
            throw new NativeLibraryException(id, r.status());
        }
        Path primary = r.primary().orElseThrow(() -> new NativeLibraryException(id, "resolved without a primary file"));
        // Every required file is loaded in descriptor order (e.g. streamline: sl.interposer.dll, then sl.common.dll);
        // the first one is the primary and is returned. Optional files stay the consumer's responsibility.
        List<String> loadedNames = new ArrayList<>();
        for (String name : r.descriptor().requiredFiles()) {
            Path p = r.file(name).orElseThrow(() -> new NativeLibraryException(id, "resolved without required file " + name));
            try {
                loader.load(p.toString());
            } catch (UnsatisfiedLinkError e) {
                throw new NativeLibraryException(id, LogPaths.show("the OS refused to load " + p + " (already loaded: " + loadedNames + "): " + e.getMessage()), e);
            }
            loadedNames.add(name);
        }
        LOGGER.info("{} native_loaded id={} file={} files={} status={}", BridgeCore.LOG_PREFIX, id, primary.getFileName(), loadedNames, r.status().state());
        return primary;
    }

    // ---- remove

    @Override
    public List<Path> remove(String id) {
        LibraryDescriptor d = descriptor(id);
        Path dir = nativesDirectory();
        List<Path> removed = new ArrayList<>();
        for (String f : d.allFiles()) {
            Path p = locate(dir, f);
            if (p != null) {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new NativeLibraryException(id, LogPaths.show("cannot delete " + p + " (is it loaded by the running game?): " + e), e);
                }
                removed.add(p);
            }
        }
        LOGGER.info("{} native_removed id={} files={}", BridgeCore.LOG_PREFIX, id, removed.size());
        fire(new LibraryEvent(id, LibraryEvent.Type.REMOVED, resolve(id).status()));
        return removed;
    }

    // ---- install

    /** A file extracted/copied into the staging directory, waiting for validation. */
    private record Staged(String canonicalName, String origin, Path file) {}

    @Override
    public InstallResult install(String id, Path source) {
        LibraryDescriptor d = descriptor(id);
        if (source == null || !Files.isRegularFile(source)) {
            throw new NativeLibraryException(id, "source is not a file: " + LogPaths.show(source));
        }
        String srcName = source.getFileName().toString();
        String lower = srcName.toLowerCase(Locale.ROOT);
        Path dir = nativesDirectory();
        Path staging = null;
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            staging = Files.createTempDirectory(dir, ".staging-");
            List<Staged> staged;
            if (lower.endsWith(".dll")) {
                String canonical = canonicalName(d, srcName);
                if (canonical == null) {
                    throw new NativeLibraryException(id, "file name '" + srcName + "' is not allowed for this library; allowed: " + d.allFiles());
                }
                Path tmp = staging.resolve(canonical);
                copyLimited(id, source, tmp, d.maxFileBytes(), srcName);
                staged = List.of(new Staged(canonical, srcName, tmp));
            } else if (lower.endsWith(".zip")) {
                staged = extractZip(d, source, staging);
            } else {
                throw new NativeLibraryException(id, "unsupported source '" + srcName + "': expected a .dll or a .zip");
            }

            // Validate everything first; nothing has touched the natives directory yet.
            for (Staged s : staged) {
                FileCheck c = checkStructure(d, s.canonicalName, s.file, Files.size(s.file));
                if (c.rejection != null) {
                    throw new NativeLibraryException(id, s.origin + ": " + c.rejection);
                }
            }
            for (Staged s : staged) {
                Path target = dir.resolve(s.canonicalName);
                if (Files.isSymbolicLink(target)) {
                    throw new NativeLibraryException(id, "refusing to overwrite a symbolic link: " + LogPaths.show(target));
                }
                Path existing = locate(dir, s.canonicalName);
                if (existing != null && !existing.getFileName().toString().equals(s.canonicalName)) {
                    Files.delete(existing); // same file with different letter case; avoid two spellings
                }
                moveInto(s.file, target);
                written.add(target);
            }
        } catch (IOException e) {
            String partial = written.isEmpty() ? "" : " (already written: " + written + ")";
            throw new NativeLibraryException(id, LogPaths.show("install failed: " + e + partial), e);
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
        LibraryStatus st = resolve(id).status();
        LOGGER.info("{} native_installed id={} files={} status={}", BridgeCore.LOG_PREFIX, id, written.size(), st.state());
        fire(new LibraryEvent(id, LibraryEvent.Type.INSTALLED, st));
        return new InstallResult(id, written, st);
    }

    static void moveInto(Path from, Path target) throws IOException {
        try {
            Files.move(from, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String canonicalName(LibraryDescriptor d, String name) {
        for (String f : d.allFiles()) {
            if (f.equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private List<Staged> extractZip(LibraryDescriptor d, Path zip, Path staging) throws IOException {
        String id = d.id();
        // name -> candidates (several entries of one archive can share a base name, e.g. dev/ and rel/)
        Map<String, List<Staged>> byName = new LinkedHashMap<>();
        int count = 0;
        int candidates = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (++count > MAX_ZIP_ENTRIES) {
                    throw new NativeLibraryException(id, "archive has more than " + MAX_ZIP_ENTRIES + " entries");
                }
                String base = checkEntryName(id, e.getName());
                if (e.isDirectory() || base == null) {
                    continue;
                }
                String canonical = canonicalName(d, base);
                if (canonical == null) {
                    continue; // not on the allowlist: never extracted
                }
                if (++candidates > MAX_CANDIDATES) {
                    throw new NativeLibraryException(id, "archive has more than " + MAX_CANDIDATES + " candidate files");
                }
                Path tmp = Files.createTempFile(staging, "cand-", ".bin");
                try (InputStream in = zf.getInputStream(e)) {
                    copyLimited(id, in, tmp, d.maxFileBytes(), e.getName());
                }
                byName.computeIfAbsent(canonical, k -> new ArrayList<>()).add(new Staged(canonical, e.getName(), tmp));
            }
        }
        if (byName.isEmpty()) {
            throw new NativeLibraryException(id, "archive '" + zip.getFileName() + "' contains none of the allowed files " + d.allFiles());
        }
        List<Staged> chosen = new ArrayList<>();
        for (Map.Entry<String, List<Staged>> en : byName.entrySet()) {
            List<Staged> ok = new ArrayList<>();
            List<String> why = new ArrayList<>();
            for (Staged s : en.getValue()) {
                FileCheck c = checkStructure(d, en.getKey(), s.file, Files.size(s.file));
                if (c.rejection == null) {
                    ok.add(s);
                } else {
                    why.add(s.origin + ": " + c.rejection);
                }
            }
            if (ok.isEmpty()) {
                throw new NativeLibraryException(id, "no usable " + en.getKey() + " in the archive: " + why);
            }
            if (ok.size() > 1) {
                List<String> names = new ArrayList<>();
                for (Staged s : ok) {
                    names.add(s.origin);
                }
                throw new NativeLibraryException(id, "ambiguous: " + en.getKey() + " occurs " + ok.size()
                        + " times as a valid x64 DLL in the archive " + names + "; extract the one you want and install that file");
            }
            Staged only = ok.get(0);
            Path named = staging.resolve(en.getKey());
            Files.move(only.file, named);
            chosen.add(new Staged(en.getKey(), only.origin, named));
        }
        return chosen;
    }

    /**
     * Fail-closed entry name check. Throws for names that could escape a target directory (absolute, drive letter,
     * ".." segment, NUL); returns the last path segment, or null for an empty one. Backslashes count as separators.
     * The extractor additionally never uses entry paths to build target paths (only allowlisted base names).
     */
    static String checkEntryName(String id, String rawName) {
        if (rawName.indexOf('\0') >= 0) {
            throw new NativeLibraryException(id, "archive entry with a NUL in its name: rejected (zip-slip)");
        }
        String n = rawName.replace('\\', '/');
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            throw new NativeLibraryException(id, "archive entry with an absolute path '" + rawName + "': archive rejected (zip-slip)");
        }
        String last = null;
        for (String seg : n.split("/")) {
            if (seg.equals("..")) {
                throw new NativeLibraryException(id, "archive entry '" + rawName + "' escapes the target directory: archive rejected (zip-slip)");
            }
            if (!seg.isEmpty()) {
                last = seg;
            }
        }
        return last;
    }

    private static void copyLimited(String id, Path from, Path to, long max, String origin) throws IOException {
        try (InputStream in = Files.newInputStream(from)) {
            copyLimited(id, in, to, max, origin);
        }
    }

    static void copyLimited(String id, InputStream in, Path to, long max, String origin) throws IOException {
        long total = 0;
        try (OutputStream out = Files.newOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new NativeLibraryException(id, origin + " exceeds the size limit of " + max + " bytes");
                }
                out.write(buf, 0, n);
            }
        }
    }

    static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            List<Path> all = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new NativeLibraryException("-", LogPaths.show("cannot clean the staging directory " + root + ": " + e), e);
        }
    }
}
