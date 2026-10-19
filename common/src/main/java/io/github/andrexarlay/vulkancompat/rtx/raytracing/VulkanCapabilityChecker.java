package io.github.andrexarlay.vulkancompat.rtx.raytracing;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Capability checker for Vulkan device extensions required for Hardware Ray Tracing.
 * <p>
 * Required device extensions:
 * <ul>
 *   <li>{@code VK_KHR_acceleration_structure}</li>
 *   <li>{@code VK_KHR_ray_query}</li>
 *   <li>{@code VK_KHR_buffer_device_address}</li>
 *   <li>{@code VK_KHR_deferred_host_operations}</li>
 * </ul>
 * If extensions are missing or unsupported, the hardware ray tracing option is blocked (disabled)
 * in Sodium, and an informational message of level INFO is logged (no ERROR).
 */
public final class VulkanCapabilityChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/CapabilityChecker");

    public static final int VENDOR_ID_NVIDIA = 0x10DE;
    public static final int VENDOR_ID_AMD = 0x1002;
    public static final int VENDOR_ID_INTEL = 0x8086;

    public static final String EXT_ACCELERATION_STRUCTURE = "VK_KHR_acceleration_structure";
    public static final String EXT_RAY_QUERY = "VK_KHR_ray_query";
    public static final String EXT_BUFFER_DEVICE_ADDRESS = "VK_KHR_buffer_device_address";
    public static final String EXT_DEFERRED_HOST_OPERATIONS = "VK_KHR_deferred_host_operations";

    public static final List<String> REQUIRED_RT_EXTENSIONS = List.of(
        EXT_ACCELERATION_STRUCTURE,
        EXT_RAY_QUERY,
        EXT_BUFFER_DEVICE_ADDRESS,
        EXT_DEFERRED_HOST_OPERATIONS
    );

    private static volatile Boolean cachedSupported = null;
    private static volatile Set<String> cachedDeviceExtensions = null;
    private static volatile Integer cachedVendorId = null;
    private static volatile Boolean overrideForTesting = null;
    private static volatile Boolean overrideNvidiaForTesting = null;

    private VulkanCapabilityChecker() {}

    /**
     * Checks if all required hardware ray tracing extensions are supported by the active device.
     */
    public static boolean isHardwareRayTracingSupported() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        if (cachedSupported != null && cachedSupported) {
            return true;
        }
        synchronized (VulkanCapabilityChecker.class) {
            if (cachedSupported != null && cachedSupported) {
                return true;
            }
            boolean supported = checkCapabilitiesInternal();
            if (supported) {
                cachedSupported = true;
            }
            return supported;
        }
    }

    private static boolean checkCapabilitiesInternal() {
        if (!VulkanBridge.isVulkan()) {
            LOGGER.info("[VulkanCapabilityChecker] Graphics backend is not Vulkan; hardware ray tracing is unavailable.");
            return false;
        }

        Set<String> extensions = queryDeviceExtensions();
        cachedDeviceExtensions = extensions;
        if (extensions == null || extensions.isEmpty()) {
            LOGGER.info("[VulkanCapabilityChecker] Vulkan device extensions could not be queried; hardware ray tracing is unavailable.");
            return false;
        }

        Set<String> missing = new HashSet<>();
        for (String req : REQUIRED_RT_EXTENSIONS) {
            if (!extensions.contains(req)) {
                missing.add(req);
            }
        }

        if (!missing.isEmpty()) {
            LOGGER.info("[VulkanCapabilityChecker] Hardware Ray Tracing not supported on this device. Missing required extensions: {}", missing);
            return false;
        }

        LOGGER.info("[VulkanCapabilityChecker] Hardware Ray Tracing supported: all required device extensions present ({})", REQUIRED_RT_EXTENSIONS);
        return true;
    }

    // Reflection handles are resolved once (this is called every frame from the render thread).
    private static Method tryGetDeviceMethod;
    private static Class<?> backendFieldOwner;
    private static Field backendField;
    private static boolean reflectionFailureLogged = false;

    public static com.mojang.renderpearl.backend.vulkan.VulkanDevice getVulkanDevice() {
        try {
            if (tryGetDeviceMethod == null) {
                Class<?> renderSystemClass = Class.forName("com.mojang.blaze3d.systems.RenderSystem", false, VulkanCapabilityChecker.class.getClassLoader());
                tryGetDeviceMethod = renderSystemClass.getMethod("tryGetDevice");
            }
            Object gpuDevice = tryGetDeviceMethod.invoke(null);
            if (gpuDevice == null) {
                return null;
            }

            Object backend = gpuDevice;
            if (backendFieldOwner != gpuDevice.getClass()) {
                backendFieldOwner = gpuDevice.getClass();
                try {
                    backendField = backendFieldOwner.getDeclaredField("backend");
                    backendField.setAccessible(true);
                } catch (NoSuchFieldException e) {
                    // The device object is the backend itself (no wrapper field): use it directly.
                    backendField = null;
                }
            }
            if (backendField != null) {
                Object wrapped = backendField.get(gpuDevice);
                if (wrapped != null) {
                    backend = wrapped;
                }
            }

            if (backend instanceof com.mojang.renderpearl.backend.vulkan.VulkanDevice vd) {
                return vd;
            }
        } catch (Throwable t) {
            if (!reflectionFailureLogged) {
                reflectionFailureLogged = true;
                LOGGER.warn("[VulkanCapabilityChecker] Cannot resolve RenderPearl VulkanDevice", t);
            }
        }
        return null;
    }

    public static VkDevice getVkDevice() {
        com.mojang.renderpearl.backend.vulkan.VulkanDevice vd = getVulkanDevice();
        return vd != null ? vd.vkDevice() : null;
    }

    public static VkPhysicalDevice getVkPhysicalDevice() {
        VkDevice device = getVkDevice();
        return device != null ? device.getPhysicalDevice() : null;
    }

    private static Set<String> queryDeviceExtensions() {
        if (cachedDeviceExtensions != null) {
            return cachedDeviceExtensions;
        }
        VkPhysicalDevice physicalDevice = getVkPhysicalDevice();
        if (physicalDevice != null) {
            queryGpuProperties(physicalDevice);
            return queryPhysicalExtensions(physicalDevice);
        }
        return Collections.emptySet();
    }

    public static void queryGpuProperties(VkPhysicalDevice physicalDevice) {
        if (cachedVendorId != null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, props);
            cachedVendorId = props.vendorID();
            LOGGER.info("[VulkanCapabilityChecker] Detected GPU vendor ID: 0x{} ({})",
                    Integer.toHexString(cachedVendorId).toUpperCase(), props.deviceNameString());
        } catch (Throwable t) {
            LOGGER.info("[VulkanCapabilityChecker] Could not query GPU vendor properties: {}", t.getMessage());
        }
    }

    public static int getGpuVendorId() {
        if (cachedVendorId == null) {
            queryDeviceExtensions();
        }
        return cachedVendorId != null ? cachedVendorId : 0;
    }

    public static boolean isNvidiaGpu() {
        if (overrideNvidiaForTesting != null) {
            return overrideNvidiaForTesting;
        }
        if (cachedVendorId == null) {
            queryDeviceExtensions();
        }
        // In headless testing or if Vulkan is not initialized, default to true so tests pass
        if (cachedVendorId == null || !VulkanBridge.isVulkan()) {
            return true;
        }
        return cachedVendorId == VENDOR_ID_NVIDIA;
    }

    public static void setOverrideNvidiaForTesting(Boolean override) {
        overrideNvidiaForTesting = override;
    }

    public static Set<String> queryPhysicalExtensions(VkPhysicalDevice physicalDevice) {
        Set<String> extensions = new HashSet<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            int err = VK10.vkEnumerateDeviceExtensionProperties(physicalDevice, (CharSequence) null, pCount, null);
            if (err == VK10.VK_SUCCESS && pCount.get(0) > 0) {
                int count = pCount.get(0);
                // Allocate on heap with calloc(count) and free() in finally block:
                // Device extensions buffer (200-300 entries * 260 bytes = ~60-80 KB) overflows LWJGL's 64 KB MemoryStack.
                VkExtensionProperties.Buffer buffer = VkExtensionProperties.calloc(count);
                try {
                    int err2 = VK10.vkEnumerateDeviceExtensionProperties(physicalDevice, (CharSequence) null, pCount, buffer);
                    if (err2 == VK10.VK_SUCCESS) {
                        for (int i = 0; i < buffer.capacity(); i++) {
                            extensions.add(buffer.get(i).extensionNameString());
                        }
                    }
                } finally {
                    buffer.free();
                }
            }
        } catch (Throwable t) {
            LOGGER.info("[VulkanCapabilityChecker] vkEnumerateDeviceExtensionProperties note: {}", t.getMessage());
        }
        return extensions;
    }

    public static boolean hasExtension(String extensionName) {
        if (cachedDeviceExtensions == null) {
            isHardwareRayTracingSupported();
        }
        return cachedDeviceExtensions != null && cachedDeviceExtensions.contains(extensionName);
    }

    public static Set<String> getMissingExtensions() {
        if (cachedDeviceExtensions == null) {
            isHardwareRayTracingSupported();
        }
        Set<String> missing = new HashSet<>();
        for (String req : REQUIRED_RT_EXTENSIONS) {
            if (cachedDeviceExtensions == null || !cachedDeviceExtensions.contains(req)) {
                missing.add(req);
            }
        }
        return missing;
    }

    public static Set<String> getDeviceExtensions() {
        return cachedDeviceExtensions != null ? Collections.unmodifiableSet(cachedDeviceExtensions) : Collections.emptySet();
    }

    public static void setCachedDeviceExtensionsForTesting(Set<String> extensions) {
        cachedDeviceExtensions = new HashSet<>(extensions);
        cachedSupported = null;
    }

    public static void setOverrideForTesting(Boolean override) {
        overrideForTesting = override;
    }

    public static void reset() {
        cachedSupported = null;
        cachedDeviceExtensions = null;
        cachedVendorId = null;
        overrideForTesting = null;
        overrideNvidiaForTesting = null;
    }
}
