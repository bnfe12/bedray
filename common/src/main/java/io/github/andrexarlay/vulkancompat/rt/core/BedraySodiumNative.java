package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Stateless format-conversion call; native owns the shader push/groups and recording barriers. */
public final class BedraySodiumNative {
    static final int FRAME_BYTES = 56;
    private static final int MAX_QUADS = Integer.MAX_VALUE / 28;
    private final BedrayCoreNative library;
    private final long core, program;
    private final MethodHandle decode;

    public BedraySodiumNative(BedrayCoreNative library, long core, long program) {
        if (core == 0 || program == 0) throw new IllegalArgumentException("core and Sodium program are required");
        this.library = library; this.core = core; this.program = program;
        decode = library.resourceFunction("bedray_core_decode_sodium",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    static MemorySegment frame(Arena arena, long command, long program, long raw, long output, long flags, int quads) {
        if (command == 0 || program == 0 || quads <= 0 || quads > MAX_QUADS)
            throw new IllegalArgumentException("Sodium decode needs a recording, program and bounded positive quad count");
        long rawBytes = quads * 80L, outputBytes = quads * 112L, flagBytes = quads * 4L;
        if (!validRange(raw, rawBytes) || !validRange(output, outputBytes) || overlaps(raw, rawBytes, output, outputBytes)
            || (flags != 0 && (!validRange(flags, flagBytes) || overlaps(flags, flagBytes, raw, rawBytes)
                || overlaps(flags, flagBytes, output, outputBytes))))
            throw new IllegalArgumentException("Sodium decode ranges must be aligned, nonwrapping and nonoverlapping");
        var desc = arena.allocate(FRAME_BYTES, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, FRAME_BYTES);
        desc.set(JAVA_LONG, 8, command); desc.set(JAVA_LONG, 16, program);
        desc.set(JAVA_LONG, 24, raw); desc.set(JAVA_LONG, 32, output); desc.set(JAVA_LONG, 40, flags);
        desc.set(JAVA_INT, 48, quads); desc.set(JAVA_INT, 52, 0);
        return desc;
    }

    private static boolean validRange(long address, long bytes) {
        return address != 0 && (address & 3L) == 0 && Long.compareUnsigned(address, -1L - bytes) <= 0;
    }
    private static boolean overlaps(long a, long aBytes, long b, long bBytes) {
        return Long.compareUnsigned(a, b + bBytes) < 0 && Long.compareUnsigned(b, a + aBytes) < 0;
    }

    public void record(long command, long raw, long output, long flags, int quads) {
        library.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, program, raw, output, flags, quads);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) decode.invokeExact(MemorySegment.ofAddress(core), desc, error);
            if (status != BedrayCoreLayouts.STATUS_OK)
                throw new BedrayCoreException("decode_sodium", status, error.getString(BedrayCoreLayouts.ERROR_MESSAGE), null);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new BedrayCoreException("decode_sodium", BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
        }
    }
}
