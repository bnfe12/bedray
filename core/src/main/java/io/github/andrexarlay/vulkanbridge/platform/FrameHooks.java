package io.github.andrexarlay.vulkanbridge.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook dispatcher for loaders that have no tick/frame event of their own (Fabric without Fabric API). The loader module
 * owns a mixin on {@code Minecraft} that calls {@link #fireClientTick()} and {@link #firePostFrame()}.
 * Pure Java, no Minecraft classes. Exceptions from hooks propagate (not swallowed).
 */
public final class FrameHooks {
    private record Entry(HookPriority priority, Runnable hook) {}

    private final List<Runnable> tickHooks = new CopyOnWriteArrayList<>();
    private volatile List<Entry> frameHooks = List.of();

    public void addClientTickHook(Runnable hook) {
        tickHooks.add(hook);
    }

    public synchronized void addPostFrameHook(HookPriority priority, Runnable hook) {
        List<Entry> sorted = new ArrayList<>(frameHooks);
        int index = sorted.size();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).priority().ordinal() > priority.ordinal()) {
                index = i;
                break;
            }
        }
        sorted.add(index, new Entry(priority, hook));
        frameHooks = List.copyOf(sorted);
    }

    public void fireClientTick() {
        for (Runnable hook : tickHooks) {
            hook.run();
        }
    }

    public void firePostFrame() {
        for (Entry entry : frameHooks) {
            entry.hook().run();
        }
    }

    /** Shared instance used by the Fabric loader module and its Minecraft mixin. */
    public static final FrameHooks INSTANCE = new FrameHooks();
}
