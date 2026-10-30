package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Both hardware tracing modes on the AOT Slang programs: {@code passes/path_trace.slang} traces one path per pixel into
 * linear HDR radiance ({@link RtPathTraceSlangPass}), then {@code passes/path_display.slang} folds it into a running mean
 * and writes exposure + tone curve into the renderer's RGBA8 {@code rt_output}. Depth goes straight into
 * {@code rt_depth}, so the composite, bloom, particles and upscalers downstream are unchanged.
 *
 * <p>Ray Tracing mode is the same pipeline with a one-indirect-bounce profile ({@link #rayMode}): one sample, no adaptive
 * governor, its own {@code rays_restir_*} and {@code rays_accumulate} switches. Path Tracing mode traces the player's
 * bounce and sample counts through the adaptive governor.
 *
 * <p>The pass fills the seven DLSS-RR guide images when the host binds them ({@code PATH_TRACE_OPTION_GBUFFER}) and
 * NRD's per-pixel {@code IN_MV} vec4 when the frame carries the motion buffer
 * ({@code PATH_TRACE_OPTION_NRD_MOTION}). Open against the pipeline this replaced: the traced sky is the horizon
 * gradient plus the sun and moon discs, with neither clouds nor aerial fog in it, and the frame's counters come from a
 * separate pass ({@link RtFrameStatsSlangPass}) recorded on both backends.
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

    /** Session gate for DI support; allocation and retirement belong to the selected backend. */
    static boolean diRequested() {
        return RtConfig.SLANG_BLOCK_EMITTERS && RtConfig.SLANG_RESTIR_DI;
    }

    /**
     * True when this frame should read and write the reservoirs: allocated above, and the player left reuse switched on.
     * Each profile keeps its own switches - the ray mode's {@code rays_restir_*} predate this pass, the path mode's
     * {@code path_restir_*} were added with it - and a profile that read the other's would leave its own rows dead.
     */
    boolean diActive() {
        return diRequested() && (rayMode ? RtSettings.restirTemporal() : RtSettings.pathRestirDi());
    }

    /** The same decision without the frame's own gates: what the mode log reports and what the dispatch refines. */
    boolean spatialRequested() {
        return backend.spatialAvailable() && diActive()
            && (rayMode ? RtSettings.restirSpatial() : RtSettings.pathRestirSpatial());
    }

    /** The accumulate switch that belongs to this profile: the ray mode has its own, the path mode its own. */
    private boolean accumulateRequested() {
        return rayMode ? RtSettings.raysAccumulation() : RtSettings.accumulate();
    }

    /**
     * The effect switches of the ray mode, as {@code PATH_TRACE_OPTION_*} bits. The path profile sets none: it always
     * traces the full transport, and its own screen has no per-effect switches to honour.
     */
    int rayEffectFlags() {
        if (!rayMode) {
            return 0;
        }
        int flags = 0;
        if (!RtSettings.reflections()) {
            flags |= RtPathTraceSlangPass.OPTION_NO_SPECULAR_BOUNCE;
        }
        if (!RtSettings.gi()) {
            // Without GI a diffuse bounce either collects the sky alone (AO) or is not traced at all.
            flags |= RtSettings.ao() ? RtPathTraceSlangPass.OPTION_AMBIENT_ONLY_BOUNCE
                : RtPathTraceSlangPass.OPTION_NO_DIFFUSE_BOUNCE;
        }
        if (!RtSettings.shadows()) {
            flags |= RtPathTraceSlangPass.OPTION_NO_KEY_SHADOWS;
        }
        if (!RtSettings.softShadows()) {
            flags |= RtPathTraceSlangPass.OPTION_HARD_KEY_SHADOWS;
        }
        return flags;
    }

    /**
     * The pixel-shadow look option, as the flag plus the cell size in texture pixels. It belongs to both profiles: the
     * deleted legacy renderer honoured it in the path and the ray pass alike, and the setting lives in the shared look
     * page. A size outside the settings screen's 1..16 range is clamped rather than refused: a stale config must not
     * blank the shadows of a frame.
     */
    static int pixelShadowFlags() {
        if (!RtSettings.pixelShadows()) {
            return 0;
        }
        int size = Math.min(RtPathTraceSlangPass.PIXEL_SHADOW_MAX_SIZE,
            Math.max(1, RtSettings.pixelShadowSize()));
        return RtPathTraceSlangPass.OPTION_PIXEL_SHADOWS | (size << RtPathTraceSlangPass.PIXEL_SHADOW_SIZE_SHIFT);
    }

    /** Far plane of a diffuse bounce: GI's own distance, or the AO radius when that bounce only collects the sky. */
    float diffuseBounceDistance() {
        if (!rayMode) {
            return 0f;
        }
        return RtSettings.gi() ? RtSettings.giDistance() : RtSettings.aoRadius();
    }

    /** Far plane of a specular bounce, from the ray mode's "Reflection Distance"; 0 = the ray far plane. */
    float specularBounceDistance() {
        return rayMode ? RtSettings.reflectionDistance() : 0f;
    }

    /** The range the emitter list of this frame was collected over, so the sampler never reaches past it. */
    float emitterBounceDistance(RtFrameParams params) {
        return rayMode ? RtSettings.emissiveDistance() + 8f : Math.min(params.rayDistance, 128f);
    }

    /** Candidate count per light sample: the ray mode's own quality slider, the path mode's A/B property. */
    int emitterCandidates() {
        return rayMode ? Math.min(4, Math.max(1, RtSettings.emissiveQuality())) * 8
            : RtConfig.SLANG_EMITTER_CANDIDATES;
    }

    /** True when this runtime can run both Slang programs: AOT artifacts present and every required capability enabled. */
    static boolean isSupported(SlangRuntime runtime) {
        return supportProblem(runtime) == null;
    }

    /** Why this runtime cannot run the Slang path, or null when it can. Used verbatim in the fallback/failure log. */
    static String supportProblem(SlangRuntime runtime) {
        return supportProblem(runtime, false);
    }

    static String supportProblem(SlangRuntime runtime, boolean guides) {
        if (RtConfig.SLANG_RADIANCE_CASCADES && !RtConfig.SLANG_PATH_TRACE_CPP)
            return "radianceCascades requires nativePathTrace=true";
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
        return RtConfig.SLANG_PATH_TRACE_CPP ? NativePathTraceBackend.supportProblem(runtime, guides) : null;
    }

    /** Scattering events the ray mode traces: the surface the camera sees plus one indirect one. */
    static final int RAY_MODE_MAX_DEPTH = 2;
    /** Samples per pixel the ray mode traces: one, because a frame is one sample and the mean carries the rest. */
    static final int RAY_MODE_SPP = 1;

    private final boolean requestedGbuffer;
    /**
     * Ray Tracing mode: the same two AOT programs, traced with one bounce and one sample, reading the ray profile's own
     * {@code rays_restir_*} and {@code rays_accumulate} switches. The effect set is the one the deleted
     * {@code rt_rays.comp} produced - sun shadows through NEE, reflections and one-bounce indirect light through the
     * BSDF sample, emissive blocks through their own NEE - but it comes from the path transport instead of a
     * per-effect raycaster, so there is one shader to keep correct.
     */
    private final boolean rayMode;
    private final PathTraceBackend backend;
    private final RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
    private RtFrameBindings.Views views;
    private int accumFrames;
    private boolean forceReset = true;
    /** True when the previous display dispatch left a mean and a depth history that reprojection can read. */
    private boolean historyWritten;
    private long reservoirFingerprint = Long.MIN_VALUE;
    // Backend owns the ring; host tracks only whether its reset request ran DI.
    private final RestirDiState diRequests = new RestirDiState(true);
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
     * {@code requestedGbuffer}: what the renderer asked for. The pass fills the DLSS-RR guides when the frame carries
     * them and records the request so the renderer does not rebuild it every frame (see {@link #builtFor(boolean)}).
     */
    RtSlangPathPass(VkDevice device, SlangRuntime runtime, boolean requestedGbuffer) {
        this(device, runtime, requestedGbuffer, false);
    }

    /** {@code rayMode}: Ray Tracing mode instead of Path Tracing; see {@link #rayMode}. */
    RtSlangPathPass(VkDevice device, SlangRuntime runtime, boolean requestedGbuffer, boolean rayMode) {
        this(createBackend(device, runtime), requestedGbuffer, rayMode);
    }

    RtSlangPathPass(PathTraceBackend backend, boolean requestedGbuffer) {
        this(backend, requestedGbuffer, false);
    }

    RtSlangPathPass(PathTraceBackend backend, boolean requestedGbuffer, boolean rayMode) {
        this.requestedGbuffer = requestedGbuffer;
        this.rayMode = rayMode;
        this.backend = Objects.requireNonNull(backend);
        LOGGER.info("[VKB-RT] {} backend={} spatial={} gbuffer={}",
            rayMode ? "ray effects" : "path tracing", backend.getClass().getSimpleName(),
            backend.spatialAvailable(), requestedGbuffer ? "dlss_rr guides" : "off");
    }

    private static PathTraceBackend createBackend(VkDevice device, SlangRuntime runtime) {
        if (runtime == null) throw new IllegalArgumentException("path trace needs a SlangRuntime");
        return RtConfig.SLANG_PATH_TRACE_CPP ? new NativePathTraceBackend(runtime)
            : new ReferencePathTraceBackend(device, runtime);
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
        return rayMode ? RtMode.RAY_TRACED : RtMode.PATH_TRACED;
    }

    @Override
    public String describe(RtFrameParams p) {
        String restir = diActive() ? backend.reservoirState() : "off";
        if (!restir.equals("off") && spatialRequested()) restir += "+spatial";
        return (rayMode ? "ray_slang(" : "path_slang(") + "bounces=" + p.bounces + ",spp=" + p.spp + ",dist=" + p.rayDistance
            + (!rayMode && RtConfig.SLANG_RADIANCE_CASCADES ? ",rc=finite-window-v2,rc_axis=" + RtConfig.SLANG_RC_BASE_AXIS : "")
            + (accumulateRequested() ? ",accumulate" : "") + ",restir_di=" + restir
            + ",frames=" + (accumFrames + 1) + ",denoiser=" + (requestedGbuffer ? "dlss_rr" : "off") + ")";
    }

    @Override
    public void prepare(VkCommandBuffer cmd, RtMemory mem, int width, int height) {
        backend.prepare(width, height);
        if (lastWidth != width || lastHeight != height) {
            diRequests.layoutFor(width, height);
            lastWidth = width;
            lastHeight = height;
            historyWritten = false;
            accumFrames = 0;
            forceReset = true;
        }
    }

    @Override
    public void configure(RtFrameParams params, boolean denoising) {
        params.mode = RtConfig.effectiveMode();
        if (rayMode) {
            // The ray profile: the surface the camera sees plus one indirect one, one sample, no adaptive governor. What
            // the deleted ray pass drew as separate rays (sun shadows through NEE, reflections and one-bounce GI through
            // the BSDF sample, emissive blocks through their own NEE) falls out of that one path.
            params.bounces = RAY_MODE_MAX_DEPTH;
            params.spp = RAY_MODE_SPP;
        } else {
            params.bounces = RtAdaptiveQuality.bounces(RtSettings.spp(), RtSettings.bounces());
            params.spp = RtAdaptiveQuality.spp(RtSettings.spp());
        }
        // The ray mode's far plane is its world radius ("RT Distance"), exactly the extent the scene was built for;
        // the path mode keeps its own longest-ray slider.
        params.rayDistance = rayMode ? Math.max(32, params.rtRadiusXz * 16) : RtSettings.pathMaxDistance();
        params.giDistance = RtSettings.giDistance();
        params.emissiveDistance = RtSettings.emissiveDistance();
        params.aoRadius = RtSettings.aoRadius();
        params.flags |= RtFrameParams.FLAG_EMISSIVE;

        // The mean is reprojected per pixel, so camera motion no longer invalidates the whole history; only a change
        // the reprojection cannot explain (new lights, new geometry, new settings) restarts it.
        boolean lightingChanged = hasLighting
            && (differs(params.key.dir, lastSunDirection)
                || differs(params.key.color, lastSunColor)
                || differs(params.skyColor, lastSkyColor));
        // spp is deliberately not in the hash: the adaptive governor moves it several times a minute, and a running mean
        // of frames traced at different sample counts is still a mean of unbiased estimators - each frame carries the
        // pixel's own average, only with a different variance. Restarting the history to equalise the weights would
        // throw away the convergence the accumulation exists for.
        int settingsHash = Objects.hash(params.bounces, params.rayDistance, accumulateRequested(), params.mode,
            RtSettings.pathPreset, RtSettings.preset, rayEffectFlags(), diffuseBounceDistance(),
            specularBounceDistance(), emitterBounceDistance(params), emitterCandidates());
        if (forceReset || lightingChanged || params.sceneChanged || settingsHash != lastSettingsHash
            || !accumulateRequested()) {
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
            diRequests.reset();
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
    public void bind(RtFrameBindings.Views common) {
        views = common;
    }

    @Override
    public boolean usesGbuffer() {
        return requestedGbuffer;
    }

    @Override
    public boolean builtFor(boolean gbuffer) {
        return gbuffer == requestedGbuffer;
    }

    @Override
    public void resetHistory() {
        forceReset = true;
        historyWritten = false;
        diRequests.reset();
    }

    @Override
    public void dispatch(VkCommandBuffer cmd, RtFrameParams params, int width, int height) {
        if (views == null) throw new IllegalStateException("path_slang dispatched before bind");
        fillFrame(params, width, height);
        // The emitter list of the legacy path, bound as a second descriptor range: switching block emitters off is an A/B
        // on the shader's NEE branch only, the list keeps being rebuilt either way.
        // "RT Block Light" off drops the emitter list from the trace entirely: no emitter NEE, no reservoir branch.
        int emitterCount = RtConfig.SLANG_BLOCK_EMITTERS && (!rayMode || RtSettings.emissive())
            ? Math.max(0, params.emitterCount) : 0;
        RtPathTraceSlangPass.Emitters emitters = new RtPathTraceSlangPass.Emitters(
            emitterCount > 0 ? params.emitterHandle : 0L, params.emitterOffset, emitterCount);
        int diFlags = emitterCount > 0 && diActive() ? DI_FLAG_ENABLED
            | (params.cameraStatic ? DI_FLAG_STATIC : 0) | (params.flipY ? DI_FLAG_FLIP_Y : 0) : 0;
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
        // The renderer hands over the denoiser's guide images only when it built this pass for a G-buffer; the push
        // flag and the bindings then agree, which is what the record validates. NRD additionally reads a per-pixel vec4
        // motion buffer, bound only for that layout, so its push bit follows the buffer the frame actually carries.
        RtPathTraceSlangPass.Guides guides = RtPathTraceSlangPass.Guides.of(views.gbuffer());
        int optionFlags = (guides != null ? RtPathTraceSlangPass.OPTION_GBUFFER : 0)
            | (views.motion() != 0L && guides != null ? RtPathTraceSlangPass.OPTION_NRD_MOTION : 0)
            | rayEffectFlags()
            | pixelShadowFlags();
        RtPathTraceSlangPass.Dispatch dispatch = new RtPathTraceSlangPass.Dispatch(Math.max(1, params.bounces),
            8, sampleIndex++, 0, optionFlags, 0.01f, 0.1f, Math.max(1f, params.rayDistance), MAX_RADIANCE, 0.5f,
            emitterCount, emitterCount > 0 ? RtConfig.SLANG_EMITTER_PROBABILITY : 0f, params.emitterCamRelX,
            params.emitterCamRelY, params.emitterCamRelZ, emitterCandidates(),
            params.cameraDeltaX, params.cameraDeltaY, params.cameraDeltaZ, diFlags, 0,
            capturedEntities.atlasSize(), capturedEntities.selfAvatarTriStart(), capturedEntities.selfAvatarTriCount(),
            RtSettings.autoNormalsOn() ? RtSettings.autoNormalStrengthPercent() / 100f : 0f,
            emitterBounceDistance(params), diffuseBounceDistance(), specularBounceDistance());
        boolean nativeScene = views.table() != null && views.table().sceneOwned;
        int flags = (historyWritten ? 0 : BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY)
            | (accumulateRequested() ? BedrayCoreLayouts.PATH_TRACE_FRAME_ACCUMULATE : 0)
            | (params.cameraStatic ? BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_STATIC : 0)
            | (params.flipY ? BedrayCoreLayouts.PATH_TRACE_FRAME_FLIP_Y : 0)
            | (spatialRequested() ? BedrayCoreLayouts.PATH_TRACE_FRAME_SPATIAL : 0)
            | (nativeScene ? BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_SCENE : 0)
            | (diRequests.resetPending() ? BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_DI : 0)
            // The native backend builds its own FrameConstants, so the eye's medium travels as a host bit and the core
            // puts it in the frame word the shader reads; the Slang backend already wrote that word itself.
            | (params.cameraInWater ? BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_IN_WATER : 0)
            // The player's water switches travel the same way: the core has no settings screen to read, and a frame
            // that says nothing about them traces water as a flat sheet with unbent light.
            | ((params.flags & RtFrameParams.FLAG_WATER_REALISTIC) != 0
                ? BedrayCoreLayouts.PATH_TRACE_FRAME_WATER_REALISTIC : 0)
            | ((params.flags & RtFrameParams.FLAG_CAUSTICS) != 0
                ? BedrayCoreLayouts.PATH_TRACE_FRAME_WATER_CAUSTICS : 0);
        backend.record(new PathTraceBackend.Frame(cmd, views, frame, dispatch, emitters, capturedEntities,
            new RtPathTraceSlangPass.BufferView(params.statsBuffer, params.statsOffset,
                RtFrameStatsSlangPass.COUNTER_BYTES), flags, accumFrames));
        historyWritten = true;
        diRequests.afterDispatch(diFlags != 0);
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
        RtSlangFrameData.fill(frame, p, width, height, sampleIndex, accumFrames);
    }

    @Override public void discardRecording(long commandBuffer) {
        if (backend.discardRecording(commandBuffer)) resetHistory();
    }
    @Override public void releaseRecording(long commandBuffer) { backend.releaseRecording(commandBuffer); }
    @Override public void releaseRecordingsAfterHostDrain() { backend.releaseRecordingsAfterHostDrain(); }
    @Override public void destroy() {
        if (destroyed) return;
        backend.close();
        destroyed = true;
    }
}
