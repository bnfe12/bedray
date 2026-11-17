package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameGenMenuPolicyTest {
    @Test
    void noScreenKeepsGenerationOn() {
        assertFalse(FrameGenMenuPolicy.suppresses(false, true, false));
        assertFalse(FrameGenMenuPolicy.suppresses(false, false, false));
    }

    @Test
    void pauseAndSettingsScreensSuppressGeneration() {
        // pause menu, options, video settings, Bedray and Sodium settings: pause screens that are not in-game UI
        assertTrue(FrameGenMenuPolicy.suppresses(true, true, false));
    }

    @Test
    void chatDoesNotSuppressGeneration() {
        // ChatScreen is not a pause screen
        assertFalse(FrameGenMenuPolicy.suppresses(true, false, false));
    }

    @Test
    void inGameInterfaceDoesNotSuppressGeneration() {
        // inventory and container screens are in-game UI
        assertFalse(FrameGenMenuPolicy.suppresses(true, true, true));
        assertFalse(FrameGenMenuPolicy.suppresses(true, false, true));
    }
}
