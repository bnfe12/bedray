package io.github.andrexarlay.vulkancompat.rtx.debug;

import java.util.List;

/**
 * A source of lines for the F3 block "Bedray". Providers are asked once per {@link PerfMonitor#WINDOW_NS}
 * (render thread), never per frame, so they may format freely. Register with {@link DebugInfo#register}; the order of
 * registration is the order on screen. Built-in providers: {@code core} (backend, upscaler, resolution, jitter), {@code rt}
 * (state with reason, geometry/BLAS/TLAS), {@code perf} (GPU/CPU stage times, CPU/GPU-bound). Other modules (for example RT
 * modes / RtTimings of feat/rt-modes) add their own provider instead of editing DebugInfo.
 */
public interface DebugLineProvider {
    /** Stable id; registering the same id again replaces the earlier provider. */
    String id();

    /** Appends this provider's lines (may append nothing). */
    void appendLines(List<String> out);
}
