package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NativePipelineCacheTest {
    @Test void nativeRegistrationBorrowsSharedCacheUnderTheDeviceOwnerAndKeepsOldDllFallback() throws Exception {
        String runtime = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java"));
        String cached = "RtVulkanPipelineCache.withCache(device, cache -> SlangProgram.register(library, core, pass.get(), cache))";
        assertTrue(runtime.contains(cached));
        assertTrue(runtime.indexOf("SlangPassDriverGate.blocker(name, pass.get().spirv())") < runtime.indexOf(cached));
        assertTrue(runtime.contains(": SlangProgram.register(library, core, pass.get());"));
        assertTrue(runtime.contains("native_export_unavailable"));
        String ffi = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java"));
        assertTrue(ffi.contains("bindOptional(linker, lookup, \"bedray_core_register_program_cached\""));
        assertTrue(ffi.contains("registerProgramCachedHandle.invokeExact(handle(core), desc, pipelineCache, out, error)"));
        assertTrue(ffi.contains(": (int) registerProgramHandle.invokeExact(handle(core), desc, out, error)"));
    }
}
