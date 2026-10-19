package io.github.andrexarlay.vulkanbridge.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * "Before VkDevice" hooks. Hooks registered before {@link #fire()} run at fire time in registration order; hooks
 * registered after it run immediately. Pure Java. Exceptions propagate (the remaining hooks are not run).
 */
public final class EarlyHooks {
    private final List<Runnable> pending = new ArrayList<>();
    private boolean fired;

    public void register(Runnable hook) {
        boolean runNow;
        synchronized (this) {
            runNow = fired;
            if (!runNow) {
                pending.add(hook);
            }
        }
        if (runNow) {
            hook.run();
        }
    }

    /** Runs the pending hooks once; later calls do nothing. */
    public void fire() {
        List<Runnable> toRun;
        synchronized (this) {
            if (fired) {
                return;
            }
            fired = true;
            toRun = new ArrayList<>(pending);
            pending.clear();
        }
        for (Runnable hook : toRun) {
            hook.run();
        }
    }

    public synchronized boolean hasFired() {
        return fired;
    }

    /** Shared instance used by {@link LoaderAccess#registerEarlyHook} and {@link BridgeBootstrap}. */
    public static final EarlyHooks INSTANCE = new EarlyHooks();
}
