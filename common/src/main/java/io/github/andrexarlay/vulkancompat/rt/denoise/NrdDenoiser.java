package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rt.world.CameraBobPose;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * NRD denoising of the RT lit view, diffuse and specular. Owns the G-buffer images that rt_rays.comp / rt_path.comp write
 * (VKB_GBUFFER), the NRD input/output images, the pack/compose pipelines and the native denoiser handle.
 *
 * <p>Per frame ({@link #record}), inside the RT command buffer after the primary-ray dispatch: pack (rt_pack_nrd.comp) ->
 * vkb_nrd_dispatch (REBLUR or RELAX diffuse+specular) -> compose (adds sun and block light, writes the RT output image).
 * Every image stays in VK_IMAGE_LAYOUT_GENERAL; passes are separated by full memory barriers.
 *
 * <p>The specular lane carries the traced reflection radiance with its own hit distance. IN_MV is the per-pixel world-space
 * motion of the primary hit from the ray shader's Vec4Buf (entities and moving blocks included, zero for the static world),
 * matching {@code FRAME_MV_WORLD_SPACE}. SIGMA shadows are not used (the sun shadow is a hard ray composed after the
 * denoiser). Level L2 until a run in game.
 */
public final class NrdDenoiser {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-NRD");

    private static final int FMT_R32F = VK10.VK_FORMAT_R32_SFLOAT;
    private static final int FMT_RGBA16F = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int FMT_RGBA8 = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    private static final int USAGE_STORAGE_SAMPLED = VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
    /** Camera moves farther than this between two frames: restart the history (respawn, teleport). */
    private static final double TELEPORT_BLOCKS = 32.0;

    private final VkDevice device;
    private final RtMemory memory;
    private final NrdNative nat;
    private final NrdNative.LibraryInfo info;
    private final DenoiserMode mode;
    private final NrdReblurParams reblur = NrdReblurParams.defaults();
    private final NrdPackPipeline packPipeline;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment resources = arena.allocate((long) NrdLayouts.RES_COUNT * NrdLayouts.RESOURCE_SIZE, 8);
    private final MemorySegment frameSettingsSeg = arena.allocate(NrdLayouts.FRAME_SETTINGS_SIZE, 8);
    private final MemorySegment reblurSeg = arena.allocate(NrdLayouts.REBLUR_SETTINGS_SIZE, 8);
    private final NrdFrameSettings frame = new NrdFrameSettings();

    private long handle;
    private int width;
    private int height;
    private RtMemory.Img gbViewZ;
    private RtMemory.Img gbNormalRough;
    private RtMemory.Img gbDiffuse;
    private RtMemory.Img gbAlbedo;
    private RtMemory.Img gbDirect;
    private RtMemory.Img gbSpecular;
    private RtMemory.Img gbSpecAlbedo;
    private RtMemory.Img inNormalRough;
    private RtMemory.Img inDiffuse;
    private RtMemory.Img inSpecular;
    private RtMemory.Img inMotion;
    private RtMemory.Img outDiffuse;
    private RtMemory.Img outSpecular;
    private long boundOutputView;
    /** Per-pixel entity motion Vec4Buf of the ray shader (device address, 0 = none, then IN_MV is static-world only). */
    public long entityMotionAddress;
    private boolean imagesReady;
    private boolean restartNext;
    private boolean havePrev;
    private int frameCounter;
    private final Matrix4f prevProjection = new Matrix4f();
    private final Matrix4f prevRotation = new Matrix4f();
    private double prevX;
    private double prevY;
    private double prevZ;
    private final Matrix4f nrdProjection = new Matrix4f();
    private final Matrix4f nrdProjectionPrev = new Matrix4f();
    private final Matrix4f effectiveRotation = new Matrix4f();

    private long logWindowStartNs;
    private int logFrames;
    private NrdNative.Stats lastStats;

    /**
     * Initialises vkb_nrd (once per process), creates the denoiser and all images.
     *
     * @throws NrdException when NRD is not available or a step fails (the message says why; the caller logs
     *                      {@code [VKB-NRD] status=disabled reason=...} and continues without a denoiser)
     */
    public NrdDenoiser(VkDevice device, RtMemory memory, DenoiserMode mode, NrdAvailability availability, int width, int height,
                       long outputView) {
        if (!mode.isNrd()) {
            throw new IllegalArgumentException("mode must be an NRD mode");
        }
        this.device = device;
        this.memory = memory;
        this.mode = mode;
        NrdRuntime.ensureInitialised(device, availability);
        this.nat = NrdRuntime.nativeLib();
        this.info = NrdRuntime.info();
        if (!isSupportedNrdVersion(info.major(), info.minor())) {
            throw new NrdException("version", "NRD.dll is " + info + ", supported versions are 4.17.x and 4.18.x", null);
        }
        if (info.normalEncoding() != NrdPackMath.NORMAL_R10G10B10A2_UNORM && info.normalEncoding() != NrdPackMath.NORMAL_RGBA16_SNORM) {
            throw new NrdException("normal_encoding", "NRD.dll normal encoding " + info.normalEncoding()
                + " is not supported by the pack shader path (supported: 2 R10G10B10A2, 4 RGBA16_SNORM)", null);
        }
        requireStorageFormat(info.normalRoughnessVkFormat(), "IN_NORMAL_ROUGHNESS");
        requireStorageFormat(FMT_RGBA16F, "RGBA16F");
        requireStorageFormat(FMT_R32F, "R32F");
        this.packPipeline = new NrdPackPipeline(device);
        try {
            createAll(width, height, outputView);
        } catch (RuntimeException e) {
            destroyAll();
            packPipeline.destroy();
            throw e;
        }
        LOGGER.info("[VKB-NRD] denoiser_ready mode={} size={}x{} nrd={} view={}", mode.logName(), width, height, info,
            DenoiserConfig.VIEW.name().toLowerCase(Locale.ROOT));
    }

    static boolean isSupportedNrdVersion(int major, int minor) {
        return major == 4 && (minor == 17 || minor == 18);
    }

    private void requireStorageFormat(int format, String what) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties props = VkFormatProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), format, props);
            int need = VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if ((props.optimalTilingFeatures() & need) != need) {
                throw new NrdException("format", what + " VkFormat " + format + " has no STORAGE+SAMPLED support on this GPU", null);
            }
        }
    }

    /** Views of the seven G-buffer images rt_rays.comp / rt_path.comp write (bindings 4..8 and 13..14 of the gbuffer RtPipeline). */
    public long[] gbufferViews() {
        return new long[] {gbViewZ.view, gbNormalRough.view, gbDiffuse.view, gbAlbedo.view, gbDirect.view,
            gbSpecular.view, gbSpecAlbedo.view};
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public DenoiserMode mode() {
        return mode;
    }

    private void createAll(int w, int h, long outputView) {
        this.width = w;
        this.height = h;
        gbViewZ = memory.createImage("nrd_gb_viewz", w, h, FMT_R32F, USAGE_STORAGE_SAMPLED);
        gbNormalRough = memory.createImage("nrd_gb_normal", w, h, FMT_RGBA16F, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        gbDiffuse = memory.createImage("nrd_gb_diffuse", w, h, FMT_RGBA16F, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        gbAlbedo = memory.createImage("nrd_gb_albedo", w, h, FMT_RGBA8, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        gbDirect = memory.createImage("nrd_gb_direct", w, h, FMT_RGBA16F, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        gbSpecular = memory.createImage("nrd_gb_spec", w, h, FMT_RGBA16F, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        gbSpecAlbedo = memory.createImage("nrd_gb_spec_albedo", w, h, FMT_RGBA8, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        inNormalRough = memory.createImage("nrd_in_normal", w, h, info.normalRoughnessVkFormat(), USAGE_STORAGE_SAMPLED);
        inDiffuse = memory.createImage("nrd_in_diffuse", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        inSpecular = memory.createImage("nrd_in_spec", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        inMotion = memory.createImage("nrd_in_mv", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        outDiffuse = memory.createImage("nrd_out_diffuse", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        outSpecular = memory.createImage("nrd_out_spec", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        handle = nat.createDenoiser(w, h, mode.nativeModeMask(), 3);
        reblur.write(reblurSeg);
        if (mode == DenoiserMode.NRD_REBLUR) {
            nat.setReblurSettings(handle, reblurSeg);
        }
        fillResources();
        writeSet(outputView);
        imagesReady = false;
        restartNext = true;
        havePrev = false;
    }

    /** Clear native temporal history on the next recorded frame after a transport/settings change. */
    public void resetHistory() {
        restartNext = true;
    }

    private void fillResources() {
        resources.fill((byte) 0);
        setRes(NrdLayouts.RES_IN_MV, inMotion, FMT_RGBA16F);
        setRes(NrdLayouts.RES_IN_NORMAL_ROUGHNESS, inNormalRough, info.normalRoughnessVkFormat());
        setRes(NrdLayouts.RES_IN_VIEWZ, gbViewZ, FMT_R32F);
        setRes(NrdLayouts.RES_IN_DIFF_RADIANCE_HITDIST, inDiffuse, FMT_RGBA16F);
        setRes(NrdLayouts.RES_IN_SPEC_RADIANCE_HITDIST, inSpecular, FMT_RGBA16F);
        setRes(NrdLayouts.RES_OUT_DIFF_RADIANCE_HITDIST, outDiffuse, FMT_RGBA16F);
        setRes(NrdLayouts.RES_OUT_SPEC_RADIANCE_HITDIST, outSpecular, FMT_RGBA16F);
    }

    private void setRes(int slot, RtMemory.Img img, int format) {
        long base = (long) slot * NrdLayouts.RESOURCE_SIZE;
        resources.set(JAVA_LONG, base + NrdLayouts.RES_IMAGE, img.image);
        resources.set(JAVA_LONG, base + NrdLayouts.RES_VIEW, img.view);
        resources.set(JAVA_INT, base + NrdLayouts.RES_FORMAT, format);
    }

    private void writeSet(long outputView) {
        packPipeline.writeViews(new long[] {gbViewZ.view, gbNormalRough.view, gbDiffuse.view, gbAlbedo.view, gbDirect.view,
            inNormalRough.view, inDiffuse.view, outDiffuse.view, outputView,
            gbSpecular.view, inSpecular.view, outSpecular.view, inMotion.view, gbSpecAlbedo.view});
        boundOutputView = outputView;
    }

    /**
     * Follows a resize of the RT output. Waits for the device to go idle, so call it only when the size or the output view changed.
     * A size change recreates all images and the NRD pools and restarts the history.
     */
    public void ensure(int w, int h, long outputView) {
        if (w == width && h == height) {
            if (outputView != boundOutputView) {
                VK10.vkDeviceWaitIdle(device);
                writeSet(outputView);
            }
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        nat.destroyDenoiser(handle);
        handle = 0L;
        destroyImages();
        createAll(w, h, outputView);
        LOGGER.info("[VKB-NRD] resized size={}x{} (history restarted)", w, h);
    }

    /**
     * Records pack, NRD and compose into {@code cmd}. Must follow the rt_rays.comp dispatch and a full memory barrier.
     * {@code projection} is the unbobbed base projection and {@code fullProjection} is the bobbed projection used by RT rays.
     * NRD receives the base projection separately and the equivalent bobbed world-to-view transform, matching RT ray placement
     * and the bob-aware IN_VIEWZ guide. Jitter is passed in pixel units, matching the ray shader's {@code cameraJitter}.
     */
    public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f viewRotation, double camX, double camY, double camZ,
                       boolean tonemap) {
        record(cmd, projection, viewRotation, camX, camY, camZ, tonemap, 1.1f);
    }

    /** As above with the tone mapping exposure of the compose pass (1.1 = RtLighting.EXPOSURE of the ray mode). */
    public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f viewRotation, double camX, double camY, double camZ,
                       boolean tonemap, float exposure) {
        record(cmd, projection, projection, viewRotation, camX, camY, camZ, tonemap, exposure, 0f, 0f, 0f, 0f);
    }

    public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f fullProjection, Matrix4f viewRotation,
                       double camX, double camY, double camZ, boolean tonemap, float exposure,
                       float jitterX, float jitterY, float jitterPrevX, float jitterPrevY) {
        if (!imagesReady) {
            initImages(cmd);
            imagesReady = true;
        }
        buildFrameSettings(projection, fullProjection, viewRotation, camX, camY, camZ,
            jitterX, jitterY, jitterPrevX, jitterPrevY);
        frame.write(frameSettingsSeg);
        nat.setFrameSettings(handle, frameSettingsSeg);

        int flags = (tonemap ? 1 : 0) | (DenoiserConfig.VIEW == DenoiserConfig.View.SPLIT ? 2 : 0);
        ByteBuffer pc = org.lwjgl.system.MemoryUtil.memAlloc(NrdPackPipeline.PUSH_BYTES);
        try {
            NrdPackPipeline.writePush(pc, info.normalEncoding(), info.roughnessEncoding(), mode.packShaderCode(), flags, reblur,
                DenoiserConfig.DENOISING_RANGE, width, height, exposure, entityMotionAddress);
            packPipeline.recordPack(cmd, pc, width, height);
            VkBarriers.fullMemoryBarrier(cmd);
            lastStats = nat.dispatch(handle, cmd.address(), mode.nativeModeMask(), resources);
            VkBarriers.fullMemoryBarrier(cmd);
            if (DenoiserConfig.VIEW != DenoiserConfig.View.NOISY) {
                packPipeline.recordCompose(cmd, pc, width, height);
            }
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pc);
        }
        frameCounter++;
        havePrev = true;
        prevProjection.set(projection);
        prevRotation.set(effectiveRotation);
        prevX = camX;
        prevY = camY;
        prevZ = camZ;
        logWindow();
    }

    private long lastFrameNs;

    private void buildFrameSettings(Matrix4f projection, Matrix4f fullProjection, Matrix4f viewRotation,
                                    double camX, double camY, double camZ,
                                    float jitterX, float jitterY, float jitterPrevX, float jitterPrevY) {
        CameraBobPose.effectiveWorldToView(fullProjection, projection, viewRotation, effectiveRotation);
        boolean flip = DenoiserConfig.PROJ_FLIP_Y;
        if (flip) {
            NrdCameraMath.negateClipY(projection, nrdProjection);
        } else {
            nrdProjection.set(projection);
        }
        if (havePrev) {
            if (flip) {
                NrdCameraMath.negateClipY(prevProjection, nrdProjectionPrev);
            } else {
                nrdProjectionPrev.set(prevProjection);
            }
            NrdCameraMath.previousWorldToView(prevRotation, prevX, prevY, prevZ, camX, camY, camZ, frame.worldToViewPrev);
        } else {
            nrdProjectionPrev.set(nrdProjection);
            frame.worldToViewPrev.set(effectiveRotation);
        }
        frame.viewToClip.set(nrdProjection);
        frame.viewToClipPrev.set(nrdProjectionPrev);
        frame.worldToView.set(effectiveRotation);
        frame.jitter[0] = jitterX;
        frame.jitter[1] = jitterY;
        frame.jitterPrev[0] = havePrev ? jitterPrevX : jitterX;
        frame.jitterPrev[1] = havePrev ? jitterPrevY : jitterY;
        frame.frameIndex = frameCounter;
        long nowNs = System.nanoTime();
        frame.timeDeltaMs = lastFrameNs == 0L ? 0.0f : Math.min(250.0f, (nowNs - lastFrameNs) / 1.0e6f);
        lastFrameNs = nowNs;
        boolean teleport = havePrev && NrdCameraMath.isTeleport(prevX, prevY, prevZ, camX, camY, camZ, TELEPORT_BLOCKS);
        if (restartNext) {
            frame.accumulationMode = NrdLayouts.ACCUM_CLEAR_AND_RESTART;
            restartNext = false;
        } else if (teleport) {
            frame.accumulationMode = NrdLayouts.ACCUM_RESTART;
        } else {
            frame.accumulationMode = NrdLayouts.ACCUM_CONTINUE;
        }
        frame.flags = NrdLayouts.FRAME_MV_WORLD_SPACE;
        frame.rectWidth = width;
        frame.rectHeight = height;
        frame.rectWidthPrev = width;
        frame.rectHeightPrev = height;
        frame.denoisingRange = DenoiserConfig.DENOISING_RANGE;
    }

    /** First frame: UNDEFINED -> GENERAL for every image. */
    private void initImages(VkCommandBuffer cmd) {
        RtMemory.Img[] all = {gbViewZ, gbNormalRough, gbDiffuse, gbAlbedo, gbDirect, gbSpecular, gbSpecAlbedo, inNormalRough,
            inDiffuse, inSpecular, inMotion, outDiffuse, outSpecular};
        for (RtMemory.Img i : all) {
            VkBarriers.transition(cmd, i.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        }
        VkBarriers.fullMemoryBarrier(cmd);
    }

    private void logWindow() {
        long now = System.nanoTime();
        if (logWindowStartNs == 0L) {
            logWindowStartNs = now;
        }
        logFrames++;
        if (now - logWindowStartNs < 1_000_000_000L) {
            return;
        }
        NrdNative.Stats s = lastStats;
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-NRD] denoise mode=%s frames=%d dispatches=%d clears=%d sets=%d const_bytes=%d cpu_ms=%.3f view=%s size=%dx%d",
            mode.logName(), logFrames, s.dispatches(), s.clears(), s.descriptorSets(), s.constantBytes(), s.cpuMs(),
            DenoiserConfig.VIEW.name().toLowerCase(Locale.ROOT), width, height));
        logFrames = 0;
        logWindowStartNs = now;
    }

    private void destroyImages() {
        RtMemory.Img[] all = {gbViewZ, gbNormalRough, gbDiffuse, gbAlbedo, gbDirect, gbSpecular, gbSpecAlbedo, inNormalRough,
            inDiffuse, inSpecular, inMotion, outDiffuse, outSpecular};
        for (RtMemory.Img i : all) {
            if (i != null) {
                memory.destroy(i);
            }
        }
        gbViewZ = gbNormalRough = gbDiffuse = gbAlbedo = gbDirect = gbSpecular = gbSpecAlbedo = inNormalRough = inDiffuse =
            inSpecular = inMotion = outDiffuse = outSpecular = null;
    }

    private void destroyAll() {
        if (handle != 0L) {
            nat.destroyDenoiser(handle);
            handle = 0L;
        }
        destroyImages();
    }

    /** Waits for the device and releases everything this instance owns (vkb_nrd stays loaded for the process). */
    public void destroy() {
        VK10.vkDeviceWaitIdle(device);
        destroyAll();
        packPipeline.destroy();
        arena.close();
        LOGGER.info("[VKB-NRD] denoiser_destroyed");
    }
}
