package io.github.andrexarlay.vulkancompat.rt.sodium;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe map {@code sectionKey (SectionPos.asLong) -> DecodedSection} of the geometry Sodium has accepted
 * (R1a: CPU only, no GPU objects). Mutations come from the render thread (mixins H3/H4); readers may be any thread.
 *
 * <p>Log format for the verifier (one line per second while something changed, plus a final line at JVM shutdown):
 * {@code [VKB-RT] sections=<N> quads=<Q> solid=<..> cutout=<..> translucent=<..> added=<..> removed=<..> replaced=<..> failed=<..>}
 * and once {@code [VKB-RT] first_section key=(x,y,z) quads=<..> pos_min=(..) pos_max=(..)}.
 */
public final class SectionGeometryRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    public static final SectionGeometryRegistry INSTANCE = new SectionGeometryRegistry();

    /** Nominal section-local position range; a first section outside it (plus tolerance) means the formulas are wrong. */
    private static final float RANGE_MIN = 0.0f;
    private static final float RANGE_MAX = 16.0f;
    private static final float RANGE_TOLERANCE = 0.0625f;
    /**
     * Legitimate excursion of baked block geometry outside [0,16] from the block random offset
     * (javap BlockBehaviour.getMaxHorizontalOffset = 0.25f, getMaxVerticalOffset = 0.2f; the offset function
     * Properties.offsetType gives y = (n/15 - 1) * maxVertical, i.e. [-0.2, 0], x/z = (n/15 - 0.5) * 2 * maxHorizontal).
     * Grass, flowers and similar blocks therefore legitimately reach y=-0.1333 or x=-0.25.
     */
    private static final float OFFSET_HORIZONTAL = 0.25f;
    private static final float OFFSET_VERTICAL = 0.2f;
    private static final long LOG_INTERVAL_MS = 1000L;

    public interface Listener {
        /** A section was added or its geometry was replaced ({@code previous} != null). Called on the mutating thread. */
        void onSectionAdded(DecodedSection section, DecodedSection previous);

        /** A section with geometry was removed. */
        void onSectionRemoved(long key, DecodedSection removed);
    }

    private final ConcurrentHashMap<Long, DecodedSection> sections = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long[] passQuads = new long[DecodedSection.PASS_COUNT];
    private long totalQuads;
    private final AtomicLong added = new AtomicLong();
    private final AtomicLong removed = new AtomicLong();
    private final AtomicLong replaced = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong version = new AtomicLong();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean firstSectionLogged = new AtomicBoolean();
    private final AtomicBoolean reporterStarted = new AtomicBoolean();
    private volatile boolean outOfRangeLogged;

    SectionGeometryRegistry() {
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Accepts the geometry of a section. An empty section (no quads) removes the key, matching Sodium which replaces all
     * three passes of a section on every accepted build output.
     */
    public void submit(DecodedSection section) {
        if (section.isEmpty()) {
            remove(section.key);
            return;
        }
        DecodedSection previous;
        synchronized (this) {
            previous = sections.put(section.key, section);
            if (previous != null) {
                subtract(previous);
            }
            add(section);
        }
        if (previous == null) {
            added.incrementAndGet();
        } else {
            replaced.incrementAndGet();
        }
        version.incrementAndGet();
        dirty.set(true);
        startReporter();
        logFirstSection(section);
        for (Listener l : listeners) {
            l.onSectionAdded(section, previous);
        }
    }

    /** Removes the geometry of a section (no-op when the key is unknown). */
    public void remove(long key) {
        DecodedSection old;
        synchronized (this) {
            old = sections.remove(key);
            if (old != null) {
                subtract(old);
            }
        }
        if (old == null) {
            return;
        }
        removed.incrementAndGet();
        version.incrementAndGet();
        dirty.set(true);
        for (Listener l : listeners) {
            l.onSectionRemoved(key, old);
        }
    }

    /**
     * Removes every section (RT switched off: the decoded CPU copies are no longer needed). Goes through {@link #remove} so
     * counters, version and listeners stay consistent. Render thread (same as the other mutations).
     *
     * @return number of sections dropped
     */
    public int clear() {
        int n = 0;
        for (Long key : new java.util.ArrayList<>(sections.keySet())) {
            if (sections.containsKey(key)) {
                remove(key);
                n++;
            }
        }
        return n;
    }

    /** Counts a section whose decoding failed (the error with stack trace is logged by the caller). */
    public long recordFailure() {
        dirty.set(true);
        startReporter();
        return failed.incrementAndGet();
    }

    /** Incremented on every accepted submit and every removal; consumers poll it to detect changes. */
    public long version() {
        return version.get();
    }

    /** Weakly consistent iteration over the current sections (safe while other threads mutate). */
    public void forEachSection(java.util.function.Consumer<DecodedSection> consumer) {
        sections.values().forEach(consumer);
    }

    public DecodedSection get(long key) {
        return sections.get(key);
    }

    public int sectionCount() {
        return sections.size();
    }

    public long failedCount() {
        return failed.get();
    }

    public long addedCount() {
        return added.get();
    }

    public long removedCount() {
        return removed.get();
    }

    public long replacedCount() {
        return replaced.get();
    }

    public synchronized long totalQuads() {
        return totalQuads;
    }

    public synchronized long passQuads(int pass) {
        return passQuads[pass];
    }

    /** Formatted statistics line, see class comment. */
    public synchronized String statsLine() {
        return String.format(Locale.ROOT,
                "[VKB-RT] sections=%d quads=%d solid=%d cutout=%d translucent=%d added=%d removed=%d replaced=%d failed=%d",
                sections.size(), totalQuads,
                passQuads[DecodedSection.PASS_SOLID], passQuads[DecodedSection.PASS_CUTOUT],
                passQuads[DecodedSection.PASS_TRANSLUCENT],
                added.get(), removed.get(), replaced.get(), failed.get());
    }

    /** Clears everything including counters; for tests. */
    synchronized void resetForTests() {
        sections.clear();
        totalQuads = 0;
        java.util.Arrays.fill(passQuads, 0);
        added.set(0);
        removed.set(0);
        replaced.set(0);
        failed.set(0);
        dirty.set(false);
        firstSectionLogged.set(false);
    }

    private void add(DecodedSection s) {
        totalQuads += s.totalQuads();
        for (int p = 0; p < DecodedSection.PASS_COUNT; p++) {
            passQuads[p] += s.quadsInPass(p);
        }
    }

    private void subtract(DecodedSection s) {
        totalQuads -= s.totalQuads();
        for (int p = 0; p < DecodedSection.PASS_COUNT; p++) {
            passQuads[p] -= s.quadsInPass(p);
        }
    }

    private void logFirstSection(DecodedSection s) {
        if (!firstSectionLogged.compareAndSet(false, true)) {
            return;
        }
        String msg = String.format(Locale.ROOT,
                "[VKB-RT] first_section key=(%d,%d,%d) quads=%d pos_min=(%.4f,%.4f,%.4f) pos_max=(%.4f,%.4f,%.4f)",
                s.sectionX, s.sectionY, s.sectionZ, s.totalQuads(),
                s.posMin[0], s.posMin[1], s.posMin[2], s.posMax[0], s.posMax[1], s.posMax[2]);
        boolean inRange = true;
        for (int a = 0; a < 3; a++) {
            float lowAllow = (a == 1 ? OFFSET_VERTICAL : OFFSET_HORIZONTAL) + RANGE_TOLERANCE;
            float highAllow = (a == 1 ? 0.0f : OFFSET_HORIZONTAL) + RANGE_TOLERANCE;
            if (s.posMin[a] < RANGE_MIN - lowAllow || s.posMax[a] > RANGE_MAX + highAllow) {
                inRange = false;
            }
        }
        if (inRange) {
            LOGGER.info(msg);
        } else {
            outOfRangeLogged = true;
            LOGGER.error("{} ERROR: positions outside section-local [0,16] beyond block random offset (x/z +-{}, y -{}, +tolerance {}), decoder formulas are wrong",
                    msg, OFFSET_HORIZONTAL, OFFSET_VERTICAL, RANGE_TOLERANCE);
        }
    }

    public boolean firstSectionOutOfRange() {
        return outOfRangeLogged;
    }

    private void startReporter() {
        if (!reporterStarted.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(LOG_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (dirty.getAndSet(false)) {
                    LOGGER.info(statsLine());
                }
            }
        }, "VKB-RT-Stats");
        t.setDaemon(true);
        t.start();
        // Best-effort final line; the periodic thread already reports the last change within one second.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> LOGGER.info(statsLine() + " final=true"), "VKB-RT-Final"));
    }
}
