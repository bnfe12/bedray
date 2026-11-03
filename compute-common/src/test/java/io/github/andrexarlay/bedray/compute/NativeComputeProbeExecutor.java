package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.AotComputeExecutor;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real native core on the probe's zero-extension device; the test remains submission owner. */
final class NativeComputeProbeExecutor implements AotComputeExecutor, AutoCloseable {
    private final BedrayCoreNative library;
    private final long core;
    private final List<SlangProgram> programs = new ArrayList<>();

    NativeComputeProbeExecutor(VkDevice device, VkQueue queue, int family) {
        String path = System.getProperty("bedray.compute.corePath");
        if (path == null) throw new IllegalStateException("Set -PbedrayCorePath to the tested renderer core DLL");
        library = BedrayCoreNative.load(Path.of(path));
        try {
            var physical = device.getPhysicalDevice();
            core = library.create(physical.getInstance().address(), physical.address(), device.address(), queue.address(),
                family, VK11.VK_API_VERSION_1_1, VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"),
                device.getCapabilities().vkGetDeviceProcAddr, 0);
        } catch (RuntimeException | Error error) { library.close(); throw error; }
    }

    @Override public Program prepare(String name, int pushBytes, int localX, int localY) {
        var pass = SlangPassLibrary.load(name).orElseThrow();
        var m = pass.manifest();
        if (!"compute".equals(m.stage()) || m.requiredCapabilities() != 0 || m.pushConstantBytes() != pushBytes
            || !Arrays.equals(m.threadGroupSize(), new int[]{localX, localY, 1}))
            throw new IllegalStateException("Bad NoRT probe contract: " + name);
        var program = SlangProgram.register(library, core, pass);
        programs.add(program);
        return (cmd, x, y, z, resources, push) -> program.dispatch(cmd, x, y, z, resources.stream().map(r ->
            new SlangProgram.Resource(r.name(), r.descriptorType(), r.imageLayout(), r.handle(), r.sampler(), r.offset(), r.range())).toList(), push);
    }

    void completed(long command) { library.releaseRecording(core, command); }
    void discarded(long command) { library.discardRecording(core, command); }

    @Override public void close() {
        for (var program : programs) program.close();
        library.destroy(core);
        library.close();
    }
}
