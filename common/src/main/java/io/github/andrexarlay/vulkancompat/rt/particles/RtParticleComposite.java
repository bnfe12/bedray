package io.github.andrexarlay.vulkancompat.rt.particles;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayParticlesNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Game-layer adapter; C++ owns the AOT pipeline and immutable per-dispatch descriptors. */
final class RtParticleComposite {
    private static final int PUSH_BYTES = 16;
    private final SlangRuntime runtime;
    private final BedrayParticlesNative nativePass;
    private SlangProgram maskProgram;
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

    boolean record(VkCommandBuffer cmd, long layerView, long rtView, int width, int height, float strength,
                   RtMemory.Img mask, RtMemory.Img reactive, long motion, long depthView) {
        if (closed) throw new IllegalStateException("Particle composite is closed");
        if (cmd == null || rtView == 0 || layerView == rtView || width <= 0 || height <= 0
            || !Float.isFinite(strength) || strength < 0) throw new IllegalArgumentException("Invalid particle composite inputs");
        boolean writeMask = mask != null && reactive != null && mask.width == width && mask.height == height
            && reactive.width == width && reactive.height == height && (layerView != 0L || motion != 0L);
        if (writeMask && maskProgram == null) maskProgram = runtime.program("temporal_coverage_mask").orElse(null);
        writeMask &= maskProgram != null;
        if (layerView == 0L && !writeMask) return false;
        if (!recordings.add(cmd.address())) throw new IllegalStateException("Particle recording still pending");
        if (layerView != 0L) nativePass.record(cmd.address(), layerView, rtView, width, height, strength);
        if (!writeMask) return false;
        // Every texel is overwritten. UNDEFINED discards prior contents without a per-frame layout state on the CPU.
        VkBarriers.transition(cmd, mask.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
            VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkBarriers.transition(cmd, reactive.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
            VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var push = stack.calloc(16);
            push.putLong(0, motion).putInt(8, layerView != 0L ? 1 : 0);
            maskProgram.dispatch(cmd.address(), maskProgram.groups(width, 0), maskProgram.groups(height, 1), 1,
                List.of(SlangProgram.Resource.sampledImage("particleLayer", layerView != 0L ? layerView : depthView),
                    SlangProgram.Resource.storageImage("coverageMask", mask.view),
                    SlangProgram.Resource.storageImage("reactiveMask", reactive.view)), push);
        }
        return true;
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
