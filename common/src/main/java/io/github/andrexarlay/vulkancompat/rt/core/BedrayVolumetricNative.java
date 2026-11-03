package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;

/** Coarse recorder; native owns history, upload buffers, immutable descriptors and barriers. */
public final class BedrayVolumetricNative implements AutoCloseable {
    public static final int FRAME_CONSTANTS_BYTES = 608, MARCH_BYTES = 96, APPLY_BYTES = 24;
    public static final int NATIVE_SCENE = 1, RESET_HISTORY = 2;
    private final BedrayCoreNative library;
    private final long core;
    private final MethodHandle render, destroy;
    private boolean closed;

    public BedrayVolumetricNative(BedrayCoreNative library, long core, long legacyMarch, long sceneMarch,
                                  long apply, long imageBudget, long bufferBudget) {
        if (core == 0) throw new IllegalArgumentException("core is required");
        this.library = library; this.core = core;
        var configure = library.resourceFunction("bedray_core_configure_volumetric",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        render = library.resourceFunction("bedray_core_render_volumetric", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        destroy = library.resourceFunction("bedray_core_destroy_volumetric", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            var desc = configuration(arena, legacyMarch, sceneMarch, apply, imageBudget, bufferBudget);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            check("configure_volumetric", (int) configure.invokeExact(MemorySegment.ofAddress(core), desc, error), error);
        } catch (Throwable failure) { throw failure("configure_volumetric", failure); }
    }

    static MemorySegment configuration(Arena arena, long legacy, long scene, long apply, long images, long buffers) {
        if ((legacy == 0 && scene == 0) || apply == 0 || images <= 0 || buffers <= 0)
            throw new IllegalArgumentException("march/apply programs and positive budgets are required");
        var desc = arena.allocate(48, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, 48);
        desc.set(JAVA_LONG, 8, legacy); desc.set(JAVA_LONG, 16, scene); desc.set(JAVA_LONG, 24, apply);
        desc.set(JAVA_LONG, 32, images); desc.set(JAVA_LONG, 40, buffers);
        return desc;
    }

    static MemorySegment frame(Arena arena, long command, long output, long depth, long tlas, long sections,
                               long sectionOffset, long sectionBytes, int width, int height, int flags, int frameIndex,
                               ByteBuffer constants, ByteBuffer march, ByteBuffer apply) {
        if (command == 0 || output == 0 || depth == 0 || tlas == 0 || sections == 0 || output == depth
            || sectionOffset < 0 || sectionBytes <= 0 || sectionBytes % 64 != 0 || width <= 0 || height <= 0
            || (flags & ~(NATIVE_SCENE | RESET_HISTORY)) != 0)
            throw new IllegalArgumentException("invalid volumetric frame inputs");
        var constantsMemory = payload(constants, FRAME_CONSTANTS_BYTES);
        var marchMemory = payload(march, MARCH_BYTES);
        var applyMemory = payload(apply, APPLY_BYTES);
        var desc = arena.allocate(120, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, 120);
        desc.set(JAVA_LONG, 8, command); desc.set(JAVA_LONG, 16, output); desc.set(JAVA_LONG, 24, depth);
        desc.set(JAVA_LONG, 32, tlas); desc.set(JAVA_LONG, 40, sections);
        desc.set(JAVA_LONG, 48, sectionOffset); desc.set(JAVA_LONG, 56, sectionBytes);
        desc.set(JAVA_INT, 64, width); desc.set(JAVA_INT, 68, height);
        desc.set(JAVA_INT, 72, flags); desc.set(JAVA_INT, 76, frameIndex);
        desc.set(ADDRESS, 80, constantsMemory); desc.set(JAVA_INT, 88, FRAME_CONSTANTS_BYTES);
        desc.set(JAVA_INT, 92, MARCH_BYTES); desc.set(ADDRESS, 96, marchMemory);
        desc.set(ADDRESS, 104, applyMemory); desc.set(JAVA_INT, 112, APPLY_BYTES); desc.set(JAVA_INT, 116, 0);
        return desc;
    }

    private static MemorySegment payload(ByteBuffer buffer, int bytes) {
        if (buffer == null || !buffer.isDirect() || buffer.remaining() != bytes)
            throw new IllegalArgumentException("volumetric payload needs " + bytes + " direct bytes");
        return MemorySegment.ofBuffer(buffer);
    }

    public void record(long command, long output, long depth, long tlas, long sections, long sectionBytes,
                       int width, int height, int flags, int frameIndex, ByteBuffer constants, ByteBuffer march, ByteBuffer apply) {
        if (closed) throw new IllegalStateException("native volumetric pass is closed");
        library.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, output, depth, tlas, sections, 0, sectionBytes, width, height, flags, frameIndex,
                constants, march, apply);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            check("render_volumetric", (int) render.invokeExact(MemorySegment.ofAddress(core), desc, error), error);
        } catch (Throwable failure) { throw failure("render_volumetric", failure); }
    }

    @Override public void close() {
        if (closed) return;
        library.ensureOpen();
        try {
            check("destroy_volumetric", (int) destroy.invokeExact(MemorySegment.ofAddress(core)), MemorySegment.NULL);
            closed = true;
        } catch (Throwable failure) { throw failure("destroy_volumetric", failure); }
    }
    private static void check(String operation, int status, MemorySegment error) {
        if (status == BedrayCoreLayouts.STATUS_OK) return;
        throw new BedrayCoreException(operation, status, error.equals(MemorySegment.NULL)
            ? "volumetric recordings may still be in use" : error.getString(BedrayCoreLayouts.ERROR_MESSAGE), null);
    }
    private static RuntimeException failure(String operation, Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new BedrayCoreException(operation, BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
    }
}
