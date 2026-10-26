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
    public void keepsTheGameDirectoryAliasAndPlainNames() {
        assertEquals("null", LogPaths.show(null));
        assertEquals("libxess.dll", LogPaths.show("libxess.dll"), "a bare file name is not a leak and must not be mangled");
    }
}
