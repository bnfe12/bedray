package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Vanilla {@code tinted_glass} (amethyst glass) is see-through to the camera but blocks ALL light, unlike clear and
 * stained glass which transmit (tinted) light. It must therefore carry its own material rule so the baker can tag it
 * as a light blocker for the visibility walk while the camera path keeps it a translucent pane. Clear glass and the
 * sixteen stained colours keep the transmitting {@link AutoPbr#RULE_GLASS} rule.
 */
class TintedGlassTest {

    @Test
    void tintedGlassGetsItsOwnLightBlockingRule() {
        AutoPbr.Material tinted = AutoPbr.classify("block/tinted_glass");
        assertEquals("tinted_glass", tinted.rule(),
            "tinted_glass blocks light in vanilla and must not share the transmitting glass rule");
    }

    @Test
    void clearAndStainedGlassKeepTheTransmittingRule() {
        assertEquals(AutoPbr.RULE_GLASS, AutoPbr.classify("block/glass").rule(), "clear glass transmits light");
        assertEquals(AutoPbr.RULE_GLASS, AutoPbr.classify("block/red_stained_glass").rule(),
            "stained glass transmits tinted light");
        assertEquals(AutoPbr.RULE_GLASS, AutoPbr.classify("block/light_blue_stained_glass").rule(),
            "every stained colour transmits tinted light");
        assertEquals(AutoPbr.RULE_GLASS, AutoPbr.classify("block/glass_pane").rule(), "clear pane transmits light");
        assertEquals(AutoPbr.RULE_GLASS, AutoPbr.classify("block/red_stained_glass_pane_top").rule(),
            "stained pane transmits tinted light");
    }

    @Test
    void tintedGlassRuleIsDistinctFromClearGlass() {
        assertNotEquals(AutoPbr.classify("block/glass").rule(), AutoPbr.classify("block/tinted_glass").rule(),
            "the blocker and the transmitter must be distinguishable downstream");
    }
}