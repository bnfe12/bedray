package io.github.andrexarlay.vulkancompat.rt.material;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * GPU side of the RT materials: two RGBA8 images with the layout of the block atlas (material: roughness / f0-or-metal /
 * emission / source; normal: LabPBR {@code _n} texel), sampled by rt_rays.comp and rt_path.comp (bindings 9 and 10).
 * Re-baked whenever the block atlas object or its size changes (resource reload). The upload is recorded into the frame
 * command buffer through a host-visible staging buffer that is freed {@link #STAGING_LIFETIME} frames later (the engine
 * keeps at most two frames in flight).
 */
public final class RtMaterialAtlas {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int STAGING_LIFETIME = 4;

    private final RtMemory memory;
    private RtMemory.Img material;
    private RtMemory.Img normal;
    private RtMemory.Buf staging;
    private int stagingFrames;
    private Object bakedAtlas;
    private int bakedW = -1;
    private int bakedH = -1;
    private MaterialAtlasBaker.Result last;

    public RtMaterialAtlas(RtMemory memory) {
        this.memory = memory;
    }

    public long materialView() {
        return material.view;
    }

    public long normalView() {
        return normal.view;
    }

    public MaterialAtlasBaker.Result lastBake() {
        return last;
    }

    /**
     * Makes the images match {@code atlas}: bakes and records the upload into {@code cmd} when the atlas changed. Must be
     * called outside a render pass, before the RT dispatch of the frame. Returns true when an upload was recorded.
     */
    public boolean ensure(VkCommandBuffer cmd, TextureAtlas atlas, int width, int height, ResourceProvider resources) {
        if (staging != null && --stagingFrames <= 0) {
            memory.destroy(staging);
            staging = null;
        }
        if (atlas == bakedAtlas && width == bakedW && height == bakedH) {
            return false;
        }
        MaterialAtlasBaker.Result r = MaterialAtlasBaker.bake(atlas, width, height, resources);
        last = r;
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-RT] materials baked atlas=%dx%d sprites=%d labpbr_s=%d labpbr_n=%d auto_normals=%d emissive_sprites=%d metal_sprites=%d errors=%d ms=%.1f",
            width, height, r.sprites(), r.labS(), r.labN(), MaterialAtlasBaker.autoNormalSprites, r.emissiveSprites(), r.metalSprites(),
            r.errors(), r.ms()));
        if (material == null || material.width != width || material.height != height) {
            if (material != null) {
                VK10.vkDeviceWaitIdle(memory.device); // in-flight frames may still sample the old images
                memory.destroy(material);
                memory.destroy(normal);
            }
            int usage = VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            material = memory.createImage("rt_material_atlas", width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, usage);
            normal = memory.createImage("rt_normal_atlas", width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, usage);
            VkBarriers.transition(cmd, material.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, normal.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL);
        }
        long bytes = (long) width * height * 4L;
        if (staging != null) {
            VK10.vkDeviceWaitIdle(memory.device);
            memory.destroy(staging);
        }
        staging = memory.createBuffer("rt_material_staging", bytes * 2, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        stagingFrames = STAGING_LIFETIME;
        MemoryUtil.memIntBuffer(staging.mapped, width * height).put(r.material());
        MemoryUtil.memIntBuffer(staging.mapped + bytes, width * height).put(r.normal());
        VkBarriers.fullMemoryBarrier(cmd);
        copy(cmd, staging.handle, 0L, material.image, width, height);
        copy(cmd, staging.handle, bytes, normal.image, width, height);
        VkBarriers.fullMemoryBarrier(cmd);
        bakedAtlas = atlas;
        bakedW = width;
        bakedH = height;
        return true;
    }

    private static void copy(VkCommandBuffer cmd, long buffer, long offset, long image, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(offset).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageExtent().width(w).height(h).depth(1);
            // GENERAL is a valid layout for transfer destinations (VUID-vkCmdCopyBufferToImage-dstImageLayout-01396)
            VK10.vkCmdCopyBufferToImage(cmd, buffer, image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    public void destroy() {
        if (staging != null) {
            memory.destroy(staging);
            staging = null;
        }
        if (material != null) {
            memory.destroy(material);
            memory.destroy(normal);
            material = null;
            normal = null;
        }
        bakedAtlas = null;
    }
}
