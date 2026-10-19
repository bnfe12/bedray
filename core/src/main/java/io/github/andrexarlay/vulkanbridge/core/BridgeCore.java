package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;

/** Holder of the process-wide core state. Internal. */
public final class BridgeCore {
    public static final String LOG_PREFIX = "[VKB-CORE]";
    private static final DeviceFeatureRegistryImpl REGISTRY = new DeviceFeatureRegistryImpl();

    private static volatile java.lang.reflect.Method tryGetDevice;
    private static volatile NativeLibrariesImpl libraries;

    private BridgeCore() {}

    public static DeviceFeatureRegistry registry() {
        return REGISTRY;
    }

    /**
     * Native library manager (created on first use). The game directory comes from the installed
     * {@link io.github.andrexarlay.vulkanbridge.platform.LoaderAccess} (set by the loader module's earliest entry
     * point); it does not need {@code Minecraft.getInstance()}.
     */
    public static NativeLibraries libraries() {
        NativeLibrariesImpl l = libraries;
        if (l == null) {
            synchronized (BridgeCore.class) {
                l = libraries;
                if (l == null) {
                    l = new NativeLibrariesImpl(() -> io.github.andrexarlay.vulkanbridge.platform.Platforms.get().gameDir(), System::load);
                    libraries = l;
                }
            }
        }
        return l;
    }

    /**
     * True when the live RenderSystem device is the engine OpenGL device
     * (com.mojang.renderpearl.backend.opengl.GlDevice, found with "unzip -l libs/minecraft-26.3-client.jar").
     * False when there is no device yet or RenderSystem is not loadable (e.g. in unit tests).
     */
    public static boolean liveDeviceIsOpenGl() {
        try {
            java.lang.reflect.Method m = tryGetDevice;
            if (m == null) {
                // Looked up once; only reached before MixinWindow resolved the backend (after that this method is not called).
                Class<?> rs = Class.forName("com.mojang.blaze3d.systems.RenderSystem", false, BridgeCore.class.getClassLoader());
                m = rs.getMethod("tryGetDevice");
                tryGetDevice = m;
            }
            Object device = m.invoke(null);
            return device != null && device.getClass().getName().equals("com.mojang.renderpearl.backend.opengl.GlDevice");
        } catch (ReflectiveOperationException | LinkageError e) {
            // Not an error: the class is simply not available in this environment, the answer is "not known to be OpenGL".
            return false;
        }
    }
}
