package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import static java.lang.foreign.ValueLayout.*;

/** Coarse bloom ABI. Native core owns the pass, images, barriers and recorded uses. */
public final class BedrayBloomNative implements AutoCloseable {
    static final int CONFIG_BYTES = 64, FRAME_BYTES = 56, PUSH_BYTES = 128;
    private final long core;
    private final MethodHandle render, destroy;
    private boolean closed;

    public BedrayBloomNative(BedrayCoreNative library, long core, long[] programs,
                             long linearSampler, long nearestSampler, long budget) {
        if (core == 0) throw new IllegalArgumentException("core is required");
        this.core = core;
        MethodHandle configure = library.resourceFunction("bedray_core_configure_bloom",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        render = library.resourceFunction("bedray_core_render_bloom", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        destroy = library.resourceFunction("bedray_core_destroy_bloom", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = configuration(arena, programs, linearSampler, nearestSampler, budget);
            MemorySegment error = arena.allocate(BedrayCoreLayouts.ERROR);
            check("configure_bloom", (int) configure.invokeExact(MemorySegment.ofAddress(core), desc, error), error);
        } catch (Throwable failure) { throw failure("configure_bloom", failure); }
    }

    static MemorySegment configuration(Arena arena, long[] programs, long linear, long nearest, long budget) {
        if (programs == null || programs.length != 4 || linear == 0 || nearest == 0 || budget <= 0)
            throw new IllegalArgumentException("four programs, samplers and positive image budget are required");
        MemorySegment desc = arena.allocate(CONFIG_BYTES, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, CONFIG_BYTES);
        for (int i = 0; i < programs.length; i++) {
            if (programs[i] == 0) throw new IllegalArgumentException("bloom program is zero");
            desc.set(JAVA_LONG, 8 + 8L * i, programs[i]);
        }
        desc.set(JAVA_LONG, 40, linear); desc.set(JAVA_LONG, 48, nearest); desc.set(JAVA_LONG, 56, budget);
        return desc;
    }

    static MemorySegment frame(Arena arena, long command, int width, int height, long output, long depth,
                               ByteBuffer push, boolean bloom) {
        if (command == 0 || width <= 0 || height <= 0 || output == 0 || depth == 0
            || push == null || !push.isDirect() || push.remaining() != PUSH_BYTES)
            throw new IllegalArgumentException("recording, positive extent, views and 128 direct push bytes are required");
        MemorySegment desc = arena.allocate(FRAME_BYTES, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, FRAME_BYTES);
        desc.set(JAVA_LONG, 8, command); desc.set(JAVA_INT, 16, width); desc.set(JAVA_INT, 20, height);
        desc.set(JAVA_LONG, 24, output); desc.set(JAVA_LONG, 32, depth);
        desc.set(JAVA_INT, 40, bloom ? 1 : 0); desc.set(JAVA_INT, 44, PUSH_BYTES);
        desc.set(ADDRESS, 48, MemorySegment.ofBuffer(push));
        return desc;
    }

    public void record(long command, int width, int height, long output, long depth, ByteBuffer push, boolean bloom) {
        if (closed) throw new IllegalStateException("native bloom is closed");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = frame(arena, command, width, height, output, depth, push, bloom);
            MemorySegment error = arena.allocate(BedrayCoreLayouts.ERROR);
            check("render_bloom", (int) render.invokeExact(MemorySegment.ofAddress(core), desc, error), error);
        } catch (Throwable failure) { throw failure("render_bloom", failure); }
    }

    @Override public void close() {
        if (closed) return;
        try {
            check("destroy_bloom", (int) destroy.invokeExact(MemorySegment.ofAddress(core)), MemorySegment.NULL);
            closed = true;
        } catch (Throwable failure) { throw failure("destroy_bloom", failure); }
    }

    private static void check(String operation, int status, MemorySegment error) {
        if (status == BedrayCoreLayouts.STATUS_OK) return;
        String detail = error.equals(MemorySegment.NULL) ? "bloom recordings may still be in use"
            : error.getString(BedrayCoreLayouts.ERROR_MESSAGE);
        throw new BedrayCoreException(operation, status, detail, null);
    }
    private static RuntimeException failure(String operation, Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new BedrayCoreException(operation, BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
    }
}
