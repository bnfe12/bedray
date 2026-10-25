package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr.DlssRayReconstruction;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr.DlssRrParams;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * [DLSS-RR] NVIDIA DLSS Ray Reconstruction denoiser (DLSS 3.5 / DLSS-D).
 * Replaces conventional denoisers (like NRD) with the unified AI model
 * (NVSDK_NGX_DLSS_Denoise_Mode_DLUnified).
 *
 * <p>Owns the G-buffer images that {@code rt_rays.comp} / {@code rt_path.comp} write
 * ({@code VKB_GBUFFER}), the pack targets (combined raw radiance plus the specular-albedo guide), the auxiliary motion
 * texture and the native DLSS-RR feature handle.
 *
 * <p>During {@link #record}, the pack shader (rt_pack_nrd.comp, DLSS-RR mode) folds the raw diffuse and specular G-buffer
 * channels into one noisy colour and writes the F0 guide; the G-buffer textures are then transitioned from
 * {@code VK_IMAGE_LAYOUT_GENERAL} to {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}, camera matrices and eval parameters
 * are populated, {@link DlssRayReconstruction#evaluate} is executed, and images are transitioned back to {@code GENERAL}.
 * Screen-space motion (camera + entities) comes from the velocity pass. Level L2 until a run in game.
 */
public final class DlssRrDenoiser {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssRrDenoiser");

    private static final int FMT_R32F = VK10.VK_FORMAT_R32_SFLOAT;
    private static final int FMT_RG16F = VK10.VK_FORMAT_R16G16_SFLOAT;
    private static final int FMT_RGBA16F = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int FMT_RGBA8 = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    private static final int USAGE_STORAGE_SAMPLED = VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
    private static final double TELEPORT_BLOCKS = 32.0;
    /**
     * NGX sharpness of the RR output (0 = off, the default). Sharpening is left to the upscale pass, which runs at display
     * resolution and would otherwise compound with this one. {@code -Dvkrtx.dlssrr.sharpness} enables it for A/B runs.
     */
    private static final float SHARPNESS = sharpnessSetting();
    private long lastRecordNanos;

    private final VkDevice device;
    private final RtMemory memory;
    private final DlssRayReconstruction rr;
    private final boolean ownsRr;
    private VelocityPipeline velocityPipeline;
    private final boolean ownsVelocityPipeline;

    private long feature;
    private int width;
    private int height;
    private long boundOutputImage;
    private long boundOutputView;

    private RtMemory.Img gbViewZ;
    private RtMemory.Img gbNormalRough;
    private RtMemory.Img gbDiffuse;
    private RtMemory.Img gbAlbedo;
    private RtMemory.Img gbDirect;
    private RtMemory.Img gbSpecular;
    private RtMemory.Img gbSpecAlbedo;
    private RtMemory.Img inNormalRough;
    private RtMemory.Img inDiffuse;
    private RtMemory.Img inMotion;
    private RtMemory.Img inSpecular;
    private RtMemory.Img outDiffuse;
    /** Disocclusion mask (R32F, 1 = no valid history), NGX pInDisocclusionMask (nvsdk_ngx_helpers_dlssd_vk.h:125). */
    private RtMemory.Img inDisocc;
    private RtMemory.Buf disoccBuf;
    /** Linear view Z of the previous frame (copied from gbViewZ after the velocity pass) for the depth disocclusion test. */
    private RtMemory.Buf prevViewZBuf;
    private boolean prevViewZValid;
    static final boolean DEPTH_OCC_OFF = Boolean.getBoolean("vkrtx.dlssrr.depthocc.off");
    static final boolean DISOCC_OFF = Boolean.getBoolean("vkrtx.dlssrr.disocc.off");
    private NrdPackPipeline packPipeline;

    private boolean imagesReady;
    private boolean restartNext = true;
    private boolean havePrev;
    private double prevX;
    private double prevY;
    private double prevZ;
    private final Matrix4f prevProjection = new Matrix4f();
    private final Matrix4f prevViewRotation = new Matrix4f();
    private final Matrix4f invViewProj = new Matrix4f();
    private final Matrix4f prevViewProjMat = new Matrix4f();

    private final float[] worldToViewMatrix = new float[16];
    private final float[] viewToClipMatrix = new float[16];

    /**
     * Creates the DLSS-RR denoiser, locating {@code vkb_dlss.dll} via Bedray Core or system property.
     */
    public DlssRrDenoiser(VkDevice device, RtMemory memory, int width, int height, RtMemory.Img output) {
        this(device, memory, width, height, output != null ? output.image : 0L, output != null ? output.view : 0L);
    }

    public DlssRrDenoiser(VkDevice device, RtMemory memory, int width, int height, long outputImage, long outputView) {
        this.device = device;
        this.memory = memory;
        Path wrapperDll = resolveWrapperDll();
        if (wrapperDll == null || !Files.isRegularFile(wrapperDll)) {
            throw new IllegalStateException("[DLSS-RR] vkb_dlss.dll not available for Ray Reconstruction");
        }
        this.rr = DlssRayReconstruction.load(wrapperDll);
        this.ownsRr = true;
        this.packPipeline = device != null ? new NrdPackPipeline(device) : null;
        VelocityPipeline vp = null;
        if (device != null) {
            try {
                vp = new VelocityPipeline(device);
            } catch (Throwable t) {
                LOGGER.warn("[DLSS-RR] Failed to initialize VelocityPipeline: {}", t.getMessage());
            }
        }
        this.velocityPipeline = vp;
        this.ownsVelocityPipeline = true;
        try {
            createAll(width, height, outputImage, outputView);
        } catch (RuntimeException e) {
            destroyImages();
            if (packPipeline != null) {
                packPipeline.destroy();
                packPipeline = null;
            }
            if (this.velocityPipeline != null) {
                this.velocityPipeline.cleanup();
                this.velocityPipeline = null;
            }
            throw e;
        }
        LOGGER.info("[DLSS-RR] denoiser_ready size={}x{} wrapper={}", width, height, io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(wrapperDll));
    }

    /**
     * Injected constructor for tests and custom wrappers.
     */
    public DlssRrDenoiser(VkDevice device, RtMemory memory, DlssRayReconstruction rr, int width, int height, RtMemory.Img output) {
        this(device, memory, rr, width, height, output != null ? output.image : 0L, output != null ? output.view : 0L);
    }

    public DlssRrDenoiser(VkDevice device, RtMemory memory, DlssRayReconstruction rr, int width, int height, long outputImage, long outputView) {
        this(device, memory, rr, null, width, height, outputImage, outputView);
    }

    public DlssRrDenoiser(VkDevice device, RtMemory memory, DlssRayReconstruction rr, VelocityPipeline velocityPipeline, int width, int height, long outputImage, long outputView) {
        this.device = device;
        this.memory = memory;
        this.rr = Objects.requireNonNull(rr, "rr");
        this.ownsRr = false;
        this.packPipeline = device != null ? new NrdPackPipeline(device) : null;
        VelocityPipeline vp = velocityPipeline;
        if (vp == null && device != null) {
            try {
                vp = new VelocityPipeline(device);
            } catch (Throwable t) {
                LOGGER.warn("[DLSS-RR] Failed to initialize VelocityPipeline: {}", t.getMessage());
            }
        }
        this.velocityPipeline = vp;
        this.ownsVelocityPipeline = velocityPipeline == null;
        try {
            createAll(width, height, outputImage, outputView);
        } catch (RuntimeException e) {
            destroyImages();
            if (packPipeline != null) {
                packPipeline.destroy();
                packPipeline = null;
            }
            if (ownsVelocityPipeline && this.velocityPipeline != null) {
                this.velocityPipeline.cleanup();
                this.velocityPipeline = null;
            }
            throw e;
        }
        LOGGER.info("[DLSS-RR] denoiser_ready (injected rr) size={}x{}", width, height);
    }

    public VelocityPipeline velocityPipeline() {
        return velocityPipeline;
    }

    private static float sharpnessSetting() {
        String v = System.getProperty("vkrtx.dlssrr.sharpness");
        if (v != null && !v.isBlank()) {
            try {
                return Math.max(0.0f, Math.min(1.0f, Float.parseFloat(v.trim())));
            } catch (NumberFormatException ignored) {
                LOGGER.warn("[DLSS-RR] -Dvkrtx.dlssrr.sharpness='{}' is not a number, using 0", v);
            }
        }
        return 0.0f;
    }

    /** Wall-clock delta of two RR evaluations: NGX uses it to scale its history and anti-ghosting response. */
    private float frameDeltaMs() {
        long now = System.nanoTime();
        float ms = lastRecordNanos == 0L ? 16.6f : (now - lastRecordNanos) / 1.0e6f;
        lastRecordNanos = now;
        return Math.max(1.0f, Math.min(100.0f, ms));
    }

    private static Path resolveWrapperDll() {
        try {
            DlssAvailability.Library lib = DlssAvailability.library();
            if (lib != null && lib.usable() && lib.wrapperDll() != null) {
                return lib.wrapperDll();
            }
        } catch (Throwable ignored) {
        }
        String testProp = System.getProperty("vkbridge.test.dlssWrapper");
        if (testProp != null && !testProp.isBlank()) {
            return Path.of(testProp);
        }
        return null;
    }

    private void createAll(int w, int h, long outputImage, long outputView) {
        this.width = w;
        this.height = h;
        this.boundOutputImage = outputImage;
        this.boundOutputView = outputView;

        gbViewZ = memory.createImage("dlss_rr_gb_viewz", w, h, FMT_R32F,
            USAGE_STORAGE_SAMPLED | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        gbNormalRough = memory.createImage("dlss_rr_gb_normal", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        gbDiffuse = memory.createImage("dlss_rr_gb_diffuse", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        gbAlbedo = memory.createImage("dlss_rr_gb_albedo", w, h, FMT_RGBA8, USAGE_STORAGE_SAMPLED);
        gbDirect = memory.createImage("dlss_rr_gb_direct", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        gbSpecular = memory.createImage("dlss_rr_gb_spec", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        gbSpecAlbedo = memory.createImage("dlss_rr_gb_spec_albedo", w, h, FMT_RGBA8, USAGE_STORAGE_SAMPLED);
        inNormalRough = memory.createImage("dlss_rr_in_normal", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        inDiffuse = memory.createImage("dlss_rr_in_color", w, h, FMT_RGBA16F,
            USAGE_STORAGE_SAMPLED | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        inMotion = memory.createImage("dlss_rr_in_mv", w, h, FMT_RG16F,
            USAGE_STORAGE_SAMPLED | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        inSpecular = memory.createImage("dlss_rr_in_spec_albedo", w, h, FMT_RGBA16F,
            USAGE_STORAGE_SAMPLED | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        outDiffuse = memory.createImage("dlss_rr_out_diffuse", w, h, FMT_RGBA16F, USAGE_STORAGE_SAMPLED);
        inDisocc = memory.createImage("dlss_rr_in_disocc", w, h, FMT_R32F,
            USAGE_STORAGE_SAMPLED | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        prevViewZBuf = memory.createBuffer("dlss_rr_prev_viewz", (long) w * h * 4L,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
        prevViewZValid = false;
        disoccBuf = memory.createBuffer("dlss_rr_disocc_px", (long) w * h * 4L,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);

        if (boundOutputView != 0L) {
            writeSet(boundOutputView);
        }

        imagesReady = false;
        restartNext = true;
        havePrev = false;
    }

    void writeSet(long outputView) {
        this.boundOutputView = outputView;
        if (packPipeline != null && boundOutputView != 0L) {
            // RR never reads bindings 11/12 (compose reads the RR result from 7, motion comes from the velocity pass).
            packPipeline.writeViews(new long[] {
                gbViewZ.view, gbNormalRough.view, gbDiffuse.view, gbAlbedo.view, gbDirect.view,
                inNormalRough.view, inDiffuse.view, outDiffuse.view, boundOutputView,
                gbSpecular.view, inSpecular.view, outDiffuse.view, inMotion.view, gbSpecAlbedo.view
            });
        }
    }

    private void initImages(VkCommandBuffer cmd) {
        RtMemory.Img[] all = {gbViewZ, gbNormalRough, gbDiffuse, gbAlbedo, gbDirect, gbSpecular, gbSpecAlbedo, inNormalRough,
            inDiffuse, inMotion, inSpecular, outDiffuse, inDisocc};
        for (RtMemory.Img i : all) {
            VkBarriers.transition(cmd, i.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue zero = VkClearColorValue.calloc(stack);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, inMotion.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
            VK10.vkCmdClearColorImage(cmd, outDiffuse.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
        }
        VkBarriers.fullMemoryBarrier(cmd);
        imagesReady = true;
    }

    private void ensureNgx() {
        if (rr == null || device == null) {
            return;
        }
        try {
            long vkDev = device.address();
            long vkPd = device.getPhysicalDevice().address();
            long vkInst = device.getPhysicalDevice().getInstance().address();
            org.lwjgl.system.FunctionProvider fp = org.lwjgl.vulkan.VK.getFunctionProvider();
            long gipa = fp != null ? fp.getFunctionAddress("vkGetInstanceProcAddr") : 0L;
            long gdpa = device.getCapabilities().vkGetDeviceProcAddr;
            if (gdpa == 0L && fp != null) {
                gdpa = fp.getFunctionAddress("vkGetDeviceProcAddr");
            }
            Path featureDir = null;
            try {
                DlssAvailability.Library lib = DlssAvailability.library();
                if (lib != null && lib.featureDir() != null) {
                    featureDir = lib.featureDir();
                }
            } catch (Throwable ignored) {
            }
            if (featureDir == null) {
                Path wrapper = resolveWrapperDll();
                featureDir = wrapper != null && wrapper.getParent() != null ? wrapper.getParent() : Path.of(".");
            }
            Path logDir = featureDir;
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.gameDirectory != null) {
                    logDir = mc.gameDirectory.toPath().resolve("logs");
                }
            } catch (Throwable ignored) {
            }
            DlssNative.Caps[] caps = new DlssNative.Caps[1];
            DlssNative.Status st = rr.init(vkInst, vkPd, vkDev, gipa, gdpa, featureDir, logDir, null, null, caps);
            if (!st.ok()) {
                // The native wrapper returns OK for a repeated init on the same VkDevice.
                // ERR_ALREADY_INIT means another device owns its context: creating a feature
                // with this command buffer would cross device lifetimes.
                throw new IllegalStateException("[DLSS-RR] NGX init rejected device 0x"
                    + Long.toHexString(vkDev) + ": code=" + st.code() + " " + st.error());
            }
            LOGGER.info("[DLSS-RR] NGX initialized for device 0x{}", Long.toHexString(vkDev));
        } catch (Throwable t) {
            // ensureFeature must stop here; a failed init must never fall through to native create.
            throw new IllegalStateException("[DLSS-RR] NGX initialization failed: " + t.getMessage(), t);
        }
    }

    private void ensureFeature(VkCommandBuffer cmd) {
        if (feature != 0L) {
            return;
        }
        ensureNgx();
        DlssQuality quality = DlssQuality.DLAA;
        int flags = DlssRrParams.FLAG_IS_HDR | DlssRrParams.FLAG_AUTO_EXPOSURE;
        DlssRrParams.CreateInfo ci = new DlssRrParams.CreateInfo(
            width, height, width, height, quality,
            flags,
            false,
            DlssRrParams.ROUGHNESS_PACKED,
            DlssRrParams.DEPTH_LINEAR
        );
        long[] outFeat = new long[1];
        DlssNative.Status st = rr.createFeature(cmd.address(), ci, outFeat);
        if (!st.ok()) {
            ci = new DlssRrParams.CreateInfo(
                width, height, width, height, DlssQuality.QUALITY,
                flags | DlssRrParams.FLAG_MV_LOW_RES,
                false,
                DlssRrParams.ROUGHNESS_PACKED,
                DlssRrParams.DEPTH_LINEAR
            );
            st = rr.createFeature(cmd.address(), ci, outFeat);
        }
        if (!st.ok()) {
            throw new IllegalStateException("[DLSS-RR] Failed to create Ray Reconstruction feature: " + st);
        }
        feature = outFeat[0];
        LOGGER.info("[DLSS-RR] Created feature handle=0x{} for {}x{}", Long.toHexString(feature), width, height);
    }

    /**
     * Views of the seven G-buffer images rt_rays.comp / rt_path.comp write (bindings 4..8 and 13..14 of the gbuffer
     * RtPipeline). The count must match {@link io.github.andrexarlay.vulkancompat.rt.world.RtPipeline#GBUFFER_VIEWS}:
     * a short array fails the bind of the gbuffer variant on the first frame and disables RT for the session.
     */
    public long[] gbufferViews() {
        return new long[] {gbViewZ.view, gbNormalRough.view, gbDiffuse.view, gbAlbedo.view, gbDirect.view,
            gbSpecular.view, gbSpecAlbedo.view};
    }

    public RtMemory.Img gbViewZ() { return gbViewZ; }
    public RtMemory.Img gbNormalRough() { return gbNormalRough; }
    public RtMemory.Img gbDiffuse() { return gbDiffuse; }
    public RtMemory.Img gbAlbedo() { return gbAlbedo; }
    public RtMemory.Img gbDirect() { return gbDirect; }
    public RtMemory.Img inMotion() { return inMotion; }
    public RtMemory.Img inSpecular() { return inSpecular; }
    public RtMemory.Img outDiffuse() { return outDiffuse; }

    public int width() { return width; }
    public int height() { return height; }
    public long feature() { return feature; }
    public boolean isImagesReady() { return imagesReady; }

    /**
     * Follows a resize of the RT output. Recreates images and releases feature if size changed.
     */
    public void ensure(int w, int h, RtMemory.Img output) {
        ensure(w, h, output != null ? output.image : 0L, output != null ? output.view : 0L);
    }

    public void ensure(VkCommandBuffer cmd, int w, int h, RtMemory.Img output) {
        ensure(w, h, output);
        if (cmd != null && !imagesReady) {
            initImages(cmd);
        }
    }

    public void ensure(int w, int h, long outputImage, long outputView) {
        if (w == width && h == height) {
            this.boundOutputImage = outputImage;
            if (this.boundOutputView != outputView) {
                if (device != null) {
                    VK10.vkDeviceWaitIdle(device);
                }
                writeSet(outputView);
            }
            return;
        }
        if (device != null) {
            VK10.vkDeviceWaitIdle(device);
        }
        if (feature != 0L) {
            rr.releaseFeature(feature);
            feature = 0L;
        }
        destroyImages();
        createAll(w, h, outputImage, outputView);
        LOGGER.info("[DLSS-RR] resized size={}x{} (history restarted)", w, h);
    }

    public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f viewRotation, double camX, double camY, double camZ,
                       boolean tonemap) {
        record(cmd, projection, viewRotation, camX, camY, camZ, tonemap, 1.1f, 0L);
    }

    public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f viewRotation, double camX, double camY, double camZ,
                       boolean tonemap, float exposure) {
        record(cmd, projection, viewRotation, camX, camY, camZ, tonemap, exposure, 0L);
    }

    /** Per-pixel entity motion buffer of rt_path (device address, 0 = none); set by RtFrameRenderer before {@link #record}. */
    public long entityMotionAddress;

    public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f viewRotation, double camX, double camY, double camZ,
                       boolean tonemap, float exposure, long depthView) {
        record(cmd, projection, viewRotation, camX, camY, camZ, tonemap, exposure, depthView, 0.0f, 0.0f);
    }

    /**
     * Records DLSS-RR with the sub-pixel offset that was applied to this frame's primary rays.
     * NGX jitter uses the same sign convention as the selected DLSS upscaler mode.
     */
    public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f viewRotation, double camX, double camY, double camZ,
                       boolean tonemap, float exposure, long depthView, float jitterPixelsX, float jitterPixelsY) {
        if (!imagesReady) {
            initImages(cmd);
        }
        ensureFeature(cmd);
        MotionProbe.tick();
        float frameDeltaMs = frameDeltaMs();

        boolean teleport = havePrev && Math.hypot(camX - prevX, Math.hypot(camY - prevY, camZ - prevZ)) > TELEPORT_BLOCKS;
        boolean reset = restartNext || teleport;
        restartNext = false;

        Matrix4f currViewProj = new Matrix4f(projection != null ? projection : new Matrix4f())
            .mul(viewRotation != null ? viewRotation : new Matrix4f());
        currViewProj.invert(invViewProj);

        if (havePrev && !reset) {
            prevProjection.mul(prevViewRotation, prevViewProjMat);
        } else {
            prevViewProjMat.set(currViewProj);
        }
        Vec3 camDelta = (havePrev && !reset)
            ? new Vec3(camX - prevX, camY - prevY, camZ - prevZ)
            : Vec3.ZERO;

        if (projection != null) {
            prevProjection.set(projection);
        }
        if (viewRotation != null) {
            prevViewRotation.set(viewRotation);
        }
        prevX = camX;
        prevY = camY;
        prevZ = camZ;
        havePrev = true;

        if (viewRotation != null) {
            viewRotation.get(worldToViewMatrix);
        }
        if (projection != null) {
            if (DenoiserConfig.PROJ_FLIP_Y) {
                NrdCameraMath.negateClipY(projection, new Matrix4f()).get(viewToClipMatrix);
            } else {
                projection.get(viewToClipMatrix);
            }
        }

        if (depthView == 0L) {
            if (WorldTargetManager.getWorldTarget() != null && WorldTargetManager.getWorldTarget().getDepthTextureView() != null) {
                depthView = WorldTargetManager.extractVkImageView(WorldTargetManager.getWorldTarget().getDepthTextureView());
            } else if (WorldTargetManager.getSavedMainRenderTarget() != null && WorldTargetManager.getSavedMainRenderTarget().getDepthTextureView() != null) {
                depthView = WorldTargetManager.extractVkImageView(WorldTargetManager.getSavedMainRenderTarget().getDepthTextureView());
            }
        }

        // Calculate camera motion vectors via VelocityPipeline into inMotion before transitioning to SHADER_READ_ONLY_OPTIMAL
        if (velocityPipeline != null && depthView != 0L) {
            try {
                if (gbViewZ != null && depthView == gbViewZ.view) {
                    // the RT G-buffer view Z is linear (negative in front of the camera), not an NDC depth
                    velocityPipeline.dispatchLinearViewZ(cmd, depthView, inMotion.view, width, height, invViewProj, prevViewProjMat, camDelta,
                        reset ? 0L : entityMotionAddress, DISOCC_OFF ? 0L : disoccBuf.address,
                        (DEPTH_OCC_OFF || reset || !prevViewZValid) ? 0L : prevViewZBuf.address);
                    if (!DISOCC_OFF) {
                        VkBarriers.fullMemoryBarrier(cmd);
                        try (MemoryStack st = MemoryStack.stackPush()) {
                            org.lwjgl.vulkan.VkBufferImageCopy.Buffer r = org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, st);
                            r.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                            r.get(0).imageExtent().set(width, height, 1);
                            VK10.vkCmdCopyBufferToImage(cmd, disoccBuf.handle, inDisocc.image, VK10.VK_IMAGE_LAYOUT_GENERAL, r);
                            // this frame's view Z becomes the previous one of the next frame (read by the velocity pass above)
                            VK10.vkCmdCopyImageToBuffer(cmd, gbViewZ.image, VK10.VK_IMAGE_LAYOUT_GENERAL, prevViewZBuf.handle, r);
                            prevViewZValid = true;
                        }
                    }
                } else {
                    velocityPipeline.dispatch(cmd, depthView, inMotion.view, width, height, invViewProj, prevViewProjMat, camDelta);
                }
                VkBarriers.fullMemoryBarrier(cmd);
                if (MotionProbe.armed()) {
                    MotionProbe.record(cmd, memory, gbViewZ, inMotion, DISOCC_OFF ? null : inDisocc, width, height, invViewProj, prevViewProjMat, camDelta, reset);
                    VkBarriers.fullMemoryBarrier(cmd);
                }
            } catch (Throwable t) {
                LOGGER.warn("[DLSS-RR] VelocityPipeline dispatch failed: {}", t.getMessage());
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkClearColorValue zero = VkClearColorValue.calloc(stack);
                    VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
                    range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdClearColorImage(cmd, inMotion.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
                }
                VkBarriers.fullMemoryBarrier(cmd);
            }
        } else if (reset) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkClearColorValue zero = VkClearColorValue.calloc(stack);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
                range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                VK10.vkCmdClearColorImage(cmd, inMotion.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }

        // DLSS-RR takes one noisy colour plus the two albedo guides: fold the raw diffuse and specular G-buffer channels
        // (fog included) and write the F0 guide. IN_MV is left alone: the velocity pass above produced screen-space motion.
        if (packPipeline != null) {
            ByteBuffer packPc = MemoryUtil.memAlloc(NrdPackPipeline.PUSH_BYTES);
            try {
                NrdPackPipeline.writePush(packPc, 0, 0, 2 /* DLSS-RR */, 0, NrdReblurParams.defaults(),
                    DenoiserConfig.DENOISING_RANGE, width, height, exposure, 0L);
                packPipeline.recordPack(cmd, packPc, width, height);
            } finally {
                MemoryUtil.memFree(packPc);
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }

        RtMemory.Img[] gbuffers = {gbViewZ, gbNormalRough, gbDiffuse, gbAlbedo, gbDirect, inDiffuse, inMotion, inSpecular, inDisocc};

        // 1. Transition G-buffer textures from GENERAL to SHADER_READ_ONLY_OPTIMAL, and output to GENERAL
        VkBarriers.fullMemoryBarrier(cmd);
        for (RtMemory.Img img : gbuffers) {
            VkBarriers.transition(cmd, img.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        VkBarriers.transition(cmd, outDiffuse.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
            VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
        if (boundOutputImage != 0L) {
            VkBarriers.transition(cmd, boundOutputImage, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
        }

        // 2. Build EvalInfo (output in GENERAL with storage usage readWrite=true)
        DlssRrParams.Image color = new DlssRrParams.Image(
            inDiffuse.image, inDiffuse.view, FMT_RGBA16F, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, false);
        DlssRrParams.Image out = new DlssRrParams.Image(
            outDiffuse.image, outDiffuse.view, FMT_RGBA16F, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, true);
        DlssRrParams.Image depth = new DlssRrParams.Image(
            gbViewZ.image, gbViewZ.view, FMT_R32F, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, false);
        DlssRrParams.Image mv = new DlssRrParams.Image(
            inMotion.image, inMotion.view, FMT_RG16F, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, false);
        DlssRrParams.Image diffAlbedo = new DlssRrParams.Image(
            gbAlbedo.image, gbAlbedo.view, FMT_RGBA8, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, false);
        DlssRrParams.Image specAlbedo = new DlssRrParams.Image(
            inSpecular.image, inSpecular.view, FMT_RGBA16F, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, false);
        // Normals: RGBA16F containing (Nx, Ny, Nz, Roughness).
        // Nx, Ny, Nz are unit normal vectors in [-1, 1] (geometric or shading per RtSettings.dlssRrGeometricNormals()).
        // Roughness is linear material roughness in [0, 1] packed into the alpha channel (ROUGHNESS_PACKED).
        DlssRrParams.Image normals = new DlssRrParams.Image(
            gbNormalRough.image, gbNormalRough.view, FMT_RGBA16F, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, false);

        DlssRrParams.EvalInfo eval = new DlssRrParams.EvalInfo(
            reset,
            color, out, depth, mv, DlssRrParams.Image.NONE,
            diffAlbedo, specAlbedo, normals, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE, DlssRrParams.Image.NONE, DlssRrParams.Image.NONE,
            DlssRrParams.Image.NONE,
            DISOCC_OFF ? DlssRrParams.Image.NONE
                : new DlssRrParams.Image(inDisocc.image, inDisocc.view, FMT_R32F, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT, false),
            jitterPixelsX, jitterPixelsY,
            1.0f, 1.0f,
            SHARPNESS,
            1.0f, exposure,
            frameDeltaMs,
            width, height,
            worldToViewMatrix, viewToClipMatrix
        );

        // 3. Evaluate and restore layouts to GENERAL
        try {
            if (io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.dlssViaStreamline()) {
                // The constants of a frame are sent by the FIRST feature that needs them, and the ray traced frame is
                // recorded before the upscale, so this call owns them. Passing the render size as the window made frame
                // generation and the upscaler run the whole frame against a viewport that does not exist (window !=
                // render size whenever a quality preset below 100% is used); the real output size is the upscaler's.
                int outW = io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.isActive()
                    ? io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.outputWidth() : width;
                int outH = io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.isActive()
                    ? io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.outputHeight() : height;
                io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.ensureConstants(width, height, outW, outH);
            }
            DlssNative.Status st = rr.evaluate(cmd.address(), feature, eval);
            if (!st.ok()) {
                throw new IllegalStateException("[DLSS-RR] evaluate failed: " + st);
            }
        } finally {
            for (RtMemory.Img img : gbuffers) {
                VkBarriers.transition(cmd, img.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
            VkBarriers.transition(cmd, outDiffuse.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            if (boundOutputImage != 0L) {
                VkBarriers.transition(cmd, boundOutputImage, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }

        // 4. Compose the DLSS-RR result (final radiance: direct light is already in its input) and tone map
        if (packPipeline != null && boundOutputView != 0L) {
            ByteBuffer pc = MemoryUtil.memAlloc(NrdPackPipeline.PUSH_BYTES);
            try {
                NrdPackPipeline.writePush(pc, 0, 0, 2 /* DLSS-RR */, tonemap ? 1 : 0, NrdReblurParams.defaults(),
                    DenoiserConfig.DENOISING_RANGE, width, height, exposure, 0L);
                packPipeline.recordCompose(cmd, pc, width, height);
            } finally {
                MemoryUtil.memFree(pc);
            }
            VkBarriers.fullMemoryBarrier(cmd);
        }
    }

    private void destroyImages() {
        RtMemory.Img[] all = {gbViewZ, gbNormalRough, gbDiffuse, gbAlbedo, gbDirect, gbSpecular, gbSpecAlbedo, inNormalRough,
            inDiffuse, inMotion, inSpecular, outDiffuse, inDisocc};
        for (RtMemory.Img i : all) {
            if (i != null) {
                memory.destroy(i);
            }
        }
        gbViewZ = gbNormalRough = gbDiffuse = gbAlbedo = gbDirect = gbSpecular = gbSpecAlbedo = inNormalRough = inDiffuse =
            inMotion = inSpecular = outDiffuse = inDisocc = null;
        if (disoccBuf != null) {
            memory.destroy(disoccBuf);
            disoccBuf = null;
        }
        if (prevViewZBuf != null) {
            memory.destroy(prevViewZBuf);
            prevViewZBuf = null;
        }
        imagesReady = false;
    }

    public void destroy() {
        if (device != null) {
            VK10.vkDeviceWaitIdle(device);
        }
        if (feature != 0L) {
            rr.releaseFeature(feature);
            feature = 0L;
        }
        destroyImages();
        if (packPipeline != null) {
            packPipeline.destroy();
            packPipeline = null;
        }
        if (ownsVelocityPipeline && velocityPipeline != null) {
            try {
                velocityPipeline.cleanup();
            } catch (Throwable t) {
                LOGGER.warn("[DLSS-RR] Error cleaning up VelocityPipeline: {}", t.getMessage());
            }
            velocityPipeline = null;
        }
        if (ownsRr && rr != null) {
            rr.close();
        }
        LOGGER.info("[DLSS-RR] denoiser_destroyed");
    }
}
