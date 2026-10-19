package io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3;

import java.util.function.LongConsumer;

/** Keeps calls that dereference the SDK's fiSwapChain inside its lifetime. No GPU dependency. */
final class Fsr3Teardown {
    private Fsr3Teardown() {}

    static void releaseGeneration(long swapchain, LongConsumer disable, Runnable waitForPresents, Runnable destroy) {
        if (swapchain != 0L) {
            disable.accept(swapchain);
            waitForPresents.run();
        }
        destroy.run();
    }

    static void releaseSwapchainContext(long swapchain, Runnable waitForPresents, Runnable destroy) {
        if (swapchain != 0L) {
            waitForPresents.run();
        }
        destroy.run();
    }
}
