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

/** Registers and validates the FrameStatsSlangPass program for the C++ recorder. */
final class RtFrameStatsSlangPass {
    private RtFrameStatsSlangPass() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    static final String PASS_NAME = "frame_stats";
    /** {@code FrameStatsPush} of the entry pass; checked against the generated manifest before the first dispatch. */
    static final int PUSH_BYTES = 8;
    /** The slot's counter head: {@code hits}, {@code samples}, {@code lumLogSum}, {@code histAccepted}. */
    static final int COUNTER_BYTES = 16;

    /** One binding the host promises to supply, checked against the generated manifest before the first dispatch. */
    private record Expected(String name, int descriptorType) {
    }

    private static final List<Expected> EXPECTED = List.of(
        new Expected("radiance", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("accum", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("depth", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("stats", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER));

    /**
     * One dispatch's push block. The frame index rotates which 4x4 tiles are metered, so a camera that has not moved
     * still measures a different sixteenth of the screen every frame instead of the same one forever.
     */
    record Stats(int frameIndex) {
        void writeTo(ByteBuffer out) {
            out.putInt(0, frameIndex);
            out.putInt(4, 0);
        }
    }

    /** Programs are borrowed from the runtime cache; only the runtime retires them. */
    static Optional<SlangProgram> registerProgram(SlangRuntime runtime) {
        if (runtime == null) {
            return Optional.empty();
        }
        try {
            Optional<SlangProgram> pass = runtime.program(PASS_NAME);
            if (pass.isEmpty()) {
                LOGGER.info("[VKB-RT] frame statistics off: Slang pass {} is not registered", PASS_NAME);
                return Optional.empty();
            }
            requireManifest(pass.get().manifest());
            return pass;
        } catch (RuntimeException | Error e) {
            LOGGER.info("[VKB-RT] frame statistics off: {}", e.getMessage() == null ? e.toString() : e.getMessage());
            return Optional.empty();
        }
    }

    /** Checks the generated manifest against the resources this class supplies; the shader is the source of truth. */
    static void requireManifest(SlangPassManifest manifest) {
        if (manifest.pushConstantBytes() != PUSH_BYTES) {
            throw new IllegalStateException(manifest.name() + " pushes " + manifest.pushConstantBytes()
                + " B, host block is " + PUSH_BYTES + " B");
        }
        if (manifest.bindings().size() != EXPECTED.size()) {
            throw new IllegalStateException(manifest.name() + " declares " + manifest.bindings().size()
                + " binding(s), host supplies " + EXPECTED.size());
        }
        for (Expected want : EXPECTED) {
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
        SlangPassManifest.Binding stats = manifest.binding("stats");
        if (stats.elementBytes() != 4) {
            throw new IllegalStateException(manifest.name() + ": 'stats' holds " + stats.elementBytes()
                + " B per element, the counter words are 4 B");
        }
    }
}
