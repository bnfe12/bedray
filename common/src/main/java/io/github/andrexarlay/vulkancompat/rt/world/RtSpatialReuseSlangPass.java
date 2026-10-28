package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Host side of {@code passes/restir_spatial.slang}: the second ReSTIR DI stage of the Slang path tracer. It owns no
 * memory of its own - the atlases, the emitter list, the reservoir ring and the frame-constant block all belong to
 * {@link RtPathTraceSlangPass} and its caller - and records the two checker dispatches a frame needs.
 *
 * <p>Binding it to the trace pass's own {@code FrameConstants} upload is deliberate: the render size the pass iterates
 * and the size the reservoirs were addressed with come from one buffer, so a pooled record can never be indexed against
 * a resolution that no longer matches the ring.
 *
 * <p>Optional by design. {@link #create(SlangRuntime)} yields empty when the AOT artifact is missing or its manifest
 * disagrees with this class, and the caller then runs temporal reuse alone - the same degrade the legacy path uses when
 * the spatial kernel was switched off at build time.
 *
 * <p>The native phased-dispatch operation owns the checker loop and compute memory dependencies,
 * including availability after the final phase for next frame's temporal import.
 */
final class RtSpatialReuseSlangPass implements AutoCloseable {
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
        new Expected("reservoirs", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER));

    /**
     * One dispatch's push block: the frame index seeds the neighbour draws, {@code parity} is the half the trace pass
     * wrote this frame, {@code phase} the checker colour this dispatch owns.
     */
    record Spatial(int frameIndex, int parity, int phase, int flags,
                   float cameraRelativeX, float cameraRelativeY, float cameraRelativeZ, float maxDistance) {
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
            out.putInt(48, 0);
            out.putInt(52, 0);
            out.putInt(56, 0);
            out.putInt(60, 0);
        }
    }

    /** True when an AOT artifact exists for this device runtime; the manifest is only checked when the pass is created. */
    static boolean isAvailable(SlangRuntime runtime) {
        return runtime != null && SlangPassLibrary.available(PASS_NAME);
    }

    /**
     * Registers the pass, or yields empty with the reason in the log. A program that got registered and then failed the
     * manifest check is closed again, so a wrong contract cannot leave a pipeline behind in the core.
     */
    static Optional<RtSpatialReuseSlangPass> create(SlangRuntime runtime, Logger log) {
        if (runtime == null) {
            return Optional.empty();
        }
        SlangProgram program = null;
        try {
            Optional<SlangProgram> pass = runtime.program(PASS_NAME);
            if (pass.isEmpty()) {
                log.info("[VKB-RT] spatial reuse off: Slang pass {} is not registered", PASS_NAME);
                return Optional.empty();
            }
            program = pass.get();
            requireManifest(program);
            return Optional.of(new RtSpatialReuseSlangPass(runtime, program));
        } catch (RuntimeException e) {
            if (program != null) {
                program.close();
            }
            log.info("[VKB-RT] spatial reuse off: {}", e.getMessage() == null ? e.toString() : e.getMessage());
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
        requireElementBytes(manifest, "emitters", RtPathTraceSlangPass.EMITTER_BYTES);
        requireElementBytes(manifest, "reservoirs", RtPathTraceSlangPass.RESERVOIR_BYTES);
    }

    private static void requireElementBytes(SlangPassManifest manifest, String name, int expected) {
        SlangPassManifest.Binding binding = manifest.binding(name);
        if (binding.elementBytes() != expected) {
            throw new IllegalStateException(manifest.name() + ": '" + name + "' holds " + binding.elementBytes()
                + " B per element, host allocates " + expected + " B");
        }
    }

    private final SlangRuntime runtime;
    private final SlangProgram program;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean closed;

    private RtSpatialReuseSlangPass(SlangRuntime runtime, SlangProgram program) {
        this.runtime = runtime;
        this.program = program;
    }

    /** The pool-merge branch of one pixel block: records both halves of the checker, in the two phases the shader expects. */
    void record(VkCommandBuffer cmd, RtPathTraceSlangPass.Atlases atlases, RtPathTraceSlangPass.Emitters emitters,
                RtPathTraceSlangPass.Reservoirs reservoirs, SlangProgram.Resource frame, int width, int height,
                int frameIndex, int parity, float cameraRelativeX, float cameraRelativeY, float cameraRelativeZ,
                float maxDistance) {
        if (closed) {
            throw new IllegalStateException("spatial reuse pass is closed");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("spatial reuse needs a positive extent, got " + width + "x" + height);
        }
        if (reservoirs == null || reservoirs.buffer() == 0L || reservoirs.bytes() <= 0L) {
            throw new IllegalArgumentException("spatial reuse pools reservoirs, so it needs the ring the trace pass wrote");
        }
        if (emitters == null || emitters.buffer() == 0L || emitters.count() <= 0) {
            throw new IllegalArgumentException("spatial reuse re-evaluates samples through the emitter list");
        }
        if (atlases.sampler() == 0L || atlases.albedoView() == 0L || atlases.materialView() == 0L
            || atlases.normalView() == 0L) {
            throw new IllegalArgumentException("spatial reuse needs the three block atlases and their sampler");
        }
        int flags = SPATIAL_ENABLED;
        Spatial spatial = new Spatial(frameIndex, parity, 0, flags, cameraRelativeX, cameraRelativeY,
            cameraRelativeZ, maxDistance);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            spatial.writeTo(push);
            recordings.add(cmd.address());
            program.dispatchPhases(cmd.address(), program.groups(width, 0), program.groups(height, 1), 1,
                resources(atlases, emitters, reservoirs, frame), push, 8, PHASES);
        }
    }

    private List<SlangProgram.Resource> resources(RtPathTraceSlangPass.Atlases atlases,
                                                  RtPathTraceSlangPass.Emitters emitters,
                                                  RtPathTraceSlangPass.Reservoirs reservoirs,
                                                  SlangProgram.Resource frame) {
        return List.of(
            SlangProgram.Resource.sampled("albedoAtlas", atlases.albedoView(), atlases.sampler()),
            SlangProgram.Resource.sampled("materialAtlas", atlases.materialView(), atlases.sampler()),
            SlangProgram.Resource.sampled("normalAtlas", atlases.normalView(), atlases.sampler()),
            frame,
            SlangProgram.Resource.storageBuffer("emitters", emitters.buffer(), emitters.offset(),
                Math.max(1, emitters.count()) * (long) RtPathTraceSlangPass.EMITTER_BYTES),
            SlangProgram.Resource.storageBuffer("reservoirs", reservoirs.buffer(), 0L, reservoirs.bytes()));
    }

    /**
     * Host calls this once the GPU finished (or the recording was discarded) for {@code commandBuffer}: the core keeps
     * one descriptor pool per recording, and an unreleased one pins it and refuses core shutdown.
     */
    void releaseRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) {
            return;
        }
        if (!runtime.isClosed()) {
            runtime.releaseRecording(commandBuffer);
        }
        recordings.remove(commandBuffer);
    }

    void releaseRecordings() {
        for (Long commandBuffer : List.copyOf(recordings)) {
            releaseRecording(commandBuffer);
        }
    }

    String passName() {
        return program.name();
    }

    /** The core program handle the native pass configures itself with. */
    long programHandle() {
        return program.nativeHandle();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseRecordings();
        program.close();
    }
}
