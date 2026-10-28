package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreException;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Host adapter for the additive native path-trace pass
 * ({@code bedray_core_configure_path_trace} / {@code bedray_core_render_path_trace}).
 *
 * <p>The pass itself owns the radiance, motion, accumulation and history-depth
 * images, the per-recording {@code FrameConstants} upload and the one-element
 * dummies. This class only packs the host's frame state into
 * {@code BedrayPathTraceFrameDesc} and hands the borrowed scene resources to the
 * core. It never submits, waits or creates a Vulkan object.
 *
 * <p>Lifetime: every frame recorded here pins the pass's own resources for that
 * command buffer. {@code SlangRuntime.releaseRecording(commandBuffer)} reaches
 * {@code bedray_core_release_recording}, which releases those pins together with
 * the core's descriptor sets; the host must call it only after proven GPU
 * completion or proven discard of the recording.
 */
final class BedrayPathTraceNative implements AutoCloseable {
    /**
     * One frame's host state. {@code dispatch} carries the exact
     * {@code PathTracePush} fields the Java path would have written, so the two
     * paths cannot drift in their path limits or ReSTIR state.
     */
    record FrameInput(long commandBuffer, RtPipeline.Views views, RtPathTraceSlangPass.Frame frame,
                      RtPathTraceSlangPass.Dispatch dispatch, RtPathTraceSlangPass.Emitters emitters,
                      RtPathTraceSlangPass.Reservoirs reservoirs, RtPathTraceSlangPass.Entities entities,
                      int flags, int accumFrames, int displayFlags, float maxRadiance,
                      float reprojectionTolerance) {
    }

    private final SlangRuntime runtime;
    private final BedrayCoreNative library;
    private final long core;
    private final long programTrace;
    /** {@code path_trace_scene}, or 0 when the jar did not compile it. */
    private final long programTraceScene;
    private final long programSpatial;
    private final long programDisplay;
    private final long sampler;
    private boolean configured;
    private boolean closed;

    BedrayPathTraceNative(SlangRuntime runtime, long programTrace, long programTraceScene, long programSpatial,
                          long programDisplay, long sampler) {
        if (runtime == null || programTrace == 0L || programDisplay == 0L) {
            throw new IllegalArgumentException("native path trace needs a runtime, a trace and a display program");
        }
        if (sampler == 0L) {
            throw new IllegalArgumentException("native path trace needs the atlas sampler");
        }
        this.runtime = runtime;
        this.library = runtime.library();
        this.core = runtime.core();
        this.programTrace = programTrace;
        this.programTraceScene = programTraceScene;
        this.programSpatial = programSpatial;
        this.programDisplay = programDisplay;
        this.sampler = sampler;
    }

    /**
     * Configures the pass on first use. The budgets mirror the Java path's
     * owners: an eighth of device memory for images (capped at 1 GiB) and a
     * quarter for the constant-buffer pool.
     */
    private void configure(RtPathTraceSlangPass.Frame frame) {
        long deviceBytes = runtime.capabilities().deviceMemoryBytes();
        long imageBudget = deviceBytes > 0 ? Math.min(1L << 30, deviceBytes / 8) : 512L << 20;
        long bufferBudget = deviceBytes > 0 ? Math.min(1L << 30, deviceBytes / 4) : 16L << 20;
        library.configurePathTrace(core, programTrace, programTraceScene, programSpatial, programDisplay,
            imageBudget, bufferBudget, frame.width, frame.height);
        configured = true;
    }

    void render(FrameInput input) {
        if (closed) {
            throw new IllegalStateException("native path trace is closed");
        }
        RtPathTraceSlangPass.Frame frame = input.frame();
        if (frame == null || frame.width <= 0 || frame.height <= 0) {
            throw new IllegalArgumentException("native path trace needs a positive frame extent");
        }
        if (input.views() == null || input.views().table() == null) {
            throw new IllegalArgumentException("native path trace needs the RtWorld section table");
        }
        if ((input.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_SCENE) != 0 && programTraceScene == 0L) {
            throw new IllegalStateException("the native scene table needs Slang pass path_trace_scene,"
                + " which this jar did not compile");
        }
        if (!configured) {
            configure(frame);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(BedrayCoreLayouts.PT_FRAME_DESC);
            fill(desc, input);
            library.renderPathTrace(core, desc);
        }
    }

    private void fill(MemorySegment desc, FrameInput input) {
        RtPathTraceSlangPass.Frame frame = input.frame();
        RtPathTraceSlangPass.Dispatch dispatch = input.dispatch();
        RtPipeline.Views views = input.views();

        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_ABI_VERSION, BedrayCoreLayouts.ABI_VERSION);
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_FLAGS, input.flags());
        desc.set(JAVA_LONG, BedrayCoreLayouts.PTFD_COMMAND_BUFFER, input.commandBuffer());
        desc.set(JAVA_LONG, BedrayCoreLayouts.PTFD_OUTPUT_VIEW, views.output());
        desc.set(JAVA_LONG, BedrayCoreLayouts.PTFD_DEPTH_VIEW, views.depth());

        long scene = BedrayCoreLayouts.PTFD_SCENE;
        desc.set(JAVA_LONG, scene + BedrayCoreLayouts.PTS_TLAS, views.tlas());
        putBuffer(desc, scene + BedrayCoreLayouts.PTS_SECTIONS,
            views.table().handle, 0L, views.table().size);
        desc.set(JAVA_LONG, scene + BedrayCoreLayouts.PTS_ALBEDO_VIEW, views.atlas());
        desc.set(JAVA_LONG, scene + BedrayCoreLayouts.PTS_MATERIAL_VIEW, views.material());
        desc.set(JAVA_LONG, scene + BedrayCoreLayouts.PTS_NORMAL_VIEW, views.normal());
        desc.set(JAVA_LONG, scene + BedrayCoreLayouts.PTS_ATLAS_SAMPLER, sampler);
        // A zero block-material range selects the pass's own zeroed dummy, which
        // is exactly what the Java path binds: the trace never indexes past
        // pc.materialCount, and the live path does not build that table.
        putBuffer(desc, scene + BedrayCoreLayouts.PTS_BLOCK_MATERIALS, 0L, 0L, 0L);

        RtPathTraceSlangPass.Emitters emitters = input.emitters();
        long emitterBuffer = emitters == null ? 0L : emitters.buffer();
        long emitterBytes = emitterBuffer == 0L ? 0L
            : Math.max(1, emitters.count()) * (long) RtPathTraceSlangPass.EMITTER_BYTES;
        putBuffer(desc, scene + BedrayCoreLayouts.PTS_EMITTERS, emitterBuffer,
            emitters == null ? 0L : emitters.offset(), emitterBytes);

        RtPathTraceSlangPass.Reservoirs reservoirs = input.reservoirs();
        putBuffer(desc, scene + BedrayCoreLayouts.PTS_RESERVOIRS,
            reservoirs == null ? 0L : reservoirs.buffer(), 0L,
            reservoirs == null ? 0L : reservoirs.bytes());

        RtPathTraceSlangPass.Entities entities = input.entities() == null
            ? RtPathTraceSlangPass.Entities.NONE
            : input.entities();
        putRange(desc, scene + BedrayCoreLayouts.PTS_ENTITY_POSITIONS, entities.positions());
        putRange(desc, scene + BedrayCoreLayouts.PTS_ENTITY_INDICES, entities.indices());
        putRange(desc, scene + BedrayCoreLayouts.PTS_ENTITY_ATTRS, entities.attrs());
        putRange(desc, scene + BedrayCoreLayouts.PTS_ENTITY_RECTS, entities.rects());
        putRange(desc, scene + BedrayCoreLayouts.PTS_ENTITY_TEXELS, entities.texels());
        putRange(desc, scene + BedrayCoreLayouts.PTS_ENTITY_PREVIOUS, entities.previous());

        long camera = BedrayCoreLayouts.PTFD_CAMERA;
        putMatrix(desc, camera + BedrayCoreLayouts.PTC_VIEW, frame.view);
        putMatrix(desc, camera + BedrayCoreLayouts.PTC_PROJECTION, frame.projection);
        putMatrix(desc, camera + BedrayCoreLayouts.PTC_VIEW_PROJECTION, frame.viewProjection);
        putMatrix(desc, camera + BedrayCoreLayouts.PTC_INV_VIEW_PROJECTION, frame.invViewProjection);
        putMatrix(desc, camera + BedrayCoreLayouts.PTC_PREV_VIEW_PROJECTION, frame.prevViewProjection);
        putVector(desc, camera + BedrayCoreLayouts.PTC_ORIGIN, frame.origin);
        putVector(desc, camera + BedrayCoreLayouts.PTC_FORWARD, frame.forward);
        putVector(desc, camera + BedrayCoreLayouts.PTC_RIGHT, frame.right);
        putVector(desc, camera + BedrayCoreLayouts.PTC_UP, frame.up);
        putVector(desc, camera + BedrayCoreLayouts.PTC_JITTER, frame.jitter);
        desc.set(JAVA_INT, camera + BedrayCoreLayouts.PTC_WIDTH, frame.width);
        desc.set(JAVA_INT, camera + BedrayCoreLayouts.PTC_HEIGHT, frame.height);
        desc.set(JAVA_INT, camera + BedrayCoreLayouts.PTC_DISPLAY_WIDTH, frame.displayWidth);
        desc.set(JAVA_INT, camera + BedrayCoreLayouts.PTC_DISPLAY_HEIGHT, frame.displayHeight);

        long lighting = BedrayCoreLayouts.PTFD_LIGHTING;
        putVector(desc, lighting + BedrayCoreLayouts.PTL_SUN_DIRECTION, frame.sunDirection);
        putVector(desc, lighting + BedrayCoreLayouts.PTL_SUN_COLOR, frame.sunColor);
        putVector(desc, lighting + BedrayCoreLayouts.PTL_MOON_DIRECTION, frame.moonDirection);
        putVector(desc, lighting + BedrayCoreLayouts.PTL_MOON_COLOR, frame.moonColor);
        desc.set(JAVA_FLOAT, lighting + BedrayCoreLayouts.PTL_EXPOSURE, frame.exposure.x);
        desc.set(JAVA_FLOAT, lighting + BedrayCoreLayouts.PTL_MAX_RADIANCE, input.maxRadiance());
        desc.set(JAVA_FLOAT, lighting + BedrayCoreLayouts.PTL_REPROJECTION_TOLERANCE,
            input.reprojectionTolerance());

        long atmosphere = BedrayCoreLayouts.PTFD_ATMOSPHERE;
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_ZENITH_COLOR, frame.zenithColor);
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_HORIZON_COLOR, frame.horizonColor);
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_FOG_COLOR, frame.fogColor);
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_FOG, frame.fog);
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_CLOUD, frame.cloud);

        long path = BedrayCoreLayouts.PTFD_PATH;
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_MAX_DEPTH, dispatch.maxDepth());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_MAX_SURFACE_STEPS, dispatch.maxSurfaceSteps());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_SAMPLE_INDEX, dispatch.sampleIndex());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_MATERIAL_COUNT, dispatch.materialCount());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_FLAGS, dispatch.flags());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_RAY_BIAS, dispatch.rayBias());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_ALPHA_CUTOFF, dispatch.alphaCutoff());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_MAX_DISTANCE, dispatch.maxDistance());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_MAX_RADIANCE, dispatch.maxRadiance());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_SUN_PROBABILITY, dispatch.sunProbability());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_EMITTER_COUNT, dispatch.emitterCount());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_EMITTER_PROBABILITY, dispatch.emitterProbability());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_EMITTER_CAMERA_RELATIVE, dispatch.emitterCamRelX());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_EMITTER_CAMERA_RELATIVE + 4, dispatch.emitterCamRelY());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_EMITTER_CAMERA_RELATIVE + 8, dispatch.emitterCamRelZ());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_EMITTER_CAMERA_RELATIVE + 12, dispatch.emitterCandidates());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_DI_CAMERA_DELTA, dispatch.diCamDeltaX());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_DI_CAMERA_DELTA + 4, dispatch.diCamDeltaY());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_DI_CAMERA_DELTA + 8, dispatch.diCamDeltaZ());
        desc.set(JAVA_FLOAT, path + BedrayCoreLayouts.PTP_DI_CAMERA_DELTA + 12, 0f);
        // The pass forwards diState verbatim: the host owns the reservoir ring,
        // its parity and its reset, so a host-side fill can never disagree with
        // a parity the pass advanced on its own.
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_DI_STATE, dispatch.diFlags());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_DI_STATE + 4, dispatch.diParity());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_DI_STATE + 8, 0);
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_DI_STATE + 12, 0);
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_ENTITY_STATE, entities.atlasSize());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_ENTITY_STATE + 4, entities.selfAvatarTriStart());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_ENTITY_STATE + 8, entities.selfAvatarTriCount());
        desc.set(JAVA_INT, path + BedrayCoreLayouts.PTP_ENTITY_STATE + 12, 0);

        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_FRAME_INDEX, frame.frameIndex);
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_SAMPLES_PER_FRAME, frame.samplesPerFrame);
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_ACCUM_FRAMES, input.accumFrames());
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_DISPLAY_FLAGS, input.displayFlags());
        // exposure.values.w is a delta time in the frame contract; the Java path
        // leaves it zero and keeps the absolute frame time in origin.w.
        desc.set(JAVA_FLOAT, BedrayCoreLayouts.PTFD_FRAME_TIME, 0f);
    }

    private static void putBuffer(MemorySegment at, long base, long handle, long offset, long bytes) {
        at.set(JAVA_LONG, base + BedrayCoreLayouts.PTB_HANDLE, handle);
        at.set(JAVA_LONG, base + BedrayCoreLayouts.PTB_OFFSET, offset);
        at.set(JAVA_LONG, base + BedrayCoreLayouts.PTB_BYTES, bytes);
    }

    private static void putRange(MemorySegment at, long base, RtPathTraceSlangPass.BufferView view) {
        long handle = view == null || view.buffer() == 0L ? 0L : view.buffer();
        at.set(JAVA_LONG, base + BedrayCoreLayouts.PTR_HANDLE, handle);
        at.set(JAVA_LONG, base + BedrayCoreLayouts.PTR_OFFSET, handle == 0L ? 0L : view.offset());
        at.set(JAVA_LONG, base + BedrayCoreLayouts.PTR_BYTES, handle == 0L ? 0L : view.bytes());
    }

    private static void putVector(MemorySegment at, long offset, Vector4f value) {
        at.set(JAVA_FLOAT, offset, value.x);
        at.set(JAVA_FLOAT, offset + 4, value.y);
        at.set(JAVA_FLOAT, offset + 8, value.z);
        at.set(JAVA_FLOAT, offset + 12, value.w);
    }

    /** JOML and Slang both store float4x4 column-major. */
    private static void putMatrix(MemorySegment at, long offset, Matrix4f value) {
        for (int i = 0; i < 16; ++i) {
            at.set(JAVA_FLOAT, offset + i * 4L, value.get(i / 4, i % 4));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (configured) {
            try {
                library.destroyPathTrace(core);
            } catch (BedrayCoreException e) {
                throw new IllegalStateException("native path-trace shutdown refused: " + e.getMessage(), e);
            }
            configured = false;
        }
        closed = true;
    }
}
