package io.github.andrexarlay.vulkanbridge.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendGuardTest {
    @Test
    void alwaysKeepsVulkanOnUncleanStartup(@TempDir Path dir) {
        Path state = BackendGuard.stateFile(dir);
        assertEquals(BackendGuard.Decision.KEEP_VULKAN, BackendGuard.onUncleanStartup(state, true, 2));
        assertEquals(1, BackendGuard.readCount(state));
        assertEquals(BackendGuard.Decision.KEEP_VULKAN, BackendGuard.onUncleanStartup(state, true, 2));
        assertEquals(2, BackendGuard.readCount(state));
        assertEquals(BackendGuard.Decision.KEEP_VULKAN, BackendGuard.onUncleanStartup(state, true, 2));
        assertEquals(3, BackendGuard.readCount(state));
    }

    @Test
    void disabledGuardLetsTheVanillaResetThroughAndWritesNothing(@TempDir Path dir) {
        Path state = BackendGuard.stateFile(dir);
        assertEquals(BackendGuard.Decision.VANILLA_DISABLED, BackendGuard.onUncleanStartup(state, false, 2));
        assertFalse(Files.exists(state));
    }

    @Test
    void badMaxKeepsPropertyFallsBackInsteadOfCrashingTheStartup() {
        System.setProperty(BackendGuard.PROPERTY_MAX_KEEPS, "two");
        try {
            assertEquals(BackendGuard.DEFAULT_MAX_KEEPS, BackendGuard.maxKeepsFromProperties());
        } finally {
            System.clearProperty(BackendGuard.PROPERTY_MAX_KEEPS);
        }
        System.setProperty(BackendGuard.PROPERTY_MAX_KEEPS, "-1");
        try {
            assertEquals(BackendGuard.DEFAULT_MAX_KEEPS, BackendGuard.maxKeepsFromProperties());
        } finally {
            System.clearProperty(BackendGuard.PROPERTY_MAX_KEEPS);
        }
    }

    @Test
    void cleanStartupResetsTheCounter(@TempDir Path dir) {
        Path state = BackendGuard.stateFile(dir);
        BackendGuard.onUncleanStartup(state, true, 2);
        BackendGuard.onUncleanStartup(state, true, 2);
        BackendGuard.onCleanStartup(state);
        assertFalse(Files.exists(state));
        assertEquals(BackendGuard.Decision.KEEP_VULKAN, BackendGuard.onUncleanStartup(state, true, 2));
        BackendGuard.onCleanStartup(dir.resolve("missing.properties"));
    }

    @Test
    void stateFileIsUtf8WithoutBom(@TempDir Path dir) throws Exception {
        Path state = BackendGuard.stateFile(dir);
        BackendGuard.onUncleanStartup(state, true, 2);
        byte[] bytes = Files.readAllBytes(state);
        assertNotEquals((byte) 0xEF, bytes[0]);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains(BackendGuard.KEY + "=1"));
    }

    @Test
    void propertiesDefaults() {
        System.clearProperty(BackendGuard.PROPERTY_ENABLED);
        System.clearProperty(BackendGuard.PROPERTY_MAX_KEEPS);
        assertTrue(BackendGuard.enabledFromProperties());
        assertEquals(BackendGuard.DEFAULT_MAX_KEEPS, BackendGuard.maxKeepsFromProperties());
        System.setProperty(BackendGuard.PROPERTY_ENABLED, "false");
        try {
            assertFalse(BackendGuard.enabledFromProperties());
        } finally {
            System.clearProperty(BackendGuard.PROPERTY_ENABLED);
        }
    }
}
