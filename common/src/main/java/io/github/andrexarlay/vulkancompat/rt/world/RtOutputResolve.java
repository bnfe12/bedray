package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
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
        var push = ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());
        frame.forward.get(0, push); frame.right.get(16, push); frame.up.get(32, push);
        push.putFloat(48, frame.jitter.x).putFloat(52, frame.jitter.y);
        push.putInt(56, output.width).putInt(60, output.height).putInt(64, colorReady ? 1 : 0);
        recordings.add(command.address()); // retain even when native recording fails partway
        program.dispatch(command.address(), (output.width + 7) / 8, (output.height + 7) / 8, 1,
            List.of(SlangProgram.Resource.storageImage("rawColor", raw.view),
                SlangProgram.Resource.storageImage("rayDepth", images.depth().view),
                SlangProgram.Resource.storageImage("outputColor", output.view),
                SlangProgram.Resource.storageImage("outputDepth", images.displayDepth().view)), push);
    }

    void release(long command, boolean discarded) {
        if (!recordings.contains(command)) return;
        if (discarded) runtime.discardRecording(command); else runtime.releaseRecording(command);
        recordings.remove(command);
    }
    void afterHostDrain() { for (long command : List.copyOf(recordings)) release(command, false); }
}
