package io.github.andrexarlay.vulkancompat.rtx.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class TestReportBundleTest {
    @Test void exportContainsOnlyScrubbedAllowlistedTechnicalFiles(@TempDir Path game) throws Exception {
        Files.createDirectories(game.resolve("logs"));
        Files.createDirectories(game.resolve("bedray/profiles/test"));
        Files.writeString(game.resolve("logs/latest.log"),
            "Setting user: TEST_PLAYER\n--accessToken SYNTHETIC_TEST_VALUE token=SYNTHETIC_TEST_VALUE\nserver 192.0.2.1\nWARN GPU failed\n");
        Files.writeString(game.resolve("accounts.json"), "THIS_FILE_MUST_NEVER_BE_EXPORTED");
        Path capture = game.resolve("bedray/profiles/test");
        Files.writeString(capture.resolve("frames.csv"), "frame,interval_ms\n1,16.0\n");
        Files.writeString(capture.resolve("manifest.json"), "{\"capture_complete\":false}");
        Files.writeString(capture.resolve("summary.txt"), "Incomplete measurement\n");
        Files.writeString(capture.resolve("unrelated.txt"), "DO_NOT_INCLUDE");
        Path result = TestReportBundle.write(game, "GPU: test; account TEST_PLAYER", capture,
            DiagnosticsScrubber.of("TEST_PLAYER", game.toString()));
        try (ZipFile zip = new ZipFile(result.toFile())) {
            assertEquals(6, zip.size());
            String log = new String(zip.getInputStream(zip.getEntry("latest.log")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(log.contains("GPU failed"));
            assertFalse(log.contains("TEST_PLAYER"));
            assertFalse(log.contains("SYNTHETIC_TEST_VALUE"));
            assertFalse(log.contains("192.0.2.1"));
            assertNotNull(zip.getEntry("performance/frames.csv"));
            assertNull(zip.getEntry("accounts.json"));
            assertNull(zip.getEntry("unrelated.txt"));
        }
    }

    @Test void missingCaptureAndLogAreExplicit(@TempDir Path game) throws Exception {
        Path result = TestReportBundle.write(game, "test", null, DiagnosticsScrubber.patternsOnly());
        try (ZipFile zip = new ZipFile(result.toFile())) {
            String readme = new String(zip.getInputStream(zip.getEntry("README.txt")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(readme.contains("No completed FPS capture"));
            assertEquals(3, zip.size());
        }
    }

    @Test void refusesCaptureOutsideGameAndBoundsLogTail(@TempDir Path game, @TempDir Path outside) throws Exception {
        assertThrows(java.io.IOException.class,
            () -> TestReportBundle.write(game, "test", outside, DiagnosticsScrubber.patternsOnly()));
        Files.createDirectories(game.resolve("logs"));
        Files.writeString(game.resolve("logs/latest.log"), "a".repeat(3 * 1024 * 1024) + "\nWARN final error\n");
        Path result = TestReportBundle.write(game, "test", null, DiagnosticsScrubber.patternsOnly());
        try (ZipFile zip = new ZipFile(result.toFile())) {
            String log = new String(zip.getInputStream(zip.getEntry("latest.log")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(log.contains("WARN final error"));
            assertTrue(log.length() < 2 * 1024 * 1024 + 100);
        }
    }

    @Test void issueUsesExistingGpuFormAndKeepsFullLogsOutOfUrl() {
        var uri = TestReportBundle.issueUri("Test GPU & driver", "0.2.0 @abc", "100 FPS", "test.zip");
        assertEquals("https", uri.getScheme());
        assertEquals("github.com", uri.getHost());
        assertEquals("/bnfe12/bedray/issues/new", uri.getPath());
        String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("template=gpu_test_report.yml"));
        assertTrue(query.contains("hardware=Test GPU & driver"));
        assertTrue(query.contains("performance=100 FPS"));
        assertTrue(query.contains("test.zip"));
        assertFalse(query.contains("token"));
        assertTrue(uri.toString().length() < 2000);
    }
}
