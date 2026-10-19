package io.github.andrexarlay.vulkancompat.autotest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticlesScenarioTest {
    @Test
    void defaultStepsParse() {
        var steps = ParticlesScenario.parseSteps(ParticlesScenario.DEFAULT_STEPS);
        assertEquals(10, steps.size());
        assertTrue(steps.get(1).mods().contains("off"));
        assertEquals(150, steps.get(0).frames());
    }

    @Test
    void rejectsUnknownKindAndModifier() {
        assertThrows(IllegalArgumentException.class, () -> ParticlesScenario.parseSteps("lava:rays"));
        assertThrows(IllegalArgumentException.class, () -> ParticlesScenario.parseSteps("flame:rays+bogus"));
    }

    @Test
    void frameModifierOverridesTheDefault() {
        assertEquals(40, ParticlesScenario.parseSteps("smoke:path+f40").get(0).frames());
    }
}
