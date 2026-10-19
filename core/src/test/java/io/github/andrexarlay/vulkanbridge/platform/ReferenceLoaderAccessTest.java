package io.github.andrexarlay.vulkanbridge.platform;

import java.nio.file.Path;

/** Runs the shared contract against a reference implementation (the Fabric-style FrameHooks based one). */
class ReferenceLoaderAccessTest extends LoaderAccessContract {
    private static final class Reference implements LoaderAccess {
        private final FrameHooks hooks = new FrameHooks();

        @Override public String loaderName() { return "reference"; }
        @Override public String loaderVersion() { return "0"; }
        @Override public Path gameDir() { return Path.of("game"); }
        @Override public Path configDir() { return Path.of("game", "config"); }
        @Override public boolean isDevEnvironment() { return true; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public void registerClientTickHook(Runnable hook) { hooks.addClientTickHook(hook); }
        @Override public void registerPostFrameHook(HookPriority priority, Runnable hook) { hooks.addPostFrameHook(priority, hook); }
    }

    @Override
    protected LoaderAccess create() {
        return new Reference();
    }
}
