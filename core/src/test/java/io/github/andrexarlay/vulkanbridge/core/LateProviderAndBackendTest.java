package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.BackendType;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** L2: late requirement providers and the cached backend answer. Not proof of in-game behaviour. */
class LateProviderAndBackendTest {
    @AfterEach
    void resetDetector() {
        VulkanBackendDetector.reset();
    }

    private static DeviceRequirement req(String id, int api) {
        return new DeviceRequirement(id, Set.of("VK_X_" + id), List.of(), api, true, null);
    }

    @Test
    void lateProviderRunsOnceBeforeFreezeAndItsRequirementsCount() {
        var reg = new DeviceFeatureRegistryImpl();
        var calls = new AtomicInteger();
        reg.addLateProvider("t.late", r -> {
            calls.incrementAndGet();
            assertFalse(r.isFrozen(), "providers run before the registry is frozen");
            r.register(req("t.from_late", 0x403000));
        });
        reg.register(req("t.ctor", 0x402000));
        reg.runLateProviders();
        reg.runLateProviders();
        reg.freeze();
        assertEquals(1, calls.get());
        assertEquals(List.of("t.ctor", "t.from_late"), reg.snapshot().stream().map(DeviceRequirement::id).toList());
        assertEquals(0x403000, reg.requestedInstanceApiVersion());
        assertTrue(reg.isFrozen());
    }

    @Test
    void freezeRunsLateProvidersWhenNobodyDidBefore() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.addLateProvider("t.late", r -> r.register(req("t.x", 0)));
        reg.freeze();
        assertEquals(1, reg.snapshot().size());
    }

    @Test
    void providersRunInRegistrationOrder() {
        var reg = new DeviceFeatureRegistryImpl();
        List<String> order = new ArrayList<>();
        reg.addLateProvider("t.a", r -> order.add("a"));
        reg.addLateProvider("t.b", r -> order.add("b"));
        reg.runLateProviders();
        assertEquals(List.of("a", "b"), order);
    }

    @Test
    void addingAfterTheRunIsRejectedAndDuplicateIdIsRejected() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.addLateProvider("t.a", r -> { });
        assertThrows(IllegalStateException.class, () -> reg.addLateProvider("t.a", r -> { }));
        assertThrows(IllegalArgumentException.class, () -> reg.addLateProvider(" ", r -> { }));
        assertThrows(IllegalArgumentException.class, () -> reg.addLateProvider("t.n", null));
        reg.runLateProviders();
        var e = assertThrows(IllegalStateException.class, () -> reg.addLateProvider("t.too_late", r -> { }));
        assertTrue(e.getMessage().contains("t.too_late"), e.getMessage());
    }

    @Test
    void failingProviderIsNotSwallowed() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.addLateProvider("t.bad", r -> { throw new UnsupportedOperationException("boom"); });
        var e = assertThrows(IllegalStateException.class, reg::runLateProviders);
        assertTrue(e.getMessage().contains("t.bad") && e.getCause() instanceof UnsupportedOperationException, e.getMessage());
        // providers are done: no silent retry that would run a half-registered provider twice
        reg.runLateProviders();
    }

    @Test
    void registrationFromProviderStillFailsAfterFreeze() {
        var reg = new DeviceFeatureRegistryImpl();
        reg.freeze();
        assertThrows(IllegalStateException.class, () -> reg.addLateProvider("t.late", r -> { }));
    }

    @Test
    void windowReportResolvesBackendForGood() {
        VulkanBackendDetector.reset();
        assertFalse(VulkanBackendDetector.isBackendResolved());
        VulkanBackendDetector.onBackendResolved(true);
        assertTrue(VulkanBackendDetector.isBackendResolved());
        assertTrue(VulkanBackendDetector.isVulkanActive());
        assertEquals(BackendType.VULKAN, VulkanBridge.backend());
        assertTrue(VulkanBridge.isVulkan());

        VulkanBackendDetector.onBackendResolved(false);
        assertEquals(BackendType.OPENGL, VulkanBridge.backend());
        assertFalse(VulkanBridge.isVulkan());
        // a later probe cannot flip a resolved answer
        assertFalse(VulkanBackendDetector.isVulkanActive());
    }

    @Test
    void resetClearsResolvedState() {
        VulkanBackendDetector.onBackendResolved(true);
        VulkanBackendDetector.reset();
        assertFalse(VulkanBackendDetector.isBackendResolved());
    }
}
