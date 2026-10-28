package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * The ReSTIR DI ring's memory of itself: which half a frame reads, whether the half it wants to read holds records, and
 * whether the storage still has to be cleared.
 *
 * <p>The ring has two owners and they answer those questions differently. Without {@code NATIVE_DI} the host owns both
 * halves, clears them with a command-buffer fill and alternates the parity itself. With {@code NATIVE_DI} the core
 * allocates the ring, clears it, and keeps its own parity and history bit: the host only forwards a reset request and
 * learns from the next dispatch whether it was acknowledged. Both contracts live here so the pass asks questions
 * instead of maintaining four flags side by side, and so the rules are exercisable without a device.
 *
 * <p>Not thread-safe: one instance belongs to one recorded frame at a time, exactly like the pass that owns it.
 */
final class RestirDiState {

    /** Bits of {@code PathTracePush.diState.x}; mirrors the {@code PATH_TRACE_DI_*} constants of the trace shader. */
    static final int FLAG_ENABLED = 1;
    static final int FLAG_HISTORY = 1 << 1;
    static final int FLAG_STATIC = 1 << 2;
    static final int FLAG_FLIP_Y = 1 << 3;

    /** True when the core allocates, clears and alternates the ring and the host only requests resets. */
    private final boolean nativeRing;
    /** True when the last dispatch that used the ring left records a later frame may merge into. */
    private boolean historyWritten;
    /** True until the ring is known to be zeroed, or while a forwarded reset has not been acknowledged. */
    private boolean resetPending = true;
    /** Counts dispatches that wrote records; its low bit is the external ring's parity. */
    private int frame;
    private int extentWidth;
    private int extentHeight;

    RestirDiState(boolean nativeRing) {
        this.nativeRing = nativeRing;
    }

    boolean nativeRing() {
        return nativeRing;
    }

    /** True when a frame may bind a ring at all: the core's own, or the host buffer the pass currently holds. */
    boolean available(boolean hostRingBound) {
        return nativeRing || hostRingBound;
    }

    boolean resetPending() {
        return resetPending;
    }

    boolean historyWritten() {
        return historyWritten;
    }

    /**
     * The half this frame reads. A native ring's parity is the core's private state, and a host mirror of it would be a
     * second source of truth the two could disagree on, so this stays zero and the core derives its own.
     */
    int parity() {
        return nativeRing ? 0 : frame & 1;
    }

    /**
     * The {@code diState} word for this frame. HISTORY only means something to the external ring: with {@code NATIVE_DI}
     * the core knows which half it wrote itself.
     */
    int flags(boolean cameraStatic, boolean flipY) {
        int flags = FLAG_ENABLED;
        if (!nativeRing && historyWritten) {
            flags |= FLAG_HISTORY;
        }
        if (cameraStatic) {
            flags |= FLAG_STATIC;
        }
        if (flipY) {
            flags |= FLAG_FLIP_Y;
        }
        return flags;
    }

    /** True when the records were laid out for another extent, which moves every index by itself. */
    boolean extentDiffers(int width, int height) {
        return width != extentWidth || height != extentHeight;
    }

    /**
     * Declares the extent a new or re-laid-out ring holds records for. Always resets: fresh storage is uninitialised,
     * and a changed extent addresses different pixels in every slot.
     */
    void layoutFor(int width, int height) {
        extentWidth = width;
        extentHeight = height;
        reset();
    }

    /** Invalidates every record: nothing may be read from either half until the ring is cleared again. */
    void reset() {
        historyWritten = false;
        resetPending = true;
        frame = 0;
    }

    /** The host filled the whole ring, so nothing is pending any more. */
    void ringCleared() {
        resetPending = false;
    }

    /**
     * The dispatch returned; {@code wrote} is true when the shader took the DI branch. A native core clears a forwarded
     * reset only in that case, because it touches the ring only when it runs DI, so the request survives a skipped frame.
     */
    void afterDispatch(boolean wrote) {
        historyWritten = wrote;
        if (!wrote) {
            return;
        }
        if (nativeRing) {
            resetPending = false;
        } else {
            frame++;
        }
    }

    /** The {@code restir_di} field of the mode log: what this frame will actually do, not what is switched on. */
    String mode(boolean enabled, boolean hostRingBound, boolean spatial) {
        if (!enabled || !available(hostRingBound)) {
            return "off";
        }
        return (historyWritten ? "temporal" : "fresh") + (spatial ? "+spatial" : "");
    }
}
