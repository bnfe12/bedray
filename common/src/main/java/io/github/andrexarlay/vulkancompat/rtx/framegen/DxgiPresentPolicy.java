package io.github.andrexarlay.vulkancompat.rtx.framegen;

/** DXGI presentation uses a D3D12 swapchain; Bedray's frame-generation providers currently require Vulkan's swapchain. */
public final class DxgiPresentPolicy {
    private DxgiPresentPolicy() {
    }

    public static boolean allow(boolean requested, FrameGenProvider.Active provider) {
        return requested && provider == FrameGenProvider.Active.NONE;
    }
}
