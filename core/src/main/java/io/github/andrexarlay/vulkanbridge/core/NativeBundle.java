package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts the Windows runtime from a distribution JAR into {@code bedray/natives/}.
 * <p>
 * A file is moved into place only after its SHA-256 matches the catalog or bundle index. Existing user files are kept.
 * Resources: {@code /META-INF/natives/windows-x64/<name>} and licence texts {@code /META-INF/licenses/natives/<name>/<file>}.
 */
public final class NativeBundle {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/NativeBundle");

    public static final String RESOURCE_DIR = "/META-INF/natives/windows-x64/";
    public static final String LICENSE_DIR = "/META-INF/licenses/natives/";
    public static final String INDEX = RESOURCE_DIR + "bundle.properties";
    private static final List<String> OPTIONAL_RUNTIME = List.of("bedray_core.dll", "vkb_dlss.dll", "nvngx_dlss.dll",
        "nvngx_dlssd.dll", "nvngx_dlssnr.dll", "NRD.dll", "vkb_nrd.dll",
        "streamline/vkb_sl.dll", "streamline/sl.interposer.dll", "streamline/sl.common.dll", "streamline/sl.dlss.dll",
        "streamline/sl.dlss_d.dll", "streamline/sl.dlss_g.dll", "streamline/sl.reflex.dll", "streamline/sl.pcl.dll",
        "streamline/nvngx_dlss.dll", "streamline/nvngx_dlssd.dll", "streamline/nvngx_dlssg.dll", "streamline/NvLowLatencyVk.dll");
    /** Licence files shipped for each bundled DLL (also copied next to the DLLs, as the licences require). */
    static final Map<String, List<String>> LICENSE_FILES = Map.of(
        "amd_fidelityfx_vk.dll", List.of("LICENSE.txt"),
        "libxess.dll", List.of("LICENSE.txt", "third-party-programs.txt"));

    private NativeBundle() {}

    public enum Outcome { EXTRACTED, ALREADY_PRESENT, KEPT_USER_FILE, NOT_EMBEDDED, FAILED }

    public record Entry(String file, Outcome outcome, String detail) {}

    /** True when this jar contains the binary of {@code file}. */
    public static boolean isEmbedded(String file) {
        return NativeBundle.class.getResource(RESOURCE_DIR + file) != null;
    }

    /** Extracts every bundled file that is not there yet; never throws, every result is in the returned list (and logged). */
    public static List<Entry> extractAll(Path nativesDir) {
        if (!windowsX64()) return List.of();
        List<Entry> out = new ArrayList<>();
        for (NativeCatalog.Group g : NativeCatalog.GROUPS) {
            for (NativeCatalog.Slot s : g.slots()) {
                if (s.bundled()) {
                    Entry e = extractOne(nativesDir, s);
                    out.add(e);
                    log(e);
                }
            }
        }
        out.addAll(extractRuntime(nativesDir, false));
        return out;
    }

    /** Streamline may have to be extracted before the loader's ordinary entry point runs. */
    public static void extractEarlyStreamline(Path gameDir) {
        if (windowsX64()) {
            extractRuntime(streamlineDirectory(gameDir).getParent(), true);
        }
    }

    /** Uses the same configured native root as the resolver, before the loader seam is installed. */
    public static Path streamlineDirectory(Path gameDir) {
        return NativeCatalog.Folder.STREAMLINE.resolve(gameDir.resolve(NativeLibrariesImpl.root()).resolve("natives"));
    }

    private static boolean windowsX64() {
        String arch = System.getProperty("os.arch", "");
        return System.getProperty("os.name", "").startsWith("Windows")
            && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    private static List<Entry> extractRuntime(Path nativesDir, boolean streamlineOnly) {
        try (InputStream stream = NativeBundle.class.getResourceAsStream(INDEX)) {
            if (stream == null) return List.of();
            var index = new java.util.Properties();
            index.load(stream);
            if (!"1".equals(index.getProperty("schema"))) throw new IOException("unsupported bundle schema");
            List<Entry> entries = new ArrayList<>();
            for (String path : OPTIONAL_RUNTIME) {
                boolean streamline = path.startsWith("streamline/");
                if (streamlineOnly && !streamline) continue;
                String hash = index.getProperty(path);
                if (hash == null) continue;
                Entry entry = hash.matches("[0-9a-f]{64}")
                    ? extractOne(nativesDir, streamline ? NativeCatalog.Folder.STREAMLINE : NativeCatalog.Folder.NATIVES,
                        path.substring(path.lastIndexOf('/') + 1), path, hash)
                    : new Entry(path, Outcome.FAILED, "invalid bundle checksum");
                entries.add(entry);
                log(entry);
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            Entry entry = new Entry("bundle.properties", Outcome.FAILED, LogPaths.show(e.toString()));
            log(entry);
            return List.of(entry);
        }
    }

    private static void log(Entry e) {
        if (e.outcome() == Outcome.FAILED) {
            LOGGER.warn("[VKB-NATIVES] bundled file={} outcome={} {}", e.file(), e.outcome(), e.detail());
        } else {
            LOGGER.info("[VKB-NATIVES] bundled file={} outcome={} {}", e.file(), e.outcome(), e.detail());
        }
    }

    static Entry extractOne(Path nativesDir, NativeCatalog.Slot slot) {
        return extractOne(nativesDir, slot.folder(), slot.file(), slot.file(), slot.bundledSha256());
    }

    private static Entry extractOne(Path nativesDir, NativeCatalog.Folder folder, String name, String resourcePath, String expected) {
        URL res = NativeBundle.class.getResource(RESOURCE_DIR + resourcePath);
        if (res == null) {
            return new Entry(name, Outcome.NOT_EMBEDDED, "this build does not contain the binary. See docs/INSTALL.md for "
                + LogPaths.show(folder.resolve(nativesDir)));
        }
        Path dir = folder.resolve(nativesDir);
        try {
            if (Files.isSymbolicLink(nativesDir) || Files.isSymbolicLink(dir)) {
                return new Entry(name, Outcome.FAILED, LogPaths.show(dir) + " is a symbolic link; refusing to write into it");
            }
            Files.createDirectories(dir);
            long resSize = res.openConnection().getContentLengthLong();
            Path existing = NativeFiles.find(dir, name);
            if (existing != null) {
                if (Files.isSymbolicLink(existing)) {
                    return new Entry(name, Outcome.KEPT_USER_FILE, LogPaths.show(existing) + " is a symbolic link; left alone");
                }
                String have = Files.size(existing) == resSize ? NativeFiles.sha256(existing) : null;
                if (expected.equals(have)) {
                    copyLicenses(nativesDir, name);
                    return new Entry(name, Outcome.ALREADY_PRESENT, "sha256=" + have + " path=" + LogPaths.show(existing));
                }
                return new Entry(name, Outcome.KEPT_USER_FILE, "path=" + LogPaths.show(existing) + " differs from the bundled file (sha256 "
                    + (have == null ? "not computed, size " + Files.size(existing) + " != " + resSize : have) + ", bundled " + expected
                    + "); your file is kept");
            }
            Path tmp = Files.createTempFile(dir, ".bundle-", ".tmp");
            try {
                String sha = copyAndHash(res, tmp);
                if (!sha.equals(expected)) {
                    return new Entry(name, Outcome.FAILED, "jar content sha256=" + sha + " but expected " + expected + "; not extracted");
                }
                Path target = dir.resolve(name);
                // Same-directory rename. Preserve a file installed by another launcher during extraction.
                Files.move(tmp, target);
                copyLicenses(nativesDir, name);
                return new Entry(name, Outcome.EXTRACTED, "sha256=" + sha + " bytes=" + Files.size(target) + " path=" + LogPaths.show(target));
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            return new Entry(name, Outcome.FAILED, LogPaths.show(e.toString()));
        }
    }

    private static String copyAndHash(URL resource, Path tmp) throws IOException, java.security.NoSuchAlgorithmException {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.security.DigestInputStream(resource.openStream(), digest);
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 16];
            long remaining = NativeFiles.MAX_BYTES;
            for (int count; (count = in.read(buffer)) != -1; ) {
                remaining -= count;
                if (remaining < 0) throw new IOException("resource exceeds the native library size limit");
                out.write(buffer, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Licence texts next to the DLL: {@code <natives>/licenses/<dll>/<file>}. Missing resources are logged, not hidden. */
    private static void copyLicenses(Path nativesDir, String dll) throws IOException {
        Path outDir = nativesDir.resolve("licenses").resolve(dll);
        for (String f : LICENSE_FILES.getOrDefault(dll, List.of())) {
            Path target = outDir.resolve(f);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (InputStream in = NativeBundle.class.getResourceAsStream(LICENSE_DIR + dll + "/" + f)) {
                if (in == null) {
                    LOGGER.warn("[VKB-NATIVES] licence text {} for {} is missing in the jar", f, dll);
                    continue;
                }
                Files.createDirectories(outDir);
                Files.copy(in, target);
            }
        }
    }
}
