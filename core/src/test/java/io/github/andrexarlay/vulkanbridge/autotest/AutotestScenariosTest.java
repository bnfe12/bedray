package io.github.andrexarlay.vulkanbridge.autotest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** L2: scenario registry only (the harness itself needs the game). */
class AutotestScenariosTest {
    private static AutotestScenario scenario(String name, boolean world) {
        return new AutotestScenario() {
            @Override public String name() { return name; }
            @Override public boolean needsWorld() { return world; }
            @Override public void run(AutotestContext ctx) { }
        };
    }

    @AfterEach
    void clear() {
        AutotestScenarios.clearForTests();
    }

    @Test
    void registersAndFindsCustomScenario() {
        var s = scenario("sun", true);
        AutotestScenarios.register(s);
        assertSame(s, AutotestScenarios.find("sun"));
        assertTrue(AutotestScenarios.isKnown("sun"));
        assertTrue(AutotestScenarios.isKnown("menu"));
        assertFalse(AutotestScenarios.isKnown("nope"));
        assertNull(AutotestScenarios.find("menu"));
        assertTrue(AutotestScenarios.knownNames().containsAll(java.util.List.of("menu", "world", "sun")));
    }

    @Test
    void rejectsBuiltInDuplicateAndBadNames() {
        assertThrows(IllegalArgumentException.class, () -> AutotestScenarios.register(scenario("world", true)));
        assertThrows(IllegalArgumentException.class, () -> AutotestScenarios.register(scenario("Bad Name", true)));
        assertThrows(IllegalArgumentException.class, () -> AutotestScenarios.register(scenario("", true)));
        AutotestScenarios.register(scenario("settings", false));
        assertThrows(IllegalStateException.class, () -> AutotestScenarios.register(scenario("settings", false)));
    }

    @Test
    void needsWorldDefaultsToTrue() {
        var s = new AutotestScenario() {
            @Override public String name() { return "d"; }
            @Override public void run(AutotestContext ctx) { }
        };
        assertTrue(s.needsWorld());
    }
}
