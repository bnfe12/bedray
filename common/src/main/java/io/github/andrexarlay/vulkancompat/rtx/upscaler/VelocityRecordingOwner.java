package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion.Retirement;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.function.Consumer;
import java.util.function.Function;

/** Owns the velocity runtime borrow and the exact submissions that also contain temporal SDK commands. */
public final class VelocityRecordingOwner implements AutoCloseable {
    private final RecordingCompletion completion;
    private SlangRuntimePool.Lease lease;
    private VelocityPipeline pipeline;
    private boolean recording, draining, closing;

    public VelocityRecordingOwner(Consumer<Throwable> reportFailure) {
        completion = new RecordingCompletion(reportFailure);
    }

    public VelocityPipeline pipeline() { return pipeline; }

    public VelocityPipeline ensure(VulkanDevice device) {
        if (closing) throw new IllegalStateException("velocity owner is closing; resources retained");
        if (lease != null && lease.runtime().hostDevice() != device)
            throw new IllegalStateException("velocity runtime belongs to a different host device");
        if (lease == null) lease = SlangRuntimePool.acquire(device);
        if (pipeline == null) pipeline = new VelocityPipeline(lease.runtime());
        return pipeline;
    }

    public boolean record(VulkanDevice device, Function<VkCommandBuffer, Boolean> commands) {
        if (recording || draining) throw new IllegalStateException("recursive temporal recording/drain");
        VelocityPipeline owner = ensure(device);
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VkCommandBuffer command = encoder.allocateAndBeginTransientCommandBuffer();
        long handle = command.address();
        var ticket = completion.beginWithDiscard(handle, new Retirement(
            () -> owner.releaseRecording(handle), () -> owner.discardRecording(handle)));
        boolean executeAttempted = false, discardAttempted = false;
        String stage = "commands";
        recording = true;
        try {
            if (!commands.apply(command)) {
                // A failed SDK call may have recorded commands. None of this CB may execute.
                discardAttempted = true;
                completion.discard(ticket);
                return false;
            }
            stage = "end";
            int status = VK10.vkEndCommandBuffer(command);
            if (status != VK10.VK_SUCCESS) throw new IllegalStateException("vkEndCommandBuffer returned " + status);
            completion.submissionAttempted(ticket);
            executeAttempted = true;
            stage = "execute";
            encoder.execute(command);
            stage = "enqueue_completion";
            completion.submitted(ticket, action -> encoder.queueForDestroy(action::run));
            return true;
        } catch (RuntimeException | Error failure) {
            if (!executeAttempted && !discardAttempted) {
                try { completion.discard(ticket); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            String context = "temporal recording stage=" + stage + " command=0x" + Long.toHexString(handle)
                + " outcome=" + (executeAttempted ? "unknown" : "unsubmitted") + " pending=" + completion.pendingCount();
            if (failure instanceof Error error) {
                error.addSuppressed(new IllegalStateException(context));
                throw error;
            }
            throw new IllegalStateException(context, failure);
        } finally {
            recording = false;
        }
    }

    /** Vendor contexts call this before destruction; an open recording can never be a completion proof. */
    public void drain(VulkanDevice expectedDevice) {
        if (recording || draining)
            throw new IllegalStateException("temporal retirement during an active recording/drain; resources retained");
        if (expectedDevice == null || VulkanCapabilityChecker.getVulkanDevice() != expectedDevice)
            throw new IllegalStateException("temporal context host device changed; resources retained");
        if (lease != null && lease.runtime().hostDevice() != expectedDevice)
            throw new IllegalStateException("temporal recording belongs to another host device; resources retained");
        drain();
    }

    public void drain() {
        if (completion.pendingCount() == 0) return;
        if (recording || draining) throw new IllegalStateException("temporal drain during an active recording; resources retained");
        if (lease == null) throw new IllegalStateException("temporal recordings lost their runtime lease; resources retained");
        VulkanDevice device = lease.runtime().hostDevice();
        if (VulkanCapabilityChecker.getVulkanDevice() != device)
            throw new IllegalStateException("temporal host device changed; resources retained");
        draining = true;
        try {
            VulkanCommandEncoder encoder = device.createCommandEncoder();
            try (var fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(5_000_000_000L))
                    throw new IllegalStateException("temporal host fence timed out; pending=" + completion.pendingCount());
                completion.afterHostDrain();
            }
            if (completion.pendingCount() != 0)
                throw new IllegalStateException("temporal recording proof unavailable; pending=" + completion.pendingCount());
        } finally {
            draining = false;
        }
    }

    @Override public void close() {
        if (recording || draining || completion.pendingCount() != 0)
            throw new IllegalStateException("temporal owner needs exact completion/discard before close");
        closing = true;
        if (pipeline != null) pipeline.cleanup();
        if (lease != null) lease.close();
        pipeline = null;
        lease = null;
        closing = false;
    }
}
