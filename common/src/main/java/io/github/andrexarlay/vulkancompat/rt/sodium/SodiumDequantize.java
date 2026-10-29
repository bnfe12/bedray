package io.github.andrexarlay.vulkancompat.rt.sodium;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedraySodiumNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Minecraft capture adapter. The native core owns the AOT pipeline and its recording lifetime. */
public final class SodiumDequantize {
    public static final int PUSH_BYTES = 32;
    private final SlangRuntime runtime;
    private final BedraySodiumNative nativePass;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean closed;

    public SodiumDequantize(SlangRuntime runtime) {
        if (runtime == null) throw new IllegalArgumentException("Slang runtime is required");
        this.runtime = runtime;
        var program = runtime.program("sodium_dequantize")
            .orElseThrow(() -> new IllegalStateException("Missing AOT Sodium decoder"));
        var manifest = program.manifest();
        int required = BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS | BedrayCoreLayouts.CAP_SHADER_INT64;
        if (!runtime.supports(required) || manifest.pushConstantBytes() != PUSH_BYTES
            || !manifest.bindings().isEmpty()
            || !java.util.Arrays.equals(manifest.threadGroupSize(), new int[] {64, 1, 1})) {
            throw new IllegalStateException("Sodium decoder requires BDA/Int64, 32-byte push constants and 64 threads");
        }
        nativePass = new BedraySodiumNative(runtime.library(), runtime.core(), program.nativeHandle());
    }

    /** Caller supplies nonoverlapping raw/arena ranges and orders the writes before AS build. */
    public void dispatch(VkCommandBuffer cmd, long rawAddr, long arenaAddr, long flagsAddr, int quadCount) {
        if (closed) throw new IllegalStateException("Sodium decoder is closed");
        if (cmd == null || rawAddr == 0 || arenaAddr == 0 || quadCount <= 0
            || quadCount > Integer.MAX_VALUE / 28 || ((rawAddr | arenaAddr | flagsAddr) & 3L) != 0) {
            throw new IllegalArgumentException("Invalid Sodium decode addresses/count");
        }
        // Several section decodes share one CB. One host proof releases all of
        // its immutable core dispatch records, rather than recycling by frame age.
        recordings.add(cmd.address());
        nativePass.record(cmd.address(), rawAddr, arenaAddr, flagsAddr, quadCount);
    }

    /** Only after the host drained submissions and forbids resubmission. */
    public void releaseRecordingsAfterHostDrain() {
        for (long cmd : List.copyOf(recordings)) {
            releaseRecording(cmd);
        }
    }

    /** Host proof of completion or deliberate discard for this recording. */
    public void releaseRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) return;
        runtime.releaseRecording(commandBuffer);
        recordings.remove(commandBuffer);
    }

    public void destroy() {
        if (closed) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("Sodium recordings require host completion");
        // The cached program is borrowed; SlangRuntime is its sole owner.
        closed = true;
    }
}
