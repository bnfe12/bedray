package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.*;
import static io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts.*;

/** Native producer recording only; asynchronous vendor-output leases remain host-owned. */
public final class BedrayFgPrepareNative {
    static final int FRAME_BYTES = (int) FG_PREPARE_DESC.byteSize();
    private final BedrayCoreNative library;
    private final long core, program, sampler;

    public BedrayFgPrepareNative(BedrayCoreNative library, long core, long program, long sampler) {
        if (library == null || core == 0 || program == 0 || sampler == 0)
            throw new IllegalArgumentException("FG producer needs the shared core, AOT program and sampler");
        this.library = library; this.core = core; this.program = program; this.sampler = sampler;
        if (!library.hasFgPrepare()) throw new IllegalStateException("native core has no FG producer export");
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
        var result = arena.allocate(FG_PREPARE_DESC);
        result.set(JAVA_INT, FGP_ABI_VERSION, ABI_VERSION); result.set(JAVA_INT, FGP_STRUCT_SIZE, FRAME_BYTES);
        result.set(JAVA_LONG, FGP_COMMAND_BUFFER, command); result.set(JAVA_LONG, FGP_PROGRAM, program); result.set(JAVA_LONG, FGP_SAMPLER, sampler);
        result.set(JAVA_LONG, FGP_SOURCE_COLOR_IMAGE, sourceColor); result.set(JAVA_LONG, FGP_HUDLESS_IMAGE, hudless);
        result.set(JAVA_LONG, FGP_DEPTH_VIEW, depth); result.set(JAVA_LONG, FGP_INPUT_MOTION_VIEW, inputMotion);
        result.set(JAVA_LONG, FGP_OUTPUT_DEPTH_VIEW, outputDepth); result.set(JAVA_LONG, FGP_OUTPUT_MOTION_VIEW, outputMotion);
        result.set(JAVA_INT, FGP_WINDOW_WIDTH, windowWidth); result.set(JAVA_INT, FGP_WINDOW_HEIGHT, windowHeight);
        result.set(JAVA_INT, FGP_RENDER_WIDTH, renderWidth); result.set(JAVA_INT, FGP_RENDER_HEIGHT, renderHeight);
        result.set(JAVA_LONG, FGP_RESERVED, 0);
        return result;
    }

    public void record(long command, long sourceColor, long hudless, long depth, long inputMotion, long outputDepth, long outputMotion,
                       int windowWidth, int windowHeight, int renderWidth, int renderHeight) {
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, program, sampler, sourceColor, hudless, depth, inputMotion, outputDepth, outputMotion,
                windowWidth, windowHeight, renderWidth, renderHeight);
            library.renderFgPrepare(core, desc);
        }
    }
}
