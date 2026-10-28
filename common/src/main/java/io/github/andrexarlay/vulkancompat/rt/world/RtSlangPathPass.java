package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayGpuImagesNative;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayGpuBuffersNative;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Path Tracing mode on the AOT Slang programs: {@code passes/path_trace.slang} traces one path per pixel into linear
 * HDR radiance ({@link RtPathTraceSlangPass}), then {@code passes/path_display.slang} folds it into a running mean and
 * writes exposure + tone curve into the renderer's RGBA8 {@code rt_output}. Depth goes straight into {@code rt_depth},
 * so the composite, bloom, particles and upscalers downstream are unchanged.
 *
 * <p>What this pass does not do yet, compared with the GLSL {@link RtPathTracerPass}: no NRD / DLSS-RR G-buffer (the
 * renderer only selects this pass when no G-buffer denoiser is wanted, or when the Slang path is forced), no auto
 * exposure (the GLSL shader feeds the luminance statistics; exposure is fixed at 1), a two-colour sky instead of
 * {@code rt_sky.glsl}; the mean is reprojected per pixel through the previous view-projection, so accumulation
 * survives camera motion but restarts on disocclusion.
 *
 * <p>Recording lifetime: every command buffer passed to {@link #dispatch} keeps native descriptor pools until
 * {@link #releaseRecording(long)}; the renderer calls it from the same submission's completion callback or after
 * deliberately discarding an unsubmitted recording. Handle reuse supplies no completion proof.
 */
public final class RtSlangPathPass implements RtPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    static final String DISPLAY_PASS = "path_display";
    static final int DISPLAY_PUSH_BYTES = 32;
    static final int DISPLAY_FLAG_ACCUMULATE = 1;
    static final int DISPLAY_FLAG_TONEMAP = 1 << 1;
    static final int DISPLAY_FLAG_REPROJECT = 1 << 2;
    static final int DISPLAY_FLAG_CAMERA_STATIC = 1 << 3;
    static final int DISPLAY_FLAG_FLIP_Y = 1 << 4;
    static final float MAX_RADIANCE = 64f;
    /** Relative depth disagreement (in blocks at unit distance) that still counts as the same surface when reprojecting. */
    static final float REPROJECTION_TOLERANCE = 0.02f;

    /** Bits of {@code PathTracePush.diState.x}; mirrors the {@code PATH_TRACE_DI_*} constants of the shader. */
    static final int DI_FLAG_ENABLED = 1;
    static final int DI_FLAG_HISTORY = 1 << 1;
    static final int DI_FLAG_STATIC = 1 << 2;
    static final int DI_FLAG_FLIP_Y = 1 << 3;

    /**
     * True when this runtime wants a reservoir ring at all: the emitter list is bound and the session switch is on. This
     * decides allocation only - it is a {@code static final}, so it cannot change while a frame is being recorded, and
     * freeing the ring on a settings flip would cost a {@code vkDeviceWaitIdle} exactly when the player is watching.
     */
    static boolean diRequested() {
        return RtConfig.SLANG_BLOCK_EMITTERS && RtConfig.SLANG_RESTIR_DI;
    }

    /** True when this frame should read and write the reservoirs: allocated above, and the player left reuse switched on. */
    static boolean diActive() {
        return diRequested() && RtSettings.pathRestirDi();
    }

    /**
     * True when this frame's records should also be pooled across neighbours: the kernel exists, the player left the
     * stage on, and the trace pass actually wrote a half this frame ({@code diFlags} says it did, an emitter list has
     * to be behind it). Spatial reuse merges temporal records, so without the first there is nothing to merge.
     */
    boolean spatialActive(int diFlags, int emitterCount) {
        return spatialRequested() && diFlags != 0 && emitterCount > 0;
    }

    /** The same decision without the frame's own gates: what the mode log reports and what the dispatch refines. */
    boolean spatialRequested() {
        return spatialReuse != null && diActive() && RtSettings.pathRestirSpatial();
    }

    /** True when this runtime can run both Slang programs: AOT artifacts present and every required capability enabled. */
    static boolean isSupported(SlangRuntime runtime) {
        return supportProblem(runtime) == null;
    }

    /** Why this runtime cannot run the Slang path, or null when it can. Used verbatim in the fallback/failure log. */
    static String supportProblem(SlangRuntime runtime) {
        if (runtime == null) {
            return "no Slang runtime";
        }
        if (!SlangPassLibrary.available(RtPathTraceSlangPass.PASS_NAME)) {
            return "AOT pass " + RtPathTraceSlangPass.PASS_NAME + " is missing from the jar";
        }
        if (!runtime.supports(RtPathTraceSlangPass.REQUIRED_CAPABILITIES)) {
            return "device capabilities required by " + RtPathTraceSlangPass.PASS_NAME
                + " (rayQuery, bufferDeviceAddress, shaderInt64) are not enabled";
        }
        if (!SlangPassLibrary.available(DISPLAY_PASS)) {
            return "AOT pass " + DISPLAY_PASS + " is missing from the jar";
        }
        return null;
    }

    private final SlangRuntime runtime;
    private final boolean requestedGbuffer;
    private final RtPathTraceSlangPass trace;
    /**
     * The second ReSTIR DI stage, or null when the AOT artifact is missing or its manifest disagrees with the host
     * contract (both logged once at creation). Temporal reuse keeps running without it, exactly as the legacy path
     * still pools reservoirs when its spatial kernel was compiled out.
     */
    private final RtSpatialReuseSlangPass spatialReuse;
    private final SlangProgram display;
    private final long sampler;
    /**
     * True when the native core records the whole path-trace frame: radiance,
     * motion, accumulation and history images, the ReSTIR spatial stage and the
     * display step. The Java orchestration below stays as the default until the
     * native pass has live evidence.
     */
    private final boolean nativePathTrace;
    private BedrayPathTraceNative pathTraceNative;
    private final RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
    private final Set<Long> recordings = new LinkedHashSet<>();
    private RtMemory memory;
    private final BedrayGpuImagesNative historyImages;
    private final BedrayGpuBuffersNative nativeBuffers;
    private final BedrayGpuImagesNative.Image[] accum = new BedrayGpuImagesNative.Image[2];
    private final BedrayGpuImagesNative.Image[] historyDepth = new BedrayGpuImagesNative.Image[2];
    private int historyParity;
    /** Two halves of per-pixel ReSTIR DI reservoirs, owned here so the Slang ring never aliases the GLSL one. */
    private BedrayGpuBuffersNative.Buffer reservoirs;
    private RtPipeline.Views views;
    private int accumFrames;
    private boolean forceReset = true;
    /** True when the previous display dispatch left a mean and a depth history that reprojection can read. */
    private boolean historyWritten;
    private long reservoirPixels;
    private long reservoirFingerprint = Long.MIN_VALUE;
    private boolean reservoirResetRequested = true;
    /** True when the previous traced dispatch wrote the half the other parity reads. */
    private boolean reservoirHistoryWritten;
    /** Advanced only by a dispatch that actually wrote reservoirs, so a skipped frame leaves the ring consistent. */
    private int reservoirFrame;
    private int lastSettingsHash;
    private int lastWidth;
    private int lastHeight;
    private final float[] lastSunDirection = new float[3];
    private final float[] lastSunColor = new float[3];
    private final float[] lastSkyColor = new float[3];
    private boolean hasLighting;
    private int sampleIndex;
    private boolean entityRingReported;
    private boolean destroyed;

    /**
     * {@code requestedGbuffer}: what the renderer asked for. This pass never writes the G-buffer, but it remembers the
     * request so the renderer does not rebuild it every frame for not matching (see {@link #builtFor(boolean)}).
     */
    RtSlangPathPass(VkDevice device, SlangRuntime runtime, RtMemory memory, boolean requestedGbuffer) {
        this.runtime = runtime;
        if (runtime == null) {
            throw new IllegalArgumentException("path trace needs a SlangRuntime");
        }
        this.nativePathTrace = RtConfig.SLANG_PATH_TRACE_CPP && runtime.library().hasPathTrace();
        if (RtConfig.SLANG_PATH_TRACE_CPP && !nativePathTrace) {
            LOGGER.warn("[VKB-RT] vkbridge.rt.nativePathTrace=true but the loaded bedray_core has no path-trace"
                + " exports; keeping the Java path");
        }
        this.memory = memory;
        this.requestedGbuffer = requestedGbuffer;
        this.trace = new RtPathTraceSlangPass(device, runtime);
        BedrayGpuImagesNative allocatedHistory = null;
        BedrayGpuBuffersNative allocatedBuffers = null;
        try {
            long deviceBytes = runtime.capabilities().deviceMemoryBytes();
            allocatedHistory = runtime.createImageOwner(deviceBytes > 0 ? Math.min(1L << 30, deviceBytes / 8) : 256L << 20);
            allocatedBuffers = runtime.createBufferOwner(deviceBytes > 0 ? Math.min(1L << 30, deviceBytes / 4) : 512L << 20);
            Optional<SlangProgram> program = runtime.program(DISPLAY_PASS);
            if (program.isEmpty()) {
                throw new IllegalStateException("missing Slang pass " + DISPLAY_PASS);
            }
            this.display = program.get();
            requireDisplayManifest(display);
            this.sampler = runtime.atlasSampler();
        } catch (RuntimeException | Error e) {
            if (allocatedBuffers != null) allocatedBuffers.close();
            if (allocatedHistory != null) allocatedHistory.close();
            trace.close();
            throw e;
        }
        historyImages = allocatedHistory;
        nativeBuffers = allocatedBuffers;
        // Deliberately outside the try above: spatial reuse is an extra stage, not a precondition of path tracing, and a
        // machine without its artifact still gets the temporal reservoirs.
        this.spatialReuse = RtSpatialReuseSlangPass.create(runtime, LOGGER).orElse(null);
        LOGGER.info("[VKB-RT] path tracing on AOT Slang passes {} + {}{} (GLSL rt_path.comp not used{})",
            RtPathTraceSlangPass.PASS_NAME, DISPLAY_PASS,
            spatialReuse == null ? "" : " + " + RtSpatialReuseSlangPass.PASS_NAME,
            requestedGbuffer ? ", G-buffer denoiser disabled" : "");
    }

    static void requireDisplayManifest(SlangProgram program) {
        SlangPassManifest manifest = program.manifest();
        if (manifest.pushConstantBytes() != DISPLAY_PUSH_BYTES) {
            throw new IllegalStateException(program.name() + " pushes " + manifest.pushConstantBytes() + " B, host block is "
                + DISPLAY_PUSH_BYTES + " B");
        }
        for (String name : List.of("radiance", "accum", "outImage", "depth", "historyDepth", "motion", "previousAccum", "previousDepth")) {
            SlangPassManifest.Binding binding = manifest.binding(name);
            if (binding == null || binding.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE) {
                throw new IllegalStateException(program.name() + ": '" + name + "' must be a storage image");
            }
        }
        SlangPassManifest.Binding constants = manifest.binding("frame");
        if (constants == null || constants.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
            throw new IllegalStateException(program.name() + ": 'frame' must be the FrameConstants buffer");
        }
        if (manifest.bindings().size() != 9) {
            throw new IllegalStateException(program.name() + " declares " + manifest.bindings().size()
                + " binding(s), host supplies 9");
        }
    }

    @Override
    public RtMode mode() {
        return RtMode.PATH_TRACED;
    }

    @Override
    public String describe(RtFrameParams p) {
        String restir = !diActive() || reservoirs == null ? "off"
            : (reservoirHistoryWritten ? "temporal" : "fresh") + (spatialRequested() ? "+spatial" : "");
        return "path_slang(bounces=" + p.bounces + ",spp=1,dist=" + p.rayDistance
            + (RtSettings.accumulate() ? ",accumulate" : "") + ",restir_di=" + restir
            + ",frames=" + (accumFrames + 1) + ",denoiser=off)";
    }

    @Override
    public void prepare(VkCommandBuffer cmd, RtMemory mem, int width, int height) {
        memory = mem;
        prepareReservoirs(mem, width, height);
        if (nativePathTrace) {
            // The native pass owns radiance, motion, accumulation and history
            // depth, and it resets them itself when the extent changes. The host
            // still owns the DI ring above, so only its size bookkeeping stays
            // here.
            if (lastWidth != width || lastHeight != height) {
                lastWidth = width;
                lastHeight = height;
                historyWritten = false;
                // The pass retires its own images on an extent change; a zero
                // sample count keeps this frame from reading a half that was
                // just recreated.
                accumFrames = 0;
            }
            return;
        }
        if (accum[0] != null && lastWidth == width && lastHeight == height) {
            return;
        }
        BedrayGpuImagesNative.Image[] nextAccum = new BedrayGpuImagesNative.Image[2];
        BedrayGpuImagesNative.Image[] nextDepth = new BedrayGpuImagesNative.Image[2];
        try {
            for (int i = 0; i < 2; ++i) {
                nextAccum[i] = historyImages.create(width, height, VK10.VK_FORMAT_R32G32B32A32_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
                nextDepth[i] = historyImages.create(width, height, VK10.VK_FORMAT_R32_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
            }
        } catch (RuntimeException | Error e) {
            for (int i = 0; i < 2; ++i) {
                historyImages.retire(nextAccum[i]);
                historyImages.retire(nextDepth[i]);
            }
            throw e;
        }
        for (int i = 0; i < 2; ++i) {
            historyImages.retire(accum[i]);
            historyImages.retire(historyDepth[i]);
            accum[i] = nextAccum[i];
            historyDepth[i] = nextDepth[i];
        }
        historyWritten = false;
        historyParity = 0;
        lastWidth = width;
        lastHeight = height;
        forceReset = true;
    }

    /**
     * The reservoir ring of the DI branch: two halves of one 64-byte record per pixel, device-local because the reset is
     * a command-buffer fill. Turning ReSTIR DI off frees it again - the buffer is 250 MB at 1080p and holding it unused
     * would compete with the section arenas.
     */
    private void prepareReservoirs(RtMemory mem, int width, int height) {
        long pixels = (long) width * height;
        long need = pixels * 2L * RtPathTraceSlangPass.RESERVOIR_BYTES;
        if (!diRequested()) {
            if (reservoirs != null) {
                nativeBuffers.retire(reservoirs);
                reservoirs = null;
            }
            reservoirHistoryWritten = false;
            reservoirPixels = 0L;
            return;
        }
        if (reservoirs != null && reservoirs.size() >= need) {
            if (pixels != reservoirPixels) {
                // A smaller render scale moves every pixel index, so the records of the old layout must not be read.
                reservoirPixels = pixels;
                reservoirResetRequested = true;
                reservoirHistoryWritten = false;
            }
            return;
        }
        BedrayGpuBuffersNative.Buffer next = nativeBuffers.createDeviceLocal(need,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        nativeBuffers.retire(reservoirs);
        reservoirs = next;
        reservoirPixels = pixels;
        reservoirResetRequested = true;
        reservoirHistoryWritten = false;
        LOGGER.info("[VKB-RT] Slang ReSTIR DI reservoirs {}x{} x2 = {} MiB", width, height,
            Math.max(1L, need / (1024L * 1024L)));
    }

    @Override
    public void configure(RtFrameParams params, boolean denoising) {
        params.mode = RtConfig.effectiveMode();
        params.bounces = RtAdaptiveQuality.bounces(RtSettings.spp(), RtSettings.bounces());
        params.spp = 1;
        params.rayDistance = RtSettings.pathMaxDistance();
        params.flags |= RtFrameParams.FLAG_EMISSIVE;

        // The mean is reprojected per pixel, so camera motion no longer invalidates the whole history; only a change
        // the reprojection cannot explain (new lights, new geometry, new settings) restarts it.
        boolean lightingChanged = hasLighting
            && (differs(params.key.dir, lastSunDirection)
                || differs(params.key.color, lastSunColor)
                || differs(params.skyColor, lastSkyColor));
        int settingsHash = Objects.hash(params.bounces, params.rayDistance, RtSettings.accumulate(), params.mode,
            RtSettings.pathPreset, RtSettings.preset);
        if (forceReset || lightingChanged || params.sceneChanged || settingsHash != lastSettingsHash
            || !RtSettings.accumulate()) {
            accumFrames = 0;
            historyWritten = false;
        } else if (accumFrames < RtFrameParams.MAX_ACCUM) {
            accumFrames++;
        }
        // The reservoir ring has its own, much narrower invalidation rule: only a real reset (start, resize, a change of
        // what is being traced) or a different light list makes a stored sample wrong. A removed torch would otherwise
        // keep glowing through history forever, while streaming sections and the accumulate switch leave it valid - and
        // filling hundreds of MB every frame would cost far more than the reuse saves.
        if (forceReset || settingsHash != lastSettingsHash || params.emitterFingerprint != reservoirFingerprint) {
            reservoirResetRequested = true;
            reservoirHistoryWritten = false;
        }
        reservoirFingerprint = params.emitterFingerprint;
        forceReset = false;
        lastSettingsHash = settingsHash;
        System.arraycopy(params.key.dir, 0, lastSunDirection, 0, 3);
        System.arraycopy(params.key.color, 0, lastSunColor, 0, 3);
        System.arraycopy(params.skyColor, 0, lastSkyColor, 0, 3);
        hasLighting = true;
        params.accumCount = accumFrames;
    }

    private static boolean differs(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) > RtAccumulation.SUN_EPS
            || Math.abs(a[1] - b[1]) > RtAccumulation.SUN_EPS
            || Math.abs(a[2] - b[2]) > RtAccumulation.SUN_EPS;
    }

    @Override
    public void bind(RtPipeline.Views common) {
        views = common;
    }

    @Override
    public boolean usesGbuffer() {
        return false;
    }

    @Override
    public boolean builtFor(boolean gbuffer) {
        return gbuffer == requestedGbuffer;
    }

    @Override
    public void resetHistory() {
        forceReset = true;
        historyWritten = false;
        reservoirResetRequested = true;
        reservoirHistoryWritten = false;
    }

    @Override
    public void dispatch(VkCommandBuffer cmd, RtFrameParams params, int width, int height) {
        if (views == null || (!nativePathTrace && accum[0] == null)) {
            throw new IllegalStateException("path_slang dispatched before prepare/bind");
        }
        recordings.add(cmd.address());
        if (reservoirs != null) nativeBuffers.retain(cmd.address(), reservoirs);
        fillFrame(params, width, height);
        // The emitter list of the legacy path, bound as a second descriptor range: switching block emitters off is an A/B
        // on the shader's NEE branch only, the list keeps being rebuilt either way.
        int emitterCount = RtConfig.SLANG_BLOCK_EMITTERS ? Math.max(0, params.emitterCount) : 0;
        RtPathTraceSlangPass.Emitters emitters = new RtPathTraceSlangPass.Emitters(
            emitterCount > 0 ? params.emitterHandle : 0L, params.emitterOffset, emitterCount);
        // ReSTIR DI reuses the emitter samples of the previous dispatch, so it needs both a bound list and the ring.
        // The half written this frame is the opposite of the half read, which is why the parity only advances on a
        // dispatch that actually wrote records.
        RtPathTraceSlangPass.Reservoirs reservoirRange = RtPathTraceSlangPass.Reservoirs.NONE;
        int diFlags = 0;
        int diParity = reservoirFrame & 1;
        if (emitterCount > 0 && diActive() && reservoirs != null) {
            if (reservoirResetRequested) {
                VK10.vkCmdFillBuffer(cmd, reservoirs.handle(), 0L, reservoirs.size(), 0);
                VkBarriers.fullMemoryBarrier(cmd);
                reservoirResetRequested = false;
            }
            diFlags = DI_FLAG_ENABLED | (reservoirHistoryWritten ? DI_FLAG_HISTORY : 0)
                | (params.cameraStatic ? DI_FLAG_STATIC : 0) | (params.flipY ? DI_FLAG_FLIP_Y : 0);
            reservoirRange = new RtPathTraceSlangPass.Reservoirs(reservoirs.handle(), reservoirs.size());
        }
        // The models of this frame: the ring slice the entity BLAS was built from, so a committed primitive index names a
        // triangle of these buffers and of nothing else. Turning the capture off leaves the terrain alone in the trace.
        RtPathTraceSlangPass.Entities capturedEntities = entityBindingsOfThisFrame();
        if (!entityRingReported) {
            entityRingReported = true;
            // One line for the whole session, before the dispatch: "mobs are not in the picture" splits into a capture
            // that never filled the ring and a trace that ignores a ring that is full, and only this line tells them apart.
            if (capturedEntities.indices().bytes() > 0L) {
                LOGGER.info("[VKB-RT] Slang path trace reads the entity ring: {} triangles, {} vertices,"
                        + " atlas {} texels per side, first-person body {}+{}",
                    capturedEntities.indices().bytes() / 12L, capturedEntities.positions().bytes() / 12L,
                    capturedEntities.atlasSize(), capturedEntities.selfAvatarTriStart(),
                    capturedEntities.selfAvatarTriCount());
            } else {
                LOGGER.info("[VKB-RT] Slang path trace has no entity capture in this frame (-Dvkbridge.rt.slangEntities"
                    + " = {}): the trace sees terrain only", RtConfig.SLANG_ENTITIES);
            }
        }
        RtPathTraceSlangPass.Dispatch dispatch = new RtPathTraceSlangPass.Dispatch(Math.max(1, params.bounces),
            8, sampleIndex++, 0, 0, 0.01f, 0.1f, Math.max(1f, params.rayDistance), MAX_RADIANCE, 0.5f,
            emitterCount, emitterCount > 0 ? RtConfig.SLANG_EMITTER_PROBABILITY : 0f, params.emitterCamRelX,
            params.emitterCamRelY, params.emitterCamRelZ, RtConfig.SLANG_EMITTER_CANDIDATES,
            params.cameraDeltaX, params.cameraDeltaY, params.cameraDeltaZ, diFlags, diParity,
            capturedEntities.atlasSize(), capturedEntities.selfAvatarTriStart(), capturedEntities.selfAvatarTriCount());
        if (nativePathTrace) {
            recordNative(cmd, params, emitters, reservoirRange, capturedEntities, dispatch);
            // The ring is the host's: the native pass only forwards the parity it
            // was given, so the host still decides when a written half becomes
            // readable history.
            if (diFlags == 0) {
                reservoirHistoryWritten = false;
            } else {
                reservoirHistoryWritten = true;
                reservoirFrame++;
            }
            return;
        }
        trace.record(cmd, new RtPathTraceSlangPass.Scene(views.tlas(), views.table()),
            new RtPathTraceSlangPass.Atlases(views.atlas(), views.material(), views.normal(), sampler),
            emitters, reservoirRange, capturedEntities, views.depth(), frame, dispatch);
        if (diFlags == 0) {
            reservoirHistoryWritten = false;
        } else {
            reservoirHistoryWritten = true;
            reservoirFrame++;
        }
        // Spatial reuse pools the records the trace pass just wrote, in the half it just wrote. Nothing in this frame
        // reads them - the benefit lands in the next frame's temporal import - so the stage owns its own ordering: the
        // barrier between its two phases and a trailing one, both inside record().
        if (spatialReuse != null && spatialActive(diFlags, emitterCount) && reservoirs != null) {
            spatialReuse.record(cmd, new RtPathTraceSlangPass.Atlases(views.atlas(), views.material(),
                    views.normal(), sampler), emitters, reservoirRange, trace.frameResource(), width, height,
                params.frame, diParity, params.emitterCamRelX, params.emitterCamRelY, params.emitterCamRelZ,
                Math.max(1f, params.rayDistance));
        }

        int flags = DISPLAY_FLAG_TONEMAP | (RtSettings.accumulate() ? DISPLAY_FLAG_ACCUMULATE : 0);
        if (params.cameraStatic) {
            flags |= DISPLAY_FLAG_CAMERA_STATIC;
        }
        // Surface motion also needs reprojection when the camera is stationary.
        if (historyWritten) {
            flags |= DISPLAY_FLAG_REPROJECT;
        }
        if (params.flipY) {
            flags |= DISPLAY_FLAG_FLIP_Y;
        }
        int write = historyParity, read = write ^ 1;
        for (int i = 0; i < 2; ++i) {
            historyImages.retain(cmd.address(), accum[i]);
            historyImages.retain(cmd.address(), historyDepth[i]);
            // Destinations are overwritten completely. The source needs an
            // initial layout only after a reset; valid history is kept intact.
            if (i == write || !historyWritten) {
                VkBarriers.transition(cmd, accum[i].image(), VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkBarriers.transition(cmd, historyDepth[i].image(), VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(DISPLAY_PUSH_BYTES);
            push.putInt(0, accumFrames).putInt(4, flags).putFloat(8, 1.0f).putFloat(12, MAX_RADIANCE);
            push.putFloat(16, params.cameraDeltaX).putFloat(20, params.cameraDeltaY)
                .putFloat(24, params.cameraDeltaZ).putFloat(28, REPROJECTION_TOLERANCE);
            display.dispatch(cmd.address(), display.groups(width, 0), display.groups(height, 1), 1, List.of(
                SlangProgram.Resource.storageImage("radiance", trace.radianceView()),
                SlangProgram.Resource.storageImage("accum", accum[write].view()),
                SlangProgram.Resource.storageImage("outImage", views.output()),
                SlangProgram.Resource.storageImage("depth", views.depth()),
                SlangProgram.Resource.storageImage("historyDepth", historyDepth[write].view()),
                SlangProgram.Resource.storageImage("motion", trace.motionView()),
                SlangProgram.Resource.storageImage("previousAccum", accum[read].view()),
                SlangProgram.Resource.storageImage("previousDepth", historyDepth[read].view()),
                trace.frameResource()), push);
        }
        historyWritten = true;
        historyParity ^= 1;
        VkBarriers.fullMemoryBarrier(cmd); // rt_output and rt_depth are read by the composite, bloom and upscalers next
    }

    /**
     * Records trace → optional spatial → display through the additive native pass. The pass owns the radiance, motion,
     * accumulation and history-depth images and its own history parity; the host supplies the scene it already owns, the
     * DI ring and the path limits, exactly as for the Java path. The host still owns the ring's parity: the pass forwards
     * {@code dispatch.diParity()} verbatim instead of advancing one of its own.
     */
    private void recordNative(VkCommandBuffer cmd, RtFrameParams params, RtPathTraceSlangPass.Emitters emitters,
                              RtPathTraceSlangPass.Reservoirs reservoirRange,
                              RtPathTraceSlangPass.Entities entities,
                              RtPathTraceSlangPass.Dispatch dispatch) {
        if (pathTraceNative == null) {
            pathTraceNative = new BedrayPathTraceNative(runtime, trace.programHandle(), trace.sceneProgramHandle(),
                spatialReuse == null ? 0L : spatialReuse.programHandle(), display.nativeHandle(), sampler);
        }
        // The native table is a different record layout: the core has to dispatch
        // path_trace_scene for this frame, not just bind a different buffer.
        boolean nativeScene = views.table() != null && views.table().sceneOwned;
        int flags = (historyWritten ? 0 : BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY)
            | (RtSettings.accumulate() ? BedrayCoreLayouts.PATH_TRACE_FRAME_ACCUMULATE : 0)
            | (params.cameraStatic ? BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_STATIC : 0)
            | (params.flipY ? BedrayCoreLayouts.PATH_TRACE_FRAME_FLIP_Y : 0)
            | (spatialRequested() ? BedrayCoreLayouts.PATH_TRACE_FRAME_SPATIAL : 0)
            | (nativeScene ? BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_SCENE : 0);
        // The pass derives the display bits from these same frame bits, so the host contributes none of its own: there
        // is one definition of TONEMAP/ACCUMULATE/CAMERA_STATIC/REPROJECT/FLIP_Y, in the pass.
        pathTraceNative.render(new BedrayPathTraceNative.FrameInput(cmd.address(), views, frame, dispatch,
            emitters, reservoirRange, entities, flags, accumFrames, 0, MAX_RADIANCE, REPROJECTION_TOLERANCE));
        historyWritten = true;
        // The pass already ends with a full memory barrier; the Java path keeps this one for the composite and bloom.
        VkBarriers.fullMemoryBarrier(cmd);
    }

    /**
     * This frame's captured models as the descriptor ranges the trace pass binds, read from the same manager whose
     * statistics buffer the legacy path reaches them through. An empty ring reports no range at all, which is what makes
     * the pass substitute its one-element dummy rather than a stale slice.
     */
    private static RtPathTraceSlangPass.Entities entityBindingsOfThisFrame() {
        if (!RtConfig.SLANG_ENTITIES) {
            return RtPathTraceSlangPass.Entities.NONE;
        }
        RtEntityGeometryManager eg = RtEntityGeometryManager.INSTANCE;
        RtEntityGeometryManager.GpuRange indices = eg.frameIndices();
        io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager capture =
            io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance();
        return new RtPathTraceSlangPass.Entities(entityView(eg.framePositions()),
            entityView(eg.framePreviousPositions()), entityView(indices),
            entityView(eg.frameAttributes()), entityView(eg.atlasRects()), entityView(eg.atlasTexels()),
            eg.atlasTexelsPerSide(), indices.present() ? capture.selfAvatarTriStart() : 0,
            indices.present() ? capture.selfAvatarTriCount() : 0);
    }

    private static RtPathTraceSlangPass.BufferView entityView(RtEntityGeometryManager.GpuRange range) {
        return range.present()
            ? new RtPathTraceSlangPass.BufferView(range.buffer().handle, range.offset(), range.bytes())
            : RtPathTraceSlangPass.BufferView.NONE;
    }

    /**
     * FrameConstants from the renderer's frame state. The camera basis is derived from the same inverse view-projection
     * and the same pixel-to-NDC mapping (including flipY) the GLSL cameraRay uses: three points on one plane of constant
     * clip depth give forward, right, down, tan(half fov) and aspect exactly, so a Slang ray through a pixel is the GLSL
     * ray through that pixel.
     */
    private void fillFrame(RtFrameParams p, int width, int height) {
        float yUp = p.flipY ? 1f : -1f; // matrix-space NDC y of the top edge (Slang ndc.y = -1)
        // The world, the projection and the TLAS are anchored at the raw camera position; the engine view adds the
        // view-bob eye offset.  Remove it before deriving the basis and keep it as the ray origin, exactly like the
        // legacy cameraOrigin()/cameraRay() pair, or walking shifts the whole world against the eye.
        Vector3f bob = new Vector3f(p.bobX, p.bobY, p.bobZ);
        Vector3f centre = unproject(p.invViewProj, 0f, 0f).sub(bob);
        Vector3f rightEdge = unproject(p.invViewProj, 1f, 0f).sub(bob);
        Vector3f bottomEdge = unproject(p.invViewProj, 0f, -yUp).sub(bob);
        Vector3f rightVec = new Vector3f(rightEdge).sub(centre);
        Vector3f downVec = new Vector3f(bottomEdge).sub(centre);
        float centreLen = Math.max(centre.length(), 1.0e-6f);
        float rightLen = Math.max(rightVec.length(), 1.0e-6f);
        float downLen = Math.max(downVec.length(), 1.0e-6f);
        Vector3f forward = new Vector3f(centre).div(centreLen);
        Vector3f right = new Vector3f(rightVec).div(rightLen);
        Vector3f up = new Vector3f(downVec).div(-downLen);

        frame.invViewProjection.set(p.invViewProj);
        frame.viewProjection.set(p.invViewProj).invert();
        frame.prevViewProjection.set(p.prevViewProj);
        frame.forward.set(forward.x, forward.y, forward.z, downLen / centreLen);
        frame.right.set(right.x, right.y, right.z, rightLen / downLen);
        frame.up.set(up.x, up.y, up.z, 0f);
        frame.origin.set(bob.x, bob.y, bob.z, p.frame / 20f);
        // Sub-pixel jitter per frame so the running mean also antialiases; zw is the pixel size the shader uses for NDC.
        float jx = accumFrames == 0 ? 0f : halton(sampleIndex + 1, 2) - 0.5f;
        float jy = accumFrames == 0 ? 0f : halton(sampleIndex + 1, 3) - 0.5f;
        frame.jitter.set(jx, jy, 1f / width, 1f / height);
        frame.width = width;
        frame.height = height;
        frame.displayWidth = width;
        frame.displayHeight = height;

        float[] dir = p.key.dir;
        float[] color = p.key.color;
        float sunRadius = Math.max(RtSettings.sunRadiusRad(), 1.0e-4f);
        frame.sunDirection.set(dir[0], dir[1], dir[2], sunRadius);
        frame.sunColor.set(color[0], color[1], color[2], 1f);
        frame.moonDirection.set(-dir[0], -dir[1], -dir[2], sunRadius);
        frame.moonColor.set(0f, 0f, 0f, 0f);

        // SkyRenderState.skyColor is the colour vanilla draws (display space); the legacy sky converts it to radiance with
        // tonemapInverse before shading.  The display pass tone-maps this frame again, so writing the raw colour here would
        // tone-map the sky twice (gain 1.2 + shoulder on an already display-referred value) and wash it out.
        float[] sky = p.skyColor;
        float[] zenith = RtTonemap.invert(sky[0], sky[1], sky[2]);
        float[] horizon = RtTonemap.invert(sky[0] * 0.7f + 0.3f, sky[1] * 0.7f + 0.3f, sky[2] * 0.7f + 0.3f);
        frame.zenithColor.set(zenith[0], zenith[1], zenith[2], 1f);
        frame.horizonColor.set(horizon[0], horizon[1], horizon[2], 1f);
        frame.fogColor.set(frame.horizonColor);
        frame.fog.set(0f, 0f, 0f, 0f);
        frame.cloud.set(0f, 0f, 0f, 0f);

        frame.exposure.set(1f, 0f, 0f, 0f);
        frame.autoExposure = 0;
        frame.tonemapOperator = 0;
        frame.bloomEnabled = 0;
        frame.frameIndex = p.frame;
        frame.sampleIndex = sampleIndex;
        frame.samplesPerFrame = 1;
        frame.flags = 0;
    }

    private static Vector3f unproject(Matrix4f invViewProj, float x, float y) {
        Vector4f v = new Vector4f(x, y, 0.5f, 1f).mul(invViewProj);
        float w = Math.abs(v.w) < 1.0e-12f ? 1.0e-12f : v.w;
        return new Vector3f(v.x / w, v.y / w, v.z / w);
    }

    private static float halton(int index, int base) {
        float f = 1f;
        float r = 0f;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    @Override
    public void releaseRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) {
            return;
        }
        trace.releaseRecording(commandBuffer);
        historyImages.releaseRecording(commandBuffer);
        nativeBuffers.releaseRecording(commandBuffer);
        if (spatialReuse != null) {
            spatialReuse.releaseRecording(commandBuffer);
        }
        if (!runtime.isClosed()) {
            runtime.releaseRecording(commandBuffer); // the display dispatch shares the command buffer's native record
        }
        recordings.remove(commandBuffer);
    }

    @Override
    public void releaseRecordingsAfterHostDrain() {
        for (Long commandBuffer : List.copyOf(recordings)) {
            releaseRecording(commandBuffer);
        }
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }
        if (!recordings.isEmpty()) {
            throw new IllegalStateException("Slang path shutdown requires a completed host submission drain");
        }
        if (pathTraceNative != null) {
            pathTraceNative.close();
            pathTraceNative = null;
        }
        trace.close();
        if (spatialReuse != null) {
            spatialReuse.close();
        }
        historyImages.close();
        historyWritten = false;
        nativeBuffers.close();
        reservoirs = null;
        reservoirHistoryWritten = false;
        reservoirPixels = 0L;
        destroyed = true;
    }
}
