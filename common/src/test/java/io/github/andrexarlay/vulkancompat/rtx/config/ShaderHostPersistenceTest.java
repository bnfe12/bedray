package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostConfig;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2: shader_host must survive a bedray.toml save/load round trip through VulkanRtxConfig. Before the fix the key was
 * dropped on save (writeAll never wrote the section) and ignored on load (applyKeyValue had no case), so a config save
 * silently reset the host to native. No GPU, no Minecraft: not evidence that a pack renders in game.
 */
class ShaderHostPersistenceTest {
    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        System.clearProperty(ShaderHostConfig.OVERRIDE_PROPERTY);
        ShaderHostConfig.setPersisted(ShaderHostKind.NATIVE);
        VulkanRtxConfig.setGameDirForTesting(Path.of("").toAbsolutePath().normalize());
    }

    @Test
    void applyKeyValueDelegatesShaderHost() {
        VulkanRtxConfig.applyKeyValue("shader_host", "iris");
        assertEquals(ShaderHostKind.IRIS, ShaderHostConfig.persisted(), "load must apply shader_host");
        VulkanRtxConfig.applyKeyValue("shader_host", "native");
        assertEquals(ShaderHostKind.NATIVE, ShaderHostConfig.persisted());
    }

    @Test
    void writeAllPersistsTheShaderHostSection() throws Exception {
        ShaderHostConfig.setPersisted(ShaderHostKind.IRIS);
        StringWriter w = new StringWriter();
        VulkanRtxConfig.writeAll(w);
        String toml = w.toString();
        assertTrue(toml.contains("[shader_host]"), "section header must be written: " + toml);
        assertTrue(toml.contains("shader_host = \"iris\""), "value must be written: " + toml);
    }

    @Test
    void shaderHostSurvivesSaveLoadRoundTrip() throws Exception {
        File cfg = dir.resolve("bedray.toml").toFile();
        VulkanRtxConfig.setConfigFileForTesting(cfg);
        VulkanRtxConfig.setGameDirForTesting(dir);
        ShaderHostConfig.setPersisted(ShaderHostKind.IRIS);
        VulkanRtxConfig.save();
        String written = Files.readString(cfg.toPath(), StandardCharsets.UTF_8);
        assertTrue(written.contains("shader_host = \"iris\""), "saved file must keep shader_host: " + written);

        // the game clobbers the in-memory value, then reloads from the file: iris must come back
        ShaderHostConfig.setPersisted(ShaderHostKind.NATIVE);
        VulkanRtxConfig.load();
        assertEquals(ShaderHostKind.IRIS, ShaderHostConfig.persisted(), "load must re-apply shader_host from the file");
    }

    @Test
    void sessionOverrideIsNeverPersisted() throws Exception {
        File cfg = dir.resolve("bedray.toml").toFile();
        VulkanRtxConfig.setConfigFileForTesting(cfg);
        VulkanRtxConfig.setGameDirForTesting(dir);
        ShaderHostConfig.setPersisted(ShaderHostKind.NATIVE);
        System.setProperty(ShaderHostConfig.OVERRIDE_PROPERTY, "iris");
        assertEquals(ShaderHostKind.IRIS, ShaderHostConfig.requested(), "the -D override drives the session");
        VulkanRtxConfig.save();
        String written = Files.readString(cfg.toPath(), StandardCharsets.UTF_8);
        assertTrue(written.contains("shader_host = \"native\""), "the persisted value is saved, not the override: " + written);
        assertFalse(written.contains("shader_host = \"iris\""), "the -D override must never leak into the file");
    }
}