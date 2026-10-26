package io.github.andrexarlay.vulkanbridge.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 JUnit tests for the log redaction used by every {@code [VKB-*]} message that can carry a file path.
 *
 * <p>The real leak shape is a Foreign Function &amp; Memory load failure: {@code SymbolLookup.libraryLookup} throws
 * {@code IllegalArgumentException: Cannot open library: C:/Users/&lt;user&gt;/.../libxess.dll} (measured with a one-file
 * probe on Temurin 25.0.4, 2026-10-26), so the path reaches the log both through the message built around it and through
 * the throwable itself — as a concatenated {@code + e} and as a {@code Caused by} line inside a printed stack.
 */
public class LogPathsTest {

    private static final String FOREIGN_PROFILE = "C:/Users/someone/AppData/Roaming/FreesmLauncher/instances/RT/libxess.dll";

    @Test
    public void redactsAnyUserProfileDirectoryNotOnlyThisMachine() {
        String shown = LogPaths.show(FOREIGN_PROFILE);
        assertFalse(shown.contains("someone"), "the profile segment of another machine must not survive: " + shown);
        assertTrue(shown.startsWith("<home>/AppData/"), shown);
    }

    @Test
    public void redactsThePathInsideAnExceptionMessage() {
        String home = System.getProperty("user.home");
        IllegalArgumentException boom = new IllegalArgumentException("Cannot open library: " + home + "/natives/xess/libxess.dll");
        String shown = LogPaths.show(boom);
        assertFalse(shown.contains(home), "show(Throwable) must not print the raw home: " + shown);
        assertTrue(shown.startsWith("java.lang.IllegalArgumentException: Cannot open library: <home>"), shown);
    }

    @Test
    public void redactsAProfileNameThatContainsASpace() {
        // Windows account names may contain spaces; the redactor used to stop at the first one and print the tail.
        String shown = LogPaths.show("C:/Users/John Smith/AppData/Roaming/FreesmLauncher/instances/RT/libxess.dll");
        assertFalse(shown.contains("Smith"), "the tail of a spaced profile name must not survive: " + shown);
        assertTrue(shown.startsWith("<home>/AppData/"), shown);
        String unix = LogPaths.show("/home/mary jones/.minecraft/logs/latest.log");
        assertFalse(unix.contains("jones"), unix);
        assertTrue(unix.startsWith("<home>/.minecraft/"), unix);
    }

    @Test
    public void redactsEveryCauseOfAPrintedStack() {
        String home = System.getProperty("user.home");
        Exception inner = new IllegalArgumentException("Cannot open library: " + home + "/a/b.dll");
        Exception outer = new IllegalStateException("cannot load the denoiser", inner);
        String stack = LogPaths.stack(outer);
        assertFalse(stack.contains(home), "a Caused by line leaks the same path as the wrapper: " + stack);
        assertTrue(stack.contains("Caused by: java.lang.IllegalArgumentException: Cannot open library: <home>"), stack);
        assertTrue(stack.contains("at io.github.andrexarlay"), "the frames that make the stack useful must stay: " + stack);
    }

    @Test
    public void keepsTheReasonThatFollowsAPath() {
        // NIO prints "<path>: Access denied"; swallowing the whole line would leave the owner with nothing to diagnose.
        String shown = LogPaths.show("java.nio.file.AccessDeniedException: C:\\Users\\bob\\bedray\\sl: Access denied");
        assertFalse(shown.contains("bob"), shown);
        assertTrue(shown.endsWith(": Access denied"), shown);
    }

    @Test
    public void redactsAJsonEscapedPath() {
        // A Properties/JSON dump doubles the separator, and a single-separator pattern does not match it.
        String shown = LogPaths.show("{\"dir\":\"C:\\\\\\\\Users\\\\\\\\bob\\\\\\\\game\"}");
        assertFalse(shown.contains("bob"), shown);
        assertTrue(shown.contains("<home>"), shown);
    }

    @Test
    public void keepsUrlsAndDocumentationPathsThatMentionUsersOrHome() {
        // The unanchored form matched any "/users/" or "/home/", so these came out of the log as <home>. An absolute
        // profile root starts with a drive letter or with a separator that is not the tail of a word.
        assertEquals("https://github.com/users/foo/bedray/releases",
            LogPaths.show("https://github.com/users/foo/bedray/releases"));
        assertEquals("see docs/users/manual.pdf", LogPaths.show("see docs/users/manual.pdf"));
        assertEquals("https://aka.ms/vs/redist/home/page", LogPaths.show("https://aka.ms/vs/redist/home/page"));
    }

    @Test
    public void redactsAProfileNameThatContainsAnApostrophe() {
        // Windows and macOS accounts use apostrophes; the old name class stopped at one and printed O'Brien's tail.
        String shown = LogPaths.show("C:\\Users\\O'Brien\\AppData\\Roaming\\FreesmLauncher\\libxess.dll");
        assertFalse(shown.contains("Brien"), "the tail of an apostrophe name must not survive: " + shown);
        assertTrue(shown.startsWith("<home>\\AppData\\"), shown);
        String nix = LogPaths.show("/home/d'amico/.minecraft/logs/latest.log");
        assertFalse(nix.contains("amico"), nix);
        assertTrue(nix.startsWith("<home>/.minecraft/"), nix);
    }

    @Test
    public void redactsAHomeNestedUnderAnotherAbsoluteRoot() {
        // Fedora Silverblue/Universal Blue and Solaris-family put the profile under /var/home or /export/home; the
        // word-boundary lookbehind used to refuse any separator preceded by a letter, so these printed the account raw.
        String silver = LogPaths.show("/var/home/bob/.local/share/minecraft/logs/latest.log");
        assertFalse(silver.contains("bob"), "Silverblue home leaked: " + silver);
        assertTrue(silver.startsWith("<home>/.local/"), silver);
        String solaris = LogPaths.show("/export/home/jones/game/natives/libxess.dll");
        assertFalse(solaris.contains("jones"), solaris);
        assertTrue(solaris.startsWith("<home>/game/"), solaris);
    }

    @Test
    public void redactsADotlessUncShareRoot() {
        // A server share has a leading backslash run then dotless segments (server, share) before Users; the segment skip
        // walks them and the whole leading path collapses into <home>, hiding server and share too. A server name with a
        // dot (\\wsl.localhost\...) is a documented hole — it is only caught on the owner's own machine, where
        // user.home is replaced literally first.
        String unc = LogPaths.show("\\\\fileserver\\games\\Users\\bob\\AppData\\Roaming\\libxess.dll");
        assertFalse(unc.contains("bob"), "UNC home leaked: " + unc);
        assertFalse(unc.contains("fileserver"), unc);
        assertTrue(unc.startsWith("<home>\\"), unc);
    }

    @Test
    public void keepsTheSchemeOfAFileUrlThatNamesAHomeSegment() {
        // "file:/home/bob/game" is a real absolute path and its profile must redact, but the drive-letter branch used to
        // eat the scheme's last letter and print "fil<home>".
        String shown = LogPaths.show("file:/home/bob/game/config.toml");
        assertFalse(shown.contains("bob"), shown);
        assertTrue(shown.startsWith("file:<home>/game/"), "the file: scheme must survive intact: " + shown);
    }

    @Test
    public void mapsTheGameDirectoryToItsOwnAlias() {
        java.nio.file.Path gameDir = java.nio.file.Path.of("X:", "instances", "RT 26.3", "minecraft").toAbsolutePath();
        LogPaths.setGameDir(gameDir);
        try {
            String shown = LogPaths.show(gameDir.resolve("mods").resolve("bedray.jar"));
            assertTrue(shown.startsWith("<gameDir>" + java.io.File.separator), shown);
            assertFalse(shown.contains("minecraft"), shown);
        } finally {
            LogPaths.setGameDir(null);
        }
        assertFalse(LogPaths.show(gameDir.resolve("mods").resolve("bedray.jar")).contains("<gameDir>"),
            "resetting the game directory must not leave the old alias in place");
    }

    @Test
    public void keepsTheGameDirectoryAliasAndPlainNames() {
        assertEquals("null", LogPaths.show(null));
        assertEquals("null", LogPaths.stack(null), "a missing cause must print like the message form does");
        assertEquals("libxess.dll", LogPaths.show("libxess.dll"), "a bare file name is not a leak and must not be mangled");
    }
}
