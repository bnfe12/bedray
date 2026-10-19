package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.BackendType;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** L2: pure Java logic of the requirement registry and API value types. Not proof of in-game behaviour. */
class DeviceFeatureRegistryTest {
    private static DeviceRequirement req(String id, int api, boolean optional, java.util.function.Supplier<Boolean> cond) {
        return new DeviceRequirement(id, Set.of("VK_X_" + id), List.of(), api, optional, cond);
    }

    @Test
    void emptyRegistryRequestsNoInstanceApi() {
        var reg = new DeviceFeatureRegistryImpl();
        assertEquals(0, reg.requestedInstanceApiVersion());
        assertTrue(reg.snapshot().isEmpty());
        assertFalse(reg.isFrozen());
    }

    @Test
    void keepsRegistrationOrderAndSkipsFalseConditions() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.register(req("t.a", 0x402000, true, null));
        reg.register(req("t.b", 0x403000, true, () -> Boolean.TRUE));
        reg.register(req("t.off", 0x404000, true, () -> Boolean.FALSE));
        assertEquals(List.of("t.a", "t.b"), reg.snapshot().stream().map(DeviceRequirement::id).toList());
        // max over ACTIVE requirements only: t.off asks for 0x404000 but must not count
        assertEquals(0x403000, reg.requestedInstanceApiVersion());
    }

    @Test
    void nullConditionResultCountsAsFalse() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.register(req("t.null", 0, true, () -> null));
        assertTrue(reg.snapshot().isEmpty());
    }

    @Test
    void duplicateIdThrows() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.register(req("t.a", 0, true, null));
        assertThrows(IllegalStateException.class, () -> reg.register(req("t.a", 0, true, null)));
        // an id skipped by its condition is also taken
        reg.register(req("t.off", 0, true, () -> Boolean.FALSE));
        assertThrows(IllegalStateException.class, () -> reg.register(req("t.off", 0, true, null)));
    }

    @Test
    void requiredRequirementIsRejectedLoudly() {
        var reg = new DeviceFeatureRegistryImpl();
        var e = assertThrows(IllegalArgumentException.class, () -> reg.register(req("t.req", 0, false, null)));
        assertTrue(e.getMessage().contains("t.req"));
    }

    @Test
    void snapshotIsImmutable() {
        var reg = new DeviceFeatureRegistryImpl();
        var a = req("t.a", 0, true, null);
        reg.register(a);
        assertThrows(UnsupportedOperationException.class, () -> reg.snapshot().add(a));
    }

    @Test
    void freezeIsIdempotentAndBlocksRegistration() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.register(req("t.a", 0, true, null));
        reg.freeze();
        reg.freeze();
        assertTrue(reg.isFrozen());
        var e = assertThrows(IllegalStateException.class, () -> reg.register(req("t.late", 0, true, null)));
        assertTrue(e.getMessage().contains("t.late") && e.getMessage().contains("frozen"), e.getMessage());
        assertEquals(1, reg.snapshot().size());
    }

    @Test
    void valueTypesValidateInput() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceRequirement(" ", null, null, 0, true, null));
        assertThrows(IllegalArgumentException.class, () -> new DeviceRequirement("x", null, null, -1, true, null));
        assertThrows(NullPointerException.class, () -> new DeviceRequirement(null, null, null, 0, true, null));
        assertThrows(IllegalArgumentException.class, () -> new FeatureRequest(" ", "f"));
        var r = new DeviceRequirement("x", null, null, 0, true, null);
        assertTrue(r.deviceExtensions().isEmpty() && r.features().isEmpty() && r.conditionHolds());
        assertEquals("org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR",
            FeatureRequest.of(VkPhysicalDeviceRayQueryFeaturesKHR.class, "rayQuery").struct());
        assertEquals("VK12", FeatureRequest.core(FeatureRequest.VK12, "bufferDeviceAddress").struct());
    }

    @Test
    void facadeIsSafeWithoutDevice() {
        assertNotNull(VulkanBridge.backend());
        assertEquals("0.6.0", VulkanBridge.apiVersion());
        assertNotNull(VulkanBridge.deviceFeatures());
        assertNotEquals(BackendType.VULKAN, BackendType.OPENGL);
    }
}
