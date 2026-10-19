package io.github.andrexarlay.vulkanbridge.platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract every {@link LoaderAccess} implementation must pass. Subclass it and return the implementation from
 * {@link #create()}. Implementations that need a running loader (real NeoForge/Fabric) override
 * {@link #requiresRunningLoader()} to return true: the tests are then skipped outside the loader (the same checks are
 * covered in the game by the {@code [VKB-BRIDGE] loader=...} log line).
 * Shipped as test fixtures of module common (java-test-fixtures), usable from every loader module.
 */
public abstract class LoaderAccessContract {
    protected abstract LoaderAccess create();

    /** True for implementations whose accessors only work inside a running loader. */
    protected boolean requiresRunningLoader() {
        return false;
    }

    private LoaderAccess access() {
        Assumptions.assumeFalse(requiresRunningLoader(), "needs a running loader");
        return create();
    }

    @Test
    void nameAndVersionAreNotBlank() {
        LoaderAccess a = access();
        assertNotNull(a.loaderName());
        assertFalse(a.loaderName().isBlank());
        assertNotNull(a.loaderVersion());
        assertFalse(a.loaderVersion().isBlank());
    }

    @Test
    void directoriesAreAvailable() {
        LoaderAccess a = access();
        assertNotNull(a.gameDir());
        assertNotNull(a.configDir());
    }

    @Test
    void unknownModIsNotLoaded() {
        assertFalse(access().isModLoaded("vkbridge_no_such_mod_" + System.nanoTime()));
    }

    @Test
    void registeringTickAndFrameHooksDoesNotRunThem() {
        LoaderAccess a = access();
        AtomicInteger runs = new AtomicInteger();
        a.registerClientTickHook(runs::incrementAndGet);
        a.registerPostFrameHook(HookPriority.HIGH, runs::incrementAndGet);
        a.registerPostFrameHook(HookPriority.NORMAL, runs::incrementAndGet);
        a.registerPostFrameHook(HookPriority.LOW, runs::incrementAndGet);
        assertEquals(0, runs.get());
    }

    @Test
    void earlyHookRunsExactlyOnceAfterFire() {
        LoaderAccess a = access();
        AtomicInteger runs = new AtomicInteger();
        a.registerEarlyHook(runs::incrementAndGet);
        EarlyHooks.INSTANCE.fire();
        EarlyHooks.INSTANCE.fire();
        assertEquals(1, runs.get());
        a.registerEarlyHook(runs::incrementAndGet);
        assertEquals(2, runs.get(), "a hook registered after the early point runs immediately");
        assertTrue(EarlyHooks.INSTANCE.hasFired());
    }
}
