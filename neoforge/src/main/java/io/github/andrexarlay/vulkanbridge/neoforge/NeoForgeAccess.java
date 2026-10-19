package io.github.andrexarlay.vulkanbridge.neoforge;

import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.LoaderAccess;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * NeoForge implementation of {@link LoaderAccess}.
 * Signatures (javap -p): FMLPaths.GAMEDIR and FMLLoader.getCurrent()/isProduction() in loader-12.0.9.jar;
 * FMLPaths.CONFIGDIR, FMLLoader.getVersionInfo().neoForgeVersion(), LoadingModList.get().getModFileById(String);
 * IEventBus.addListener(EventPriority, Class, Consumer) in bus-8.0.5.jar; ClientTickEvent.Pre / RenderFrameEvent.Post
 * in neoforge-26.3.0.57-beta-universal.jar.
 */
public final class NeoForgeAccess implements LoaderAccess {
    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String loaderVersion() {
        return FMLLoader.getCurrent().getVersionInfo().neoForgeVersion();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isDevEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }

    @Override
    public void registerClientTickHook(Runnable hook) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, ClientTickEvent.Pre.class, event -> hook.run());
    }

    @Override
    public void registerPostFrameHook(HookPriority priority, Runnable hook) {
        NeoForge.EVENT_BUS.addListener(map(priority), RenderFrameEvent.Post.class, event -> hook.run());
    }

    private static EventPriority map(HookPriority priority) {
        return switch (priority) {
            case HIGH -> EventPriority.HIGH;
            case NORMAL -> EventPriority.NORMAL;
            case LOW -> EventPriority.LOW;
        };
    }
}
