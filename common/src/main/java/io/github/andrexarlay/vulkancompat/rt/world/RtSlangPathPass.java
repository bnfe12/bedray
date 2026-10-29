package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Path Tracing mode on the AOT Slang programs: {@code passes/path_trace.slang} traces one path per pixel into linear
 * HDR radiance ({@link RtPathTraceSlangPass}), then {@code passes/path_display.slang} folds it into a running mean and
 * writes exposure + tone curve into the renderer's RGBA8 {@code rt_output}. Depth goes straight into {@code rt_depth},
 * so the composite, bloom, particles and upscalers downstream are unchanged.
 *
 * <p>The pass fills the seven DLSS-RR guide images when the host binds them ({@code PATH_TRACE_OPTION_GBUFFER}); NRD
 * additionally needs the world-space motion of the primary hit, which this pass does not write, so the renderer only
 * accepts {@code DLSS_RR} here. Not done yet, compared with the GLSL {@link RtPathTracerPass}: no auto exposure (the
 * GLSL shader feeds the luminance statistics; exposure is fixed at 1), a two-colour sky instead of
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

    /** Session gate for DI support; allocation and retirement belong to the selected backend. */
    static boolean diRequested() {
        return RtConfig.SLANG_BLOCK_EMITTERS && RtConfig.SLANG_RESTIR_DI;
    }

    /** True when this frame should read and write the reservoirs: allocated above, and the player left reuse switched on. */
    static boolean diActive() {
        return diRequested() && RtSettings.pathRestirDi();
    }

    /** The same decision without the frame's own gates: what the mode log reports and what the dispatch refines. */
    boolean spatialRequested() {
        return backend.spatialAvailable() && diActive() && RtSettings.pathRestirSpatial();
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

    private final boolean requestedGbuffer;
    private final PathTraceBackend backend;
    private final RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
    private RtPipeline.Views views;
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
        this(createBackend(device, runtime), requestedGbuffer);
    }

    RtSlangPathPass(PathTraceBackend backend, boolean requestedGbuffer) {
        this.requestedGbuffer = requestedGbuffer;
        this.backend = Objects.requireNonNull(backend);
        LOGGER.info("[VKB-RT] path tracing backend={} spatial={} gbuffer={}",
            backend.getClass().getSimpleName(), backend.spatialAvailable(),
            requestedGbuffer ? "dlss_rr guides" : "off");
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
        return RtMode.PATH_TRACED;
    }

    @Override
    public String describe(RtFrameParams p) {
        String restir = diActive() ? backend.reservoirState() : "off";
        if (!restir.equals("off") && spatialRequested()) restir += "+spatial";
        return "path_slang(bounces=" + p.bounces + ",spp=1,dist=" + p.rayDistance
            + (RtSettings.accumulate() ? ",accumulate" : "") + ",restir_di=" + restir
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
    public void bind(RtPipeline.Views common) {
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
        int emitterCount = RtConfig.SLANG_BLOCK_EMITTERS ? Math.max(0, params.emitterCount) : 0;
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
        // flag and the bindings then agree, which is what the record validates.
        RtPathTraceSlangPass.Guides guides = RtPathTraceSlangPass.Guides.of(views.gbuffer());
        int optionFlags = guides != null ? RtPathTraceSlangPass.OPTION_GBUFFER : 0;
        RtPathTraceSlangPass.Dispatch dispatch = new RtPathTraceSlangPass.Dispatch(Math.max(1, params.bounces),
            8, sampleIndex++, 0, optionFlags, 0.01f, 0.1f, Math.max(1f, params.rayDistance), MAX_RADIANCE, 0.5f,
            emitterCount, emitterCount > 0 ? RtConfig.SLANG_EMITTER_PROBABILITY : 0f, params.emitterCamRelX,
            params.emitterCamRelY, params.emitterCamRelZ, RtConfig.SLANG_EMITTER_CANDIDATES,
            params.cameraDeltaX, params.cameraDeltaY, params.cameraDeltaZ, diFlags, 0,
            capturedEntities.atlasSize(), capturedEntities.selfAvatarTriStart(), capturedEntities.selfAvatarTriCount(),
            RtSettings.autoNormalsOn() ? RtSettings.autoNormalStrengthPercent() / 100f : 0f);
        boolean nativeScene = views.table() != null && views.table().sceneOwned;
        int flags = (historyWritten ? 0 : BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY)
            | (RtSettings.accumulate() ? BedrayCoreLayouts.PATH_TRACE_FRAME_ACCUMULATE : 0)
            | (params.cameraStatic ? BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_STATIC : 0)
            | (params.flipY ? BedrayCoreLayouts.PATH_TRACE_FRAME_FLIP_Y : 0)
            | (spatialRequested() ? BedrayCoreLayouts.PATH_TRACE_FRAME_SPATIAL : 0)
            | (nativeScene ? BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_SCENE : 0)
            | (diRequests.resetPending() ? BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_DI : 0);
        backend.record(new PathTraceBackend.Frame(cmd, views, frame, dispatch, emitters, capturedEntities,
            flags, accumFrames));
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
