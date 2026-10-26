package io.github.andrexarlay.vulkanbridge.platform;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Makes file paths safe to print in logs of a public release: the game directory becomes {@code <gameDir>}, any other
 * user profile directory (Windows, Linux, macOS, and the {@code user.home} property) becomes {@code <home>}. Works on
 * any value, including an exception's {@code toString()} and a whole stack trace, because drivers and the FFM loader
 * put absolute paths inside their messages. Pure string handling, no Minecraft classes.
 */
public final class LogPaths {
    private static final Pattern PROFILE = Pattern.compile("(?i)(?:[a-z]:)?[/\\\\](?:users|home)[/\\\\][^/\\\\\\s]+");
    private static volatile String gameDir = "";

    private LogPaths() {
    }

    /** Remembers the game directory so that later paths are printed relative to it. */
    public static void setGameDir(Path dir) {
        gameDir = dir == null ? "" : dir.toAbsolutePath().normalize().toString();
    }

    public static String show(Object path) {
        if (path == null) {
            return "null";
        }
        String s = String.valueOf(path);
        String g = gameDir;
        if (!g.isEmpty()) {
            s = replaceAnySlash(s, g, "<gameDir>");
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            s = replaceAnySlash(s, home, "<home>");
        }
        return PROFILE.matcher(s).replaceAll("<home>");
    }

    /**
     * The whole stack trace of {@code t}, including every {@code Caused by} line, redacted like {@link #show}.
     * Needed because the throwable logging form ({@code LOGGER.error(msg, t)}) prints the cause chain, and a FFM
     * {@code libraryLookup} failure carries the absolute DLL path inside its own message: redacting only the message
     * written around it would leave the leak one line below.
     */
    public static String stack(Throwable t) {
        if (t == null) {
            return "null";
        }
        StringWriter buffer = new StringWriter();
        t.printStackTrace(new PrintWriter(buffer));
        return show(buffer.toString());
    }

    private static String replaceAnySlash(String s, String needle, String repl) {
        String fwd = needle.replace((char) 92, '/');
        String back = needle.replace('/', (char) 92);
        return s.replace(needle, repl).replace(fwd, repl).replace(back, repl);
    }
}
