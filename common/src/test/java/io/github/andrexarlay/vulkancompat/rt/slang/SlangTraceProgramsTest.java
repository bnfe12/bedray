package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SlangTraceProgramsTest {
    private static final int RAY = BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE;
    private static final int SER = BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV;
    @Test void explicitComputePreferenceIgnoresAvailablePipelineFeatures() {
        assertEquals(List.of("path_trace_scene"), SlangTracePrograms.candidates("path_trace_scene", RAY | SER, false, false, true));
    }
    @Test void ordinaryRaygenPreferenceUsesComputeIfTheDeviceLacksThePipeline() {
        assertEquals(List.of("path_trace_scene"), SlangTracePrograms.candidates("path_trace_scene", 0, true, false, false));
        assertEquals(List.of("path_trace_scene.raygen", "path_trace_scene"),
            SlangTracePrograms.candidates("path_trace_scene", RAY | SER, true, false, true));
    }
    @Test void serRequestIncludesFallbacksInOrder() {
        assertEquals(List.of("path_trace_scene.ser", "path_trace_scene.raygen", "path_trace_scene"),
            SlangTracePrograms.candidates("path_trace_scene", RAY | SER, false, true, true));
    }
    @Test void noopHintUsesOrdinaryRaygenRatherThanClaimingReordering() {
        assertEquals(List.of("path_trace_scene.raygen", "path_trace_scene"),
            SlangTracePrograms.candidates("path_trace_scene", RAY | SER, false, true, false));
    }
    @Test void unavailableSerKeepsRaygenWhileUnavailableRaygenKeepsCompute() {
        assertEquals(List.of("path_trace_scene.raygen", "path_trace_scene"),
            SlangTracePrograms.candidates("path_trace_scene", RAY, false, true, true));
        assertEquals(List.of("path_trace_scene"), SlangTracePrograms.candidates("path_trace_scene", SER, false, true, true));
    }
    @Test void sceneAndFieldVariantsUseTheSameBackendPolicy() {
        for (String base : List.of("path_trace_scene", "rc_path_trace_scene"))
            assertEquals(List.of(base + ".ser", base + ".raygen", base),
                SlangTracePrograms.candidates(base, RAY | SER, true, true, true));
    }
}
