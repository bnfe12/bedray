package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value-refresh handshake the settings actions own: a preset or binding change asks Sodium to re-read every control
 * from its binding on the next frame ({@code requestValueRefresh}), and the option-list mixin consumes that request
 * exactly once ({@code consumeValueRefreshRequest}). Moved verbatim out of VulkanSodiumConfig into BedraySettingsActions;
 * this guards the read-and-clear the page relies on. Breaking the clear (returning the flag without resetting it) or the
 * set (requestValueRefresh doing nothing) fails here.
 */
public class BedraySettingsActionsTest {

    @Test
    public void refreshRequestIsConsumedExactlyOnce() {
        BedraySettingsActions.requestValueRefresh();
        assertTrue(BedraySettingsActions.consumeValueRefreshRequest(), "a requested refresh must be seen once");
        assertFalse(BedraySettingsActions.consumeValueRefreshRequest(), "the request is cleared after it is consumed");
    }
}