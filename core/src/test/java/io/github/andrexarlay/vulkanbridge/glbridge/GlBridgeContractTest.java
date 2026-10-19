package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.FramePhase;
import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlBridgeCapabilities;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions.Latency;
import io.github.andrexarlay.vulkanbridge.api.GlTier;
import io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl;
import io.github.andrexarlay.vulkanbridge.core.FramePhasesImpl;
import io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** L2: GL bridge API 0.6.0 contracts that do not need a GL context: options, deprecated defaults, request/capabilities, composite plan. */
@SuppressWarnings("deprecation")
class GlBridgeContractTest {
    private final FramePhasesImpl phases = new FramePhasesImpl();
    private final GlBridgeImpl bridge = new GlBridgeImpl(phases);
    private final GlRenderer a = f -> {};
    private final GlRenderer b = f -> {};

    @BeforeEach
    @AfterEach
    void clear() {
        System.clearProperty(GlBridgeConfig.PROPERTY);
        VulkanBackendDetector.reset();
    }

    @Test
    void openGlBackendMeansNotNeeded() {
        VulkanBackendDetector.onBackendResolved(false);
        assertEquals(GlBridge.State.NOT_NEEDED, bridge.state());
        DeviceFeatureRegistryImpl registry = new DeviceFeatureRegistryImpl();
        assertEquals(GlBridge.State.NOT_NEEDED, bridge.request("mod_a", registry));
        assertTrue(registry.snapshot().isEmpty());
        assertTrue(bridge.capabilities().tiers().isEmpty());
        VulkanBackendDetector.onBackendResolved(true);
        assertEquals(GlBridge.State.DISABLED, bridge.state());
    }

    @Test
    void bridgeListensOnBeforeGuiAndFrameEnd() {
        assertEquals(1, phases.listenerCount(FramePhase.BEFORE_GUI));
        assertEquals(1, phases.listenerCount(FramePhase.FRAME_END));
        assertEquals(0, phases.listenerCount(FramePhase.BEFORE_LEVEL));
    }

    @Test
    void optionsValidation() {
        assertThrows(IllegalArgumentException.class, () -> GlRendererOptions.DEFAULTS.withOpacity(1.5f));
        assertThrows(IllegalArgumentException.class, () -> GlRendererOptions.DEFAULTS.withOpacity(Float.NaN));
        assertThrows(NullPointerException.class, () -> GlRendererOptions.DEFAULTS.withLatency(null));
        GlRendererOptions o = GlRendererOptions.DEFAULTS.withAlphaMode(GlBridge.AlphaMode.STRAIGHT).withOpacity(0.5f).withVisible(false)
            .withLatency(Latency.PIPELINED_N_MINUS_1).withPhase(FramePhase.BEFORE_GUI);
        assertEquals(new GlRendererOptions(FramePhase.BEFORE_GUI, GlBridge.AlphaMode.STRAIGHT, 0.5f, false, Latency.PIPELINED_N_MINUS_1), o);
        assertEquals(new GlRendererOptions(FramePhase.FRAME_END, GlBridge.AlphaMode.PREMULTIPLIED, 1f, true, Latency.SAME_FRAME), GlRendererOptions.DEFAULTS);
    }

    @Test
    void tier3PhasesAreRefused() {
        for (FramePhase p : FramePhase.values()) {
            GlRendererOptions o = GlRendererOptions.DEFAULTS.withPhase(p);
            if (p == FramePhase.BEFORE_GUI || p == FramePhase.FRAME_END) {
                bridge.registerRenderer(a, o);
                assertEquals(o, bridge.options(a));
            } else {
                assertThrows(IllegalArgumentException.class, () -> bridge.registerRenderer(b, o), p.name());
                assertThrows(IllegalArgumentException.class, () -> bridge.setOptions(a, o), p.name());
            }
        }
        assertNull(bridge.options(b), "a refused registration does not register");
    }

    @Test
    void rendererWithoutOptionsFollowsDeprecatedGlobals() {
        bridge.registerRenderer(a);
        assertEquals(GlRendererOptions.DEFAULTS, bridge.options(a));
        bridge.setAlphaMode(GlBridge.AlphaMode.STRAIGHT);
        bridge.setOpacity(0.25f);
        bridge.setOverlayVisible(false);
        assertEquals(new GlRendererOptions(FramePhase.FRAME_END, GlBridge.AlphaMode.STRAIGHT, 0.25f, false, Latency.SAME_FRAME), bridge.options(a));
        GlRendererOptions own = GlRendererOptions.DEFAULTS.withLatency(Latency.PIPELINED_N_MINUS_1);
        bridge.registerRenderer(b, own);
        assertEquals(own, bridge.options(b), "explicit options ignore the globals");
        assertTrue(bridge.setOptions(a, own));
        assertEquals(own, bridge.options(a));
        bridge.registerRenderer(a);
        assertEquals(own, bridge.options(a), "re-registering without options keeps the explicit options");
    }

    @Test
    void unregisterAndSetOptionsOnUnknown() {
        assertFalse(bridge.setOptions(a, GlRendererOptions.DEFAULTS));
        bridge.registerRenderer(a, GlRendererOptions.DEFAULTS);
        assertTrue(bridge.unregisterRenderer(a));
        assertFalse(bridge.unregisterRenderer(a));
        assertNull(bridge.options(a));
        assertThrows(NullPointerException.class, () -> bridge.registerRenderer(null, GlRendererOptions.DEFAULTS));
    }

    @Test
    void requestRegistersTheRequirementOnceAndReportsTooLate() {
        DeviceFeatureRegistryImpl registry = new DeviceFeatureRegistryImpl();
        assertEquals(GlBridge.State.DISABLED, bridge.state());
        assertTrue(bridge.capabilities().tiers().isEmpty());
        GlBridge.State s = bridge.request("mod_a", registry);
        if (!GlBridgeConfig.platformSupported()) {
            assertEquals(GlBridge.State.UNSUPPORTED_PLATFORM, s);
            assertTrue(registry.snapshot().isEmpty());
            return;
        }
        assertEquals(GlBridge.State.WAITING_FOR_DEVICE, s);
        assertEquals(GlBridge.State.WAITING_FOR_DEVICE, bridge.request("mod_b", registry), "already on");
        assertEquals(1, registry.snapshot().size());
        assertEquals(GlBridgeConfig.REQUIREMENT_ID, registry.snapshot().get(0).id());
        assertEquals(java.util.Set.of("mod_a", "mod_b"), bridge.requesters());
        GlBridgeCapabilities caps = bridge.capabilities();
        assertTrue(caps.supports(GlTier.TIER_2));
        assertFalse(caps.supports(GlTier.TIER_3));
        assertEquals(java.util.Set.of(FramePhase.BEFORE_GUI, FramePhase.FRAME_END), caps.phases());
        assertEquals(java.util.EnumSet.allOf(Latency.class), caps.latencies());
        assertEquals("OPAQUE_WIN32", caps.handleType());
        assertNull(caps.deviceUuidMatch(), "not checked before READY");

        GlBridgeImpl late = new GlBridgeImpl(new FramePhasesImpl());
        DeviceFeatureRegistryImpl frozen = new DeviceFeatureRegistryImpl();
        frozen.freeze();
        assertEquals(GlBridge.State.DISABLED, late.request("mod_c", frozen));
        assertTrue(frozen.snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> late.request(" ", frozen));
    }

    @Test
    void forcedOffRefusesRequests() {
        System.setProperty(GlBridgeConfig.PROPERTY, "false");
        DeviceFeatureRegistryImpl registry = new DeviceFeatureRegistryImpl();
        assertEquals(GlBridge.State.DISABLED, bridge.request("mod_a", registry));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void invalidSwitchValueIsAnError() {
        System.setProperty(GlBridgeConfig.PROPERTY, "yes");
        assertThrows(IllegalStateException.class, GlBridgeConfig::forced);
        System.setProperty(GlBridgeConfig.PROPERTY, "TRUE");
        assertTrue(GlBridgeConfig.enabled());
    }

    @Test
    void platformCheck() {
        assertTrue(GlBridgeConfig.platformSupported("Windows 11"));
        assertFalse(GlBridgeConfig.platformSupported("Linux"));
    }

    // ---- composite plan

    @Test
    void singleRendererUsesThePushConstantPath() {
        CompositePlan p = CompositePlan.of(List.of(a),
            List.of(GlRendererOptions.DEFAULTS.withAlphaMode(GlBridge.AlphaMode.STRAIGHT).withOpacity(0.5f)));
        assertEquals(0, p.intermediates());
        assertTrue(p.straight);
        assertEquals(0.5f, p.opacity);
    }

    @Test
    void severalRenderersBlendNonDefaultOnesInGl() {
        CompositePlan p = CompositePlan.of(List.of(a, b),
            List.of(GlRendererOptions.DEFAULTS, GlRendererOptions.DEFAULTS.withOpacity(0.5f)));
        assertFalse(p.straight);
        assertEquals(1f, p.opacity);
        assertFalse(p.items.get(0).intermediate());
        assertTrue(p.items.get(1).intermediate());
        assertEquals(0.5f, p.items.get(1).opacity());
        CompositePlan q = CompositePlan.of(List.of(a, b),
            List.of(GlRendererOptions.DEFAULTS.withAlphaMode(GlBridge.AlphaMode.STRAIGHT), GlRendererOptions.DEFAULTS));
        assertTrue(q.items.get(0).intermediate());
        assertTrue(q.items.get(0).straight());
        assertFalse(q.items.get(1).intermediate());
        assertSame(a, q.items.get(0).renderer(), "registration order is kept");
        assertTrue(CompositePlan.of(List.of(), List.of()).isEmpty());
    }
}
