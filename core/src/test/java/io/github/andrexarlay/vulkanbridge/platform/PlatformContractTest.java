package io.github.andrexarlay.vulkanbridge.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract of the loader seam: {@link Platforms} holder and {@link FrameHooks} ordering. Pure Java, no game. */
class PlatformContractTest {
    private static class FakeAccess implements LoaderAccess {
        private final String name;

        FakeAccess(String name) {
            this.name = name;
        }

        @Override public String loaderName() { return name; }
        @Override public String loaderVersion() { return "1"; }
        @Override public Path configDir() { return Path.of("game", "config"); }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public Path gameDir() { return Path.of("game"); }
        @Override public boolean isDevEnvironment() { return true; }
        @Override public void registerClientTickHook(Runnable hook) { }
        @Override public void registerPostFrameHook(HookPriority priority, Runnable hook) { }
    }

    private static final class OtherAccess extends FakeAccess {
        OtherAccess(String name) {
            super(name);
        }
    }

    @BeforeEach
    @AfterEach
    void reset() {
        Platforms.resetForTests();
    }

    @Test
    void getWithoutInstallFailsLoudly() {
        assertFalse(Platforms.isInstalled());
        assertThrows(IllegalStateException.class, Platforms::get);
    }

    @Test
    void installedAccessIsReturned() {
        FakeAccess access = new FakeAccess("fake");
        Platforms.install(access);
        assertTrue(Platforms.isInstalled());
        assertSame(access, Platforms.get());
        assertEquals(Path.of("game"), Platforms.get().gameDir());
    }

    @Test
    void secondInstallIsRejectedAndKeepsTheFirst() {
        FakeAccess first = new FakeAccess("first");
        Platforms.install(first);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Platforms.install(new OtherAccess("second")));
        assertTrue(e.getMessage().contains("first") && e.getMessage().contains("second"));
        assertSame(first, Platforms.get());
    }

    @Test
    void installingSameClassAgainKeepsTheFirst() {
        FakeAccess first = new FakeAccess("same");
        Platforms.install(first);
        Platforms.install(new FakeAccess("same"));
        assertSame(first, Platforms.get());
    }

    @Test
    void discoveryRequiresExactlyOneImplementation() {
        assertThrows(IllegalStateException.class, () -> Platforms.discover(List.of()));
        assertThrows(IllegalStateException.class, () -> Platforms.discover(List.of(new FakeAccess("a"), new OtherAccess("b"))));
        FakeAccess only = new FakeAccess("only");
        assertSame(only, Platforms.discover(List.of(only)));
    }

    @Test
    void earlyHooksRunOnceAtFireAndImmediatelyAfterwards() {
        EarlyHooks hooks = new EarlyHooks();
        List<String> order = new ArrayList<>();
        hooks.register(() -> order.add("a"));
        hooks.register(() -> order.add("b"));
        assertTrue(order.isEmpty());
        assertFalse(hooks.hasFired());
        hooks.fire();
        hooks.fire();
        assertEquals(List.of("a", "b"), order);
        hooks.register(() -> order.add("late"));
        assertEquals(List.of("a", "b", "late"), order);
        assertTrue(hooks.hasFired());
    }

    @Test
    void nullInstallIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Platforms.install(null));
    }

    @Test
    void frameHooksRunByPriorityThenRegistrationOrder() {
        FrameHooks hooks = new FrameHooks();
        List<String> order = new ArrayList<>();
        hooks.addPostFrameHook(HookPriority.LOW, () -> order.add("low"));
        hooks.addPostFrameHook(HookPriority.NORMAL, () -> order.add("normal1"));
        hooks.addPostFrameHook(HookPriority.HIGH, () -> order.add("high"));
        hooks.addPostFrameHook(HookPriority.NORMAL, () -> order.add("normal2"));
        hooks.firePostFrame();
        assertEquals(List.of("high", "normal1", "normal2", "low"), order);
    }

    @Test
    void tickHooksRunInRegistrationOrderAndAreSeparateFromFrameHooks() {
        FrameHooks hooks = new FrameHooks();
        List<String> order = new ArrayList<>();
        hooks.addClientTickHook(() -> order.add("t1"));
        hooks.addClientTickHook(() -> order.add("t2"));
        hooks.addPostFrameHook(HookPriority.NORMAL, () -> order.add("f"));
        hooks.fireClientTick();
        assertEquals(List.of("t1", "t2"), order);
    }

    @Test
    void hookExceptionsPropagate() {
        FrameHooks hooks = new FrameHooks();
        hooks.addPostFrameHook(HookPriority.NORMAL, () -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(IllegalStateException.class, hooks::firePostFrame);
    }

    /** Rule: module common contains no loader API. Scans the module's own main sources (working dir = module dir). */
    @Test
    void commonSourcesHaveNoLoaderImports() throws IOException {
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "test must run with the common module as working directory: " + root.toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (t.startsWith("import net.neoforged.") || t.startsWith("import net.fabricmc.")
                            || t.contains("net.neoforged.fml") || t.contains("net.fabricmc.loader")) {
                        offenders.add(root.relativize(file) + ": " + t);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "loader API in common: " + offenders);
    }
}
