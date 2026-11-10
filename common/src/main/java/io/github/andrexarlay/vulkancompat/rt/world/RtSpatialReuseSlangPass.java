package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/** Registers and validates the SpatialReuseSlangPass program for the C++ recorder. */
final class RtSpatialReuseSlangPass {
    private RtSpatialReuseSlangPass() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    static final String PASS_NAME = "restir_spatial";
    /** {@code RestirSpatialPush} of the entry pass; checked against the generated manifest before the first dispatch. */
    static final int PUSH_BYTES = 64;
    /** Bit 0 of {@code state.w}, mirroring {@code RESTIR_SPATIAL_ENABLED} in the shader. */
    static final int SPATIAL_ENABLED = 1;
    /** The two checker colours the host dispatches, in order: phase 1 reads what phase 0 wrote. */
    static final int PHASES = 2;

    /** One binding the host promises to supply, checked against the generated manifest before the first dispatch. */
    private record Expected(String name, int descriptorType) {
    }

    private static final List<Expected> EXPECTED = List.of(
        new Expected("albedoAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("materialAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("normalAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("frame", BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER),
        new Expected("emitters", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("reservoirs", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityRects", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityTexels", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER));

    /**
     * One dispatch's push block: the frame index seeds the neighbour draws, {@code parity} is the half the trace pass
     * wrote this frame, {@code phase} the checker colour this dispatch owns.  {@code entityAtlasSize} is the captured
     * entity atlas' texels per side, which the shader needs to decode an entity emitter's texel; zero means the atlas is
     * empty and every entity lookup falls back without touching the buffers.
     */
    record Spatial(int frameIndex, int parity, int phase, int flags,
                   float cameraRelativeX, float cameraRelativeY, float cameraRelativeZ, float maxDistance,
                   int entityAtlasSize) {
        void writeTo(ByteBuffer out) {
            out.putInt(0, frameIndex);
            out.putInt(4, parity);
            out.putInt(8, phase);
            out.putInt(12, flags);
            out.putFloat(16, cameraRelativeX);
            out.putFloat(20, cameraRelativeY);
            out.putFloat(24, cameraRelativeZ);
            out.putFloat(28, 0f);
            out.putFloat(32, maxDistance);
            out.putFloat(36, 0f);
            out.putFloat(40, 0f);
            out.putFloat(44, 0f);
            out.putInt(48, entityAtlasSize);
            out.putInt(52, 0);
            out.putInt(56, 0);
            out.putInt(60, 0);
        }
    }

    /** Programs are borrowed from the runtime cache; only the runtime retires them. */
    static Optional<SlangProgram> registerProgram(SlangRuntime runtime) {
        if (runtime == null) {
            return Optional.empty();
        }
        SlangProgram program = null;
        try {
            Optional<SlangProgram> pass = runtime.program(PASS_NAME);
            if (pass.isEmpty()) {
                LOGGER.info("[VKB-RT] spatial reuse off: Slang pass {} is not registered", PASS_NAME);
                return Optional.empty();
            }
            program = pass.get();
            requireManifest(program);
            return Optional.of(program);
        } catch (RuntimeException e) {
            LOGGER.info("[VKB-RT] spatial reuse off: {}", e.getMessage() == null ? e.toString() : e.getMessage());
            return Optional.empty();
        }
    }

    /** Checks the generated manifest against the resources this class supplies; the shader is the source of truth. */
    static void requireManifest(SlangProgram program) {
        SlangPassManifest manifest = program.manifest();
        if (manifest.pushConstantBytes() != PUSH_BYTES) {
            throw new IllegalStateException(program.name() + " pushes " + manifest.pushConstantBytes()
                + " B, host block is " + PUSH_BYTES + " B");
        }
        if (manifest.bindings().size() != EXPECTED.size()) {
            throw new IllegalStateException(program.name() + " declares " + manifest.bindings().size()
                + " binding(s), host supplies " + EXPECTED.size());
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
        requireElementBytes(manifest, "frame", RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES);
        requireElementBytes(manifest, "emitters", RtPathTraceSlangPass.EMITTER_TABLE_ELEMENT_BYTES);
        requireElementBytes(manifest, "reservoirs", RtPathTraceSlangPass.RESERVOIR_BYTES);
        requireElementBytes(manifest, "entityRects", RtPathTraceSlangPass.ENTITY_VECTOR_BYTES);
        requireElementBytes(manifest, "entityTexels", RtPathTraceSlangPass.ENTITY_SCALAR_BYTES);
    }

    private static void requireElementBytes(SlangPassManifest manifest, String name, int expected) {
        SlangPassManifest.Binding binding = manifest.binding(name);
        if (binding.elementBytes() != expected) {
            throw new IllegalStateException(manifest.name() + ": '" + name + "' holds " + binding.elementBytes()
                + " B per element, host allocates " + expected + " B");
        }
    }
}
