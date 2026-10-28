package io.github.andrexarlay.vulkancompat.rt.particles;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Game-layer adapter; C++ owns the AOT pipeline and immutable per-dispatch descriptors. */
final class RtParticleComposite {
    private static final int PUSH_BYTES = 16;
    private final SlangRuntime runtime;
    private final SlangProgram program;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean closed;

    RtParticleComposite(SlangRuntime runtime) {
        this.runtime = runtime;
        this.program = runtime.program("particles_composite")
            .orElseThrow(() -> new IllegalStateException("Missing AOT particle composite"));
        var manifest = program.manifest();
        var input = manifest.binding("particleLayer");
        var output = manifest.binding("rtImage");
        if (manifest.pushConstantBytes() != PUSH_BYTES || manifest.bindings().size() != 2
            || input == null || input.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_SAMPLED_IMAGE
            || output == null || output.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE) {
            throw new IllegalStateException("Invalid particle composite manifest");
        }
    }

    void record(VkCommandBuffer cmd, long layerView, long rtView, int width, int height, float strength) {
        if (closed) throw new IllegalStateException("Particle composite is closed");
        if (cmd == null || layerView == 0 || rtView == 0 || layerView == rtView || width <= 0 || height <= 0
            || !Float.isFinite(strength) || strength < 0) throw new IllegalArgumentException("Invalid particle composite inputs");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, width).putInt(4, height).putFloat(8, strength).putFloat(12, 0);
            recordings.add(cmd.address());
            program.dispatch(cmd.address(), program.groups(width, 0), program.groups(height, 1), 1,
                List.of(SlangProgram.Resource.sampledImage("particleLayer", layerView),
                    SlangProgram.Resource.storageImage("rtImage", rtView)), push);
        }
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
        program.close();
        closed = true;
    }
}
