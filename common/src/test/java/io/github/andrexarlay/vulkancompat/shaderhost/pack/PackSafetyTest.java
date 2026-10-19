package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** I1 safety: path traversal, limits, read-only. Synthetic packs written by the test itself (no third-party content). */
class PackSafetyTest {
    @TempDir
    Path tmp;

    private Path zip(String name, String... entriesAndTexts) throws IOException {
        Path p = tmp.resolve(name);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (int i = 0; i < entriesAndTexts.length; i += 2) {
                z.putNextEntry(new ZipEntry(entriesAndTexts[i]));
                z.write(entriesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return p;
    }

    @Test
    void zipSlipEntriesAreRefused() throws IOException {
        for (String evil : new String[]{"../evil.fsh", "shaders/../../evil.fsh", "/abs/evil.fsh", "\\abs\\evil.fsh",
            "C:/Windows/evil.fsh", "shaders\\..\\..\\evil.fsh", "shaders/a\u0000.fsh"}) {
            Path p = zip("slip" + Integer.toHexString(evil.hashCode()) + ".zip", "shaders/final.fsh", "void main(){}", evil, "x");
            PackScanException e = assertThrows(PackScanException.class, () -> PackSource.open(p, ScanLimits.DEFAULT).close(), evil);
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void backslashEntriesAreNormalizedNotRejected() throws IOException {
        Path p = zip("win.zip", "shaders\\final.fsh", "void main(){}");
        try (PackSource s = PackSource.open(p, ScanLimits.DEFAULT)) {
            assertEquals(java.util.List.of("shaders/final.fsh"), s.entries());
        }
    }

    @Test
    void duplicateAfterNormalizationIsRefused() throws IOException {
        Path p = zip("dup.zip", "shaders/final.fsh", "a", "shaders//./final.fsh", "b");
        assertThrows(PackScanException.class, () -> PackSource.open(p, ScanLimits.DEFAULT).close());
    }

    @Test
    void zipBombIsStoppedOnRealBytesNotDeclaredSize() throws IOException {
        Path p = tmp.resolve("bomb.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            z.putNextEntry(new ZipEntry("shaders/final.fsh"));
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 8; i++) {
                z.write(zeros); // 8 MiB of zeros compress to a few KB
            }
            z.closeEntry();
        }
        assertTrue(Files.size(p) < 100_000);
        ScanLimits small = new ScanLimits(100, 1 << 20, 4 << 20, 512, 32);
        try (PackSource s = PackSource.open(p, small)) {
            PackScanException e = assertThrows(PackScanException.class, () -> s.read("shaders/final.fsh"));
            assertTrue(e.getMessage().contains("larger than"));
        }
    }

    @Test
    void totalBudgetIsEnforced() throws IOException {
        Path p = zip("total.zip", "shaders/a.glsl", "x".repeat(600), "shaders/b.glsl", "y".repeat(600));
        ScanLimits small = new ScanLimits(100, 1000, 1000, 512, 32);
        try (PackSource s = PackSource.open(p, small)) {
            s.read("shaders/a.glsl");
            assertThrows(PackScanException.class, () -> s.read("shaders/b.glsl"));
        }
    }

    @Test
    void entryCountDepthAndLengthLimits() throws IOException {
        ScanLimits two = new ScanLimits(2, 1000, 10000, 40, 3);
        Path many = zip("many.zip", "shaders/a", "", "shaders/b", "", "shaders/c", "");
        assertThrows(PackScanException.class, () -> PackSource.open(many, two).close());
        Path deep = zip("deep.zip", "shaders/a/b/c/d.glsl", "");
        assertThrows(PackScanException.class, () -> PackSource.open(deep, two).close());
        Path longName = zip("long.zip", "shaders/" + "x".repeat(50), "");
        assertThrows(PackScanException.class, () -> PackSource.open(longName, two).close());
    }

    @Test
    void notAZipIsRefusedCleanly() throws IOException {
        Path p = tmp.resolve("fake.zip");
        Files.writeString(p, "this is not a zip");
        assertThrows(PackScanException.class, () -> PackSource.open(p, ScanLimits.DEFAULT).close());
    }

    @Test
    void includeResolutionIsConfinedToShadersRoot() throws PackScanException {
        assertEquals("shaders/lib/common.glsl", PackPaths.resolveWithin("shaders/", "shaders/world0/final.fsh", "/lib/common.glsl"));
        assertEquals("shaders/lib/x.glsl", PackPaths.resolveWithin("shaders/", "shaders/world0/final.fsh", "../lib/x.glsl"));
        assertEquals("shaders/world0/y.glsl", PackPaths.resolveWithin("shaders/", "shaders/world0/final.fsh", "y.glsl"));
        assertThrows(PackScanException.class, () -> PackPaths.resolveWithin("shaders/", "shaders/final.fsh", "../../etc/passwd"));
        assertThrows(PackScanException.class, () -> PackPaths.resolveWithin("shaders/", "shaders/final.fsh", "/../x"));
        assertThrows(PackScanException.class, () -> PackPaths.resolveWithin("shaders/", "shaders/final.fsh", "C:/x"));
    }

    @Test
    void packChoiceIsAPlainName() throws PackScanException {
        Path dir = tmp.resolve("shaderpacks");
        assertEquals(dir.resolve("A b.zip"), ShaderpackDiscovery.resolveChoice(dir, "A b.zip"));
        for (String bad : new String[]{"../x.zip", "sub/x.zip", "..", "C:x.zip", "a\\b.zip", ""}) {
            assertThrows(PackScanException.class, () -> ShaderpackDiscovery.resolveChoice(dir, bad), bad);
        }
    }

    @Test
    void directoryPackSkipsSymlinksAndScansReadOnly() throws IOException {
        Path pack = tmp.resolve("shaderpacks/DirPack");
        Files.createDirectories(pack.resolve("shaders/lib"));
        Files.writeString(pack.resolve("shaders/final.fsh"), "#version 150\nvoid main(){}\n");
        Files.writeString(pack.resolve("shaders/lib/a.glsl"), "float a;\n");
        Path outside = tmp.resolve("secret.txt");
        Files.writeString(outside, "secret");
        boolean linked;
        try {
            Files.createSymbolicLink(pack.resolve("shaders/leak.glsl"), outside);
            linked = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            linked = false; // Windows without the symlink privilege: the rest of the test still runs
        }
        long before = Files.getLastModifiedTime(pack.resolve("shaders/final.fsh")).toMillis();
        try (PackSource s = PackSource.open(pack, ScanLimits.DEFAULT)) {
            assertFalse(s.entries().contains("shaders/leak.glsl"));
            PackReport r = ShaderpackScanner.scan(s);
            assertEquals(PackSource.Kind.DIRECTORY, r.kind());
            assertTrue(r.allPrograms().contains("final"));
            if (linked) {
                assertTrue(r.warnings().stream().anyMatch(w -> w.contains("leak.glsl")), r.warnings().toString());
            }
        }
        assertEquals(before, Files.getLastModifiedTime(pack.resolve("shaders/final.fsh")).toMillis());
        assertEquals(1, ShaderpackDiscovery.list(tmp.resolve("shaderpacks")).size());
    }
}
