package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.InstallResult;
import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.LibraryEvent;
import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.LibraryStatus;
import io.github.andrexarlay.vulkanbridge.api.LibraryStatus.State;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** L2: file level behaviour of the native library manager on a temp game directory. Nothing is loaded for real. */
class NativeLibrariesTest {
    @TempDir
    Path game;
    @TempDir
    Path src;

    final List<String> loaded = new ArrayList<>();
    NativeLibrariesImpl libs;

    @BeforeEach
    void setUp() {
        libs = new NativeLibrariesImpl(() -> game, loaded::add);
    }

    private Path file(String name, byte[] bytes) throws IOException {
        Path p = src.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
        return p;
    }

    private Path nativesDir() {
        return game.resolve("bedray").resolve("natives");
    }

    private long fileCountUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private static String sha256Bytes(byte[] b) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void missingLibraryIsReportedWithReason() {
        LibraryResolution r = libs.resolve("xess");
        assertEquals(State.MISSING, r.status().state());
        assertTrue(r.status().detail().contains("libxess.dll"));
        assertFalse(r.usable());
        assertTrue(r.primary().isEmpty());
        assertThrows(NativeLibraryException.class, r::requireFiles);
    }

    @Test
    void unknownIdIsAnArgumentError() {
        assertThrows(IllegalArgumentException.class, () -> libs.resolve("nope"));
        assertThrows(IllegalArgumentException.class, () -> libs.install("nope", src));
    }

    @Test
    void installsSingleDllAndReportsVersionAndEvent() throws IOException {
        List<LibraryEvent> events = new ArrayList<>();
        libs.addListener(events::add);
        Path dll = file("libxess.dll", PeFixtures.x64("2.1.0.5"));
        InstallResult res = libs.install("xess", dll);
        assertEquals(State.PRESENT_UNVERIFIED, res.status().state());
        assertEquals(List.of(nativesDir().resolve("libxess.dll")), res.installed());
        assertTrue(Files.isRegularFile(nativesDir().resolve("libxess.dll")));
        assertEquals(1, fileCountUnder(game), "no staging leftovers");
        LibraryResolution r = libs.resolve("xess");
        assertTrue(r.usable());
        assertEquals("2.1.0.5", r.versions().get("libxess.dll"));
        assertFalse(r.legacy());
        assertEquals(1, events.size());
        assertEquals(LibraryEvent.Type.INSTALLED, events.get(0).type());
    }

    @Test
    void fileNameMatchIsCaseInsensitiveAndCanonicalised() throws IOException {
        libs.install("xess", file("LibXeSS.DLL", PeFixtures.x64("1.0.0.0")));
        assertTrue(Files.isRegularFile(nativesDir().resolve("libxess.dll")));
    }

    @Test
    void rejectsDisallowedName() throws IOException {
        Path evil = file("evil.dll", PeFixtures.x64("1.0.0.0"));
        NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> libs.install("xess", evil));
        assertTrue(e.getMessage().contains("not allowed"));
        assertEquals(0, fileCountUnder(nativesDir()));
    }

    @Test
    void rejectsWrongArchitectures() throws IOException {
        Path arm = file("arm/libxess.dll", PeFixtures.pe(PeInspector.MACHINE_ARM64, true, true, "1.0.0.0", false, 2));
        Path x86 = file("x86/libxess.dll", PeFixtures.pe(PeInspector.MACHINE_I386, false, true, "1.0.0.0", false, 3));
        Path exe = file("exe/libxess.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, false, "1.0.0.0", false, 4));
        for (Path p : List.of(arm, x86, exe)) {
            NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> libs.install("xess", p));
            assertEquals(State.REJECTED, e.status().state());
            assertEquals(0, fileCountUnder(game), "nothing written for " + p);
        }
        assertTrue(assertThrows(NativeLibraryException.class, () -> libs.install("xess", arm)).getMessage().contains("arm64"));
        assertTrue(assertThrows(NativeLibraryException.class, () -> libs.install("xess", x86)).getMessage().contains("x86"));
    }

    @Test
    void rejectsNonPeAndTinyFiles() throws IOException {
        Path notPe = file("a/libxess.dll", new byte[4096]);
        Path tiny = file("b/libxess.dll", new byte[10]);
        assertTrue(assertThrows(NativeLibraryException.class, () -> libs.install("xess", notPe)).getMessage().contains("MZ"));
        assertTrue(assertThrows(NativeLibraryException.class, () -> libs.install("xess", tiny)).getMessage().contains("too small"));
        assertEquals(0, fileCountUnder(game));
    }

    @Test
    void rejectsTruncatedPe() throws IOException {
        byte[] full = PeFixtures.x64("1.0.0.0");
        byte[] cut = new byte[1100];
        System.arraycopy(full, 0, cut, 0, cut.length);
        // header intact, but the section the resource directory points at lies beyond the end of file
        Path p = file("c/libxess.dll", cut);
        assertThrows(NativeLibraryException.class, () -> libs.install("xess", p));
        assertEquals(0, fileCountUnder(game));
    }

    @Test
    void unsupportedSourceType() throws IOException {
        Path txt = file("libxess.txt", new byte[2000]);
        assertThrows(NativeLibraryException.class, () -> libs.install("xess", txt));
        assertThrows(NativeLibraryException.class, () -> libs.install("xess", src.resolve("does-not-exist.dll")));
    }

    // ---- zip

    @Test
    void zipSlipEntriesAbortTheWholeInstall() throws IOException {
        byte[] ok = PeFixtures.x64("1.0.0.0");
        for (String name : List.of("../libxess.dll", "a/../../libxess.dll", "..\\libxess.dll", "/libxess.dll", "C:/libxess.dll", "C:\\x\\libxess.dll")) {
            Path z = file("slip.zip", PeFixtures.zip("good/libxess.dll", ok, name, ok));
            NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> libs.install("xess", z), name);
            assertTrue(e.getMessage().contains("zip-slip"), name + ": " + e.getMessage());
            assertEquals(0, fileCountUnder(game), "nothing written for " + name);
        }
    }

    @Test
    void zipInstallsAllowedFilesByBaseNameAndIgnoresTheRest() throws IOException {
        Path z = file("sdk.zip", PeFixtures.zip(
                "docs/readme.txt", new byte[100],
                "bin/x64/sl.interposer.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "2.0.0.0", false, 5),
                "bin/x64/sl.common.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "2.0.0.0", false, 6),
                "bin/x64/sl.imgui.dll", PeFixtures.x64("1.0.0.0")));
        InstallResult res = libs.install("streamline", z);
        assertEquals(State.PRESENT_UNVERIFIED, res.status().state());
        assertEquals(2, res.installed().size());
        assertFalse(Files.exists(nativesDir().resolve("sl.imgui.dll")), "not on the allowlist");
        assertEquals(2, fileCountUnder(game));
    }

    @Test
    void partialMultiFileInstallLeavesLibraryMissing() throws IOException {
        Path z = file("part.zip", PeFixtures.zip("sl.interposer.dll", PeFixtures.x64("2.0.0.0")));
        InstallResult res = libs.install("streamline", z);
        assertEquals(State.MISSING, res.status().state());
        assertTrue(res.status().detail().contains("sl.common.dll"));
    }

    @Test
    void zipPicksTheX64CopyOverArm64() throws IOException {
        Path z = file("mixed.zip", PeFixtures.zip(
                "arm64/libxess.dll", PeFixtures.pe(PeInspector.MACHINE_ARM64, true, true, "1.0.0.0", false, 7),
                "x64/libxess.dll", PeFixtures.x64("3.0.0.0")));
        libs.install("xess", z);
        assertEquals("3.0.0.0", libs.resolve("xess").versions().get("libxess.dll"));
    }

    @Test
    void zipWithTwoValidX64CopiesIsAmbiguous() throws IOException {
        Path z = file("amb.zip", PeFixtures.zip(
                "dev/nvngx_dlss.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "3.0.0.0", false, 8),
                "rel/nvngx_dlss.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "3.0.0.0", false, 9)));
        NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> libs.install("dlss", z));
        assertTrue(e.getMessage().contains("ambiguous"));
        assertEquals(0, fileCountUnder(game));
    }

    @Test
    void zipWithOnlyWrongArchitectureIsRejected() throws IOException {
        Path z = file("arm.zip", PeFixtures.zip("libxess.dll", PeFixtures.pe(PeInspector.MACHINE_ARM64, true, true, "1.0.0.0", false, 7)));
        NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> libs.install("xess", z));
        assertTrue(e.getMessage().contains("arm64"));
        assertEquals(0, fileCountUnder(game));
    }

    @Test
    void zipWithoutAllowedFilesIsRejected() throws IOException {
        Path z = file("none.zip", PeFixtures.zip("readme.txt", new byte[10]));
        assertThrows(NativeLibraryException.class, () -> libs.install("xess", z));
    }

    @Test
    void oversizedEntryIsRejectedWhileCopying() throws IOException {
        LibraryDescriptor small = new LibraryDescriptor("small", "Small", "T", List.of("small.dll"), List.of(), "T", "https://example.invalid",
                Map.of(), null, 2000);
        libs.register(small);
        Path z = file("big.zip", PeFixtures.zip("small.dll", new byte[5000]));
        NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> libs.install("small", z));
        assertTrue(e.getMessage().contains("size limit"));
        assertEquals(0, fileCountUnder(game));
    }

    // ---- resolve / statuses

    @Test
    void handPlacedBadFileIsRejectedOnResolve() throws IOException {
        Files.createDirectories(nativesDir());
        Files.write(nativesDir().resolve("libxess.dll"), PeFixtures.pe(PeInspector.MACHINE_I386, false, true, "1.0.0.0", false, 1));
        LibraryResolution r = libs.resolve("xess");
        assertEquals(State.REJECTED, r.status().state());
        assertTrue(r.status().detail().contains("x86"));
        assertTrue(r.files().isEmpty());
        assertThrows(NativeLibraryException.class, () -> libs.load("xess"));
        assertTrue(loaded.isEmpty(), "a rejected library is never loaded");
    }

    @Test
    void legacyDirectoryIsReadButNeverWritten() throws IOException {
        Path legacy = game.resolve("vulkan_rtx").resolve("natives");
        Files.createDirectories(legacy);
        Files.write(legacy.resolve("libxess.dll"), PeFixtures.x64("1.0.0.0"));
        LibraryResolution r = libs.resolve("xess");
        assertTrue(r.usable());
        assertTrue(r.legacy());
        assertEquals(legacy.resolve("libxess.dll").toAbsolutePath().normalize(), r.primary().orElseThrow());
        // a fresh install goes to the new directory and wins
        libs.install("xess", file("libxess.dll", PeFixtures.x64("9.0.0.0")));
        LibraryResolution r2 = libs.resolve("xess");
        assertFalse(r2.legacy());
        assertEquals("9.0.0.0", r2.versions().get("libxess.dll"));
        // remove deletes only the bridge copy
        assertEquals(1, libs.remove("xess").size());
        assertTrue(Files.exists(legacy.resolve("libxess.dll")));
    }

    @Test
    void verifiedOnlyWhenHashIsKnown() throws Exception {
        byte[] bytes = PeFixtures.x64("1.0.0.0");
        LibraryDescriptor d = new LibraryDescriptor("pinned", "Pinned", "T", List.of("pinned.dll"), List.of(), "T", "https://example.invalid",
                Map.of("Pinned.DLL", Set.of(sha256Bytes(bytes))), null, LibraryDescriptor.DEFAULT_MAX_FILE_BYTES);
        libs.register(d);
        assertEquals(State.VERIFIED, libs.install("pinned", file("pinned.dll", bytes)).status().state());
        // different content under the same name: present, but not verified (not rejected)
        assertEquals(State.PRESENT_UNVERIFIED,
                libs.install("pinned", file("other/pinned.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "1.0.0.0", false, 77))).status().state());
    }

    @Test
    void minVersionIsEnforced() throws IOException {
        libs.register(new LibraryDescriptor("minv", "MinV", "T", List.of("minv.dll"), List.of(), "T", "https://example.invalid",
                Map.of(), "3.1", LibraryDescriptor.DEFAULT_MAX_FILE_BYTES));
        Path old = file("a/minv.dll", PeFixtures.x64("3.0.9.9"));
        Path noVer = file("b/minv.dll", PeFixtures.x64(null));
        assertTrue(assertThrows(NativeLibraryException.class, () -> libs.install("minv", old)).getMessage().contains("older"));
        assertTrue(assertThrows(NativeLibraryException.class, () -> libs.install("minv", noVer)).getMessage().contains("no version"));
        assertEquals(State.PRESENT_UNVERIFIED, libs.install("minv", file("c/minv.dll", PeFixtures.x64("3.1.0.0"))).status().state());
    }

    @Test
    void scanListsEveryBuiltIn() {
        List<LibraryResolution> all = libs.scan();
        assertEquals(LibraryDescriptor.builtIn().size(), all.size());
        assertTrue(all.stream().allMatch(r -> r.status().state() == State.MISSING));
    }

    // ---- load

    @Test
    void loadOnlyUsesTheResolvedPath() throws IOException {
        assertThrows(NativeLibraryException.class, () -> libs.load("xess"));
        assertTrue(loaded.isEmpty());
        libs.install("xess", file("libxess.dll", PeFixtures.x64("1.0.0.0")));
        Path p = libs.load("xess");
        assertEquals(List.of(p.toString()), loaded);
        assertEquals(nativesDir().resolve("libxess.dll").toAbsolutePath().normalize(), p);
    }

    @Test
    void loadLoadsEveryRequiredFileInDescriptorOrderAndReturnsThePrimary() throws IOException {
        Path z = file("sl.zip", PeFixtures.zip(
                "bin/x64/sl.common.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "2.0.0.0", false, 6),
                "bin/x64/sl.interposer.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "2.0.0.0", false, 5),
                "bin/x64/sl.dlss.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "2.0.0.0", false, 7)));
        libs.install("streamline", z);
        Path p = libs.load("streamline");
        Path dir = nativesDir().toAbsolutePath().normalize();
        assertEquals(dir.resolve("sl.interposer.dll"), p);
        assertEquals(List.of(dir.resolve("sl.interposer.dll").toString(), dir.resolve("sl.common.dll").toString()), loaded,
                "required files in descriptor order; optional sl.dlss.dll is not loaded");
    }

    @Test
    void failureOnASecondRequiredFileIsReportedWithCause() throws IOException {
        List<String> attempts = new ArrayList<>();
        var failing = new NativeLibrariesImpl(() -> game, path -> {
            attempts.add(path);
            if (path.endsWith("sl.common.dll")) {
                throw new UnsatisfiedLinkError("sl.common.dll dependency missing");
            }
        });
        failing.install("streamline", file("sl2.zip", PeFixtures.zip(
                "sl.interposer.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "2.0.0.0", false, 5),
                "sl.common.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "2.0.0.0", false, 6))));
        NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> failing.load("streamline"));
        assertInstanceOf(UnsatisfiedLinkError.class, e.getCause());
        assertEquals(2, attempts.size());
        assertTrue(e.getMessage().contains("sl.interposer.dll"), e.getMessage());
    }

    @Test
    void linkErrorBecomesExceptionWithCause() throws IOException {
        var failing = new NativeLibrariesImpl(() -> game, path -> {
            throw new UnsatisfiedLinkError("dependent DLL missing");
        });
        failing.install("xess", file("libxess.dll", PeFixtures.x64("1.0.0.0")));
        NativeLibraryException e = assertThrows(NativeLibraryException.class, () -> failing.load("xess"));
        assertInstanceOf(UnsatisfiedLinkError.class, e.getCause());
    }

    // ---- API objects and the PE reader

    @Test
    void descriptorRejectsUnsafeNames() {
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("x", "X", "T", List.of("../x.dll"), List.of(), "T", "u", Map.of(), null, 10));
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("X", "X", "T", List.of("x.dll"), List.of(), "T", "u", Map.of(), null, 10));
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("x", "X", "T", List.of("a/x.dll"), List.of(), "T", "u", Map.of(), null, 10));
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("x", "X", "T", List.of("x.dll"), List.of(), "T", "u", Map.of("x.dll", Set.of("ABC")), null, 10));
    }

    @Test
    void descriptorAcceptsLinuxAndMacOsNativeNames() {
        new LibraryDescriptor("so", "SO", "T", List.of("libbedray_core.so"), List.of(), "T", "u", Map.of(), null, 10);
        new LibraryDescriptor("dylib", "Dylib", "T", List.of("libbedray_core.dylib"), List.of(), "T", "u", Map.of(), null, 10);
        assertThrows(IllegalArgumentException.class, () -> new LibraryDescriptor("ver", "Ver", "T", List.of("libbedray_core.so.1"), List.of(), "T", "u", Map.of(), null, 10));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> libs.register(LibraryDescriptor.XESS));
    }

    @Test
    void rejectedResolutionCarriesNoPaths() {
        LibraryResolution r = new LibraryResolution(LibraryDescriptor.XESS, LibraryStatus.rejected("x"), Map.of("libxess.dll", game), Map.of(), false);
        assertTrue(r.files().isEmpty());
    }

    @Test
    void peInspectorReadsSignatureFlagAndVersion() throws IOException {
        Path p = file("s.dll", PeFixtures.pe(PeInspector.MACHINE_AMD64, true, true, "10.20.30.40", true, 1));
        PeInspector.Info i = PeInspector.inspect(p);
        assertTrue(i.isX64Dll());
        assertTrue(i.hasEmbeddedSignature());
        assertEquals("10.20.30.40", i.version());
        PeInspector.Info unsigned = PeInspector.inspect(file("u.dll", PeFixtures.x64(null)));
        assertFalse(unsigned.hasEmbeddedSignature());
        assertNull(unsigned.version());
        assertTrue(PeInspector.compareVersions("3.10", "3.9.9") > 0);
    }
}
