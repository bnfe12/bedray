package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreException;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreNative;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayRadianceCascadesNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Host adapter for the additive native path-trace pass
 * ({@code bedray_core_configure_path_trace} / {@code bedray_core_render_path_trace} /
 * {@code bedray_core_record_path_trace_stats}).
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
    record FrameInput(long commandBuffer, RtFrameBindings.Views views, RtPathTraceSlangPass.Frame frame,
                      RtPathTraceSlangPass.Dispatch dispatch, RtPathTraceSlangPass.Emitters emitters,
                      RtPathTraceSlangPass.Reservoirs reservoirs, RtPathTraceSlangPass.Entities entities,
                      RtPathTraceSlangPass.BufferView stats,
                      int flags, int accumFrames, int displayFlags, float maxRadiance,
                      float reprojectionTolerance) {
    }

    private final SlangRuntime runtime;
    private final BedrayCoreNative library;
    private final long core;
    private final long programTraceScene;
    private final long programSpatial;
    private final long programDisplay;
    /** {@code frame_stats}, or 0 when this jar has no AOT counter artifact for the core to run. */
    private final long programStats;
    private final long sampler;
    /**
     * True only when this jar handed the core a counter program and the core both advertises {@code FRAME_STATS} and
     * exports the call. False means the core writes no counters, which the host reports as unmeasured, not as zero.
     */
    private final boolean frameStats;
    private final BedrayRadianceCascadesNative rc;
    /** {@code restir_pt_spatial(_scene)}, lent to a core that owns the PT ring; 0 leaves that table without the stage. */
    private final long programPtSpatialScene;
    private boolean configured;
    private boolean closed;

    BedrayPathTraceNative(SlangRuntime runtime, long programTraceScene, long programSpatial,
                          long programDisplay, long programStats, long sampler, long rcBuilder,
                          long programPtSpatialScene) {
        this.programPtSpatialScene = programPtSpatialScene;
        if (runtime == null || programTraceScene == 0L || programDisplay == 0L) {
            throw new IllegalArgumentException("native path trace needs a runtime, a trace and a display program");
        }
        if (sampler == 0L) {
            throw new IllegalArgumentException("native path trace needs the atlas sampler");
        }
        this.runtime = runtime;
        this.library = runtime.library();
        this.core = runtime.core();
        this.programTraceScene = programTraceScene;
        this.programSpatial = programSpatial;
        this.programDisplay = programDisplay;
        this.programStats = programStats;
        this.sampler = sampler;
        this.frameStats = programStats != 0L && library.hasFrameStats();
        this.rc = rcBuilder == 0 ? null : new BedrayRadianceCascadesNative(library,core,rcBuilder,RtConfig.SLANG_RC_BASE_AXIS);
    }

    /**
     * Configures the pass on first use. The budgets mirror the Java path's
     * owners: an eighth of device memory for images (capped at 1 GiB) and a
     * quarter for the buffers, which hold both reservoir rings (1.36 GiB at
     * 2560x1370 with ReSTIR PT, so no 1 GiB cap here).
     */
    private void configure(RtPathTraceSlangPass.Frame frame) {
        long deviceBytes = runtime.capabilities().deviceMemoryBytes();
        long imageBudget = deviceBytes > 0 ? Math.min(1L << 30, deviceBytes / 8) : 512L << 20;
        long bufferBudget = bufferBudget(deviceBytes);
        library.configurePathTrace(core, programTraceScene, programSpatial, programDisplay,
            programStats, imageBudget, bufferBudget, frame.width, frame.height);
        configured = true;
        if (nativePt() && programPtSpatialScene != 0L)
            library.configurePathTracePt(core, programPtSpatialScene);
        if (rc != null) rc.configure();
    }

    /** True when the core owns the ReSTIR PT ring, so the host sends NATIVE_PT and leaves history and parity to it. */
    boolean displaySkyAvailable() { return library.hasStatsSlot(); }

    boolean nativePt() {
        return library.hasNativePt();
    }

    /** True when the core lends a spatial stage for the table this frame traces. */
    boolean ptSpatialAvailable() {
        return nativePt() && programPtSpatialScene != 0L;
    }

    static long bufferBudget(long deviceMemoryBytes) {
        return deviceMemoryBytes > 0 ? deviceMemoryBytes / 4 : 512L << 20;
    }

    void render(FrameInput input) {
        if (closed) {
            throw new IllegalStateException("native path trace is closed");
        }
        if ((input.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_SCENE) == 0)
            throw new IllegalStateException("path trace requires the native scene");
        RtPathTraceSlangPass.Frame frame = input.frame();
        if (frame == null || frame.width <= 0 || frame.height <= 0) {
            throw new IllegalArgumentException("native path trace needs a positive frame extent");
        }
        if (input.views() == null || input.views().table() == null) {
            throw new IllegalArgumentException("native path trace needs the RtWorld section table");
        }
        var guides = RtPathTraceSlangPass.Guides.of(input.views().gbuffer());
        boolean wantsGuides = (input.dispatch().flags() & RtPathTraceSlangPass.OPTION_GBUFFER) != 0;
        boolean completeGuides = guides != null && guides.complete();
        if (wantsGuides != completeGuides) {
            throw new IllegalArgumentException(wantsGuides
                ? "the push asks for denoiser guides but no complete G-buffer is bound"
                : "denoiser guides are bound but the push does not enable them");
        }
        if (wantsGuides && !library.hasNativeGuideOutputs()) {
            throw new IllegalStateException("native denoiser guides need a core with GUIDE_OUTPUTS");
        }
        boolean nrdMotion = (input.dispatch().flags() & RtPathTraceSlangPass.OPTION_NRD_MOTION) != 0;
        boolean worldMotion = (input.dispatch().flags() & RtPathTraceSlangPass.OPTION_WORLD_MOTION) != 0;
        boolean wantsMotion = nrdMotion || worldMotion;
        if (wantsMotion != (input.views().motion() != 0L) || (nrdMotion && !completeGuides))
            throw new IllegalArgumentException("motion option and buffer must agree; NRD also needs guide outputs");
        if (worldMotion && !library.hasWorldMotion())
            throw new IllegalStateException("world motion needs a matching bedray_core with WORLD_MOTION");
        if (wantsMotion && !library.hasNativeMotion())
            throw new IllegalStateException("native NRD needs a matching core with NRD_MOTION");
        if (!configured) {
            configure(frame);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(BedrayCoreLayouts.PT_FRAME_DESC);
            fill(desc, input);
            if (library.hasStatsSlot()) {
                // One call: the trace also adds its F3 reject split into the frame's whole StatsBuf slot at 37.
                MemorySegment targets = null, motion = null, slot = null;
                if (wantsGuides) {
                    targets = arena.allocate(BedrayCoreLayouts.PT_GUIDES_DESC);
                    fillGuides(targets, guides);
                }
                if (wantsMotion) {
                    motion = arena.allocate(BedrayCoreLayouts.PT_BUFFER);
                    fillMotion(motion, input.views().motion(), frame.width, frame.height, worldMotion);
                }
                RtPathTraceSlangPass.BufferView stats = input.stats();
                if (stats != null && stats.buffer() != 0L && stats.bytes() >= RtStatsLayout.SLOT_STRIDE) {
                    slot = arena.allocate(BedrayCoreLayouts.PT_BUFFER);
                    fillStatsSlot(slot, stats);
                }
                library.renderPathTraceExt(core, desc, targets, motion, slot);
            } else if (wantsGuides) {
                MemorySegment targets = arena.allocate(BedrayCoreLayouts.PT_GUIDES_DESC);
                fillGuides(targets, guides);
                if (wantsMotion) {
                    var motion = arena.allocate(BedrayCoreLayouts.PT_BUFFER);
                    fillMotion(motion, input.views().motion(), frame.width, frame.height);
                    library.renderPathTraceMotion(core, desc, targets, motion);
                } else {
                    library.renderPathTraceGuides(core, desc, targets);
                }
            } else {
                library.renderPathTrace(core, desc);
            }
            if (frameStats) {
                RtPathTraceSlangPass.BufferView stats = input.stats();
                if (stats == null || stats.buffer() == 0L || stats.bytes() < RtFrameStatsSlangPass.COUNTER_BYTES) {
                    throw new IllegalArgumentException("the native counter pass writes the head of this frame's slot,"
                        + " and the host handed it " + stats);
                }
                // The frame hands over the whole slot; frame_stats binds exactly its sixteen-byte head.
                library.recordPathTraceStats(core, input.commandBuffer(), stats.buffer(),
                    stats.offset(), RtFrameStatsSlangPass.COUNTER_BYTES, frame.frameIndex);
            }
        }
    }

    private void fill(MemorySegment desc, FrameInput input) {
        RtPathTraceSlangPass.Frame frame = input.frame();
        RtPathTraceSlangPass.Dispatch dispatch = input.dispatch();
        RtFrameBindings.Views views = input.views();

        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_ABI_VERSION, BedrayCoreLayouts.ABI_VERSION);
        // A core that predates the medium bit rejects the whole frame over an unknown flag, so the verdict is dropped
        // rather than negotiated: the trace then starts in air, exactly as it did before the bit existed.
        int flags = input.flags();
        if ((flags & BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_IN_WATER) != 0 && !library.hasCameraInWaterFlag()) {
            flags &= ~BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_IN_WATER;
        }
        if (!library.hasWaterModelFlags()) {
            flags &= ~(BedrayCoreLayouts.PATH_TRACE_FRAME_WATER_REALISTIC
                | BedrayCoreLayouts.PATH_TRACE_FRAME_WATER_CAUSTICS);
        }
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_FLAGS, flags);
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
            : EmitterTable.bytes(emitters.count());
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
        // fog_color / fog of the C mirror carry the sky light (AtmosphereState.skyLightZenith / skyLightHorizon).
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_FOG_COLOR, frame.skyLightZenith);
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_FOG, frame.skyLightHorizon);
        putVector(desc, atmosphere + BedrayCoreLayouts.PTA_CLOUD, frame.cloud);

        fillPath(desc.asSlice(BedrayCoreLayouts.PTFD_PATH, BedrayCoreLayouts.PT_PATH.byteSize()), dispatch, entities);

        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_FRAME_INDEX, frame.frameIndex);
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_SAMPLES_PER_FRAME, frame.samplesPerFrame);
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_ACCUM_FRAMES, input.accumFrames());
        desc.set(JAVA_INT, BedrayCoreLayouts.PTFD_DISPLAY_FLAGS, input.displayFlags());
        // exposure.values.w is a delta time in the frame contract; the Java path
        // leaves it zero and keeps the absolute frame time in origin.w.
        desc.set(JAVA_FLOAT, BedrayCoreLayouts.PTFD_FRAME_TIME, 0f);
    }

    /** The native ABI path block mirrors the shader push; share its canonical writer to preserve every lane. */
    static void fillPath(MemorySegment out, RtPathTraceSlangPass.Dispatch dispatch, RtPathTraceSlangPass.Entities entities) {
        if (out.byteSize() != BedrayCoreLayouts.PT_PATH.byteSize())
            throw new IllegalArgumentException("native path block must be 112 bytes");
        var packet = out.asByteBuffer().order(ByteOrder.nativeOrder());
        dispatch.writeTo(packet);
        // Match the resource ranges actually bound by this native frame, as before.
        int state = Math.toIntExact(BedrayCoreLayouts.PTP_ENTITY_STATE);
        packet.putInt(state, entities.atlasSize());
        packet.putInt(state + 4, entities.selfAvatarTriStart());
        packet.putInt(state + 8, io.github.andrexarlay.vulkancompat.rt.entity.SelfAvatarGate.packCountAndShadow(
            entities.selfAvatarTriCount(), io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.selfShadow));
    }

    /** Borrowed host targets in shader binding order (different from the denoiser's array order). */
    static void fillGuides(MemorySegment out, RtPathTraceSlangPass.Guides guides) {
        if (out.byteSize() != BedrayCoreLayouts.PT_GUIDES_DESC.byteSize())
            throw new IllegalArgumentException("native guide descriptor must be 64 bytes");
        if (guides == null || !guides.complete())
            throw new IllegalArgumentException("native guide outputs need all seven views");
        var packet = out.asByteBuffer().order(ByteOrder.nativeOrder());
        packet.putInt(0, BedrayCoreLayouts.ABI_VERSION);
        packet.putInt(4, Math.toIntExact(BedrayCoreLayouts.PT_GUIDES_DESC.byteSize()));
        packet.putLong(8, guides.viewZ());
        packet.putLong(16, guides.normalRough());
        packet.putLong(24, guides.specular());
        packet.putLong(32, guides.diffuse());
        packet.putLong(40, guides.albedo());
        packet.putLong(48, guides.direct());
        packet.putLong(56, guides.specAlbedo());
    }

    /** The frame's whole StatsBuf slot as a {@code BedrayPathTraceBuffer}: the trace's reject split needs all 576 bytes. */
    static void fillStatsSlot(MemorySegment out, RtPathTraceSlangPass.BufferView stats) {
        if (out.byteSize() != BedrayCoreLayouts.PT_BUFFER.byteSize() || stats == null || stats.buffer() == 0L
            || stats.bytes() < RtStatsLayout.SLOT_STRIDE)
            throw new IllegalArgumentException("the stats slot is a whole " + RtStatsLayout.SLOT_STRIDE + "-byte range");
        var packet = out.asByteBuffer().order(ByteOrder.nativeOrder());
        packet.putLong(0, stats.buffer()).putLong(8, stats.offset()).putLong(16, RtStatsLayout.SLOT_STRIDE)
            .putInt(24, 0).putInt(28, 0);
    }

    static void fillMotion(MemorySegment out, long buffer, int width, int height) {
        fillMotion(out, buffer, width, height, false);
    }

    private static void fillMotion(MemorySegment out, long buffer, int width, int height, boolean worldMotion) {
        if (out.byteSize() != BedrayCoreLayouts.PT_BUFFER.byteSize() || buffer == 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("NRD needs a buffer and a positive frame extent");
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), worldMotion ? 32L : 16L);
        var packet = out.asByteBuffer().order(ByteOrder.nativeOrder());
        packet.putLong(0, buffer).putLong(8, 0).putLong(16, bytes).putInt(24, 0).putInt(28, 0);
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
