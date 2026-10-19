package io.github.andrexarlay.vulkanbridge.glbridge;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Stage 5 check inside the game (same idea as stages 1 and 3 of the standalone smoke test): GL clears a shared image to a
 * known colour, Vulkan copies the centre pixel to a host buffer, the CPU compares. Uses its own queue submissions on the
 * engine's graphics queue (render thread, before the engine's own frame submission, so nothing races). Several frames with
 * different colours over both ring slots, so a missing wait or a stale slot would show up as a wrong pixel.
 */
final class GlSelfTest {
    private static final float[][] COLORS = {
        {1f, 0f, 1f, 1f}, {0f, 1f, 0f, 1f}, {0f, 0f, 1f, 1f}, {1f, 1f, 0f, 1f}, {0.5f, 0.5f, 0.5f, 1f}, {1f, 0f, 1f, 1f},
    };

    private GlSelfTest() {}

    /** Runs all frames, returns the log text of the first frame pixel; throws on any mismatch. */
    static String run(EngineVk e, GlThread gl, SharedRing ring) {
        VkDevice dev = e.vkDevice();
        long buf, mem, fence;
        long mapped;
        VkCommandBuffer cmd;
        try (MemoryStack s = stackPush()) {
            LongBuffer p = s.mallocLong(1);
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(s).sType$Default().size(4)
                .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            SharedRing.check(vkCreateBuffer(dev, bci, null, p), "vkCreateBuffer(selftest)");
            buf = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(dev, buf, req);
            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size())
                .memoryTypeIndex(ring.findMemoryType(req.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            SharedRing.check(vkAllocateMemory(dev, mai, null, p), "vkAllocateMemory(selftest)");
            mem = p.get(0);
            SharedRing.check(vkBindBufferMemory(dev, buf, mem, 0), "vkBindBufferMemory(selftest)");
            PointerBuffer pp = s.mallocPointer(1);
            SharedRing.check(vkMapMemory(dev, mem, 0, 4, 0, pp), "vkMapMemory(selftest)");
            mapped = pp.get(0);
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(s).sType$Default();
            SharedRing.check(vkCreateFence(dev, fci, null, p), "vkCreateFence(selftest)");
            fence = p.get(0);
            VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(ring.cmdPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer cb = s.mallocPointer(1);
            SharedRing.check(vkAllocateCommandBuffers(dev, cai, cb), "vkAllocateCommandBuffers(selftest)");
            cmd = new VkCommandBuffer(cb.get(0), dev);
        }

        String first = null;
        try {
            for (int k = 0; k < COLORS.length; k++) {
                int slot = k % ring.slots;
                float[] c = COLORS[k];
                ring.release(slot);
                final int fk = k;
                gl.run(() -> ring.glFrame(slot, c, null, fk, EXTSemaphore.GL_LAYOUT_TRANSFER_SRC_EXT, null));
                try (MemoryStack s = stackPush()) {
                    vkResetCommandBuffer(cmd, 0);
                    SharedRing.check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer(selftest)");
                    ring.recordAcquire(cmd, s, slot, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT);
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, s)
                        .imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .imageOffset(o -> o.x(ring.w / 2).y(ring.h / 2).z(0))
                        .imageExtent(x -> x.width(1).height(1).depth(1));
                    vkCmdCopyImageToBuffer(cmd, ring.image[slot], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buf, region);
                    SharedRing.check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(selftest)");
                    VkSubmitInfo si = VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd))
                        .waitSemaphoreCount(1).pWaitSemaphores(s.longs(ring.semGlToVk[slot]))
                        .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
                    SharedRing.check(vkQueueSubmit(e.queue(), si, fence), "vkQueueSubmit(selftest)");
                    SharedRing.check(vkWaitForFences(dev, fence, true, 5_000_000_000L), "vkWaitForFences(selftest)");
                    SharedRing.check(vkResetFences(dev, fence), "vkResetFences(selftest)");
                }
                int px = MemoryUtil.memGetInt(mapped);
                int expR = Math.round(c[0] * 255f), expG = Math.round(c[1] * 255f), expB = Math.round(c[2] * 255f), expA = Math.round(c[3] * 255f);
                int r = px & 0xFF, g = (px >> 8) & 0xFF, b = (px >> 16) & 0xFF, a = (px >>> 24) & 0xFF;
                // 0.5 -> 127 or 128 depending on rounding of the GL clear conversion
                boolean ok = near(r, expR) && near(g, expG) && near(b, expB) && near(a, expA);
                String hex = String.format("%02X%02X%02X%02X", r, g, b, a);
                if (k == 0) {
                    first = hex;
                }
                GlLog.line(5, "event=selftest_frame frame=" + k + " slot=" + slot + " pixel=" + hex + " expected="
                    + String.format("%02X%02X%02X%02X", expR, expG, expB, expA) + (ok ? " ok=true" : " ok=false"));
                if (!ok) {
                    throw new IllegalStateException("shared image pixel mismatch at frame " + k + " slot " + slot + ": got " + hex);
                }
            }
        } finally {
            vkFreeCommandBuffers(dev, ring.cmdPool, cmd);
            vkDestroyFence(dev, fence, null);
            vkUnmapMemory(dev, mem);
            vkDestroyBuffer(dev, buf, null);
            vkFreeMemory(dev, mem, null);
        }
        return first;
    }

    private static boolean near(int got, int expected) {
        return Math.abs(got - expected) <= 1;
    }
}
