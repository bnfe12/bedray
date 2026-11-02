package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;

/** Compatibility of additive program kinds with an older ABI-v2 DLL. */
final class SlangProgramFeatures {
    private SlangProgramFeatures() {}

    static int compatibleCapabilities(int enabled, int programFeatures) {
        if ((programFeatures & BedrayCoreLayouts.PROGRAM_RAYGEN) == 0)
            enabled &= ~(BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE | BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV);
        if ((programFeatures & BedrayCoreLayouts.PROGRAM_SER_NV) == 0)
            enabled &= ~BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV;
        return enabled;
    }
}
