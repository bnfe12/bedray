package io.github.andrexarlay.vulkanbridge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects whether the active graphics backend for Minecraft is Vulkan.
 * <p>
 * In Minecraft 26.3, the setting {@code preferredGraphicsBackend} controls whether
 * the game initializes OpenGL or Vulkan. When set to 'vulkan', Minecraft creates
 * a Vulkan surface without an active OpenGL context, causing legacy OpenGL calls
 * to fail or crash if not shimmed.
 */
public final class VulkanBackendDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/Detector");

    public static final String BACKEND_VULKAN = "vulkan";
    public static final String BACKEND_OPENGL = "opengl";

    public static final String PROPERTY_PREFERRED_BACKEND = "preferredGraphicsBackend";
    public static final String PROPERTY_MINECRAFT_BACKEND = "minecraft.graphicsBackend";
    public static final String ENV_PREFERRED_BACKEND = "PREFERRED_GRAPHICS_BACKEND";
    public static final String ENV_MINECRAFT_BACKEND = "MINECRAFT_GRAPHICS_BACKEND";

    private static final AtomicBoolean vulkanActive = new AtomicBoolean(false);
    private static volatile boolean detected = false;
    /** True once the window creation hook reported the real backend; from then on no probing happens. */
    private static volatile boolean resolved = false;
    private static volatile long lastRuntimeCheckTime = 0L;

    private VulkanBackendDetector() {}

    /**
     * Checks if the Vulkan graphics backend is active.
     * Evaluates cached status, discovers backend on startup, and checks live RenderPearl
     * device / surface runtime state if available.
     */
    public static boolean isVulkanActive() {
        if (resolved) {
            // Hot path after window creation: a plain volatile read, no reflection, no clock.
            return vulkanActive.get();
        }
        if (!detected) {
            synchronized (VulkanBackendDetector.class) {
                if (!detected) {
                    vulkanActive.set(detectBackendInternal());
                    detected = true;
                }
            }
        }
        // If not active, periodically inspect live runtime backend if initialized
        if (!vulkanActive.get()) {
            long now = System.currentTimeMillis();
            if (now - lastRuntimeCheckTime > 1000L) {
                lastRuntimeCheckTime = now;
                String explicitProp = System.getProperty(PROPERTY_PREFERRED_BACKEND);
                if (explicitProp == null) {
                    explicitProp = System.getProperty(PROPERTY_MINECRAFT_BACKEND);
                }
                if (explicitProp == null || !BACKEND_OPENGL.equalsIgnoreCase(explicitProp.replace("\"", "").replace("'", "").trim())) {
                    if (checkRuntimeBackend()) {
                        setVulkanActive(true);
                    }
                }
            }
        }
        return vulkanActive.get();
    }

    /**
     * Explicitly sets whether the Vulkan graphics backend is active.
     * Used by early lifecycle mixins (such as Window initialization).
     */
    public static void setVulkanActive(boolean active) {
        boolean previous = vulkanActive.getAndSet(active);
        detected = true;
        if (previous != active) {
            LOGGER.info("Graphics backend switched: vulkanActive={}", active);
        }
    }

    /**
     * Final answer from the engine: the GpuBackend used for window creation. Called by MixinWindow. After this the
     * result is cached and never re-probed (until {@link #reset()}).
     */
    public static void onBackendResolved(boolean vulkan) {
        synchronized (VulkanBackendDetector.class) {
            boolean previous = vulkanActive.getAndSet(vulkan);
            detected = true;
            resolved = true;
            if (previous != vulkan) {
                LOGGER.info("Graphics backend switched: vulkanActive={}", vulkan);
            }
            LOGGER.info("[VKB-CORE] backend_resolved vulkan={}", vulkan);
        }
    }

    /** True once {@link #onBackendResolved(boolean)} was called. */
    public static boolean isBackendResolved() {
        return resolved;
    }

    /**
     * Runs backend discovery from system properties, environment variables,
     * live runtime RenderPearl backend status, and options.txt.
     */
    public static boolean detectBackend() {
        synchronized (VulkanBackendDetector.class) {
            boolean active = detectBackendInternal();
            vulkanActive.set(active);
            detected = true;
            return active;
        }
    }

    private static boolean detectBackendInternal() {
        // 1. Check JVM system properties (highest precedence)
        String sysProp = System.getProperty(PROPERTY_PREFERRED_BACKEND);
        if (sysProp == null) {
            sysProp = System.getProperty(PROPERTY_MINECRAFT_BACKEND);
        }
        if (sysProp != null) {
            sysProp = sysProp.replace("\"", "").replace("'", "").trim();
            boolean isVulkan = BACKEND_VULKAN.equalsIgnoreCase(sysProp);
            LOGGER.info("Detected graphics backend via JVM property: '{}' (vulkanActive={})", sysProp, isVulkan);
            return isVulkan;
        }

        // 2. Check environment variables
        String envVal = System.getenv(ENV_PREFERRED_BACKEND);
        if (envVal == null) {
            envVal = System.getenv(ENV_MINECRAFT_BACKEND);
        }
        if (envVal != null) {
            envVal = envVal.replace("\"", "").replace("'", "").trim();
            boolean isVulkan = BACKEND_VULKAN.equalsIgnoreCase(envVal);
            LOGGER.info("Detected graphics backend via environment variable: '{}' (vulkanActive={})", envVal, isVulkan);
            return isVulkan;
        }

        // 3. Check live runtime backend / RenderPearl device if already initialized
        if (checkRuntimeBackend()) {
            LOGGER.info("Detected graphics backend via live runtime RenderPearl backend (vulkanActive=true)");
            return true;
        }

        // 4. Check options.txt file in working, game or config directory
        String optionsBackend = checkOptionsFile();
        if (optionsBackend != null) {
            optionsBackend = optionsBackend.replace("\"", "").replace("'", "").trim();
            boolean isVulkan = BACKEND_VULKAN.equalsIgnoreCase(optionsBackend);
            LOGGER.info("Detected graphics backend from options.txt: '{}' (vulkanActive={})", optionsBackend, isVulkan);
            return isVulkan;
        }

        LOGGER.info("No explicit Vulkan configuration found in options.txt. Enforcing Vulkan mode (vulkanActive=true).");
        return true;
    }

    /**
     * Safe runtime check inspecting RenderSystem GPU device and Minecraft window surface.
     */
    public static boolean checkRuntimeBackend() {
        // Check RenderPearl device via RenderSystem
        try {
            Class<?> renderSystemClass = Class.forName("com.mojang.blaze3d.systems.RenderSystem", false, VulkanBackendDetector.class.getClassLoader());
            java.lang.reflect.Method tryGetDeviceMethod = renderSystemClass.getMethod("tryGetDevice");
            Object device = tryGetDeviceMethod.invoke(null);
            if (device != null) {
                String name = device.getClass().getName().toLowerCase();
                if (name.contains("vulkan")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        // Check Minecraft instance windowSurface and options
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", false, VulkanBackendDetector.class.getClassLoader());
            java.lang.reflect.Method getInstanceMethod = mcClass.getMethod("getInstance");
            Object mc = getInstanceMethod.invoke(null);
            if (mc != null) {
                try {
                    java.lang.reflect.Field surfaceField = mcClass.getField("windowSurface");
                    Object surface = surfaceField.get(mc);
                    if (surface != null && surface.getClass().getName().toLowerCase().contains("vulkan")) {
                        return true;
                    }
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Field optionsField = mcClass.getField("options");
                    Object options = optionsField.get(mc);
                    if (options != null) {
                        java.lang.reflect.Method backendMethod = options.getClass().getMethod("preferredGraphicsBackend");
                        Object optionInstance = backendMethod.invoke(options);
                        if (optionInstance != null) {
                            java.lang.reflect.Method getMethod = optionInstance.getClass().getMethod("get");
                            Object val = getMethod.invoke(optionInstance);
                            if (val != null && "VULKAN".equalsIgnoreCase(val.toString())) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static String checkOptionsFile() {
        List<File> candidateFiles = new ArrayList<>();
        candidateFiles.add(new File("options.txt"));
        candidateFiles.add(new File("config/options.txt"));
        candidateFiles.add(new File("config/neoforge/options.txt"));
        candidateFiles.add(new File(".minecraft/options.txt"));

        String gameDir = System.getProperty("minecraft.gameDir");
        if (gameDir != null && !gameDir.isEmpty()) {
            candidateFiles.add(new File(gameDir, "options.txt"));
        }

        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            candidateFiles.add(new File(userDir, "options.txt"));
        }

        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", false, VulkanBackendDetector.class.getClassLoader());
            java.lang.reflect.Method getInstanceMethod = mcClass.getMethod("getInstance");
            Object mc = getInstanceMethod.invoke(null);
            if (mc != null) {
                java.lang.reflect.Field gameDirField = mcClass.getField("gameDirectory");
                File mcGameDir = (File) gameDirField.get(mc);
                if (mcGameDir != null) {
                    candidateFiles.add(new File(mcGameDir, "options.txt"));
                }
            }
        } catch (Throwable ignored) {
        }

        for (File file : candidateFiles) {
            if (file.exists() && file.isFile() && file.canRead()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("preferredGraphicsBackend:") || line.startsWith("preferredGraphicsBackend=")) {
                            int delimiter = line.indexOf(':');
                            if (delimiter < 0) {
                                delimiter = line.indexOf('=');
                            }
                            if (delimiter >= 0 && delimiter < line.length() - 1) {
                                return line.substring(delimiter + 1).trim();
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Ignore and try next location
                }
            }
        }
        return null;
    }

    /**
     * Resets detector state (intended for unit tests).
     */
    public static void reset() {
        synchronized (VulkanBackendDetector.class) {
            vulkanActive.set(false);
            detected = false;
            resolved = false;
            lastRuntimeCheckTime = 0L;
        }
    }
}
