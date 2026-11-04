package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** RR/FG hardware-depth encoding from the actual trace's primary sample; host still owns submission. */
final class RrHardwareDepth {
    static final int PUSH_BYTES = 128;
    private final SlangRuntime runtime;
    private final SlangProgram program;
    private final Set<Long> recordings = new LinkedHashSet<>();

    RrHardwareDepth(SlangRuntime runtime) {
        this.runtime = runtime;
        program = runtime.program("rr_depth").orElseThrow(() -> new IllegalStateException("Missing RR hardware-depth AOT"));
        var m = program.manifest();
        if (m.pushConstantBytes() != PUSH_BYTES || m.bindings().size() != 2 || m.requiredCapabilities() != 0)
            throw new IllegalStateException("Invalid RR hardware-depth AOT contract");
    }

    void record(VkCommandBuffer command, RtPathTraceSlangPass.Frame frame, RtMemory.Img source, RtMemory.Img output) {
        if (source.width != frame.width || source.height != frame.height
            || output.width != frame.width || output.height != frame.height)
            throw new IllegalArgumentException("RR depth producer/image extent mismatch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            writePush(push, frame);
            recordings.add(command.address());
            program.dispatch(command.address(), program.groups(frame.width, 0), program.groups(frame.height, 1), 1,
                List.of(SlangProgram.Resource.storageImage("linearDepth", source.view),
                    SlangProgram.Resource.storageImage("hardwareDepth", output.view)), push);
        }
    }

    static void writePush(ByteBuffer push, RtPathTraceSlangPass.Frame frame) {
        if (push == null || !push.isDirect() || push.capacity() < PUSH_BYTES)
            throw new IllegalArgumentException("RR depth push needs a direct 128-byte buffer");
        frame.forward.get(0, push); frame.right.get(16, push); frame.up.get(32, push); frame.origin.get(48, push);
        frame.viewProjection.getRow(2, new Vector4f()).get(64, push);
        frame.viewProjection.getRow(3, new Vector4f()).get(80, push);
        frame.jitter.get(96, push);
        push.putInt(112, frame.width).putInt(116, frame.height).putLong(120, 0L);
    }

    void release(long command, boolean discarded) {
        if (!recordings.contains(command)) return;
        if (discarded) runtime.discardRecording(command); else runtime.releaseRecording(command);
        recordings.remove(command);
    }

    void afterHostDrain() { for (long command : List.copyOf(recordings)) release(command, false); }
}
