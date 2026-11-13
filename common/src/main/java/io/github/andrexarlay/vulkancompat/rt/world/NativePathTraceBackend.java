package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangTracePrograms;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Coarse ABI adapter. No Java image/buffer owner, descriptor construction, Vulkan commands or history parity. */
final class NativePathTraceBackend implements PathTraceBackend {
    private final SlangRuntime runtime;
    private final BedrayPathTraceNative pass;
    private final long hdrExportProgram;
    private final boolean spatial;
    private final String sceneTraceName;
    private final Set<Long> recordings = new LinkedHashSet<>();
    /** The mode-log word of the last recorded frame's path reuse. */
    private String ptState = "off";
    private boolean closed;

    static String supportProblem(SlangRuntime runtime, boolean guides) {
        if (!runtime.library().hasPathTrace() || !runtime.library().hasNativeReservoirs()
            || !runtime.library().hasGuideBindings())
            return "C++ path tracing requires a core with NATIVE_DI and the 25-binding guide contract";
        if (guides && !runtime.library().hasNativeGuideOutputs())
            return "C++ path tracing guide output requires a matching core with GUIDE_OUTPUTS";
        if (RtConfig.SLANG_RADIANCE_CASCADES && (!SlangPassLibrary.available(RtRadianceCascadesContract.BUILD)
            || !SlangPassLibrary.available(RtRadianceCascadesContract.TRACE)))
            return "radianceCascades requires both AOT RC field entries";
        return null;
    }

    NativePathTraceBackend(SlangRuntime runtime) {
        String problem = supportProblem(runtime, false);
        if (problem != null) throw new IllegalStateException(problem);
        this.runtime = runtime;
        boolean rc = RtConfig.SLANG_RADIANCE_CASCADES;
        String sceneName = rc ? RtRadianceCascadesContract.TRACE : RtPathTraceSlangPass.PASS_NAME;
        SlangProgram scene = SlangTracePrograms.select(runtime, sceneName).orElseThrow();
        RtPathTraceSlangPass.requireManifest(scene, rc);
        sceneTraceName = scene.name();
        SlangProgram builder = rc ? runtime.program(RtRadianceCascadesContract.BUILD).orElseThrow() : null;
        if (builder != null) RtRadianceCascadesContract.requireBuilder(builder.manifest());
        SlangProgram display = runtime.program(RtSlangPathPass.DISPLAY_PASS).orElseThrow();
        RtSlangPathPass.requireDisplayManifest(display);
        SlangProgram reuse = RtSpatialReuseSlangPass.registerProgram(runtime).orElse(null);
        spatial = reuse != null;
        long stats = RtFrameStatsSlangPass.registerProgram(runtime)
            .map(program -> program.nativeHandle()).orElse(0L);
        // The experimental RC continuation does not write a PT ring.
        SlangProgram ptSpatial = runtime.library().hasNativePt() && !rc
            ? RtPathTraceSlangPass.optionalPtSpatial(runtime, RtPathTraceSlangPass.PT_SPATIAL_SCENE_PASS_NAME) : null;
        pass = new BedrayPathTraceNative(runtime, scene.nativeHandle(), reuse == null ? 0 : reuse.nativeHandle(),
            display.nativeHandle(), stats, runtime.atlasSampler(), builder == null ? 0 : builder.nativeHandle(),
            ptSpatial == null ? 0 : ptSpatial.nativeHandle());
        hdrExportProgram = pass.supportsHdrExport() ? runtime.program("path_hdr_export")
            .map(program -> {
                if (!java.util.Arrays.equals(program.manifest().threadGroupSize(), new int[]{8, 8, 1}))
                    throw new IllegalStateException("native HDR export requires an 8x8x1 compute group");
                return program.nativeHandle();
            }).orElse(0L) : 0L;
    }

    @Override public void prepare(int width, int height) {
        if (closed) throw new IllegalStateException("native path backend is closed");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("path extent must be positive");
    }

    @Override public void record(Frame frame) {
        if (closed) throw new IllegalStateException("native path backend is closed");
        if (!recordings.add(frame.command().address()))
            throw new IllegalStateException("recording still awaits completion/discard");
        int flags = frame.flags() | BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_DI | ptFlags(frame);
        // The core owns the PT ring's history and parity; whatever the host mirror holds stays out of the push.
        RtPathTraceSlangPass.Dispatch dispatch = pass.nativePt() ? frame.dispatch().withFlags(frame.dispatch().flags()
            & ~(RtPathTraceSlangPass.PT_HISTORY | RtPathTraceSlangPass.PT_PARITY)) : frame.dispatch();
        pass.render(new BedrayPathTraceNative.FrameInput(frame.command().address(), frame.views(), frame.constants(),
            dispatch, frame.emitters(), RtPathTraceSlangPass.Reservoirs.NONE, frame.entities(), frame.stats(),
            flags, frame.accumFrames(), RtSlangPathPass.tonemapperBits(RtSettings.tonemapper()) | RtSlangPathPass.postCinemaBits()
                | RtSlangPathPass.vibrantBit(),
            (float) RtSettings.pathFireflyClamp(), RtSlangPathPass.REPROJECTION_TOLERANCE));
        boolean pt = (flags & BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_PT) != 0
            && (dispatch.flags() & RtPathTraceSlangPass.OPTION_RESTIR_PT) != 0;
        ptState = !pt ? "off" : (flags & BedrayCoreLayouts.PATH_TRACE_FRAME_PT_SPATIAL) != 0 ? "native+spatial" : "native";
    }

    /**
     * The NATIVE_PT bits of one frame: ownership whenever the core has a ring, its reset when the controller asked for
     * one, and the spatial stage only for a frame that runs PT on a table the core has the stage for.
     */
    @Override public boolean supportsHdrExport() { return hdrExportProgram != 0L; }

    @Override public boolean exportHdr(long command, long view, int width, int height) {
        if (!supportsHdrExport()) return false;
        if (closed || !recordings.contains(command)) throw new IllegalStateException("HDR export needs the pending frame recording");
        pass.exportHdr(command, hdrExportProgram, view, width, height);
        return true;
    }

    private int ptFlags(Frame frame) {
        if (!pass.nativePt()) return 0;
        int flags = BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_PT;
        if (frame.ptReset()) flags |= BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_PT;
        if ((frame.dispatch().flags() & RtPathTraceSlangPass.OPTION_RESTIR_PT) != 0 && frame.ptSpatial()
            && pass.ptSpatialAvailable())
            flags |= BedrayCoreLayouts.PATH_TRACE_FRAME_PT_SPATIAL;
        return flags;
    }

    @Override public boolean spatialAvailable() { return spatial; }
    @Override public String traceProgramName() { return sceneTraceName; }
    @Override public boolean pathReuseAvailable() { return pass.nativePt(); }
    @Override public boolean displaySkyAvailable() { return pass.displaySkyAvailable(); }
    @Override public String pathReuseState() { return ptState; }
    @Override public String reservoirState() { return "native"; }

    @Override public void releaseRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) return;
        runtime.releaseRecording(commandBuffer);
        recordings.remove(commandBuffer);
    }

    @Override public boolean discardRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) return false;
        runtime.discardRecording(commandBuffer);
        recordings.remove(commandBuffer);
        return true;
    }

    @Override public void close() {
        if (closed) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("native backend still has pending recordings");
        pass.close();
        closed = true;
    }

    @Override public void releaseRecordingsAfterHostDrain() {
        for (long command : List.copyOf(recordings)) releaseRecording(command);
    }
}
