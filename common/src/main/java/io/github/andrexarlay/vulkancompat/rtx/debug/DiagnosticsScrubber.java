package io.github.andrexarlay.vulkancompat.rtx.debug;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from the text of a diagnostics report before it is written or copied. Pure Java, no Minecraft classes.
 * <p>
 * Replaced by {@value #REDACTED}:
 * <ul>
 *   <li>absolute file paths (Windows drive and UNC paths, {@code /home/..}, {@code /Users/..}, {@code ~/..} and similar); only the
 *       last name of the path (the file) is kept, for example a Windows profile path ending in {@code a.jar} becomes
 *       {@code <redacted>/a.jar};</li>
 *   <li>the account name of the operating system, the machine name, the player nick (literals given to {@link #of});</li>
 *   <li>UUIDs (dashed and the 32 character form), e-mail addresses, IPv4 and IPv6 addresses;</li>
 *   <li>values after keys such as {@code token}, {@code password}, {@code secret}, {@code api_key}, and {@code Bearer ...}.</li>
 * </ul>
 * {@link #scrubIdentity} does everything except IP addresses (used for text that holds version numbers such as {@code 310.9.1.0}).
 */
public final class DiagnosticsScrubber {
    public static final String REDACTED = "<redacted>";

    // Windows profile path: the account folder may contain spaces.
    private static final Pattern WIN_USER_HOME = Pattern.compile(
        "(?i)\\b([A-Z]:[\\\\/](?:Users|Documents and Settings)[\\\\/])[^\\\\/:*?\"<>|\\r\\n]+");
    // any drive path: inner segments may contain spaces (a separator must follow them), the last segment ends at a space
    private static final Pattern WIN_PATH = Pattern.compile(
        "\\b[A-Za-z]:(?:[\\\\/][^\\\\/:*?\"<>|\\r\\n]+(?=[\\\\/]))*[\\\\/][^\\\\/:*?\"<>|\\s'\\],;)]*");
    private static final Pattern UNC_PATH = Pattern.compile(
        "\\\\\\\\[^\\\\/:*?\"<>|\\s]+(?:\\\\[^\\\\/:*?\"<>|\\r\\n]+(?=\\\\))*\\\\[^\\\\/:*?\"<>|\\s'\\],;)]*");
    private static final Pattern UNIX_PATH = Pattern.compile(
        "(?<![\\w:.\\-/])/(?:home|Users|root|mnt|media|run/media|tmp|var|opt|srv)(?:/[^/:*?\"<>|\\s'\\],;)]+)*");
    private static final Pattern TILDE_PATH = Pattern.compile("(?<![\\w.\\-])~(?:/[^/:*?\"<>|\\s'\\],;)]+)+");
    private static final Pattern ENV_HOME = Pattern.compile("(?i)%(?:USERPROFILE|APPDATA|LOCALAPPDATA|HOMEPATH|HOMEDRIVE)%[^\\s'\"]*");

    private static final Pattern HOME_ROOT = Pattern.compile(
        "(?i)^(?:[A-Z]:[\\\\/](?:Users|Documents and Settings)|/(?:home|Users))[\\\\/][^\\\\/]+$");
    private static final Pattern UUID_DASHED = Pattern.compile(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern UUID_PLAIN = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)+");
    private static final Pattern IPV4 = Pattern.compile(
        "(?<![\\w.\\-])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?::\\d{1,5})?(?![\\w\\-]|\\.\\d)");
    private static final Pattern IPV6_FULL = Pattern.compile("(?i)(?<![\\w:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![\\w:])");
    private static final Pattern IPV6_SHORT = Pattern.compile(
        "(?i)(?<![\\w:])(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,5})?(?![\\w:])|(?<![\\w:])::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?(?![\\w:])");
    private static final Pattern SECRET_KV = Pattern.compile(
        "(?i)\\b((?:access[_\\-]?token|session[_\\-]?(?:id|token)|auth[_\\-]?token|token|password|passwd|pwd|secret|api[_\\-]?key|client[_\\-]?secret)\\s*[=:]\\s*)(\\S+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer\\s+)[A-Za-z0-9._~+/=\\-]{8,}");

    private final List<Pattern> literals;

    private DiagnosticsScrubber(List<Pattern> literals) {
        this.literals = literals;
    }

    /**
     * @param literals strings that identify this player or machine (account name, machine name, nick, UUID text, game directory...);
     *                 blank and very short (&lt; 3 characters) values are ignored
     */
    public static DiagnosticsScrubber of(String... literals) {
        Set<String> uniq = new LinkedHashSet<>();
        for (String s : literals) {
            if (s != null && s.strip().length() >= 3) {
                uniq.add(s.strip());
            }
        }
        List<String> sorted = new ArrayList<>(uniq);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));   // longest first: a path before the name inside it
        List<Pattern> ps = new ArrayList<>(sorted.size());
        for (String s : sorted) {
            ps.add(Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(s) + "(?![A-Za-z0-9_])"));
        }
        return new DiagnosticsScrubber(List.copyOf(ps));
    }

    /** Scrubber without any literal (patterns only). */
    public static DiagnosticsScrubber patternsOnly() {
        return new DiagnosticsScrubber(List.of());
    }

    /** Everything, including IP addresses. For free text: log lines, exception messages, status details. */
    public String scrub(String text) {
        return scrub(text, true);
    }

    /** Everything except IP addresses. For structured text that contains dotted version numbers. */
    public String scrubIdentity(String text) {
        return scrub(text, false);
    }

    private String scrub(String text, boolean ips) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text;
        s = SECRET_KV.matcher(s).replaceAll("$1" + REDACTED);
        s = BEARER.matcher(s).replaceAll("$1" + REDACTED);
        // paths first: a path may contain the account name, the nick or a UUID
        s = replacePaths(WIN_PATH, s);
        s = replacePaths(UNC_PATH, s);
        s = replacePaths(UNIX_PATH, s);
        s = replacePaths(TILDE_PATH, s);
        s = replacePaths(ENV_HOME, s);
        s = WIN_USER_HOME.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(REDACTED));   // profile root without a trailing path segment
        for (Pattern p : literals) {
            s = p.matcher(s).replaceAll(Matcher.quoteReplacement(REDACTED));
        }
        s = UUID_DASHED.matcher(s).replaceAll(REDACTED);
        s = UUID_PLAIN.matcher(s).replaceAll(REDACTED);
        s = EMAIL.matcher(s).replaceAll(REDACTED);
        if (ips) {
            s = IPV4.matcher(s).replaceAll(REDACTED);
            s = IPV6_FULL.matcher(s).replaceAll(REDACTED);
            s = IPV6_SHORT.matcher(s).replaceAll(REDACTED);
        }
        return s;
    }

    /** {@code <dirs>/name} becomes {@code <redacted>/name} when the last segment looks like a file (has a dot), else {@code <redacted>}. */
    private static String replacePaths(Pattern p, String s) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String path = m.group();
            int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String last = cut >= 0 ? path.substring(cut + 1) : "";
            boolean file = last.indexOf('.') > 0 && !last.endsWith(".") && !last.contains(REDACTED) && !HOME_ROOT.matcher(path).matches();
            m.appendReplacement(out, Matcher.quoteReplacement(file ? REDACTED + "/" + last : REDACTED));
        }
        m.appendTail(out);
        return out.toString();
    }
}
