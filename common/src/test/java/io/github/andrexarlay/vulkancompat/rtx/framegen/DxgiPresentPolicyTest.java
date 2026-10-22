package io.github.andrexarlay.vulkancompat.rtx.framegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxgiPresentPolicyTest {
    @Test
    void keepsVanillaDxgiRequestWhenFrameGenerationIsNotSelected() {
        assertTrue(DxgiPresentPolicy.allow(true, FrameGenProvider.Active.NONE));
        assertFalse(DxgiPresentPolicy.allow(false, FrameGenProvider.Active.NONE));
    }

    @Test
    void selectsVulkanPresentForFrameGenerationProviders() {
        assertFalse(DxgiPresentPolicy.allow(true, FrameGenProvider.Active.AMD));
        assertFalse(DxgiPresentPolicy.allow(true, FrameGenProvider.Active.NVIDIA));
    }
}
