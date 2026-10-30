package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Native producer recording only; asynchronous vendor-output leases remain host-owned. */
public final class BedrayFgPrepareNative {
    static final int FRAME_BYTES = 104;
    private final BedrayCoreNative library;
    private final long core, program, sampler;
    private final MethodHandle render;

    public BedrayFgPrepareNative(BedrayCoreNative library, long core, long program, long sampler) {
        if (library == null || core == 0 || program == 0 || sampler == 0)
            throw new IllegalArgumentException("FG producer needs the shared core, AOT program and sampler");
        this.library = library; this.core = core; this.program = program; this.sampler = sampler;
        render = library.resourceFunction("bedray_core_render_fg_prepare", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    public static void validate(long command, long program, long sampler, long sourceColor, long hudless,
                                long depth, long inputMotion, long outputDepth, long outputMotion,
                                int windowWidth, int windowHeight, int renderWidth, int renderHeight) {
        if (command == 0 || program == 0 || sampler == 0 || sourceColor == 0 || hudless == 0 || sourceColor == hudless
            || depth == 0 || inputMotion == 0 || outputDepth == 0 || outputMotion == 0 || outputDepth == outputMotion
            || outputDepth == depth || outputDepth == inputMotion || outputMotion == depth || outputMotion == inputMotion
            || windowWidth <= 0 || windowHeight <= 0 || renderWidth <= 0 || renderHeight <= 0)
            throw new IllegalArgumentException("invalid FG producer handles/extent");
    }

    static MemorySegment frame(Arena arena, long command, long program, long sampler, long sourceColor, long hudless,
                               long depth, long inputMotion, long outputDepth, long outputMotion,
                               int windowWidth, int windowHeight, int renderWidth, int renderHeight) {
        validate(command, program, sampler, sourceColor, hudless, depth, inputMotion, outputDepth, outputMotion,
            windowWidth, windowHeight, renderWidth, renderHeight);
        var result = arena.allocate(FRAME_BYTES, 8);
        result.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); result.set(JAVA_INT, 4, FRAME_BYTES);
        result.set(JAVA_LONG, 8, command); result.set(JAVA_LONG, 16, program); result.set(JAVA_LONG, 24, sampler);
        result.set(JAVA_LONG, 32, sourceColor); result.set(JAVA_LONG, 40, hudless);
        result.set(JAVA_LONG, 48, depth); result.set(JAVA_LONG, 56, inputMotion);
        result.set(JAVA_LONG, 64, outputDepth); result.set(JAVA_LONG, 72, outputMotion);
        result.set(JAVA_INT, 80, windowWidth); result.set(JAVA_INT, 84, windowHeight);
        result.set(JAVA_INT, 88, renderWidth); result.set(JAVA_INT, 92, renderHeight);
        result.set(JAVA_LONG, 96, 0);
        return result;
    }

    public void record(long command, long sourceColor, long hudless, long depth, long inputMotion, long outputDepth, long outputMotion,
                       int windowWidth, int windowHeight, int renderWidth, int renderHeight) {
        library.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, program, sampler, sourceColor, hudless, depth, inputMotion, outputDepth, outputMotion,
                windowWidth, windowHeight, renderWidth, renderHeight);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) render.invokeExact(MemorySegment.ofAddress(core), desc, error);
            if (status != BedrayCoreLayouts.STATUS_OK)
                throw new BedrayCoreException("render_fg_prepare", status, error.getString(BedrayCoreLayouts.ERROR_MESSAGE), null);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new BedrayCoreException("render_fg_prepare", BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
        }
    }
}
