package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;

/** Validated borrowed AOT program. Native records the complete chain and owns descriptors. */
final class SpatialComputePass {
    enum Stage {
        EASU("fsr1_easu", 80, 8, 8), RCAS("fsr1_rcas", 32, 8, 8), NIS("nis_scaler", 112, 128, 1);
        final String programName;
        final int pushBytes, threadsX, threadsY;
        Stage(String name, int bytes, int x, int y) { programName = name; pushBytes = bytes; threadsX = x; threadsY = y; }
    }
    private final SlangProgram program;
    SpatialComputePass(SlangRuntime runtime, Stage stage) {
        program = runtime.program(stage.programName).orElseThrow(() -> new IllegalStateException(stage.programName + " AOT program is missing"));
        var manifest = program.manifest();
        if (manifest.pushConstantBytes() != stage.pushBytes || manifest.bindings().size() != 2
            || !java.util.Arrays.equals(manifest.threadGroupSize(), new int[]{stage.threadsX, stage.threadsY, 1}))
            throw new IllegalStateException("invalid spatial AOT contract: " + stage.programName);
        var input = manifest.binding("spatialInput"); var output = manifest.binding("spatialOutput");
        if (input == null || input.binding() != 0 || input.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
            || output == null || output.binding() != 1 || output.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)
            throw new IllegalStateException("invalid spatial image bindings: " + stage.programName);
    }
    long nativeHandle() { return program.nativeHandle(); }
}
