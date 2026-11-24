package io.github.andrexarlay.bedray.api.v1.light;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Registry of {@link LightProvider}s (API 1.9). Add-ons register once during client initialisation under their mod id; the
 * host calls {@link #collect} once per frame. The registry validates what it is given, bounds how much one provider and
 * all providers together can contribute, and isolates failures so one broken add-on cannot take the frame down.
 *
 * <p>Status: the host collects and validates lights every frame and, when the add-on light switch is on, feeds the accepted
 * lights into the renderer emitter table. That injection has not been verified on a GPU yet; see
 * {@link #appliedByHost()}.
 */
public final class LightProviders {
    /** Most lights one provider may contribute per frame; the rest are dropped. */
    public static final int MAX_PER_PROVIDER = 64;
    /** Most lights all providers together may contribute per frame. */
    public static final int MAX_TOTAL = 256;
    /** Consecutive failing frames after which a provider is disabled for the session. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}");

    private static final Map<String, Entry> PROVIDERS = new LinkedHashMap<>();

    private LightProviders() {
    }

    /** Per-provider diagnostics. */
    public record Status(String addonId, boolean disabled, int failures, int lastAccepted, int lastDropped) {
    }

    private static final class Entry {
        final LightProvider provider;
        int failures;
        boolean disabled;
        int lastAccepted;
        int lastDropped;

        Entry(LightProvider provider) {
            this.provider = provider;
        }
    }

    /** Registers a provider under the add-on mod id. Throws if the id is malformed or already registered. */
    public static synchronized void register(String addonId, LightProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (addonId == null || !ID.matcher(addonId).matches()) {
            throw new IllegalArgumentException("Add-on id must match " + ID.pattern());
        }
        if (PROVIDERS.containsKey(addonId)) {
            throw new IllegalStateException("A light provider is already registered for " + addonId);
        }
        PROVIDERS.put(addonId, new Entry(provider));
    }

    public static synchronized boolean unregister(String addonId) {
        return PROVIDERS.remove(addonId) != null;
    }

    /** Test and reload hook: removes every provider. */
    public static synchronized void clear() {
        PROVIDERS.clear();
    }

    /** Collects this frame lights from every enabled provider, validated, capped and failure-isolated. */
    public static synchronized List<PointLight> collect(CameraSnapshot camera) {
        List<PointLight> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : PROVIDERS.entrySet()) {
            Entry entry = e.getValue();
            if (entry.disabled || USER_OFF.contains(e.getKey())) {
                continue;
            }
            List<PointLight> got;
            try {
                got = entry.provider.lights(camera);
                entry.failures = 0;
            } catch (RuntimeException | LinkageError failure) {
                entry.lastAccepted = 0;
                entry.lastDropped = 0;
                if (++entry.failures >= MAX_CONSECUTIVE_FAILURES) {
                    entry.disabled = true;
                }
                continue;
            }
            int accepted = 0;
            int dropped = 0;
            if (got != null) {
                for (PointLight light : got) {
                    if (light == null || accepted >= MAX_PER_PROVIDER || out.size() >= MAX_TOTAL) {
                        dropped++;
                        continue;
                    }
                    out.add(light);
                    accepted++;
                }
            }
            entry.lastAccepted = accepted;
            entry.lastDropped = dropped;
        }
        return List.copyOf(out);
    }

    public static synchronized List<Status> status() {
        List<Status> list = new ArrayList<>();
        for (Map.Entry<String, Entry> e : PROVIDERS.entrySet()) {
            Entry v = e.getValue();
            list.add(new Status(e.getKey(), v.disabled, v.failures, v.lastAccepted, v.lastDropped));
        }
        return List.copyOf(list);
    }

    /** Add-on ids the player switched off (host tools such as Mosaic Studio); providers stay registered. */
    private static final java.util.Set<String> USER_OFF = new java.util.LinkedHashSet<>();

    /** Host only: switches an add-on's lights off or on without unregistering it. */
    public static synchronized void setUserEnabled(String addonId, boolean enabled) {
        if (enabled) USER_OFF.remove(addonId);
        else USER_OFF.add(addonId);
    }

    /** Whether the player switched this add-on off. */
    public static synchronized boolean userEnabled(String addonId) { return !USER_OFF.contains(addonId); }

    private static volatile boolean applied;

    /** Whether any provider is registered; the host skips building a camera snapshot when none is. */
    public static synchronized boolean hasProviders() {
        return !PROVIDERS.isEmpty();
    }

    /** Host only: records whether the lights of the last frame were fed into the renderer. */
    public static void markApplied(boolean value) {
        applied = value;
    }

    /** Whether the running host fed accepted lights into the renderer in the last frame. */
    public static boolean appliedByHost() {
        return applied;
    }
}
