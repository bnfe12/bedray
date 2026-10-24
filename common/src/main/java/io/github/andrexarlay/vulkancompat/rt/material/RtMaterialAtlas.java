package io.github.andrexarlay.vulkancompat.rt.material;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * GPU side of the RT materials: two RGBA8 images with the layout of the block atlas (material: roughness / f0-or-metal /
 * emission / source; normal: LabPBR {@code _n} texel), sampled by rt_rays.comp and rt_path.comp (bindings 9 and 10).
 * <p>
 * Baking is progressive: a daemon worker thread bakes sprites off the render thread, and every frame the render thread
 * uploads only the sprite rects finished since the last frame (tight-packed staging, one {@link VkBufferImageCopy} per
 * sprite per image). A resource pack switch therefore spreads over a few frames instead of freezing one frame on a full
 * atlas bake + full re-upload + {@code vkDeviceWaitIdle}; until a sprite is baked it keeps the previous content or the
 * matte default. Only an atlas size change recreates the images (with a single waitIdle, as the old images may still be
 * sampled by frames in flight). The retired staging buffers are freed {@link #STAGING_LIFETIME} frames later (the engine
 * keeps at most two frames in flight).
 */
public final class RtMaterialAtlas {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final int STAGING_LIFETIME = 4;
    /** Upload at most this many texels per frame (a single sprite always goes through, however large). */
    private static final long UPLOAD_TEXEL_BUDGET = 1L << 19;

    private final RtMemory memory;
    private RtMemory.Img material;
    private RtMemory.Img normal;
    private Object bakedAtlas;
    private int bakedW = -1;
    private int bakedH = -1;
    private MaterialAtlasBaker.Result last;

    /**
     * Render-thread observer of the bake for the opacity micromap cache: epoch boundaries and every finished sprite
     * (with its albedo alpha and animated flag) so cutout texels can be classified. All methods run on the render
     * thread, never on the bake worker.
     */
    public interface MicromapSink {
        void onEpochStart();

        void onSpriteBaked(int x0, int y0, int w, int h, int[] material, byte[] alpha, boolean animated,
                           int atlasWidth, int atlasHeight);

        void onEpochFinished();
    }

    private volatile MicromapSink micromapSink;

    public void setMicromapSink(MicromapSink sink) {
        this.micromapSink = sink;
    }

    /** CPU-side atlas: the source of truth for uploads; the live arrays are shared with the emitters via {@link #last}. */
    private int[] cpuMaterial;
    private int[] cpuNormal;

    private record Completed(MaterialAtlasBaker.SpriteBake bake, Epoch owner) {
    }

    private record Retired(RtMemory.Buf buf, int frames) {
    }

    private static final class Epoch {
        final TextureAtlas atlas;
        final int width;
        final int height;
        final ResourceProvider resources;
        final ArrayDeque<TextureAtlasSprite> queue = new ArrayDeque<>();
        final long startNs = System.nanoTime();
        // worker-thread only (the render thread reads them after the volatile `finished` write)
        int labS;
        int labN;
        int emissive;
        int metal;
        int errors;
        int autoN;
        volatile boolean cancelled;
        volatile boolean finished;

        Epoch(TextureAtlas atlas, int width, int height, ResourceProvider resources) {
            this.atlas = atlas;
            this.width = width;
            this.height = height;
            this.resources = resources;
        }
    }

    private Epoch epoch;
    private volatile Epoch handoff;
    private volatile boolean shutdown;
    private Thread worker;
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    private final ArrayList<Retired> retired = new ArrayList<>();

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
     * Makes the images match {@code atlas}: starts a progressive bake when the atlas changed and records the uploads of
     * the sprites finished since the last frame into {@code cmd}. Must be called outside a render pass, before the RT
     * dispatch of the frame. Returns true when an upload was recorded.
     */
    public boolean ensure(VkCommandBuffer cmd, TextureAtlas atlas, int width, int height, ResourceProvider resources) {
        for (int i = retired.size() - 1; i >= 0; i--) {
            Retired r = retired.get(i);
            if (r.frames() - 1 <= 0) {
                memory.destroy(r.buf());
                retired.remove(i);
            } else {
                retired.set(i, new Retired(r.buf(), r.frames() - 1));
            }
        }
        if (material == null || material.width != width || material.height != height) {
            recreateImages(cmd, width, height);
        }
        if (epoch == null) {
            if (atlas == bakedAtlas && width == bakedW && height == bakedH) {
                return false;
            }
            startEpoch(atlas, width, height, resources);
        } else if (epoch.atlas != atlas || epoch.width != width || epoch.height != height) {
            epoch.cancelled = true;
            startEpoch(atlas, width, height, resources);
        }
        return drainAndUpload(cmd);
    }

    private void recreateImages(VkCommandBuffer cmd, int width, int height) {
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
        cpuMaterial = new int[width * height];
        Arrays.fill(cpuMaterial, LabPbr.materialFromAuto(AutoPbr.DEFAULT, 0f));
        cpuNormal = new int[width * height];
        Arrays.fill(cpuNormal, LabPbr.FLAT_NORMAL);
        bakedAtlas = null;
        bakedW = width;
        bakedH = height;
        // fill every texel with the default material so no region is ever undefined while the bake progresses
        long bytes = (long) width * height * 4L;
        RtMemory.Buf st = memory.createBuffer("rt_material_staging", bytes * 2, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        MemoryUtil.memIntBuffer(st.mapped, width * height).put(cpuMaterial);
        MemoryUtil.memIntBuffer(st.mapped + bytes, width * height).put(cpuNormal);
        VkBarriers.fullMemoryBarrier(cmd);
        copyFull(cmd, st.handle, 0L, material.image, width, height);
        copyFull(cmd, st.handle, bytes, normal.image, width, height);
        VkBarriers.fullMemoryBarrier(cmd);
        retired.add(new Retired(st, STAGING_LIFETIME));
    }

    private void startEpoch(TextureAtlas atlas, int width, int height, ResourceProvider resources) {
        Map<Identifier, TextureAtlasSprite> sprites = ((AtlasSpritesAccess) atlas).vkb$texturesByName();
        if (sprites == null || sprites.isEmpty()) {
            LOGGER.warn("[VKB-RT] material bake skipped: atlas sprite map unavailable yet");
            return;
        }
        if (cpuMaterial == null || cpuMaterial.length != width * height) {
            cpuMaterial = new int[width * height];
            Arrays.fill(cpuMaterial, LabPbr.materialFromAuto(AutoPbr.DEFAULT, 0f));
            cpuNormal = new int[width * height];
            Arrays.fill(cpuNormal, LabPbr.FLAT_NORMAL);
        }
        Epoch e = new Epoch(atlas, width, height, resources);
        synchronized (e.queue) {
            e.queue.addAll(sprites.values());
        }
        epoch = e;
        // a live Result from the first epoch frame: the emitters read the same arrays and refresh once at the end
        last = new MaterialAtlasBaker.Result(width, height, cpuMaterial, cpuNormal, sprites.size(), 0, 0, 0, 0, 0, 0.0);
        handoff = e;
        if (micromapSink != null) {
            micromapSink.onEpochStart();
        }
        if (worker == null) {
            worker = new Thread(this::workerLoop, "VKB-RT-MaterialBake");
            worker.setDaemon(true);
            worker.start();
        }
        LockSupport.unpark(worker);
    }

    private void workerLoop() {
        while (!shutdown) {
            Epoch e = handoff;
            if (e == null || e.finished) {
                LockSupport.parkNanos(200_000);
                continue;
            }
            handoff = null;
            boolean wantAlpha = micromapSink != null;
            TextureAtlasSprite s;
            while (!e.cancelled && !shutdown && (s = e.queue.poll()) != null) {
                MaterialAtlasBaker.SpriteBake b = MaterialAtlasBaker.bakeSprite(s, e.width, e.height, e.resources, wantAlpha);
                if (b == null) {
                    e.errors++;
                    continue;
                }
                if (b.labS()) {
                    e.labS++;
                }
                if (b.labN()) {
                    e.labN++;
                }
                if (b.metal()) {
                    e.metal++;
                }
                if (b.emissive() > 0) {
                    e.emissive++;
                }
                if (b.autoNormal()) {
                    e.autoN++;
                }
                completed.add(new Completed(b, e));
            }
            e.finished = true;
        }
    }

    private boolean drainAndUpload(VkCommandBuffer cmd) {
        Epoch e = epoch;
        if (e == null) {
            return false;
        }
        ArrayList<MaterialAtlasBaker.SpriteBake> batch = new ArrayList<>();
        long texels = 0;
        Completed c;
        while ((c = completed.poll()) != null) {
            if (c.owner() != e) {
                continue; // a superseded epoch: drop its work
            }
            batch.add(c.bake());
            texels += (long) c.bake().w() * c.bake().h();
            if (texels >= UPLOAD_TEXEL_BUDGET) {
                break;
            }
        }
        if (e.finished && completed.isEmpty()) {
            finishEpoch(e);
        }
        if (batch.isEmpty()) {
            return false;
        }
        mirrorToCpu(batch, e.width, e.height);
        if (micromapSink != null) {
            for (MaterialAtlasBaker.SpriteBake b : batch) {
                micromapSink.onSpriteBaked(b.x0(), b.y0(), b.w(), b.h(), b.material(), b.alpha(), b.animated(),
                    e.width, e.height);
            }
        }
        uploadSprites(cmd, batch);
        return true;
    }

    private void finishEpoch(Epoch e) {
        double ms = (System.nanoTime() - e.startNs) / 1.0e6;
        last = new MaterialAtlasBaker.Result(e.width, e.height, cpuMaterial, cpuNormal, last != null ? last.sprites() : 0,
            e.labS, e.labN, e.emissive, e.metal, e.errors, ms);
        bakedAtlas = e.atlas;
        bakedW = e.width;
        bakedH = e.height;
        epoch = null;
        if (micromapSink != null) {
            micromapSink.onEpochFinished();
        }
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-RT] materials baked atlas=%dx%d sprites=%d labpbr_s=%d labpbr_n=%d auto_normals=%d emissive_sprites=%d metal_sprites=%d errors=%d ms=%.1f (progressive)",
            e.width, e.height, last.sprites(), e.labS, e.labN, e.autoN, e.emissive, e.metal, e.errors, ms));
    }

    /** Copies the baked sprites into the CPU material mirror that the emitter scanner reads: the live
     * {@link MaterialAtlasBaker.Result} wraps this same array, so without this the scan sees the zero-emission defaults. */
    private void mirrorToCpu(ArrayList<MaterialAtlasBaker.SpriteBake> batch, int atlasW, int atlasH) {
        if (cpuMaterial == null || cpuMaterial.length != atlasW * atlasH) {
            return;
        }
        for (MaterialAtlasBaker.SpriteBake b : batch) {
            mirrorSprite(cpuMaterial, atlasW, b);
        }
    }

    static void mirrorSprite(int[] cpuMaterial, int atlasW, MaterialAtlasBaker.SpriteBake b) {
        int[] m = b.material();
        for (int y = 0; y < b.h(); y++) {
            System.arraycopy(m, y * b.w(), cpuMaterial, (b.y0() + y) * atlasW + b.x0(), b.w());
        }
    }

    private void uploadSprites(VkCommandBuffer cmd, ArrayList<MaterialAtlasBaker.SpriteBake> batch) {
        long texels = 0;
        for (MaterialAtlasBaker.SpriteBake b : batch) {
            texels += (long) b.w() * b.h();
        }
        long bytes = texels * 4L;
        RtMemory.Buf st = memory.createBuffer("rt_material_staging", bytes * 2, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer matRegions = VkBufferImageCopy.calloc(batch.size(), stack);
            VkBufferImageCopy.Buffer norRegions = VkBufferImageCopy.calloc(batch.size(), stack);
            long off = 0;
            for (int i = 0; i < batch.size(); i++) {
                MaterialAtlasBaker.SpriteBake b = batch.get(i);
                int wh = b.w() * b.h();
                MemoryUtil.memIntBuffer(st.mapped + off, wh).put(b.material());
                fillRegion(matRegions.get(i), off, b);
                off += (long) wh * 4L;
            }
            for (int i = 0; i < batch.size(); i++) {
                MaterialAtlasBaker.SpriteBake b = batch.get(i);
                int wh = b.w() * b.h();
                MemoryUtil.memIntBuffer(st.mapped + off, wh).put(b.normal());
                fillRegion(norRegions.get(i), off, b);
                off += (long) wh * 4L;
            }
            VkBarriers.fullMemoryBarrier(cmd);
            // GENERAL is a valid layout for transfer destinations (VUID-vkCmdCopyBufferToImage-dstImageLayout-01396)
            VK10.vkCmdCopyBufferToImage(cmd, st.handle, material.image, VK10.VK_IMAGE_LAYOUT_GENERAL, matRegions);
            VK10.vkCmdCopyBufferToImage(cmd, st.handle, normal.image, VK10.VK_IMAGE_LAYOUT_GENERAL, norRegions);
            VkBarriers.fullMemoryBarrier(cmd);
        }
        retired.add(new Retired(st, STAGING_LIFETIME));
    }

    private static void fillRegion(VkBufferImageCopy r, long bufferOffset, MaterialAtlasBaker.SpriteBake b) {
        r.bufferOffset(bufferOffset).bufferRowLength(0).bufferImageHeight(0);
        r.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        r.imageOffset().x(b.x0()).y(b.y0()).z(0);
        r.imageExtent().width(b.w()).height(b.h()).depth(1);
    }

    private static void copyFull(VkCommandBuffer cmd, long buffer, long offset, long image, int w, int h) {
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
        shutdown = true;
        if (epoch != null) {
            epoch.cancelled = true;
        }
        if (worker != null) {
            LockSupport.unpark(worker);
        }
        completed.clear();
        for (Retired r : retired) {
            memory.destroy(r.buf());
        }
        retired.clear();
        if (material != null) {
            memory.destroy(material);
            memory.destroy(normal);
            material = null;
            normal = null;
        }
        cpuMaterial = null;
        cpuNormal = null;
        bakedAtlas = null;
    }
}
