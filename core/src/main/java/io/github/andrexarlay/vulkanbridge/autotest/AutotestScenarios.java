package io.github.andrexarlay.vulkanbridge.autotest;

import io.github.andrexarlay.vulkanbridge.api.Experimental;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registry of {@link AutotestScenario} contributed by mods. Thread-safe. Registration is allowed until the harness
 * resolves its scenario on the first client tick; the order of mod construction does not matter before that.
 * Pure Java (no Minecraft classes are touched), so it is unit-testable.
 */
@Experimental
public final class AutotestScenarios {
    /** Scenarios implemented inside the harness itself. */
    public static final Set<String> BUILT_IN = Set.of("menu", "world", "inventory", "soak", "world_rotate");

    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-AUTOTEST");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final Map<String, AutotestScenario> CUSTOM = new LinkedHashMap<>();

    private AutotestScenarios() {}

    /**
     * @throws IllegalArgumentException for an invalid name or a built-in name
     * @throws IllegalStateException    when the name is already registered
     */
    public static synchronized void register(AutotestScenario scenario) {
        String name = scenario.name();
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("autotest scenario name must match [a-z0-9_]+, got: " + name);
        }
        if (BUILT_IN.contains(name)) {
            throw new IllegalArgumentException("autotest scenario name '" + name + "' is built in and cannot be replaced");
        }
        if (CUSTOM.putIfAbsent(name, scenario) != null) {
            throw new IllegalStateException("autotest scenario '" + name + "' is already registered");
        }
        LOGGER.info("[VKB-AUTOTEST] event=scenario_registered name={} needsWorld={}", name, scenario.needsWorld());
    }

    /** The registered scenario or {@code null}. Built-in names are not in this registry. */
    public static synchronized AutotestScenario find(String name) {
        return CUSTOM.get(name);
    }

    /** True for a built-in or registered name. */
    public static synchronized boolean isKnown(String name) {
        return BUILT_IN.contains(name) || CUSTOM.containsKey(name);
    }

    /** Built-in plus registered names, for messages. */
    public static synchronized List<String> knownNames() {
        List<String> all = new java.util.ArrayList<>(new java.util.TreeSet<>(BUILT_IN));
        all.addAll(CUSTOM.keySet());
        return Collections.unmodifiableList(all);
    }

    /** Removes all registered scenarios (unit tests only). */
    public static synchronized void clearForTests() {
        CUSTOM.clear();
    }
}
