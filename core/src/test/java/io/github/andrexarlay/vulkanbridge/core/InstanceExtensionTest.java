package io.github.andrexarlay.vulkanbridge.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** L2: instance extension requests and selection (pure logic). Not proof of in-game behaviour. */
class InstanceExtensionTest {
    @Test
    void addsAvailableSkipsUnavailableAndRecordsEnabled() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.requestInstanceExtensions("t.dlss", Set.of("VK_KHR_get_physical_device_properties2", "VK_X_missing"));
        reg.requestInstanceExtensions("t.other", Set.of("VK_EXT_debug_utils"));
        Set<String> engine = new HashSet<>(Set.of("VK_KHR_surface", "VK_EXT_debug_utils"));
        Set<String> available = Set.of("VK_KHR_surface", "VK_EXT_debug_utils", "VK_KHR_get_physical_device_properties2");
        Set<String> added = InstanceExtensionSelector.apply(reg, available, engine);
        assertEquals(Set.of("VK_KHR_get_physical_device_properties2"), added);
        assertTrue(engine.contains("VK_KHR_get_physical_device_properties2"));
        assertFalse(engine.contains("VK_X_missing"));
        assertEquals(Set.of("VK_KHR_get_physical_device_properties2", "VK_EXT_debug_utils"), reg.enabledInstanceExtensions());
        assertEquals(Set.of("VK_KHR_get_physical_device_properties2", "VK_X_missing", "VK_EXT_debug_utils"), reg.requestedInstanceExtensions());
    }

    @Test
    void requestAfterSealThrows() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.sealInstanceExtensions();
        assertThrows(IllegalStateException.class, () -> reg.requestInstanceExtensions("t.late", Set.of("VK_KHR_x")));
    }

    @Test
    void rejectsBadInput() {
        var reg = new DeviceFeatureRegistryImpl();
        assertThrows(IllegalArgumentException.class, () -> reg.requestInstanceExtensions(" ", Set.of("VK_A")));
        assertThrows(IllegalArgumentException.class, () -> reg.requestInstanceExtensions("t", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> reg.requestInstanceExtensions("t", Set.of("not_vk")));
        reg.requestInstanceExtensions("t", Set.of("VK_A"));
        assertThrows(IllegalStateException.class, () -> reg.requestInstanceExtensions("t", Set.of("VK_B")));
    }

    @Test
    void nothingRequestedChangesNothing() {
        var reg = new DeviceFeatureRegistryImpl();
        Set<String> engine = new HashSet<>(Set.of("VK_KHR_surface"));
        assertTrue(InstanceExtensionSelector.apply(reg, Set.of("VK_KHR_surface"), engine).isEmpty());
        assertEquals(Set.of("VK_KHR_surface"), engine);
        assertTrue(reg.enabledInstanceExtensions().isEmpty());
    }
}
