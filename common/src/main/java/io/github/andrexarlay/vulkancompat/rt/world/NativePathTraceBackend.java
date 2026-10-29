package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Coarse ABI adapter. No Java image/buffer owner, descriptor construction, Vulkan commands or history parity. */
final class NativePathTraceBackend implements PathTraceBackend {
    private final SlangRuntime runtime;
    private final BedrayPathTraceNative pass;
    private final boolean spatial;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean closed;

    NativePathTraceBackend(SlangRuntime runtime) {
        if (!runtime.library().hasPathTrace() || !runtime.library().hasNativeReservoirs()
            || !runtime.library().hasGuideBindings()) {
            throw new IllegalStateException("nativePathTrace requires a core with NATIVE_DI and the 25-binding guide contract");
        }
        this.runtime = runtime;
        SlangProgram trace = runtime.program(RtPathTraceSlangPass.PASS_NAME).orElseThrow();
        RtPathTraceSlangPass.requireManifest(trace);
        SlangProgram scene = SlangPassLibrary.available(RtPathTraceSlangPass.SCENE_PASS_NAME)
            ? runtime.program(RtPathTraceSlangPass.SCENE_PASS_NAME).orElseThrow() : null;
        if (scene != null) RtPathTraceSlangPass.requireManifest(scene);
        SlangProgram display = runtime.program(RtSlangPathPass.DISPLAY_PASS).orElseThrow();
        RtSlangPathPass.requireDisplayManifest(display);
        SlangProgram reuse = RtSpatialReuseSlangPass.registerProgram(runtime).orElse(null);
        spatial = reuse != null;
        pass = new BedrayPathTraceNative(runtime, trace.nativeHandle(), scene == null ? 0 : scene.nativeHandle(),
            reuse == null ? 0 : reuse.nativeHandle(), display.nativeHandle(), runtime.atlasSampler());
    }

    @Override public void prepare(int width, int height) {
        if (closed) throw new IllegalStateException("native path backend is closed");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("path extent must be positive");
    }

    @Override public void record(Frame frame) {
        if (closed) throw new IllegalStateException("native path backend is closed");
        if (!recordings.add(frame.command().address()))
            throw new IllegalStateException("recording still awaits completion/discard");
        pass.render(new BedrayPathTraceNative.FrameInput(frame.command().address(), frame.views(), frame.constants(),
            frame.dispatch(), frame.emitters(), RtPathTraceSlangPass.Reservoirs.NONE, frame.entities(),
            frame.flags() | BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_DI, frame.accumFrames(), 0,
            RtSlangPathPass.MAX_RADIANCE, RtSlangPathPass.REPROJECTION_TOLERANCE));
    }

    @Override public boolean spatialAvailable() { return spatial; }
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
