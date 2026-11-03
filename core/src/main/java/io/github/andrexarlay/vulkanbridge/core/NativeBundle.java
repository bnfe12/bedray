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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts the vendor DLLs that Bedray ships inside its jar (only those whose licence allows it: AMD FidelityFX under MIT and
 * Intel XeSS under the Intel Simplified Software License, see docs/NATIVES_LICENSES.md) into {@code bedray/natives/}.
 * <p>
 * Rules: the bytes are never changed; the SHA-256 is checked against {@link NativeCatalog.Slot#bundledSha256()} before the file
 * is moved into place; a file that already exists is never overwritten (if its hash differs it is the user's own and stays);
 * a jar without the binary (public source build) is reported as NOT_EMBEDDED, not as an error.
 * Resources: {@code /META-INF/natives/windows-x64/<name>} and licence texts {@code /META-INF/licenses/natives/<name>/<file>}.
 */
public final class NativeBundle {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/NativeBundle");

    public static final String RESOURCE_DIR = "/META-INF/natives/windows-x64/";
    public static final String LICENSE_DIR = "/META-INF/licenses/natives/";
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
        return out;
    }

    private static void log(Entry e) {
        if (e.outcome() == Outcome.FAILED) {
            LOGGER.warn("[VKB-NATIVES] bundled file={} outcome={} {}", e.file(), e.outcome(), e.detail());
        } else {
            LOGGER.info("[VKB-NATIVES] bundled file={} outcome={} {}", e.file(), e.outcome(), e.detail());
        }
    }

    static Entry extractOne(Path nativesDir, NativeCatalog.Slot slot) {
        String name = slot.file();
        URL res = NativeBundle.class.getResource(RESOURCE_DIR + name);
        if (res == null) {
            return new Entry(name, Outcome.NOT_EMBEDDED, "this build does not contain the binary (see docs/NATIVES_LICENSES.md); put the file in "
                + LogPaths.show(nativesDir) + " yourself");
        }
        Path dir = slot.folder().resolve(nativesDir);
        try {
            if (Files.isSymbolicLink(dir)) {
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
                if (slot.bundledSha256().equals(have)) {
                    copyLicenses(nativesDir, name);
                    return new Entry(name, Outcome.ALREADY_PRESENT, "sha256=" + have + " path=" + LogPaths.show(existing));
                }
                return new Entry(name, Outcome.KEPT_USER_FILE, "path=" + LogPaths.show(existing) + " differs from the bundled file (sha256 "
                    + (have == null ? "not computed, size " + Files.size(existing) + " != " + resSize : have) + ", bundled " + slot.bundledSha256()
                    + "); your file is kept");
            }
            Path tmp = Files.createTempFile(dir, ".bundle-", ".tmp");
            try {
                String sha;
                try (InputStream in = NativeBundle.class.getResourceAsStream(RESOURCE_DIR + name);
                     OutputStream o = Files.newOutputStream(tmp)) {
                    if (in == null) {
                        return new Entry(name, Outcome.FAILED, "resource disappeared while reading");
                    }
                    var md = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        md.update(buf, 0, n);
                        o.write(buf, 0, n);
                        total += n;
                        if (total > NativeFiles.MAX_BYTES) {
                            return new Entry(name, Outcome.FAILED, "resource larger than " + NativeFiles.MAX_BYTES + " bytes");
                        }
                    }
                    sha = java.util.HexFormat.of().formatHex(md.digest());
                }
                if (!sha.equals(slot.bundledSha256())) {
                    return new Entry(name, Outcome.FAILED, "jar content sha256=" + sha + " but expected " + slot.bundledSha256() + "; not extracted");
                }
                Path target = dir.resolve(name);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                copyLicenses(nativesDir, name);
                return new Entry(name, Outcome.EXTRACTED, "sha256=" + sha + " bytes=" + Files.size(target) + " path=" + LogPaths.show(target));
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            return new Entry(name, Outcome.FAILED, LogPaths.show(e.toString()));
        }
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
