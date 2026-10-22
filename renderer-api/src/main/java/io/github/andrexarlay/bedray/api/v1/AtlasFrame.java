package io.github.andrexarlay.bedray.api.v1;

import static org.lwjgl.vulkan.VK10.*;

/** Borrowed live block atlas on the frame's device. The host owns image/view and retires all recorded
 * uses before closing either. The provider may own a sampler, but must never destroy this view.
 * Uploads/animation updates precede sampling on the host queue; descriptors stay immutable per generation.
 */
public record AtlasFrame(long generation, long deviceAddress, long imageView, int imageLayout,
                         int vkFormat, int width, int height, ColorEncoding colorEncoding) {
    public enum ColorEncoding { SRGB_IN_UNORM }
    public AtlasFrame {
        if (generation <= 0 || deviceAddress == 0 || imageView == 0 || width <= 0 || height <= 0
                || imageLayout != VK_IMAGE_LAYOUT_GENERAL || vkFormat != VK_FORMAT_R8G8B8A8_UNORM
                || colorEncoding != ColorEncoding.SRGB_IN_UNORM)
            throw new IllegalArgumentException("Unsupported borrowed block atlas contract");
    }
}
