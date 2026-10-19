package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2: the opt-in flag decides whether the four Win32 external memory/semaphore device extensions are requested. */
class GlBridgeConfigTest {
    @AfterEach
    void clear() {
        System.clearProperty(GlBridgeConfig.PROPERTY);
    }

    @Test
    void nothingRegisteredWithoutFlag() {
        DeviceFeatureRegistryImpl registry = new DeviceFeatureRegistryImpl();
        GlBridgeConfig.registerDeviceRequirement(registry);
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void registersFourExtensionsWithFlag() {
        System.setProperty(GlBridgeConfig.PROPERTY, "true");
        DeviceFeatureRegistryImpl registry = new DeviceFeatureRegistryImpl();
        GlBridgeConfig.registerDeviceRequirement(registry);
        List<DeviceRequirement> all = registry.snapshot();
        assertEquals(1, all.size());
        assertEquals(GlBridgeConfig.REQUIREMENT_ID, all.get(0).id());
        assertTrue(all.get(0).deviceExtensions().containsAll(List.of("VK_KHR_external_memory", "VK_KHR_external_memory_win32",
            "VK_KHR_external_semaphore", "VK_KHR_external_semaphore_win32")));
        assertEquals(4, all.get(0).deviceExtensions().size());
    }
}
