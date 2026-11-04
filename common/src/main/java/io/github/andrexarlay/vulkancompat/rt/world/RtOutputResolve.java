package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One native AOT pass: output-grid depth and the raw fallback when RR skipped. Images belong to RtFrameResources. */
final class RtOutputResolve {
    private final SlangRuntime runtime;
    private final SlangProgram program;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private final RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();

    RtOutputResolve(SlangRuntime runtime) {
        this.runtime = runtime;
        program = runtime.program("output_resolve").orElseThrow(() -> new IllegalStateException("Missing output resolve AOT"));
        if (program.manifest().pushConstantBytes() != 80 || program.manifest().bindings().size() != 4)
            throw new IllegalStateException("Invalid output resolve manifest");
    }

    void record(VkCommandBuffer command, RtFrameParams params, RtFrameResources images, boolean colorReady) {
        var raw = images.output();
        var output = images.composite();
        RtSlangFrameData.fill(frame, params, raw.width, raw.height, params.frame, 0);
        // JOML's native-order ByteBuffer writes go through Unsafe and read the buffer's address field; a heap buffer
        // has address 0 there and the write faults. The push block must be direct.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            writePush(push, frame, output.width, output.height, colorReady);
            recordings.add(command.address()); // retain even when native recording fails partway
            program.dispatch(command.address(), (output.width + 7) / 8, (output.height + 7) / 8, 1,
                List.of(SlangProgram.Resource.storageImage("rawColor", raw.view),
                    SlangProgram.Resource.storageImage("rayDepth", images.depth().view),
                    SlangProgram.Resource.storageImage("outputColor", output.view),
                    SlangProgram.Resource.storageImage("outputDepth", images.displayDepth().view)), push);
        }
    }

    static final int PUSH_BYTES = 80;

    /** JOML writes through Unsafe for native-order buffers, so a heap buffer (address 0) would fault. */
    static void writePush(ByteBuffer push, RtPathTraceSlangPass.Frame frame, int width, int height, boolean colorReady) {
        if (push == null || !push.isDirect() || push.capacity() < PUSH_BYTES)
            throw new IllegalArgumentException("output resolve push needs a direct " + PUSH_BYTES + "-byte buffer");
        frame.forward.get(0, push); frame.right.get(16, push); frame.up.get(32, push);
        push.putFloat(48, frame.jitter.x).putFloat(52, frame.jitter.y);
        push.putInt(56, width).putInt(60, height).putInt(64, colorReady ? 1 : 0);
    }

    void release(long command, boolean discarded) {
        if (!recordings.contains(command)) return;
        if (discarded) runtime.discardRecording(command); else runtime.releaseRecording(command);
        recordings.remove(command);
    }
    void afterHostDrain() { for (long command : List.copyOf(recordings)) release(command, false); }
}
