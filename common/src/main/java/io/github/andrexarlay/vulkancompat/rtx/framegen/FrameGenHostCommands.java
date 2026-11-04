package io.github.andrexarlay.vulkancompat.rtx.framegen;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;

/** Host-only command allocation, submission and completion for both FG providers. */
public final class FrameGenHostCommands {
    private final FrameGenProducerRecordings recordings;
    private VulkanDevice device;

    public FrameGenHostCommands(Consumer<Throwable> reportFailure) {
        recordings = new FrameGenProducerRecordings(reportFailure);
    }

    public void record(VulkanDevice host, FrameGenGpu owner, Consumer<VkCommandBuffer> commands) {
        record(host, owner, null, commands);
    }

    public void record(VulkanDevice host, FrameGenGpu owner, FrameGenGuideReads.Read source,
                       Consumer<VkCommandBuffer> commands) {
        if (host == null || owner == null || owner.hostDevice() != host)
            throw new IllegalArgumentException("FG producer owner belongs to another host device");
        if (device != null && device != host && recordings.pendingCount() != 0)
            throw new IllegalStateException("FG producer device changed; resources retained");
        device = host;
        var encoder = host.createCommandEncoder();
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        long handle = command.address();
        recordings.record(new FrameGenProducerRecordings.Submission() {
            @Override public long commandBuffer() { return handle; }
            @Override public void end() {
                int result = VK10.vkEndCommandBuffer(command);
                if (result != VK10.VK_SUCCESS) throw new IllegalStateException("FG vkEndCommandBuffer failed: " + result);
            }
            @Override public void execute() { encoder.execute(command); }
            @Override public void afterCompletion(Runnable action) { encoder.queueForDestroy(action::run); }
        }, retirements(owner, source, handle), () -> {
            if (source != null) source.retain(handle);
            commands.accept(command);
        });
    }

    private static RecordingCompletion.Retirement[] retirements(FrameGenGpu owner, FrameGenGuideReads.Read source, long handle) {
        var output = new RecordingCompletion.Retirement(() -> owner.releaseRecording(handle), () -> owner.discardRecording(handle));
        return source == null ? new RecordingCompletion.Retirement[]{output}
            : new RecordingCompletion.Retirement[]{output, source.retirement()};
    }

    /** May run before source-target resizing without ending the vendor output-image lease. */
    public void drain() {
        recordings.drain(() -> {
            if (device == null) throw new IllegalStateException("FG producer has no host device");
            var encoder = device.createCommandEncoder();
            try (var fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(5_000_000_000L))
                    throw new IllegalStateException("FG host completion timed out; resources retained");
            }
        });
    }
}
