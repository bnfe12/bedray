package io.github.andrexarlay.vulkanbridge.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Holder of the active {@link LoaderAccess}. Resolution order: an explicitly installed instance (primary path, set by
 * the loader entry point), otherwise exactly one implementation found by {@link ServiceLoader}.
 */
public final class Platforms {
    private static volatile LoaderAccess current;

    private Platforms() {}

    /**
     * Installs the active implementation. Installing an instance of the same class again (for example after SPI
     * discovery already created one) is a no-op that keeps the first.
     *
     * @throws IllegalStateException when a different loader implementation is already active
     */
    public static synchronized void install(LoaderAccess access) {
        if (access == null) {
            throw new IllegalArgumentException("access must not be null");
        }
        if (current != null) {
            if (current.getClass() == access.getClass()) {
                return;
            }
            throw new IllegalStateException("LoaderAccess already active: " + current.loaderName()
                    + ", refusing " + access.loaderName());
        }
        current = access;
    }

    public static boolean isInstalled() {
        return current != null;
    }

    /**
     * @throws IllegalStateException when nothing is installed and SPI discovery finds none or more than one
     */
    public static LoaderAccess get() {
        LoaderAccess access = current;
        if (access != null) {
            return access;
        }
        synchronized (Platforms.class) {
            if (current == null) {
                current = discover(ServiceLoader.load(LoaderAccess.class, Platforms.class.getClassLoader()));
            }
            return current;
        }
    }

    static LoaderAccess discover(Iterable<LoaderAccess> candidates) {
        List<LoaderAccess> found = new ArrayList<>();
        candidates.forEach(found::add);
        if (found.isEmpty()) {
            throw new IllegalStateException("no LoaderAccess installed or registered through META-INF/services: the Bedray loader module has not started");
        }
        if (found.size() > 1) {
            List<String> names = new ArrayList<>();
            found.forEach(a -> names.add(a.getClass().getName()));
            throw new IllegalStateException("more than one LoaderAccess implementation on the class path (two loader jars installed?): " + names);
        }
        return found.get(0);
    }

    /** Test support only. */
    static synchronized void resetForTests() {
        current = null;
    }
}
