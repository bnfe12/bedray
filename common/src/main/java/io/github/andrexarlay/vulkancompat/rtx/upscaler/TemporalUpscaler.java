package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;

/**
 * The temporal super-resolution backend that currently owns the world target: Intel XeSS, NVIDIA DLSS or AMD FSR (never more
 * than one: only the selected backend of {@link UpscalerRequest} creates a context), or none. A thin routing layer so the
 * frame code does not branch on each technology. Render thread only.
 */
public final class TemporalUpscaler {
    private TemporalUpscaler() {}

    /** The active temporal backend, or {@code null} when none has an initialised context. */
    public static UpscalerBackend active() {
        if (XessRuntime.isActive()) {
            return UpscalerBackend.XESS;
        }
        if (DlssRuntime.isActive()) {
            return UpscalerBackend.DLSS;
        }
        if (FsrRuntime.isActive()) {
            return UpscalerBackend.FSR;
        }
        return null;
    }

    public static boolean isActive() {
        return active() != null;
    }

    /** Output (window) width of the active backend. */
    public static int outputWidth() {
        UpscalerBackend a = active();
        if (a == UpscalerBackend.DLSS) {
            return DlssRuntime.outputWidth();
        }
        return a == UpscalerBackend.FSR ? FsrRuntime.outputWidth() : XessRuntime.outputWidth();
    }

    public static int outputHeight() {
        UpscalerBackend a = active();
        if (a == UpscalerBackend.DLSS) {
            return DlssRuntime.outputHeight();
        }
        return a == UpscalerBackend.FSR ? FsrRuntime.outputHeight() : XessRuntime.outputHeight();
    }

    /** Render (input) width the backend asked for (queried from the vendor library, not a table). */
    public static int inputWidth() {
        UpscalerBackend a = active();
        if (a == UpscalerBackend.DLSS) {
            return DlssRuntime.activeInput().x();
        }
        return a == UpscalerBackend.FSR ? FsrRuntime.activeInput().width() : XessRuntime.activeInput().x();
    }

    public static int inputHeight() {
        UpscalerBackend a = active();
        if (a == UpscalerBackend.DLSS) {
            return DlssRuntime.activeInput().y();
        }
        return a == UpscalerBackend.FSR ? FsrRuntime.activeInput().height() : XessRuntime.activeInput().y();
    }

    /**
     * Changes whenever the temporal history must be reset: every (re)initialisation of any backend increments its own epoch,
     * so the sum is strictly increasing and also changes when the active backend switches (two per-backend epochs could be
     * equal across the switch and the reset would be missed).
     */
    public static int initEpoch() {
        return XessRuntime.initEpoch() + DlssRuntime.initEpoch() + FsrRuntime.initEpoch();
    }

    /** output / render width. */
    public static double scale() {
        UpscalerBackend a = active();
        if (a == UpscalerBackend.DLSS) {
            return DlssRuntime.scale();
        }
        return a == UpscalerBackend.FSR ? FsrRuntime.scale() : XessRuntime.scale();
    }

    public static String modeName() {
        UpscalerBackend a = active();
        if (a == UpscalerBackend.DLSS) {
            return String.valueOf(DlssRuntime.activeMode());
        }
        return a == UpscalerBackend.FSR ? String.valueOf(FsrRuntime.activeMode()) : String.valueOf(XessRuntime.activeMode());
    }

    /**
     * Head of {@code GameRenderer.render()}: lets each runtime set up / resize / reconfigure its context to match
     * {@link UpscalerRequest#current()} (no-op when the backend is not requested).
     */
    public static void onFrameBegin(int winW, int winH) {
        XessRuntime.onFrameBegin(winW, winH);
        DlssRuntime.onFrameBegin(winW, winH);
        FsrRuntime.onFrameBegin(winW, winH);
    }

    /** Frees the contexts (render thread). */
    public static void cleanup() {
        XessRuntime.cleanup();
        DlssRuntime.cleanup();
        FsrRuntime.cleanup();
    }

    /** A backend failed while recording a frame: disables exactly that backend for the current request. */
    public static void executeFailed(UpscalerBackend backend, String result) {
        if (backend == UpscalerBackend.DLSS) {
            DlssRuntime.executeFailed(result);
        } else if (backend == UpscalerBackend.FSR) {
            FsrRuntime.executeFailed(result);
        } else {
            XessRuntime.executeFailed(result);
        }
    }
}
