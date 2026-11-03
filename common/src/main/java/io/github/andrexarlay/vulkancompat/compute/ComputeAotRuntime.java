package io.github.andrexarlay.vulkancompat.compute;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.bedray.api.v1.AotComputeExecutor;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;

import java.util.Arrays;
import java.util.function.Consumer;

/** Shared native execution; the provider never depends on common or on RT graph ownership. */
final class ComputeAotRuntime implements AotComputeExecutor, AutoCloseable {
    private final SlangRuntimePool.Lease lease;
    private final RecordingCompletion completion;
    private long activeCommand;
    private boolean closed;

    ComputeAotRuntime(VulkanDevice device, Consumer<Throwable> reportFailure) {
        completion = new RecordingCompletion(reportFailure);
        lease = SlangRuntimePool.acquire(device);
    }

    ComputeRecording begin(long command) {
        if (closed || activeCommand != 0) throw new IllegalStateException("NoRT recording is already active or closed");
        var runtime = lease.runtime();
        var recording = new ComputeRecording(completion, command, new RecordingCompletion.Retirement(
            () -> runtime.releaseRecording(command), () -> runtime.discardRecording(command)));
        activeCommand = command;
        return recording;
    }

    void endRecording() { activeCommand = 0; }

    void afterHostDrain() { completion.afterHostDrain(); }

    @Override public Program prepare(String name, int pushBytes, int localX, int localY) {
        if (closed) throw new IllegalStateException("NoRT AOT runtime is closed");
        SlangProgram program = lease.runtime().program(name)
            .orElseThrow(() -> new IllegalStateException("Missing NoRT AOT program: " + name));
        var manifest = program.manifest();
        if (!"compute".equals(manifest.stage()) || manifest.requiredCapabilities() != 0
            || manifest.pushConstantBytes() != pushBytes
            || !Arrays.equals(manifest.threadGroupSize(), new int[]{localX, localY, 1}))
            throw new IllegalStateException("NoRT AOT contract mismatch: " + name);
        return (command, x, y, z, resources, push) -> {
            if (closed || activeCommand == 0 || activeCommand != command)
                throw new IllegalStateException("NoRT dispatch outside its host recording: " + name);
            var nativeResources = resources.stream().map(r -> new SlangProgram.Resource(
                r.name(), r.descriptorType(), r.imageLayout(), r.handle(), r.sampler(), r.offset(), r.range())).toList();
            program.dispatch(command, x, y, z, nativeResources, push);
        };
    }

    @Override public void close() {
        if (closed) return;
        if (activeCommand != 0 || completion.pendingCount() != 0)
            throw new IllegalStateException("NoRT runtime retirement without completion proof; recordings="
                + completion.pendingCount());
        lease.close();
        closed = true;
    }
}
