package io.github.andrexarlay.vulkancompat.rtx.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsScrubberTest {
    private final DiagnosticsScrubber s = DiagnosticsScrubber.of("Steve_99", "andre", "DESKTOP-XYZ12",
        "069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void windowsUserPathKeepsOnlyFileName() {
        String out = s.scrub("Loaded " + windowsPath("C:", "Users", "andre", "AppData", "Roaming", "Launcher", "instances", "Test", "mods", "bedray.jar") + " ok");
        assertEquals("Loaded <redacted>/bedray.jar ok", out);
    }

    @Test
    void windowsPathWithSpacesInAccountName() {
        String out = s.scrub("dir=" + windowsPath("C:", "Users", "John Smith", "Documents", "Minecraft", "logs", "latest.log") + ";");
        assertFalse(out.contains("John"), out);
        assertFalse(out.contains("Smith"), out);
        assertTrue(out.contains("latest.log"), out);
    }

    @Test
    void windowsHomeRootAndForwardSlashes() {
        assertEquals("home <redacted>", s.scrub("home " + windowsPath("C:", "Users", "john.smith")));
        String out = s.scrub(String.join("/", "H:", "Game", "build", "libs", "sodium.jar"));
        assertEquals("<redacted>/sodium.jar", out);
    }

    @Test
    void uncPath() {
        String out = s.scrub("share \\\\NAS01\\media\\andre\\save.dat end");
        assertFalse(out.contains("NAS01"), out);
        assertFalse(out.contains("media"), out);
    }

    @Test
    void linuxPaths() {
        assertEquals("<redacted>/bedray.jar", s.scrub("/home/maria/.local/share/PrismLauncher/mods/bedray.jar"));
        assertEquals("cfg <redacted>", s.scrub("cfg /home/maria"));
        assertEquals("<redacted>/a.toml", s.scrub("/Users/bob/Library/Prefs/x/a.toml"));
        assertEquals("<redacted>/bedray.toml", s.scrub("~/.config/bedray/bedray.toml"));
        assertEquals("<redacted>/n.so", s.scrub("/mnt/c/Users/bob/n.so"));
    }

    @Test
    void urlsAreNotTreatedAsPaths() {
        String t = "see https://example.org/home/page and java/lang/String";
        assertEquals(t, s.scrub(t));
    }

    @Test
    void literalsNickAccountMachine() {
        String out = s.scrub("Setting user: Steve_99 on DESKTOP-XYZ12 by Andre");
        assertEquals("Setting user: <redacted> on <redacted> by <redacted>", out);
        assertEquals("steve_999 stays", s.scrub("steve_999 stays"));   // not a whole word
    }

    @Test
    void uuids() {
        assertEquals("id <redacted> and <redacted>", s.scrub("id 069a79f4-44e9-4726-a5be-fca90e38aaf5 and 0123456789abcdef0123456789ABCDEF"));
        String sha = "a".repeat(64);
        assertEquals(sha, s.scrub(sha));   // a SHA-256 is not a UUID
    }

    @Test
    void emails() {
        String first = "john.doe+mc@" + "example.co.uk";
        String second = "x_y@" + "mail.ru";
        assertEquals("mail <redacted>, <redacted>.", s.scrub("mail " + first + ", " + second + "."));
        assertEquals("Foo@1a2b3c", s.scrub("Foo@1a2b3c"));
    }

    @Test
    void ipAddresses() {
        assertEquals("connect <redacted> and <redacted>", s.scrub("connect 192.168.1.20:25565 and 8.8.8.8"));
        assertEquals("v <redacted>", s.scrub("v 2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
        assertEquals("to <redacted>", s.scrub("to fe80::1ff:fe23:4567:890a"));
        assertEquals("loopback <redacted>", s.scrub("loopback ::1"));
        assertEquals("time [12:34:56] ok", s.scrub("time [12:34:56] ok"));
        assertEquals("999.1.1.1 stays", s.scrub("999.1.1.1 stays"));
    }

    @Test
    void identityScrubKeepsDottedVersions() {
        assertEquals("nvngx 310.9.1.0 at <redacted>/x.dll", s.scrubIdentity("nvngx 310.9.1.0 at C:\\Games\\x.dll"));
    }

    @Test
    void secrets() {
        assertEquals("accessToken=<redacted> x", s.scrub("accessToken=eyJhbGciOi.abc x"));
        assertEquals("Authorization: Bearer <redacted>", s.scrub("Authorization: Bearer abcdefgh12345678"));
        assertEquals("password: <redacted>", s.scrub("password: hunter2"));
    }

    @Test
    void formatOfTheReport() {
        Instant t = Instant.parse("2026-10-17T15:30:12Z");
        assertEquals("bedray-report-20261017-153012.txt", DiagnosticsReport.fileName(t));
        String text = DiagnosticsReport.render(t, List.of(new DiagnosticsReport.Section("Versions", List.of("Bedray: 1")),
            new DiagnosticsReport.Section("GPU", List.of("GPU: x", "Driver: y"))));
        assertTrue(text.startsWith("=== Bedray diagnostics report ===\nformat: 1\ngenerated (UTC): 2026-10-17T15:30:12Z\n"), text);
        assertTrue(text.contains("\n--- 1. Versions ---\nBedray: 1\n"), text);
        assertTrue(text.contains("\n--- 2. GPU ---\nGPU: x\nDriver: y\n"), text);
        assertTrue(text.contains("Nothing was sent anywhere"), text);
        assertTrue(text.endsWith("=== end of report ===\n"), text);
    }

    @Test
    void colourCodesAreStripped() {
        char sign = (char) 0xA7;
        assertEquals("RT: path 4 bounce", DiagnosticsReport.stripFormatting(sign + "aRT: " + sign + "bpath" + sign + "r 4 bounce"));
    }

    @Test
    void logTailTakesWarnAndErrorWithContinuation(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("latest.log");
        Files.writeString(log, String.join("\n",
            "[10:00:00] [main/INFO] [a]: started",
            "[10:00:01] [Render thread/WARN] [bedray]: first warning",
            "[10:00:02] [Render thread/INFO] [a]: info",
            "[10:00:03] [Render thread/ERROR] [bedray]: boom",
            "java.lang.IllegalStateException: x",
            "\tat a.B.c(B.java:1)",
            "[10:00:04] [main/INFO] [a]: end", ""));
        List<String> l = DiagnosticsReport.warnErrorLines(log, 10, 5, 1 << 20, 200);
        assertEquals(List.of("[10:00:01] [Render thread/WARN] [bedray]: first warning",
            "[10:00:03] [Render thread/ERROR] [bedray]: boom", "java.lang.IllegalStateException: x", "\tat a.B.c(B.java:1)"), l);
        assertEquals(1, DiagnosticsReport.warnErrorLines(log, 1, 0, 1 << 20, 200).size());
        assertTrue(DiagnosticsReport.warnErrorLines(dir.resolve("missing.log"), 5, 5, 100, 100).isEmpty());
    }

    @Test
    void perfHistoryAverages() {
        PerfHistory h = new PerfHistory();
        double[] gpu = new double[PerfHistory.PHASES.length];
        boolean[] ok = new boolean[PerfHistory.PHASES.length];
        ok[2] = true;   // rt
        long t0 = 1_000_000_000_000L;
        for (int i = 0; i < 10; i++) {
            gpu[2] = 4.0 + i;
            h.record(t0 + i * 500_000_000L, 10.0 + i, gpu, ok);
        }
        PerfHistory.Summary all = h.summary(t0 + 5_000_000_000L, 30);
        assertEquals(10, all.windows());
        assertEquals(14.5, all.avgFrameMs(), 1e-9);
        assertEquals(19.0, all.worstFrameMs(), 1e-9);
        assertEquals(8.5, all.avgGpuMs()[2], 1e-9);
        assertTrue(all.formatGpu().contains("rt 8.50"), all.formatGpu());
        PerfHistory.Summary last = h.summary(t0 + 5_000_000_000L, 1.2);
        assertEquals(2, last.windows());
        assertEquals("no performance data yet", new PerfHistory().summary(t0, 30).format());
    }

    private static String windowsPath(String... parts) {
        return String.join(String.valueOf((char) 92), parts);
    }
}
