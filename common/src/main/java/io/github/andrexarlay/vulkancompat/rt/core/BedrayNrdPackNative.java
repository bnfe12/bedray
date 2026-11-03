package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import static java.lang.foreign.ValueLayout.*;

/** Coarse signal-conversion phases around the external denoiser dispatch. */
public final class BedrayNrdPackNative {
    static final int FRAME_BYTES = 200;
    public static final int PACK = 0, COMPOSE = 1, VIEW_COUNT = 14, PUSH_BYTES = 56;
    private final BedrayCoreNative library;
    private final long core;
    private final MethodHandle render;

    public record Settings(int normal, int roughness, int denoiser, int flags,
                           float hitA, float hitB, float hitC, float range, float exposure, long motion) {
        public Settings {
            if (roughness < 0 || roughness > 2 || denoiser < 0 || denoiser > 2 || (flags & ~3) != 0
                || (denoiser == 2 ? normal != 0 : normal != 2 && normal != 4)
                || !Float.isFinite(hitA) || hitA <= 0 || !Float.isFinite(hitB) || hitB < 0
                || !Float.isFinite(hitC) || hitC <= 0 || !Float.isFinite(range) || range <= 0
                || !Float.isFinite(exposure) || exposure < 0 || (motion & 15) != 0)
                throw new IllegalArgumentException("invalid NRD pack settings");
        }
        public static Settings fromPush(ByteBuffer input, int width, int height) {
            if (input == null || input.capacity() < PUSH_BYTES || input.getInt(32) != width || input.getInt(36) != height)
                throw new IllegalArgumentException("NRD push extent/size mismatch");
            return new Settings(input.getInt(0), input.getInt(4), input.getInt(8), input.getInt(12),
                input.getFloat(16), input.getFloat(20), input.getFloat(24), input.getFloat(28), input.getFloat(40), input.getLong(48));
        }
    }

    public BedrayNrdPackNative(BedrayCoreNative library, long core) {
        if (library == null || core == 0) throw new IllegalArgumentException("NRD pack needs the shared core");
        this.library = library;
        this.core = core;
        render = library.resourceFunction("bedray_core_render_nrd_pack", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    public static void validate(long command, long program, long[] views, int phase, int width, int height, Settings settings) {
        if (command == 0 || program == 0 || views == null || views.length != VIEW_COUNT || settings == null
            || phase < PACK || phase > COMPOSE || width <= 0 || height <= 0)
            throw new IllegalArgumentException("invalid NRD pack frame");
        for (long view : views) if (view == 0) throw new IllegalArgumentException("NRD pack needs every borrowed view");
        long pixels = (long) width * height;
        if (pixels > 0xffff_ffffL) throw new IllegalArgumentException("NRD motion index overflow");
        long bytes = pixels * 16;
        if (settings.motion != 0 && Long.compareUnsigned(settings.motion + bytes, settings.motion) < 0)
            throw new IllegalArgumentException("NRD motion address overflow");
    }

    static MemorySegment frame(Arena arena, long command, long program, long[] views, int phase, int width, int height, Settings settings) {
        validate(command, program, views, phase, width, height, settings);
        var result = arena.allocate(FRAME_BYTES, 8);
        result.set(JAVA_INT, 0, BedrayCoreLayouts.ABI_VERSION); result.set(JAVA_INT, 4, FRAME_BYTES);
        result.set(JAVA_LONG, 8, command); result.set(JAVA_LONG, 16, program);
        for (int index = 0; index < VIEW_COUNT; ++index) result.set(JAVA_LONG, 24L + index * 8L, views[index]);
        result.set(JAVA_INT, 136, phase); result.set(JAVA_INT, 140, width); result.set(JAVA_INT, 144, height);
        result.set(JAVA_INT, 148, settings.normal); result.set(JAVA_INT, 152, settings.roughness);
        result.set(JAVA_INT, 156, settings.denoiser); result.set(JAVA_INT, 160, settings.flags);
        result.set(JAVA_FLOAT, 164, settings.hitA); result.set(JAVA_FLOAT, 168, settings.hitB);
        result.set(JAVA_FLOAT, 172, settings.hitC); result.set(JAVA_FLOAT, 176, settings.range);
        result.set(JAVA_FLOAT, 180, settings.exposure); result.set(JAVA_LONG, 184, settings.motion);
        result.set(JAVA_LONG, 192, 0);
        return result;
    }

    public void record(long command, long program, long[] views, int phase, int width, int height, Settings settings) {
        library.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            var desc = frame(arena, command, program, views, phase, width, height, settings);
            var error = arena.allocate(BedrayCoreLayouts.ERROR);
            int status = (int) render.invokeExact(MemorySegment.ofAddress(core), desc, error);
            if (status != BedrayCoreLayouts.STATUS_OK)
                throw new BedrayCoreException("render_nrd_pack", status, error.getString(BedrayCoreLayouts.ERROR_MESSAGE), null);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new BedrayCoreException("render_nrd_pack", BedrayCoreLayouts.STATUS_INTERNAL, failure.toString(), failure);
        }
    }
}
