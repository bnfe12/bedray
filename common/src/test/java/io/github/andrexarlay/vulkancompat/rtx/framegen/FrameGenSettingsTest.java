package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2: parsing and persistence of the frame generation settings (no GPU, no Streamline). */
public class FrameGenSettingsTest {
    @Test
    public void parsesModeProperty() {
        assertEquals(FrameGenSettings.Mode.OFF, FrameGenSettings.parseMode("off"));
        assertEquals(FrameGenSettings.Mode.X2, FrameGenSettings.parseMode("2x"));
        assertEquals(FrameGenSettings.Mode.X2, FrameGenSettings.parseMode("dlss"));
        assertEquals(FrameGenSettings.Mode.X3, FrameGenSettings.parseMode("X3"));
        assertEquals(FrameGenSettings.Mode.X4, FrameGenSettings.parseMode("4x"));
        assertNull(FrameGenSettings.parseMode(null));
        assertNull(FrameGenSettings.parseMode("9x"));
    }

    @Test
    public void multiplierAndGeneratedFrames() {
        assertEquals(1, FrameGenSettings.Mode.OFF.multiplier());
        assertEquals(1, FrameGenSettings.Mode.X2.generated());
        assertEquals(3, FrameGenSettings.Mode.X4.generated());
    }

    @Test
    public void tomlRoundTrip() throws Exception {
        FrameGenSettings.applyKeyValue("framegen_mode", "X3");
        FrameGenSettings.applyKeyValue("reflex_mode", "BOOST");
        FrameGenSettings.applyKeyValue("framegen_frame_limit", "120");
        StringWriter w = new StringWriter();
        FrameGenSettings.write(w);
        String t = w.toString();
        assertTrue(t.contains("[frame_generation]"));
        assertTrue(t.contains("framegen_mode = \"X3\""));
        assertTrue(t.contains("reflex_mode = \"BOOST\""));
        assertTrue(t.contains("framegen_frame_limit = 120"));
        assertEquals(120, FrameGenSettings.frameLimitFps());
        FrameGenSettings.applyKeyValue("framegen_frame_limit", "nonsense"); // keeps the value, no exception
        assertEquals(120, FrameGenSettings.frameLimitFps());
    }

    @Test
    public void statusBadgeAndLineInNeedsRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path shim = tempDir.resolve("vkb_sl.dll");
        for (String f : FrameGenEarly.REQUIRED) { // every required Streamline binary must exist, not only the interposer
            java.nio.file.Files.writeString(tempDir.resolve(f), "dummy");
        }
        java.nio.file.Files.writeString(shim, "dummy");

        String oldDir = System.getProperty(StreamlineBootstrap.PROP_DIR);
        String oldShim = System.getProperty(StreamlineBootstrap.PROP_SHIM);
        FrameGenSettings.Mode oldMode = FrameGenSettings.mode();
        try {
            System.setProperty(StreamlineBootstrap.PROP_DIR, tempDir.toString());
            System.setProperty(StreamlineBootstrap.PROP_SHIM, shim.toString());

            assertEquals(FrameGenRuntime.Availability.NEEDS_RESTART, FrameGenRuntime.availability());

            // Mode OFF
            FrameGenSettings.setMode(FrameGenSettings.Mode.OFF);
            assertEquals("Off", FrameGenRuntime.statusBadge());
            assertEquals("Off (Select 2x and restart to activate Streamline)", FrameGenRuntime.statusLine());

            // Mode X2
            FrameGenSettings.setMode(FrameGenSettings.Mode.X2);
            assertEquals("Pending Restart", FrameGenRuntime.statusBadge());
            assertEquals("Restart required to activate Streamline (2x)", FrameGenRuntime.statusLine());
        } finally {
            FrameGenSettings.setMode(oldMode);
            if (oldDir != null) System.setProperty(StreamlineBootstrap.PROP_DIR, oldDir);
            else System.clearProperty(StreamlineBootstrap.PROP_DIR);
            if (oldShim != null) System.setProperty(StreamlineBootstrap.PROP_SHIM, oldShim);
            else System.clearProperty(StreamlineBootstrap.PROP_SHIM);
        }
    }

    @Test
    public void statusBadgeFollowsProvenState() throws Exception {
        String src = readSource("src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/FrameGenRuntime.java");
        int active = src.indexOf("public static boolean isActive()");
        assertTrue(active > 0, "isActive() must exist");
        String body = src.substring(active, src.indexOf("\n    }", active));
        assertTrue(body.contains("return status().state() == State.ACTIVE;"),
            "isActive() must follow the proven state machine, not the requested mode: " + body);
        assertTrue(!body.contains("modeOn"), "isActive() must not read modeOn directly: " + body);

        int badge = src.indexOf("public static String statusBadge()");
        String badgeBody = src.substring(badge, src.indexOf("\n    }", badge));
        assertTrue(badgeBody.contains("Status st = status();"), "the badge must read the state machine: " + badgeBody);
        assertTrue(badgeBody.contains("case ACTIVE -> snapshot().multiplier() + \"x\";"), "2x only when proven: " + badgeBody);
        assertTrue(badgeBody.contains("case ENGAGING -> \"Pending\";"), "engaging must not claim a multiplier: " + badgeBody);

        String fsr = readSource("src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/fsr3/Fsr3FrameGen.java");
        assertTrue(fsr.contains("if (nat == null && (swapchainFailed || resolveDll() == null)) {"),
            "FSR 3 must not advertise READY before its library resolves");
    }

    private static String readSource(String relative) throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(relative);
        if (!java.nio.file.Files.isRegularFile(p)) {
            p = java.nio.file.Path.of("common").resolve(relative);
        }
        return java.nio.file.Files.readString(p);
    }

    @Test
    public void findConfigFileWithSystemProperties(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path configDir = tempDir.resolve("config");
        java.nio.file.Files.createDirectories(configDir);
        java.nio.file.Path toml = configDir.resolve("bedray.toml");
        java.nio.file.Files.writeString(toml, "framegen_mode = \"X2\"\n");

        String oldNeo = System.getProperty("net.neoforged.gameDir");
        String oldFabric = System.getProperty("fabric.gameDir");
        try {
            System.clearProperty("net.neoforged.gameDir");
            System.setProperty("fabric.gameDir", tempDir.toString());
            java.nio.file.Path found = FrameGenEarly.findConfigFile();
            assertEquals(toml.toAbsolutePath().normalize(), found);
            assertEquals(FrameGenSettings.Mode.X2, FrameGenSettings.peekModeFromFile(found));
        } finally {
            if (oldNeo != null) System.setProperty("net.neoforged.gameDir", oldNeo);
            else System.clearProperty("net.neoforged.gameDir");
            if (oldFabric != null) System.setProperty("fabric.gameDir", oldFabric);
            else System.clearProperty("fabric.gameDir");
        }
    }
}
