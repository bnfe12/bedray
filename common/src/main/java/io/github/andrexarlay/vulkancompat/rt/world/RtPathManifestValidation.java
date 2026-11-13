package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;

import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.*;

/**
 * The manifest contract check for {@link RtPathTraceSlangPass}. The shader is the source of truth, so a mismatch falls
 * back to the legacy path instead of binding a wrong image or pushing a short block. The pass supplies the binding tables
 * ({@code EXPECTED}/{@code PT_SPATIAL_EXPECTED}) and the byte constants; this owns the algorithm that checks a generated
 * manifest against them before the first dispatch.
 */
final class RtPathManifestValidation {

    private RtPathManifestValidation() {
    }

    /**
     * Checks the generated manifest against the resources the pass supplies. The shader is the source of truth, so a
     * mismatch falls back to the legacy path instead of binding a wrong image or pushing a short block.
     */
    static void requireManifest(SlangProgram program) {
        requireManifest(program, false);
    }

    static void requireManifest(SlangProgram program, boolean radianceCascades) {
        SlangPassManifest manifest = program.manifest();
        if (manifest.pushConstantBytes() != PUSH_BYTES) {
            throw new IllegalStateException(program.name() + " pushes " + manifest.pushConstantBytes()
                + " B, host block is " + PUSH_BYTES + " B");
        }
        int count = EXPECTED.size() + (radianceCascades ? 2 : 0);
        if (manifest.bindings().size() != count) {
            throw new IllegalStateException(program.name() + " declares " + manifest.bindings().size()
                + " binding(s), host supplies " + count);
        }
        for (Expected want : EXPECTED) {
            SlangPassManifest.Binding binding = manifest.binding(want.name());
            if (binding == null) {
                throw new IllegalStateException(program.name() + " has no '" + want.name() + "' binding");
            }
            if (binding.descriptorType() != want.descriptorType()) {
                throw new IllegalStateException(program.name() + ": '" + want.name() + "' is "
                    + BedrayCoreLayouts.descriptorTypeName(binding.descriptorType()) + ", host supplies "
                    + BedrayCoreLayouts.descriptorTypeName(want.descriptorType()));
            }
        }
        requireElementBytes(manifest, "sections", SECTION_BYTES);
        requireElementBytes(manifest, "frame", FRAME_CONSTANTS_BYTES);
        requireElementBytes(manifest, "blockMaterials", BLOCK_MATERIAL_BYTES);
        requireElementBytes(manifest, "emitters", EMITTER_TABLE_ELEMENT_BYTES);
        requireElementBytes(manifest, "reservoirs", RESERVOIR_BYTES);
        requireElementBytes(manifest, "entityPositions", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "entityIndices", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "entityAttrs", ENTITY_VECTOR_BYTES);
        requireElementBytes(manifest, "entityRects", ENTITY_VECTOR_BYTES);
        requireElementBytes(manifest, "entityTexels", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "entityPrevious", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "outMotionBuffer", MOTION_ELEMENT_BYTES);
        requireElementBytes(manifest, "ptReservoirs", PT_RESERVOIR_BYTES);
        requireElementBytes(manifest, "outPathStats", PATH_STATS_WORD_BYTES);
        if (radianceCascades) {
            RtRadianceCascadesContract.requireFieldBindings(manifest);
        }
    }

    /**
     * The spatial stage's manifest against what the trace's recordPtSpatial binds: exactly the expected trace bindings,
     * the trace's push block, and the same element sizes, so a record written by the trace is read with the same layout.
     */
    static void requirePtSpatialManifest(SlangPassManifest manifest) {
        if (manifest.pushConstantBytes() != PUSH_BYTES) {
            throw new IllegalStateException(manifest.name() + " pushes " + manifest.pushConstantBytes()
                + " B, the trace push block is " + PUSH_BYTES + " B");
        }
        if (manifest.bindings().size() != PT_SPATIAL_EXPECTED.size()) {
            throw new IllegalStateException(manifest.name() + " declares " + manifest.bindings().size()
                + " binding(s), host supplies " + PT_SPATIAL_EXPECTED.size());
        }
        for (Expected want : PT_SPATIAL_EXPECTED) {
            SlangPassManifest.Binding binding = manifest.binding(want.name());
            if (binding == null) {
                throw new IllegalStateException(manifest.name() + " has no '" + want.name() + "' binding");
            }
            if (binding.descriptorType() != want.descriptorType()) {
                throw new IllegalStateException(manifest.name() + ": '" + want.name() + "' is "
                    + BedrayCoreLayouts.descriptorTypeName(binding.descriptorType()) + ", host supplies "
                    + BedrayCoreLayouts.descriptorTypeName(want.descriptorType()));
            }
        }
        requireElementBytes(manifest, "frame", FRAME_CONSTANTS_BYTES);
        requireElementBytes(manifest, "blockMaterials", BLOCK_MATERIAL_BYTES);
        requireElementBytes(manifest, "entityPositions", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "entityIndices", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "entityAttrs", ENTITY_VECTOR_BYTES);
        requireElementBytes(manifest, "entityRects", ENTITY_VECTOR_BYTES);
        requireElementBytes(manifest, "entityTexels", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "entityPrevious", ENTITY_SCALAR_BYTES);
        requireElementBytes(manifest, "ptReservoirs", PT_RESERVOIR_BYTES);
    }

    private static void requireElementBytes(SlangPassManifest manifest, String name, int expected) {
        SlangPassManifest.Binding binding = manifest.binding(name);
        if (binding == null) {
            throw new IllegalStateException(manifest.name() + " has no '" + name + "' binding");
        }
        if (binding.elementBytes() != expected) {
            throw new IllegalStateException(manifest.name() + ": '" + name + "' holds " + binding.elementBytes()
                + " B per element, host allocates " + expected + " B");
        }
    }
}