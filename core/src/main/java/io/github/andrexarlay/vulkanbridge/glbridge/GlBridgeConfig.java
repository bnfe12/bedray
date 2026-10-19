package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Opt-in switch and device-extension request of the GL bridge (experiment, off by default).
 * {@code -Dvkbridge.glbridge=true} turns it on. Pure Java: no Minecraft or LWJGL class is touched here.
 */
public final class GlBridgeConfig {
    /** JVM property that enables the GL bridge. */
    public static final String PROPERTY = "vkbridge.glbridge";
    /** Requirement id; the engine logs "Enabling optional FeatureSet [VKB GL Bridge]" for it. */
    public static final String REQUIREMENT_ID = "VKB GL Bridge";

    /**
     * Device extensions needed to export a VkImage/VkSemaphore as a Win32 handle (Vulkan spec: VK_KHR_external_memory_win32
     * requires VK_KHR_external_memory; VK_KHR_external_semaphore_win32 requires VK_KHR_external_semaphore; the base names are
     * core in 1.1 and the engine instance is 1.2, but the names are still listed so the engine logs them in
     * "Using graphics device extensions"). Same set as the standalone smoke test (docs/experiments/gl-interop-smoke.md).
     */
    public static final Set<String> DEVICE_EXTENSIONS = Set.of(
        "VK_KHR_external_memory",
        "VK_KHR_external_memory_win32",
        "VK_KHR_external_semaphore",
        "VK_KHR_external_semaphore_win32");

    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-GLBRIDGE");

    private static volatile long bridgeWindow;

    private GlBridgeConfig() {}

    /** SDL window handle of the bridge's hidden GL window, 0 until it exists. Read by the window-event filter mixin. */
    public static long bridgeWindowHandle() {
        return bridgeWindow;
    }

    static void setBridgeWindowHandle(long handle) {
        bridgeWindow = handle;
    }

    /**
     * The JVM switch: {@code true} forces the bridge on, {@code false} forces it off (a {@code GlBridge.request} is then refused),
     * unset = decided by {@code GlBridge.request}. Any other value is an error (a typo must not silently mean "off").
     */
    public static Boolean forced() {
        String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank()) {
            return null;
        }
        if (v.trim().equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (v.trim().equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        throw new IllegalStateException("-D" + PROPERTY + "=" + v + ": expected true or false");
    }

    /** True when {@code -Dvkbridge.glbridge=true}. */
    public static boolean enabled() {
        return Boolean.TRUE.equals(forced());
    }

    /** OPAQUE_WIN32 sharing exists only on Windows. */
    public static boolean platformSupported() {
        return platformSupported(System.getProperty("os.name", ""));
    }

    static boolean platformSupported(String osName) {
        return osName.startsWith("Windows");
    }

    /**
     * Registers the device requirement when the bridge is forced on by the JVM switch on a supported platform. Must run before the
     * registry is frozen (mod constructor / early init). The engine drops an optional group whose extensions the GPU lacks and only
     * logs it; {@link GlBridgeImpl} checks the real result against the device extension list before it does anything.
     */
    public static void registerDeviceRequirement(DeviceFeatureRegistry registry) {
        if (!enabled()) {
            return;
        }
        if (!platformSupported()) {
            LOGGER.warn("[VKB-GLBRIDGE] stage=4 event=unsupported_platform os=\"{}\" note=-D{}=true_ignored", System.getProperty("os.name"), PROPERTY);
            return;
        }
        ensureRegistered(registry, "jvm_property");
    }

    /** Registers the requirement unless the registry already has it. Returns true when it was added now. */
    static boolean ensureRegistered(DeviceFeatureRegistry registry, String by) {
        boolean present = registry.snapshot().stream().anyMatch(r -> r.id().equals(REQUIREMENT_ID));
        if (present) {
            return false;
        }
        registry.register(new DeviceRequirement(REQUIREMENT_ID, DEVICE_EXTENSIONS, List.of(), 0, true, null));
        LOGGER.info("[VKB-GLBRIDGE] stage=4 event=requirement_registered id={} extensions={} by={}", REQUIREMENT_ID, DEVICE_EXTENSIONS.size(), by);
        return true;
    }
}
