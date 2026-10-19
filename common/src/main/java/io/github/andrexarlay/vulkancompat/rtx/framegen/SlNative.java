package io.github.andrexarlay.vulkancompat.rtx.framegen;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM binding of {@code vkb_sl.dll} (native/sl_bridge/src/vkb_sl.cpp): a flat C ABI over NVIDIA Streamline 2.14.1.
 * The DLL is our own code (built from NVIDIA's public headers); {@code sl.interposer.dll} itself is loaded by the shim
 * after an Authenticode check. All calls on the render thread. Every function returns the {@code sl::Result} integer
 * (0 = eOk, see {@code sl_result.h}) or a negative shim-level error documented in vkb_sl.cpp.
 */
public final class SlNative {
    private static final int LOG_CAP = 64 * 1024;

    private final Arena arena = Arena.ofShared();
    private final MethodHandle init, drainLog, loadedMask, dlssgRequirements, frameBegin, reflexSleep, reflexOptions, marker,
        setConstants, tag, dlssgOptions, dlssgState, shutdown, dlssgSupported, dlssgVersion, reflexLatency, focusWindow;
    private final MemorySegment logBuf = arena.allocate(LOG_CAP);

    private SlNative(Path shimDll) {
        SymbolLookup lookup = SymbolLookup.libraryLookup(shimDll, arena);
        Linker l = Linker.nativeLinker();
        init = bind(l, lookup, "vkb_sl_init", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        drainLog = bind(l, lookup, "vkb_sl_drain_log", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        loadedMask = bind(l, lookup, "vkb_sl_loaded_mask", FunctionDescriptor.of(JAVA_INT));
        dlssgRequirements = bind(l, lookup, "vkb_sl_dlssg_requirements", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        frameBegin = bind(l, lookup, "vkb_sl_frame_begin", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        reflexSleep = bind(l, lookup, "vkb_sl_reflex_sleep", FunctionDescriptor.of(JAVA_INT));
        reflexOptions = bind(l, lookup, "vkb_sl_reflex_options", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        marker = bind(l, lookup, "vkb_sl_marker", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        setConstants = bind(l, lookup, "vkb_sl_set_constants", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        tag = bind(l, lookup, "vkb_sl_tag", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        dlssgOptions = bind(l, lookup, "vkb_sl_dlssg_options", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        focusWindow = bind(l, lookup, "vkb_focus_game_window", FunctionDescriptor.of(JAVA_INT));
        reflexLatency = bind(l, lookup, "vkb_sl_reflex_latency", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        dlssgState = bind(l, lookup, "vkb_sl_dlssg_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        shutdown = bind(l, lookup, "vkb_sl_shutdown", FunctionDescriptor.of(JAVA_INT));
        dlssgSupported = bind(l, lookup, "vkb_sl_dlssg_supported", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        dlssgVersion = bind(l, lookup, "vkb_sl_dlssg_version", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    public static SlNative load(Path shimDll) {
        if (!Files.isRegularFile(shimDll)) {
            throw new IllegalStateException("vkb_sl.dll not found: " + shimDll);
        }
        return new SlNative(shimDll.toAbsolutePath().normalize());
    }

    private static MethodHandle bind(Linker l, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment s = lookup.find(name).orElseThrow(() -> new IllegalStateException("vkb_sl.dll has no export " + name));
        return l.downcallHandle(s, fd);
    }

    private static int call(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("vkb_sl downcall failed", t);
        }
    }

    private static MemorySegment wide(Arena a, String s) {
        byte[] b = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment m = a.allocate(b.length);
        MemorySegment.copy(b, 0, m, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, b.length);
        return m;
    }

    public int init(String slDir, String logDir, boolean verbose) {
        try (Arena a = Arena.ofConfined()) {
            return call(init, wide(a, slDir), logDir == null ? MemorySegment.NULL : wide(a, logDir), verbose ? 1 : 0);
        }
    }

    /** SL log lines queued since the last call (newline separated, may be empty). */
    public String drainLog() {
        int n = call(drainLog, logBuf, LOG_CAP);
        return n <= 0 ? "" : new String(logBuf.asSlice(0, n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    public int loadedMask() {
        return call(loadedMask);
    }

    /** {flags, vkGraphicsQueues, vkComputeQueues, driverMajor, driverMinor, driverBuild} or null on error. */
    public int[] dlssgRequirements() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT, 6);
            int r = call(dlssgRequirements, out);
            if (r != 0) {
                return null;
            }
            return out.toArray(JAVA_INT);
        }
    }

    public int frameBegin(int frameIndex) {
        return call(frameBegin, frameIndex);
    }

    public int reflexSleep() {
        return call(reflexSleep);
    }

    public int reflexOptions(int mode, int frameLimitUs) {
        return call(reflexOptions, mode, frameLimitUs);
    }

    public int marker(int pclMarker) {
        return call(marker, pclMarker);
    }

    /** m: 64 floats, v: 21 floats, i: 6 ints, see vkb_sl.cpp. */
    public int setConstants(float[] m, float[] v, int[] i, int viewport) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment mm = a.allocateFrom(JAVA_FLOAT, m);
            MemorySegment vv = a.allocateFrom(JAVA_FLOAT, v);
            MemorySegment ii = a.allocateFrom(JAVA_INT, i);
            return call(setConstants, mm, vv, ii, viewport);
        }
    }

    public int tag(int bufferType, long image, long view, int layout, int w, int h, int vkFormat, int usage, int lifecycle, int viewport) {
        return call(tag, bufferType, image, view, layout, w, h, vkFormat, usage, lifecycle, viewport);
    }

    public int dlssgOptions(int mode, int numFrames, int flags, int w, int h, int backBuffers, boolean recompose, int viewport,
                            int dynW, int dynH) {
        return call(dlssgOptions, mode, numFrames, flags, w, h, backBuffers, recompose ? 1 : 0, viewport, dynW, dynH);
    }

    /** {frameId, endToEndLatencyUs, gpuFrameTimeUs, lowLatencyAvailable} of the newest complete Reflex report, or null (none yet). */
    public int[] reflexLatency() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT, 4);
            int r = call(reflexLatency, out);
            return r == 0 ? out.toArray(JAVA_INT) : null;
        }
    }

    /** out[0..4] = status, minWidthOrHeight, framesPresentedSinceLastCall, framesToGenerateMax, vsyncSupport; returns sl::Result. */
    public int dlssgState(int[] out5, long[] vram, int viewport) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment o = a.allocate(JAVA_INT, 5);
            MemorySegment v = a.allocate(JAVA_LONG);
            int r = call(dlssgState, o, v, viewport);
            if (r == 0) {
                for (int k = 0; k < 5; k++) {
                    out5[k] = o.getAtIndex(JAVA_INT, k);
                }
                vram[0] = v.get(JAVA_LONG, 0);
            }
            return r;
        }
    }

    /** slIsFeatureSupported(DLSS_G) for the adapter with this 8 byte LUID; 0 = supported. */
    public int dlssgSupported(byte[] luid8) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment m = a.allocate(8);
            MemorySegment.copy(luid8, 0, m, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, 8);
            return call(dlssgSupported, m);
        }
    }

    /** {SL major, minor, build, NGX major, minor, build} of the DLSS_G plugin or null. */
    public int[] dlssgVersion() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT, 6);
            return call(dlssgVersion, out) == 0 ? out.toArray(JAVA_INT) : null;
        }
    }

    /** Autotest helper: foreground the game window; 1 = focused, 0 = refused by Windows, -1 = no window. */
    public int focusGameWindow() {
        return call(focusWindow);
    }

    public int shutdown() {
        return call(shutdown);
    }
}
