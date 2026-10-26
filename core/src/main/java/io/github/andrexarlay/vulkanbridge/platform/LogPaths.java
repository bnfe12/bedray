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
    /**
     * A profile directory of any machine. Five rules, each learned from a shape that actually leaked or actually got
     * swallowed: the separator runs are {@code +} because a JSON or {@code Properties} dump escapes its backslashes and
     * {@code C:\\Users\\bob} failed to match a single-separator pattern; the name may contain spaces and apostrophes
     * because Windows/macOS account names do ("John Smith", "O'Brien"), and the old form stopped at the first one and
     * printed {@code Smith\AppData\...} or {@code 'Brien\AppData\...}; the name stops at {@code : ; , ( )} and a double
     * quote because {@code AccessDeniedException} prints {@code <path>: Access denied} and the reason after the path is
     * the part the owner needs to read; the root must be absolute — a drive letter, or a leading backslash segment like
     * the Silverblue/Solaris home mounts or a dotless server share — because {@code github.com/users/foo},
     * {@code docs/users/manual.pdf} and {@code aka.ms/vs/redist/home/page} all matched the unanchored form and came out
     * as {@code <home>}; and the drive branch refuses a letter that is itself part of a word ({@code (?<![a-z])}) so that
     * the {@code file:} scheme of a {@code file:/home/bob} URL is not mistaken for an {@code e:} drive and printed as
     * {@code fil<home>}.
     *
     * <p>Any number of dotless path segments may precede {@code users|home}, so {@code /var/home/bob} (Fedora Silverblue
     * / Universal Blue), {@code /export/home/jones} and {@code \\server\share\Users\bob} redact. Skipping only dotless
     * segments is what keeps the form safe on URLs: a hostname carries a dot, so {@code github.com/users} cannot be
     * walked into. The known hole: a server name that itself contains a dot ({@code \\wsl.localhost\...\home\bob}) is
     * left as written — on the owner's own machine that path is still caught, because {@link #setGameDir} and
     * {@code user.home} are replaced literally before this pattern runs. Spaces in the name are capped at two following
     * words so prose after a bare {@code /home} is swallowed only in small pieces; a bare relative
     * {@code /home/settings.cfg} is over-redacted (a filename is indistinguishable from a username) — over-redaction
     * loses nothing the owner needs, a leak would.
     */
    private static final Pattern PROFILE = Pattern.compile(
        "(?i)(?:^|(?<=[\\s\"'(,;:=]))"
            + "(?:(?<![a-z])[a-z]:[/\\\\]+|(?:[/\\\\]+[\\w $-]+)*[/\\\\]+)"
            + "(?:users|home)[/\\\\]+"
            + "[^/\\\\:;(),\" \\r\\n]+(?: [^/\\\\:;(),\" \\r\\n]+){0,2}");

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
