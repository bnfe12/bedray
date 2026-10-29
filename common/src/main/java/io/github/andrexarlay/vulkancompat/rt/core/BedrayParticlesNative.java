package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** One coarse call; native core owns push/descriptor packing and barriers. No private GPU resources. */
public final class BedrayParticlesNative {
    static final int FRAME_BYTES = 56;
    private final BedrayCoreNative library;
    private final long core, program;
    private final MethodHandle render;

    public BedrayParticlesNative(BedrayCoreNative library, long core, long program) {
        if (core == 0 || program == 0) throw new IllegalArgumentException("core and particle program are required");
        this.library = library;
        this.core = core;
        this.program = program;
        render = library.resourceFunction("bedray_core_render_particles",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    static MemorySegment frame(Arena arena, long command, long program, long layer, long output,
                               int width, int height, float strength) {
        if (command == 0 || program == 0 || layer == 0 || output == 0 || layer == output || width <= 0 || height <= 0
            || !Float.isFinite(strength) || strength < 0)
            throw new IllegalArgumentException("particle recording needs a program, distinct views, positive extent and finite strength");
        var desc = arena.allocate(FRAME_BYTES, 8);
        desc.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); desc.set(JAVA_INT, 4, FRAME_BYTES);
        desc.set(JAVA_LONG, 8, command); desc.set(JAVA_LONG, 16, program);
        desc.set(JAVA_LONG, 24, layer); desc.set(JAVA_LONG, 32, output);
        desc.set(JAVA_INT, 40, width); desc.set(JAVA_INT, 44, height);
        desc.set(JAVA_FLOAT, 48, strength); desc.set(JAVA_INT, 52, 0);
        return desc;
    }

    public void record(long command, long layer, long output, int width, int height, float strength) {
        library.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, program, layer, output, width, height, strength);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) render.invokeExact(MemorySegment.ofAddress(core), desc, error);
            if (status != BedrayCoreLayouts.STATUS_OK)
                throw new BedrayCoreException("render_particles", status, error.getString(BedrayCoreLayouts.ERROR_MESSAGE), null);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new BedrayCoreException("render_particles", BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
        }
    }
}
