package io.github.andrexarlay.vulkancompat.resources;

import java.util.concurrent.atomic.AtomicLong;

/** Completed resource reload identity, including F3+T with the same selected pack IDs. */
public final class ResourceReloadEpoch {
    private static final AtomicLong COMPLETED = new AtomicLong(1);

    private ResourceReloadEpoch() {}

    public static long current() { return COMPLETED.get(); }
    public static long completed() { return COMPLETED.incrementAndGet(); }
}
