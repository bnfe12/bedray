package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;

/** Coarse live-velocity recorder. Native copies POD and owns immutable uploads/descriptors/barriers. */
public final class BedrayVelocityNative implements AutoCloseable {
    public static final int UNIFORM_BYTES = 368;
    private final BedrayCoreNative library;
    private final long core;
    private final MethodHandle render;
    private boolean closed;

    public BedrayVelocityNative(BedrayCoreNative library, long core, long program, long nearestSampler, long bufferBudget) {
        if (core == 0) throw new IllegalArgumentException("core is required");
        this.library = library; this.core = core;
        var configure = library.resourceFunction("bedray_core_configure_velocity", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        render = library.resourceFunction("bedray_core_render_velocity", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            var desc = configuration(arena, program, nearestSampler, bufferBudget);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            check("configure_velocity", (int) configure.invokeExact(MemorySegment.ofAddress(core), desc, error), error);
        } catch (Throwable failure) { throw failure("configure_velocity", failure); }
    }

    static MemorySegment configuration(Arena arena, long program, long sampler, long budget) {
        if (program == 0 || sampler == 0 || budget <= 0)
            throw new IllegalArgumentException("velocity program/sampler and positive budget are required");
        var desc = arena.allocate(32, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, 32);
        desc.set(JAVA_LONG, 8, program); desc.set(JAVA_LONG, 16, sampler); desc.set(JAVA_LONG, 24, budget);
        return desc;
    }

    static MemorySegment frame(Arena arena, long command, long depth, long output,
                               int width, int height, ByteBuffer uniforms) {
        if (command == 0 || depth == 0 || output == 0 || depth == output || width <= 0 || height <= 0
            || width > 65535 || height > 65535 || (long) width * height > Integer.MAX_VALUE
            || uniforms == null || !uniforms.isDirect() || uniforms.remaining() != UNIFORM_BYTES)
            throw new IllegalArgumentException("invalid velocity frame/payload");
        var desc = arena.allocate(56, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, 56);
        desc.set(JAVA_LONG, 8, command); desc.set(JAVA_LONG, 16, depth); desc.set(JAVA_LONG, 24, output);
        desc.set(JAVA_INT, 32, width); desc.set(JAVA_INT, 36, height);
        desc.set(ADDRESS, 40, MemorySegment.ofBuffer(uniforms));
        desc.set(JAVA_INT, 48, UNIFORM_BYTES); desc.set(JAVA_INT, 52, 0);
        return desc;
    }

    public void record(long command, long depth, long output, int width, int height, ByteBuffer uniforms) {
        if (closed) throw new IllegalStateException("native velocity pass is closed");
        library.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, depth, output, width, height, uniforms);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            check("render_velocity", (int) render.invokeExact(MemorySegment.ofAddress(core), desc, error), error);
        } catch (Throwable failure) { throw failure("render_velocity", failure); }
    }

    @Override public void close() {
        // The core is the sole pass owner. Closing one borrow cannot destroy
        // uploads or configuration still used by another velocity adapter.
        closed = true;
    }
    private static void check(String operation, int status, MemorySegment error) {
        if (status == BedrayCoreLayouts.STATUS_OK) return;
        throw new BedrayCoreException(operation, status, error.equals(MemorySegment.NULL)
            ? "velocity recordings may still be in use" : error.getString(BedrayCoreLayouts.ERROR_MESSAGE), null);
    }
    private static RuntimeException failure(String operation, Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new BedrayCoreException(operation, BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
    }
}
