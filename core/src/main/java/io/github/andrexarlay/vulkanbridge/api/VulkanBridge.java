package io.github.andrexarlay.vulkanbridge.api;

import io.github.andrexarlay.vulkanbridge.core.BridgeCore;
import io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector;

/** Entry point of the core API. All methods are safe to call at any time (no NPE before the device exists). */
@Experimental
public final class VulkanBridge {
    /** API version, independent of the mod version. 0.x = unstable. */
    public static final String API_VERSION = "0.6.0";

    private VulkanBridge() {}

    public static String apiVersion() {
        return API_VERSION;
    }

    /**
     * Backend of the running game. Once the window is created (MixinWindow) the answer is final and cached: VULKAN or
     * OPENGL, no reflection. Before that: VULKAN when {@link VulkanBackendDetector} says so, OPENGL when the
     * live RenderSystem device is an OpenGL one, otherwise UNKNOWN.
     */
    public static BackendType backend() {
        if (VulkanBackendDetector.isBackendResolved()) {
            // Window creation reported the real backend: cheap path, no reflection.
            return VulkanBackendDetector.isVulkanActive() ? BackendType.VULKAN : BackendType.OPENGL;
        }
        if (VulkanBackendDetector.isVulkanActive()) {
            return BackendType.VULKAN;
        }
        return BridgeCore.liveDeviceIsOpenGl() ? BackendType.OPENGL : BackendType.UNKNOWN;
    }

    public static boolean isVulkan() {
        return backend() == BackendType.VULKAN;
    }

    /** Registry of device/instance requirements; usable until the device is created. */
    public static DeviceFeatureRegistry deviceFeatures() {
        return BridgeCore.registry();
    }

    /**
     * GL to Vulkan interop service (experiment). Always returns the same object; its {@link GlBridge#state()} is DISABLED unless
     * the game was started with {@code -Dvkbridge.glbridge=true}.
     */
    public static GlBridge glBridge() {
        return io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeImpl.INSTANCE;
    }

    /**
     * Frame contract (ADR-0003, since API 0.6.0): listeners per {@link FramePhase}, projection modifiers, exclusive roles and
     * generation ids. Always returns the same object; works on both backends (the phases are GameRenderer points).
     */
    public static FramePhases framePhases() {
        return io.github.andrexarlay.vulkanbridge.core.FramePhasesImpl.INSTANCE;
    }

    /**
     * Manager of user-provided vendor native libraries (DLSS, Streamline, NRD, XeSS, FidelityFX): discovery,
     * checked installation, and the only sanctioned way to obtain a DLL path. See docs/api/native-libraries.md.
     * Needs the game directory from the installed loader module, so it throws {@link IllegalStateException} outside the game.
     */
    public static NativeLibraries libraries() {
        return BridgeCore.libraries();
    }
}
