package io.github.andrexarlay.vulkancompat.rtx.debug;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fixed, bounded technical attachments. No network operations, tokens, worlds or account files. */
public final class TestReportBundle {
    private static final int LOG_LIMIT = 2 * 1024 * 1024;
    private static final int CAPTURE_LIMIT = 24 * 1024 * 1024;
    private TestReportBundle() {}

    public static Path write(Path game, String report, Path capture, DiagnosticsScrubber scrubber) throws IOException {
        Path root = game.toRealPath();
        Path reports = root.resolve("bedray/reports");
        Files.createDirectories(reports);
        if (!reports.toRealPath().startsWith(root)) throw new IOException("reports directory leaves game root");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("diagnostics.txt", scrubber.scrubIdentity(report));
        files.put("latest.log", scrubber.scrub(tail(root.resolve("logs/latest.log"), LOG_LIMIT)));
        String captureNote = "No completed FPS capture in this session. Run /bedray test in a world first.\n";
        if (capture != null) {
            Path profiles = root.resolve("bedray/profiles");
            Path source = capture.toRealPath();
            if (!source.startsWith(profiles) || Files.isSymbolicLink(capture))
                throw new IOException("capture outside the profile directory");
            for (String name : new String[]{"summary.txt", "manifest.json", "frames.csv"}) {
                Path file = source.resolve(name);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.size(file) > CAPTURE_LIMIT) throw new IOException("capture exceeds report limit");
                    files.put("performance/" + name, scrubber.scrubIdentity(Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
            captureNote = files.getOrDefault("performance/summary.txt", "Capture summary unavailable.\n");
        }
        files.put("README.txt", "Bedray tester report. Review before attaching to the GitHub GPU test issue.\n"
            + "Contains scrubbed diagnostics, a bounded log tail and the last FPS capture from this session.\n"
            + "Worlds, screenshots, account files and launcher credentials are not included.\n"
            + "FPS counts application frames; generated display frames are excluded.\n\n" + captureNote);
        Path archive = Files.createTempFile(reports, "bedray-test-" + Instant.now().toEpochMilli() + "-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(archive);
            throw failure;
        }
        return archive;
    }

    /** Only short, scrubbed form fields go into the browser URL. Full logs stay in the local ZIP. */
    public static URI issueUri(String hardware, String build, String performance, String archiveName) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("template", "gpu_test_report.yml");
        fields.put("title", "[GPU test] " + shortText(build, 80));
        fields.put("hardware", shortText(hardware, 180));
        fields.put("build", shortText(build, 180));
        fields.put("performance", shortText(performance, 320));
        fields.put("result", "Tested with the Bedray tester build. Describe the scene and any problems here.");
        fields.put("evidence", "Attach the reviewed " + shortText(archiveName, 110)
            + " ZIP from the opened reports folder. Diagnostics are also in the clipboard. Add screenshots if useful.");
        StringBuilder url = new StringBuilder("https://github.com/bnfe12/bedray/issues/new?");
        fields.forEach((key, value) -> url.append(key).append('=')
            .append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append('&'));
        return URI.create(url.substring(0, url.length() - 1));
    }

    private static String shortText(String text, int max) {
        String clean = DiagnosticsScrubber.patternsOnly().scrubIdentity(text == null ? "unavailable" : text)
            .replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String tail(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "latest.log unavailable.\n";
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            long length = input.length();
            long start = Math.max(0, length - limit);
            input.seek(start);
            byte[] bytes = new byte[(int) (length - start)];
            input.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0) {
                int newline = text.indexOf('\n');
                text = "[Earlier log lines omitted by the 2 MiB report limit]\n" + text.substring(newline + 1);
            }
            return text;
        }
    }
}
