package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Additive configuration only; the existing native path group owns every RC dispatch and recording lease. */
public final class BedrayRadianceCascadesNative {
    static final int DESC_BYTES = 32;
    private final BedrayCoreNative library;
    private final long core, builder;
    private final int axis;
    private final MethodHandle configure;

    public BedrayRadianceCascadesNative(BedrayCoreNative library, long core, long builder, int axis) {
        if (library == null || core == 0 || builder == 0) throw new IllegalArgumentException("RC needs the shared core and builder");
        requireAxis(axis);
        this.library=library; this.core=core; this.builder=builder; this.axis=axis;
        configure=library.resourceFunction("bedray_core_configure_radiance_cascades",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }
    private static void requireAxis(int axis) {
        if (axis < 8 || axis > 32 || (axis & (axis-1)) != 0)
            throw new IllegalArgumentException("RC base axis must be 8, 16 or 32");
    }
    static MemorySegment descriptor(Arena arena, long builder, int axis, long budget) {
        requireAxis(axis);
        if (builder == 0 || budget < 0) throw new IllegalArgumentException("RC builder/budget is invalid");
        var out=arena.allocate(DESC_BYTES,8);
        out.set(JAVA_INT,0,BedrayCoreLayouts.ABI_VERSION); out.set(JAVA_INT,4,DESC_BYTES);
        out.set(JAVA_LONG,8,builder); out.set(JAVA_INT,16,axis); out.set(JAVA_INT,20,0);
        out.set(JAVA_LONG,24,budget); return out;
    }
    public void configure() {
        library.ensureOpen();
        try (Arena arena=Arena.ofConfined()) {
            var desc=descriptor(arena,builder,axis,0);
            var error=arena.allocate(BedrayCoreLayouts.ERROR);
            int status=(int)configure.invokeExact(MemorySegment.ofAddress(core),desc,error);
            if (status != BedrayCoreLayouts.STATUS_OK)
                throw new BedrayCoreException("configure_radiance_cascades",status,
                    error.getString(BedrayCoreLayouts.ERROR_MESSAGE),null);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) {
            throw new BedrayCoreException("configure_radiance_cascades",BedrayCoreLayouts.STATUS_INTERNAL,failure.toString(),failure);
        }
    }
}
