package io.github.andrexarlay.vulkancompat.rt.material;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2: pins why the copy-region arrays of the material upload cannot live on the LWJGL thread stack.
 * <p>
 * Owner crash 2026-10-24: {@code OutOfMemoryError: Out of stack space} at
 * {@code VkBufferImageCopy.calloc(batch.size(), stack)} during a frame that drained a whole 1313-sprite atlas in one
 * batch ({@code materials baked atlas=2048x2048 sprites=1313}). */
public class RtMaterialAtlasRegionsTest {
    /** Sprite count of the epoch that crashed ({@code materials baked ... sprites=1313} in the testbed log). */
    static final int WHOLE_ATLAS_SPRITES = 1313;

    /** The arena size {@link MemoryStack} itself derives from the configuration. */
    private static long arenaBytes() {
        return Configuration.STACK_SIZE.get(64) * 1024L;
    }

    @Test
    public void oneRegionArrayOfAWholeAtlasIsPastTheStockArena() {
        assertTrue(WHOLE_ATLAS_SPRITES * (long) VkBufferImageCopy.SIZEOF > 64L * 1024L,
            "1313 regions are 71.8 KiB, the stock arena is 64 KiB");
    }

    @Test
    public void anArrayThatDoesNotFitTheArenaThrows() {
        int sprites = (int) (arenaBytes() / VkBufferImageCopy.SIZEOF) + 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertThrows(OutOfMemoryError.class, () -> VkBufferImageCopy.calloc(sprites, stack),
                "the thread stack is a fixed arena: a region array sized by the atlas overflows it");
        }
    }

    @Test
    public void theUploadAllocatesWhatTheStackCannotHold() {
        VkBufferImageCopy.Buffer mat = RtMaterialAtlas.regions(WHOLE_ATLAS_SPRITES);
        VkBufferImageCopy.Buffer nor = RtMaterialAtlas.regions(WHOLE_ATLAS_SPRITES);
        try {
            assertEquals(WHOLE_ATLAS_SPRITES, mat.capacity());
            assertEquals(WHOLE_ATLAS_SPRITES, nor.capacity());
            mat.get(WHOLE_ATLAS_SPRITES - 1).bufferOffset(7L); // the whole range is addressable, not just the header
            assertEquals(7L, mat.get(WHOLE_ATLAS_SPRITES - 1).bufferOffset());
        } finally {
            mat.free();
            nor.free();
        }
    }
}
