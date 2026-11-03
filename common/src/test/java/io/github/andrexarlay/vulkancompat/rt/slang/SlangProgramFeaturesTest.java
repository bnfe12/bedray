package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SlangProgramFeaturesTest {
    private static final int BASE = BedrayCoreLayouts.CAP_RAY_QUERY | BedrayCoreLayouts.CAP_ACCELERATION_STRUCTURE
        | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS | BedrayCoreLayouts.CAP_SHADER_INT64;
    private static final int OPTIONAL = BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE | BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV;

    @Test void oldDllKeepsComputeCapabilitiesEvenWhenHostEnabledRayPipelineForAnotherPack() {
        assertEquals(BASE, SlangProgramFeatures.compatibleCapabilities(BASE | OPTIONAL, 0));
    }
    @Test void raygenDllDoesNotPretendToImplementSer() {
        assertEquals(BASE | BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE,
            SlangProgramFeatures.compatibleCapabilities(BASE | OPTIONAL, BedrayCoreLayouts.PROGRAM_RAYGEN));
    }
    @Test void completeDllPreservesOnlyFeaturesTheHostActuallyEnabled() {
        int implemented = BedrayCoreLayouts.PROGRAM_RAYGEN | BedrayCoreLayouts.PROGRAM_SER_NV;
        assertEquals(BASE, SlangProgramFeatures.compatibleCapabilities(BASE, implemented));
        assertEquals(BASE | OPTIONAL, SlangProgramFeatures.compatibleCapabilities(BASE | OPTIONAL, implemented));
    }
    @Test void malformedSerOnlyImplementationCannotEnableEitherRayFeature() {
        assertEquals(BASE, SlangProgramFeatures.compatibleCapabilities(BASE | OPTIONAL, BedrayCoreLayouts.PROGRAM_SER_NV));
    }
}
