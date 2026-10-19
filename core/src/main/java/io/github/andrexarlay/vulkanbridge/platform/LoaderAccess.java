package io.github.andrexarlay.vulkanbridge.platform;

import java.nio.file.Path;

/**
 * The only seam between the loader-neutral bridge code (module {@code common}) and a mod loader (SPI).
 * <p>
 * One implementation per loader lives in the thin loader module and is registered in
 * {@code META-INF/services/io.github.andrexarlay.vulkanbridge.platform.LoaderAccess} (discovered by
 * {@link java.util.ServiceLoader} in {@link Platforms#get()}) and, as the primary path, installed explicitly by the
 * loader's earliest entry point through {@link BridgeBootstrap#earlyInit(LoaderAccess)}.
 * Code in {@code common} must never import loader API (NeoForge or Fabric packages); it asks this interface.
 * Implementations need a public no-arg constructor, must not touch Minecraft classes and must not do business logic.
 */
public interface LoaderAccess {
    /** Short loader name for logs, e.g. {@code neoforge} or {@code fabric}. Never blank. */
    String loaderName();

    /** Loader version string for logs. Never blank. */
    String loaderVersion();

    /** The Minecraft game directory. Must work before {@code Minecraft.getInstance()} exists. */
    Path gameDir();

    /** The loader's config directory (usually {@code <gameDir>/config}). */
    Path configDir();

    /** True when running from a development environment (not a production launcher install). */
    boolean isDevEnvironment();

    /** True when a mod with this id is present (loaded or about to be loaded). Must work from the earliest entry point. */
    boolean isModLoaded(String modId);

    /**
     * Runs {@code hook} at the loader's earliest entry point, which is before the engine creates VkInstance/VkDevice
     * (Fabric preLaunch, NeoForge mod construction); runs it immediately when that point has already passed.
     * Default: the shared {@link EarlyHooks}, which the loader entry point fires through {@link BridgeBootstrap}.
     */
    default void registerEarlyHook(Runnable hook) {
        EarlyHooks.INSTANCE.register(hook);
    }

    /**
     * Runs {@code hook} at the start of every client tick. The first call happens only after the game finished
     * loading. Exceptions propagate to the caller of the tick.
     */
    void registerClientTickHook(Runnable hook);

    /**
     * Runs {@code hook} after every rendered frame. Hooks with a higher priority run first; equal priorities run in
     * registration order. Exceptions propagate to the render loop.
     * <p>
     * Loaders without native tick/frame events implement both registration methods with {@link FrameHooks#INSTANCE};
     * the common mixin {@code MixinMinecraftHooks} fires it (Fabric does this, no Fabric API needed).
     */
    void registerPostFrameHook(HookPriority priority, Runnable hook);
}
