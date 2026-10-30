package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Host side of {@code passes/frame_stats.slang}: the counter pass that fills the head of the frame's StatsBuf slot.
 * It owns no memory - the radiance image belongs to {@link RtPathTraceSlangPass}, the accumulation image to the
 * backend, the depth image and the statistics buffer to {@link RtFrameRenderer} - and records one dispatch.
 *
 * <p>It runs after the display step, because the count it reads for the history share is the one that step wrote;
 * the barrier between them is the one the backend already records before the composite reads the picture, so the
 * counters cost no synchronisation of their own.
 *
 * <p>Optional by design, like the spatial pass: {@link #create(SlangRuntime)} yields empty when the AOT artifact is
 * missing or its manifest disagrees with this class, and the frame then simply produces no statistics. That degrade
 * is audible rather than silent - with no writer, {@link RtExposure} keeps its floor and the F3 counters say n/a - so
 * the reason is logged once at creation.
 *
 * <p>The bytes it may touch are the four counter words and nothing else: the descriptor binds a 16-byte range of the
 * slot, so a shader that indexed past {@code stats[3]} would be out of its own binding rather than scribbling over
 * the emitter address of the frame.
 */
final class RtFrameStatsSlangPass implements AutoCloseable {
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

    /** True when an AOT artifact exists for this runtime; the manifest is only checked when the pass is created. */
    static boolean isAvailable(SlangRuntime runtime) {
        return runtime != null && SlangPassLibrary.available(PASS_NAME);
    }

    /** Registers a borrowed program, or yields empty with the reason in the log. The runtime retains ownership. */
    static Optional<RtFrameStatsSlangPass> create(SlangRuntime runtime) {
        return registerProgram(runtime).map(program -> new RtFrameStatsSlangPass(runtime, program));
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

    private final SlangRuntime runtime;
    private final SlangProgram program;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean closed;

    private RtFrameStatsSlangPass(SlangRuntime runtime, SlangProgram program) {
        this.runtime = runtime;
        this.program = program;
    }

    /**
     * Records the counters of one frame into the head of {@code stats}.
     *
     * @param stats the frame's own slot: the pass binds its first sixteen bytes, never the whole ring
     */
    void record(VkCommandBuffer cmd, long radianceView, long accumView, long depthView,
                RtPathTraceSlangPass.BufferView stats, int width, int height, int frameIndex) {
        if (closed) {
            throw new IllegalStateException("frame statistics pass is closed");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame statistics needs a positive extent, got " + width + "x" + height);
        }
        if (radianceView == 0L || accumView == 0L || depthView == 0L) {
            throw new IllegalArgumentException("frame statistics reads the trace's radiance, the running mean and"
                + " the primary depth");
        }
        if (stats == null || stats.buffer() == 0L || stats.bytes() < COUNTER_BYTES) {
            throw new IllegalArgumentException("frame statistics writes the counter head of this frame's slot");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            new Stats(frameIndex).writeTo(push);
            recordings.add(cmd.address());
            program.dispatch(cmd.address(), program.groups(width, 0), program.groups(height, 1), 1, List.of(
                SlangProgram.Resource.storageImage("radiance", radianceView),
                SlangProgram.Resource.storageImage("accum", accumView),
                SlangProgram.Resource.storageImage("depth", depthView),
                SlangProgram.Resource.storageBuffer("stats", stats.buffer(), stats.offset(), COUNTER_BYTES)), push);
        }
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

    String passName() {
        return program.name();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!recordings.isEmpty()) throw new IllegalStateException("statistics pass still awaits recording proof");
        closed = true;
    }
}
