package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;

/** Coarse EASU/RCAS/NIS chain recorder; all programs/samplers/views are borrowed. */
public final class BedraySpatialNative {
    public static final int FSR1 = 0, NIS = 1, SHARPEN = 1, FRAME_BYTES = 136;
    private final BedrayCoreNative library;
    private final long core;
    private final MethodHandle render;

    public BedraySpatialNative(BedrayCoreNative library, long core) {
        if (library == null || core == 0) throw new IllegalArgumentException("spatial pass requires the shared core");
        this.library = library; this.core = core;
        render = library.resourceFunction("bedray_core_render_spatial", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    static MemorySegment frame(Arena arena, long command, long[] programs, long linear, long nearest,
                               long input, long temporary, long output, int inputWidth, int inputHeight,
                               int outputWidth, int outputHeight, int backend, int flags, ByteBuffer primary, ByteBuffer secondary) {
        boolean sharpen = flags == SHARPEN;
        if (command == 0 || programs == null || programs.length != 3 || backend < FSR1 || backend > NIS
            || flags < 0 || flags > SHARPEN || (backend == NIS && sharpen)
            || programs[backend == FSR1 ? 0 : 2] == 0 || (sharpen && programs[1] == 0)
            || linear == 0 || (sharpen && nearest == 0) || input == 0 || output == 0 || input == output
            || (sharpen && (temporary == 0 || temporary == input || temporary == output))
            || inputWidth <= 0 || inputHeight <= 0 || outputWidth < inputWidth || outputHeight < inputHeight
            || outputWidth > 65535 || outputHeight > 65535
            || (backend == NIS && ((long)inputWidth * 2 < outputWidth || (long)inputHeight * 2 < outputHeight)))
            throw new IllegalArgumentException("invalid spatial chain/inputs");
        int primaryBytes = backend == FSR1 ? 80 : 112;
        var first = payload(arena, primary, primaryBytes);
        MemorySegment second = MemorySegment.NULL;
        if (sharpen) second = payload(arena, secondary, 32);
        else if (secondary != null) throw new IllegalArgumentException("unsharpened spatial pass has no secondary push");
        var desc = arena.allocate(FRAME_BYTES, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, FRAME_BYTES);
        desc.set(JAVA_LONG, 8, command);
        for (int i = 0; i < 3; i++) desc.set(JAVA_LONG, 16L + i * 8L, programs[i]);
        desc.set(JAVA_LONG, 40, linear); desc.set(JAVA_LONG, 48, nearest);
        desc.set(JAVA_LONG, 56, input); desc.set(JAVA_LONG, 64, temporary); desc.set(JAVA_LONG, 72, output);
        desc.set(JAVA_INT, 80, inputWidth); desc.set(JAVA_INT, 84, inputHeight);
        desc.set(JAVA_INT, 88, outputWidth); desc.set(JAVA_INT, 92, outputHeight);
        desc.set(JAVA_INT, 96, backend); desc.set(JAVA_INT, 100, flags);
        desc.set(ADDRESS, 104, first); desc.set(JAVA_INT, 112, primaryBytes);
        desc.set(JAVA_INT, 116, sharpen ? 32 : 0); desc.set(ADDRESS, 120, second); desc.set(JAVA_LONG, 128, 0);
        return desc;
    }

    private static MemorySegment payload(Arena arena, ByteBuffer input, int bytes) {
        if (input == null || input.remaining() != bytes) throw new IllegalArgumentException("spatial push requires " + bytes + " bytes");
        var copy = arena.allocate(bytes, 4);
        MemorySegment.copy(MemorySegment.ofBuffer(input), 0, copy, 0, bytes);
        return copy;
    }

    public void record(long command, long[] programs, long linear, long nearest, long input, long temporary, long output,
                       int inputWidth, int inputHeight, int outputWidth, int outputHeight, int backend, int flags,
                       ByteBuffer primary, ByteBuffer secondary) {
        library.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, programs, linear, nearest, input, temporary, output,
                inputWidth, inputHeight, outputWidth, outputHeight, backend, flags, primary, secondary);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            int status = (int)render.invokeExact(MemorySegment.ofAddress(core), desc, error);
            if (status != BedrayCoreLayouts.STATUS_OK)
                throw new BedrayCoreException("render_spatial", status,
                    "command=0x" + Long.toHexString(command) + " backend=" + backend + " flags=" + flags
                        + " input=" + inputWidth + "x" + inputHeight + " output=" + outputWidth + "x" + outputHeight
                        + " native_error=" + error.getString(BedrayCoreLayouts.ERROR_MESSAGE), null);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new BedrayCoreException("render_spatial", BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
        }
    }
}
