package io.github.andrexarlay.vulkanbridge.api;

import java.util.Objects;

/**
 * Per-renderer settings of the {@link GlBridge} (ADR-0006, since API 0.6.0). Immutable; change them with
 * {@link GlBridge#setOptions(GlRenderer, GlRendererOptions)} and the {@code with*} copies.
 *
 * @param phase     where the renderer's output is composited into the engine frame. Tier 2 supports
 *                  {@link FramePhase#BEFORE_GUI} (under the GUI) and {@link FramePhase#FRAME_END} (over everything, default);
 *                  other phases are refused at registration (they need Tier 3 scene inputs), see {@link GlBridgeCapabilities#phases()}
 * @param alphaMode how the renderer's colour is interpreted when blended (default PREMULTIPLIED)
 * @param opacity   opacity multiplier in [0,1] applied to the premultiplied colour (default 1)
 * @param visible   false: the renderer is neither rendered nor composited (default true)
 * @param latency   SAME_FRAME (default): the render thread waits until the GL commands of this frame are issued and composites
 *                  them in the same frame. PIPELINED_N_MINUS_1: the GL work of frame N runs on the GL thread while the game
 *                  continues and is composited in frame N+1 (one frame of latency, the render thread does not wait for GL).
 */
@Experimental
public record GlRendererOptions(FramePhase phase, GlBridge.AlphaMode alphaMode, float opacity, boolean visible, Latency latency) {
    /** See {@link GlRendererOptions#latency()}. */
    public enum Latency {
        SAME_FRAME,
        PIPELINED_N_MINUS_1
    }

    /** FRAME_END, PREMULTIPLIED, opacity 1, visible, SAME_FRAME (the 0.5.x behaviour). */
    public static final GlRendererOptions DEFAULTS =
        new GlRendererOptions(FramePhase.FRAME_END, GlBridge.AlphaMode.PREMULTIPLIED, 1f, true, Latency.SAME_FRAME);

    public GlRendererOptions {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(alphaMode, "alphaMode");
        Objects.requireNonNull(latency, "latency");
        if (!(opacity >= 0f && opacity <= 1f)) {
            throw new IllegalArgumentException("opacity must be in [0,1]: " + opacity);
        }
    }

    public GlRendererOptions withPhase(FramePhase p) {
        return new GlRendererOptions(p, alphaMode, opacity, visible, latency);
    }

    public GlRendererOptions withAlphaMode(GlBridge.AlphaMode a) {
        return new GlRendererOptions(phase, a, opacity, visible, latency);
    }

    public GlRendererOptions withOpacity(float o) {
        return new GlRendererOptions(phase, alphaMode, o, visible, latency);
    }

    public GlRendererOptions withVisible(boolean v) {
        return new GlRendererOptions(phase, alphaMode, opacity, v, latency);
    }

    public GlRendererOptions withLatency(Latency l) {
        return new GlRendererOptions(phase, alphaMode, opacity, visible, l);
    }
}
