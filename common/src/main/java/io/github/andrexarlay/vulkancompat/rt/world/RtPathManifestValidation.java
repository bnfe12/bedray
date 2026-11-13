package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.Expected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.BLOCK_MATERIAL_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.EMITTER_TABLE_ELEMENT_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.ENTITY_SCALAR_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.ENTITY_VECTOR_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.EXPECTED;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.MOTION_ELEMENT_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.PASS_NAME;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.PATH_STATS_WORD_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.PT_RESERVOIR_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.PT_SPATIAL_EXPECTED;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.PUSH_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.REQUIRED_CAPABILITIES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.RESERVOIR_BYTES;
import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.SECTION_BYTES;

/**
 * The host layout-contract checks of the path-trace pass: the device-capability gate, the generated-manifest validation
 * (bindings, push block and per-element byte sizes) and the ReSTIR PT reservoir budget. Split out of
 * {@link RtPathTraceSlangPass} so the pass keeps its frozen data contract (the layout constants and the nested records)
 * while the validation lives here; the constants are imported, not copied, so there is one source of truth for the layout.
 * The pass exposes these as delegating facades (the API the backends and autotests call).
 */
final class RtPathManifestValidation {
    private RtPathManifestValidation() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    static long ptReservoirBytes(int width, int height) {
        return 2L * width * height * PT_RESERVOIR_BYTES;
    }

    /** True when this device runtime can register the pass: the AOT artifact exists and every required bit is enabled. */
    static boolean isSupported(SlangRuntime runtime) {
        return runtime != null && runtime.supports(REQUIRED_CAPABILITIES) && SlangPassLibrary.available(PASS_NAME);
    }

    /**
     * Checks the generated manifest against the resources this class supplies. The shader is the source of truth, so a
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
     * The spatial stage's manifest against what {@link RtPathTraceSlangPass#recordPtSpatial} binds: exactly the expected trace bindings, the
     * trace's push block, and the same element sizes, so a record written by the trace is read with the same layout.
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

    /** An optional program: absent from the jar or refused by its manifest check, the stage stays off and says why. */
    static SlangProgram optionalPtSpatial(SlangRuntime runtime, String name) {
        if (!SlangPassLibrary.available(name)) {
            LOGGER.info("[VKB-RT] ReSTIR PT spatial stage off for {}: the jar has no artifact", name);
            return null;
        }
        try {
            SlangProgram spatial = runtime.program(name).orElse(null);
            if (spatial == null) {
                LOGGER.info("[VKB-RT] ReSTIR PT spatial stage off for {}: not registered", name);
                return null;
            }
            requirePtSpatialManifest(spatial.manifest());
            return spatial;
        } catch (RuntimeException e) {
            LOGGER.info("[VKB-RT] ReSTIR PT spatial stage off for {}: {}", name,
                e.getMessage() == null ? e.toString() : e.getMessage());
            return null;
        }
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