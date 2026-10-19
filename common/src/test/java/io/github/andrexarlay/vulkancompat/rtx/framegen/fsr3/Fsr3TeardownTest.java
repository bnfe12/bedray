package io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Fsr3TeardownTest {
    @Test
    void activeGenerationDisablesTheActualSwapchainBeforeDrainingAndDestroying() {
        List<String> calls = new ArrayList<>();
        long swapchain = 0x1234L;
        Fsr3Teardown.releaseGeneration(swapchain, handle -> {
            assertEquals(swapchain, handle);
            calls.add("disable");
        }, () -> calls.add("wait"), () -> calls.add("destroy"));
        assertEquals(List.of("disable", "wait", "destroy"), calls);
    }

    @Test
    void generationAfterSurfaceDestructionDoesNotConfigureOrWaitOnNullSwapchain() {
        List<String> calls = new ArrayList<>();
        Fsr3Teardown.releaseGeneration(0L, handle -> calls.add("invalid configure"),
            () -> calls.add("invalid wait"), () -> calls.add("destroy"));
        assertEquals(List.of("destroy"), calls);
    }

    @Test
    void deviceCloseWithLiveSwapchainDrainsBeforeContextDestruction() {
        List<String> calls = new ArrayList<>();
        Fsr3Teardown.releaseSwapchainContext(0x1234L, () -> calls.add("wait"), () -> calls.add("destroy"));
        assertEquals(List.of("wait", "destroy"), calls);
    }

    @Test
    void deviceCloseAfterSurfaceCloseDoesNotDispatchWaitWithClearedSdkSwapchain() {
        List<String> calls = new ArrayList<>();
        Fsr3Teardown.releaseSwapchainContext(0L, () -> calls.add("invalid wait"), () -> calls.add("destroy"));
        assertEquals(List.of("destroy"), calls);
    }
}
