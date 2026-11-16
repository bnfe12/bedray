package io.github.andrexarlay.vulkanbridge.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarInstallerTest {
    @TempDir
    Path tmp;

    private Path fakeJar(String name, String content) throws IOException {
        Path jar = tmp.resolve(name);
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        return jar;
    }

    @Test
    void installsVerifiesAndPreparesTheNativesFolder() throws IOException {
        Path jar = fakeJar("bedray-1.0.jar", "jar-bytes");
        Path game = Files.createDirectories(tmp.resolve("game"));
        JarInstaller.Result r = JarInstaller.install(jar, game);
        assertTrue(r.ok() && r.changed(), r.messages().toString());
        assertEquals("jar-bytes", Files.readString(game.resolve("mods").resolve("bedray-1.0.jar")));
        assertTrue(Files.isDirectory(game.resolve("bedray").resolve("natives")));
    }

    @Test
    void installingTwiceIsANoOp() throws IOException {
        Path jar = fakeJar("bedray-1.0.jar", "jar-bytes");
        Path game = Files.createDirectories(tmp.resolve("game"));
        JarInstaller.install(jar, game);
        JarInstaller.Result again = JarInstaller.install(jar, game);
        assertTrue(again.ok());
        assertFalse(again.changed());
    }

    @Test
    void aDifferentFileWithTheSameNameIsNeverOverwritten() throws IOException {
        Path jar = fakeJar("bedray-1.0.jar", "new");
        Path game = Files.createDirectories(tmp.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(mods.resolve("bedray-1.0.jar"), "old");
        JarInstaller.Result r = JarInstaller.install(jar, game);
        assertFalse(r.ok());
        assertEquals("old", Files.readString(mods.resolve("bedray-1.0.jar")));
    }

    @Test
    void olderBedrayJarsAreReportedNotDeleted() throws IOException {
        Path jar = fakeJar("bedray-2.0.jar", "v2");
        Path game = Files.createDirectories(tmp.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(mods.resolve("bedray-1.0.jar"), "v1");
        JarInstaller.Result r = JarInstaller.install(jar, game);
        assertTrue(r.ok());
        assertTrue(Files.exists(mods.resolve("bedray-1.0.jar")));
        assertTrue(r.messages().stream().anyMatch(m -> m.contains("bedray-1.0.jar")), r.messages().toString());
    }

    @Test
    void refusesANonJarSourceAndAMissingGameFolder() throws IOException {
        Path notJar = fakeJar("readme.txt", "x");
        assertFalse(JarInstaller.install(notJar, tmp).ok());
        assertFalse(JarInstaller.install(fakeJar("a.jar", "x"), tmp.resolve("missing")).ok());
        assertFalse(Files.exists(tmp.resolve("missing")));
    }

    @Test
    void detectsOfficialAndPrismFoldersOnlyWhenTheyExist() throws IOException {
        Path appData = Files.createDirectories(tmp.resolve("AppData"));
        assertTrue(InstallTargets.detect(Map.of("APPDATA", appData.toString())).isEmpty());
        Files.createDirectories(appData.resolve(".minecraft"));
        Files.createDirectories(appData.resolve("PrismLauncher").resolve("instances").resolve("Bedray test").resolve("minecraft"));
        Files.createDirectories(appData.resolve("PrismLauncher").resolve("instances").resolve("no game dir"));
        List<InstallTargets.Target> targets = InstallTargets.detect(Map.of("APPDATA", appData.toString()));
        assertEquals(2, targets.size(), targets.toString());
        assertTrue(targets.get(1).label().contains("Bedray test"));
        assertTrue(InstallTargets.detect(Map.of()).isEmpty());
    }
}
