package io.github.andrexarlay.vulkancompat.rt.particles;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayParticlesNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Game-layer adapter; C++ owns the AOT pipeline and immutable per-dispatch descriptors. */
final class RtParticleComposite {
    private static final int PUSH_BYTES = 16;
    private final SlangRuntime runtime;
    private final BedrayParticlesNative nativePass;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean closed;

    RtParticleComposite(SlangRuntime runtime) {
        this.runtime = runtime;
        var program = runtime.program("particles_composite")
            .orElseThrow(() -> new IllegalStateException("Missing AOT particle composite"));
        var manifest = program.manifest();
        var input = manifest.binding("particleLayer");
        var output = manifest.binding("rtImage");
        if (manifest.pushConstantBytes() != PUSH_BYTES || manifest.bindings().size() != 2
            || !java.util.Arrays.equals(manifest.threadGroupSize(), new int[]{8, 8, 1})
            || input == null || input.binding() != 0 || input.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_SAMPLED_IMAGE
            || output == null || output.binding() != 2 || output.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE) {
            throw new IllegalStateException("Invalid particle composite manifest");
        }
        nativePass = new BedrayParticlesNative(runtime.library(), runtime.core(), program.nativeHandle());
    }

    void record(VkCommandBuffer cmd, long layerView, long rtView, int width, int height, float strength) {
        if (closed) throw new IllegalStateException("Particle composite is closed");
        if (cmd == null || layerView == 0 || rtView == 0 || layerView == rtView || width <= 0 || height <= 0
            || !Float.isFinite(strength) || strength < 0) throw new IllegalArgumentException("Invalid particle composite inputs");
        if (!recordings.add(cmd.address())) throw new IllegalStateException("Particle recording still pending");
        nativePass.record(cmd.address(), layerView, rtView, width, height, strength);
    }

    void releaseRecordingsAfterHostDrain() {
        for (long cmd : List.copyOf(recordings)) {
            releaseRecording(cmd);
        }
    }

    void releaseRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) return;
        runtime.releaseRecording(commandBuffer);
        recordings.remove(commandBuffer);
    }

    void destroyAfterHostDrain() {
        if (closed) return;
        releaseRecordingsAfterHostDrain();
        // SlangRuntime owns the cached program. Recreating this adapter must not
        // receive a program that a previous adapter has already destroyed.
        closed = true;
    }
}
