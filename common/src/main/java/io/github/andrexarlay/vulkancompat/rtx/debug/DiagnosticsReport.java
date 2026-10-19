package io.github.andrexarlay.vulkancompat.rtx.debug;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Format of the text report written by {@link Diagnostics} (pure Java, unit tested): a header, numbered sections of
 * lines, a footer. Also reads the WARN / ERROR entries of latest.log.
 */
public final class DiagnosticsReport {
    public static final int FORMAT_VERSION = 1;
    public static final String TITLE = "Bedray diagnostics report";
    public static final String FILE_PREFIX = "bedray-report-";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final char SECTION_SIGN = (char) 0xA7;

    /** One block of the report. */
    public record Section(String title, List<String> lines) {
        public Section {
            lines = List.copyOf(lines);
        }
    }

    private DiagnosticsReport() {}

    /** {@code bedray-report-20261017-153012.txt} (UTC time, no player or machine identifier). */
    public static String fileName(Instant when) {
        return FILE_PREFIX + FILE_TIME.format(when) + ".txt";
    }

    public static String render(Instant when, List<Section> sections) {
        StringBuilder b = new StringBuilder(8192);
        b.append("=== ").append(TITLE).append(" ===\n");
        b.append("format: ").append(FORMAT_VERSION).append('\n');
        b.append("generated (UTC): ").append(DateTimeFormatter.ISO_INSTANT.format(when.truncatedTo(ChronoUnit.SECONDS))).append('\n');
        b.append("Technical information only. Nothing was sent anywhere. Paths, account and machine names, the player nick, UUIDs,\n");
        b.append("e-mails and IP addresses are replaced by ").append(DiagnosticsScrubber.REDACTED).append(". Read it before you attach it to an issue.\n");
        int n = 1;
        for (Section s : sections) {
            b.append("\n--- ").append(n++).append(". ").append(s.title()).append(" ---\n");
            for (String line : s.lines()) {
                b.append(line).append('\n');
            }
        }
        b.append("\n=== end of report ===\n");
        return b.toString();
    }

    /** Removes Minecraft colour codes (section sign + one character) from F3 text. */
    public static String stripFormatting(String s) {
        if (s == null || s.indexOf(SECTION_SIGN) < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == SECTION_SIGN && i + 1 < s.length()) {
                i++;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * The last {@code maxEntries} WARN / ERROR / FATAL entries of a Minecraft log file, each with up to {@code maxContinuation}
     * following continuation lines (stack frames, "Caused by"). Only the last {@code tailBytes} of the file are read. Lines are cut
     * to {@code maxLine} characters. Empty list when the file is missing; an unreadable file gives one explanatory line.
     */
    public static List<String> warnErrorLines(Path log, int maxEntries, int maxContinuation, int tailBytes, int maxLine) {
        List<String> out = new ArrayList<>();
        if (log == null || !Files.isRegularFile(log)) {
            return out;
        }
        String text;
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
            long len = f.length();
            long start = Math.max(0, len - tailBytes);
            f.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            f.readFully(buf);
            text = new String(buf, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = text.indexOf('\n');
                text = nl >= 0 ? text.substring(nl + 1) : text;
            }
        } catch (IOException e) {
            out.add("(latest.log could not be read: " + e.getClass().getSimpleName() + ")");
            return out;
        }
        List<List<String>> entries = new ArrayList<>();
        List<String> cur = null;
        int cont = 0;
        for (String raw : text.split("\r?\n")) {
            if (isLevelLine(raw)) {
                cur = null;
                if (raw.contains("/WARN]") || raw.contains("/ERROR]") || raw.contains("/FATAL]")) {
                    cur = new ArrayList<>();
                    cur.add(cut(raw, maxLine));
                    entries.add(cur);
                    cont = 0;
                }
            } else if (cur != null && cont < maxContinuation && !raw.isBlank()) {
                cur.add(cut(raw, maxLine));
                cont++;
            }
        }
        int from = Math.max(0, entries.size() - maxEntries);
        for (int i = from; i < entries.size(); i++) {
            out.addAll(entries.get(i));
        }
        return out;
    }

    /** A log line that starts a new entry: {@code [12:34:56] [Render thread/INFO] [name]: text}. */
    static boolean isLevelLine(String line) {
        if (line.length() < 12 || line.charAt(0) != '[' || line.indexOf("] [") < 0) {
            return false;
        }
        return line.contains("/INFO]") || line.contains("/WARN]") || line.contains("/ERROR]") || line.contains("/DEBUG]")
            || line.contains("/FATAL]") || line.contains("/TRACE]");
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
