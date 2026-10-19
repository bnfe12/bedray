package io.github.andrexarlay.vulkanbridge.fabric;

import io.github.andrexarlay.vulkanbridge.platform.FrameHooks;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.LoaderAccess;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Fabric implementation of {@link LoaderAccess}. Needs only fabric-loader, not Fabric API: tick and frame hooks are
 * fed by {@code MixinMinecraftHooks} through {@link FrameHooks#INSTANCE}.
 * Signatures (javap on fabric-loader-0.19.5.jar): {@code FabricLoader.getInstance()}, {@code getGameDir()} (Path),
 * {@code isDevelopmentEnvironment()}, {@code getConfigDir()}, {@code isModLoaded(String)},
 * {@code getModContainer(String)} -> {@code getMetadata().getVersion().getFriendlyString()}.
 */
public final class FabricAccess implements LoaderAccess {
    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public String loaderVersion() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .orElseThrow(() -> new IllegalStateException("fabricloader mod container missing"))
                .getMetadata().getVersion().getFriendlyString();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean isDevEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public void registerClientTickHook(Runnable hook) {
        FrameHooks.INSTANCE.addClientTickHook(hook);
    }

    @Override
    public void registerPostFrameHook(HookPriority priority, Runnable hook) {
        FrameHooks.INSTANCE.addPostFrameHook(priority, hook);
    }
}
