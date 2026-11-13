package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreException;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.RtRayPipelineDeviceRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Optional transport scheduling; geometry, signal ownership and sampling stay in the shared Slang integrator. */
public final class SlangTracePrograms {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/TraceBackend");
    private SlangTracePrograms() {}

    public static Optional<SlangProgram> select(SlangRuntime runtime, String base) {
        boolean ser = Boolean.getBoolean(RtRayPipelineDeviceRequirements.SER_PROPERTY);
        boolean raygen = RtRayPipelineDeviceRequirements.raygenRequested();
        boolean reorder = ser && runtime.supportsInvocationReordering();
        int enabled = runtime.capabilities().flags();
        for (String name : candidates(base, enabled, raygen, ser, reorder)) {
            if (!SlangPassLibrary.available(name)) continue;
            try {
                Optional<SlangProgram> program = runtime.program(name);
                if (program.isPresent()) {
                    LOGGER.info("[VKB-RAYGEN] pass={} stage={} raygen_requested={} ser_requested={} reordering_hint={} enabled=0x{}",
                        name, program.get().manifest().stage(), raygen, ser, reorder ? "reorder" : "unavailable",
                        Integer.toHexString(enabled));
                    return program;
                }
            } catch (RuntimeException failure) {
                if (name.equals(base) || failure instanceof BedrayCoreException nativeFailure
                    && nativeFailure.status() == BedrayCoreLayouts.STATUS_DEVICE_LOST) throw failure;
                LOGGER.warn("[VKB-RAYGEN] pass={} refused; trying baseline scheduling: {}", name, failure.getMessage());
            }
        }
        return Optional.empty();
    }

    static List<String> candidates(String base, int enabled, boolean raygen, boolean ser, boolean reorderHint) {
        var result = new ArrayList<String>(3);
        if ((raygen || ser) && (enabled & BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE) != 0) {
            if (ser && reorderHint && (enabled & BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV) != 0)
                result.add(base + ".ser");
            result.add(base + ".raygen");
        }
        result.add(base);
        return List.copyOf(result);
    }
}
