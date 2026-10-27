package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Host side of {@code passes/path_trace.slang}: owns the radiance output, the frame-constant upload and the dummy
 * block-material table, and records one compute dispatch into the caller's command buffer.
 *
 * <p>Driven by {@link RtSlangPathPass}, which plugs it into the frame as the Path Tracing pass. The host contract (nine
 * bindings, 608-byte {@code FrameConstants}, 40-byte push block, capability mask 517) is expressed here once instead
 * of being re-derived at the call site. The scene, the atlases and the depth target are passed to every
 * {@link #record}: the TLAS is rebuilt as chunks change, and depth goes straight into the renderer's {@code rt_depth},
 * which the composite and bloom already read.
 *
 * <p>Geometry comes from the TLAS and the 64-byte-per-slot section table {@link RtWorld} already builds; the shader
 * dereferences the planar arena through device addresses. The core inserts no barriers, so the caller keeps the
 * synchronization it had with the legacy {@code rt_path.comp} path; the pass leaves its two outputs in
 * {@code GENERAL} behind a full memory barrier.
 *
 * <p>Lifecycle: create after the {@link SlangRuntime}, call {@link #releaseRecording(long)} once the GPU finished (or
 * the recording was discarded) for every command buffer passed to {@link #record}, and {@link #close()} before the
 * runtime is closed.
 */
final class RtPathTraceSlangPass implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    static final String PASS_NAME = "path_trace";
    static final int FRAME_CONSTANTS_BYTES = 608;
    static final int PUSH_BYTES = 40;
    static final int BLOCK_MATERIAL_BYTES = 64;
    static final int SECTION_BYTES = 64;
    static final int THREAD_GROUP = 8;
    /** Frame-constant ring slots; the engine keeps at most two frames in flight, one slot is spare. */
    static final int SLOT_COUNT = 3;

    /** Optional device capabilities the generated manifest requires; see {@code bedray_core.h}. */
    static final int REQUIRED_CAPABILITIES = BedrayCoreLayouts.CAP_RAY_QUERY
        | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS
        | BedrayCoreLayouts.CAP_SHADER_INT64;

    /** One binding the host promises to supply, checked against the generated manifest before the first dispatch. */
    record Expected(String name, int descriptorType) {
    }

    static final List<Expected> EXPECTED = List.of(
        new Expected("tlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR),
        new Expected("outImage", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("sections", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("albedoAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("materialAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("normalAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("outDepth", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("frame", BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER),
        new Expected("blockMaterials", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER));

    /** Scene inputs the host must supply: the frame TLAS and the section table written by {@link RtWorld}. */
    record Scene(long tlas, RtMemory.Buf sectionTable) {
    }

    /**
     * Atlas inputs. One sampler serves all three views: the legacy pipeline uses a nearest, clamp, level-0 sampler for
     * the block atlas, and the shader samples with an explicit level 0.
     */
    record Atlases(long albedoView, long materialView, long normalView, long sampler) {
    }

    /**
     * The 608-byte {@code FrameConstants} block of {@code contracts/frame.slang}. Field order and offsets are pinned by
     * {@code gradle/slang.gradle} ({@code STRUCT_LAYOUTS}) and re-checked by {@code RtPathTraceSlangPassTest}; changing
     * either side requires changing both.
     *
     * <p>The block is filled by the future frame wiring and uploaded as-is, so the pass never guesses a camera or sun
     * value.
     */
    static final class Frame {
        static final int CAMERA = 0;
        static final int SUN = 416;
        static final int ATMOSPHERE = 480;
        static final int EXPOSURE = 560;
        static final int FRAME = 592;

        final Matrix4f view = new Matrix4f();
        final Matrix4f projection = new Matrix4f();
        final Matrix4f viewProjection = new Matrix4f();
        final Matrix4f invViewProjection = new Matrix4f();
        final Matrix4f prevViewProjection = new Matrix4f();
        final Vector4f origin = new Vector4f();               // xyz camera origin, w frame time
        final Vector4f forward = new Vector4f(0f, 0f, -1f, 1f); // w tan(half vertical fov)
        final Vector4f right = new Vector4f(1f, 0f, 0f, 1f);    // w aspect ratio
        final Vector4f up = new Vector4f(0f, 1f, 0f, 0f);
        final Vector4f jitter = new Vector4f();               // xy pixel jitter, zw 1 / render size
        int width;
        int height;
        int displayWidth;
        int displayHeight;

        final Vector4f sunDirection = new Vector4f(0f, 1f, 0f, 0f); // w angular radius
        final Vector4f sunColor = new Vector4f();                   // rgb radiance, w intensity
        final Vector4f moonDirection = new Vector4f(0f, -1f, 0f, 0f);
        final Vector4f moonColor = new Vector4f();

        final Vector4f zenithColor = new Vector4f();
        final Vector4f horizonColor = new Vector4f();
        final Vector4f fogColor = new Vector4f();
        final Vector4f fog = new Vector4f();
        final Vector4f cloud = new Vector4f();

        final Vector4f exposure = new Vector4f(1f, 0f, 0f, 0f); // x exposure, y bloom, z speed, w dt
        int autoExposure;
        int tonemapOperator;
        int bloomEnabled;
        int exposureReserved;

        int frameIndex;
        int sampleIndex;
        int samplesPerFrame = 1;
        int flags;

        void writeTo(ByteBuffer out) {
            int available = out.capacity() - out.position();
            if (available < FRAME_CONSTANTS_BYTES) {
                throw new IllegalArgumentException("FrameConstants needs " + FRAME_CONSTANTS_BYTES + " bytes, got " + available);
            }
            int base = out.position();
            putMatrix(out, base + CAMERA, view);
            putMatrix(out, base + CAMERA + 64, projection);
            putMatrix(out, base + CAMERA + 128, viewProjection);
            putMatrix(out, base + CAMERA + 192, invViewProjection);
            putMatrix(out, base + CAMERA + 256, prevViewProjection);
            putVector(out, base + CAMERA + 320, origin);
            putVector(out, base + CAMERA + 336, forward);
            putVector(out, base + CAMERA + 352, right);
            putVector(out, base + CAMERA + 368, up);
            putVector(out, base + CAMERA + 384, jitter);
            out.putInt(base + CAMERA + 400, width);
            out.putInt(base + CAMERA + 404, height);
            out.putInt(base + CAMERA + 408, displayWidth);
            out.putInt(base + CAMERA + 412, displayHeight);

            putVector(out, base + SUN, sunDirection);
            putVector(out, base + SUN + 16, sunColor);
            putVector(out, base + SUN + 32, moonDirection);
            putVector(out, base + SUN + 48, moonColor);

            putVector(out, base + ATMOSPHERE, zenithColor);
            putVector(out, base + ATMOSPHERE + 16, horizonColor);
            putVector(out, base + ATMOSPHERE + 32, fogColor);
            putVector(out, base + ATMOSPHERE + 48, fog);
            putVector(out, base + ATMOSPHERE + 64, cloud);

            putVector(out, base + EXPOSURE, exposure);
            out.putInt(base + EXPOSURE + 16, autoExposure);
            out.putInt(base + EXPOSURE + 20, tonemapOperator);
            out.putInt(base + EXPOSURE + 24, bloomEnabled);
            out.putInt(base + EXPOSURE + 28, exposureReserved);

            out.putInt(base + FRAME, frameIndex);
            out.putInt(base + FRAME + 4, sampleIndex);
            out.putInt(base + FRAME + 8, samplesPerFrame);
            out.putInt(base + FRAME + 12, flags);
        }

        private static void putMatrix(ByteBuffer out, int offset, Matrix4f value) {
            value.get(offset, out);
        }

        private static void putVector(ByteBuffer out, int offset, Vector4f value) {
            value.get(offset, out);
        }
    }

    /**
     * The 40-byte push block of {@code passes/path_trace.slang}: path limits and sampling inputs only. Exposure is not
     * here on purpose: the pass writes scene-referred radiance and the display step applies {@code FrameConstants.exposure}
     * exactly once.
     */
    record Dispatch(int maxDepth, int maxSurfaceSteps, int sampleIndex, int materialCount, int flags,
                    float rayBias, float alphaCutoff, float maxDistance, float maxRadiance, float sunProbability) {
        Dispatch {
            if (maxDepth < 1) {
                throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
            }
            if (maxSurfaceSteps < 1) {
                throw new IllegalArgumentException("maxSurfaceSteps must be >= 1, got " + maxSurfaceSteps);
            }
            if (sampleIndex < 0 || materialCount < 0) {
                throw new IllegalArgumentException("sampleIndex and materialCount must be >= 0");
            }
            if (!(rayBias >= 0f) || !Float.isFinite(rayBias)) {
                throw new IllegalArgumentException("rayBias must be finite and >= 0, got " + rayBias);
            }
            if (!(alphaCutoff >= 0f) || alphaCutoff > 1f) {
                throw new IllegalArgumentException("alphaCutoff must be in [0, 1], got " + alphaCutoff);
            }
            if (!(maxDistance > 0f) || !Float.isFinite(maxDistance)) {
                throw new IllegalArgumentException("maxDistance must be finite and > 0, got " + maxDistance);
            }
            if (!(maxRadiance > 0f) || !Float.isFinite(maxRadiance)) {
                throw new IllegalArgumentException("maxRadiance must be finite and > 0, got " + maxRadiance);
            }
            if (!(sunProbability >= 0f) || sunProbability > 1f) {
                throw new IllegalArgumentException("sunProbability must be in [0, 1], got " + sunProbability);
            }
        }

        void writeTo(ByteBuffer out) {
            if (out.capacity() - out.position() < PUSH_BYTES) {
                throw new IllegalArgumentException("path trace pushes " + PUSH_BYTES + " bytes, got "
                    + (out.capacity() - out.position()));
            }
            int base = out.position();
            out.putInt(base, maxDepth);
            out.putInt(base + 4, maxSurfaceSteps);
            out.putInt(base + 8, sampleIndex);
            out.putInt(base + 12, materialCount);
            out.putInt(base + 16, flags);
            out.putFloat(base + 20, rayBias);
            out.putFloat(base + 24, alphaCutoff);
            out.putFloat(base + 28, maxDistance);
            out.putFloat(base + 32, maxRadiance);
            out.putFloat(base + 36, sunProbability);
        }
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
        requireElementBytes(manifest, "sections", SECTION_BYTES);
        requireElementBytes(manifest, "frame", FRAME_CONSTANTS_BYTES);
        requireElementBytes(manifest, "blockMaterials", BLOCK_MATERIAL_BYTES);
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

    private final VkDevice device;
    private final SlangRuntime runtime;
    private final SlangProgram program;
    private final RtMemory memory;
    private final RtMemory.Buf[] frameConstants = new RtMemory.Buf[SLOT_COUNT];
    private final RtMemory.Buf blockMaterials;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private int slot = -1;
    private RtMemory.Img radiance;
    private int width;
    private int height;
    private boolean closed;

    /**
     * Creates the radiance image lazily on the first {@link #record} (its size comes from the frame) and the constant
     * buffers eagerly. The section table, the TLAS, the atlases and the depth target stay owned by the caller.
     */
    RtPathTraceSlangPass(VkDevice device, SlangRuntime runtime, RtMemory memory) {
        if (runtime == null) {
            throw new IllegalArgumentException("path trace needs a SlangRuntime");
        }
        this.device = device;
        this.runtime = runtime;
        this.memory = memory;
        Optional<SlangProgram> pass = runtime.program(PASS_NAME);
        if (pass.isEmpty()) {
            throw new IllegalStateException("missing Slang pass " + PASS_NAME);
        }
        this.program = pass.get();
        requireManifest(program);

        RtMemory.Buf materials = null;
        try {
            for (int i = 0; i < SLOT_COUNT; i++) {
                frameConstants[i] = memory.createBuffer("rt_path_frame_constants" + i, FRAME_CONSTANTS_BYTES,
                    VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
            }
            materials = memory.createBuffer("rt_path_block_materials", BLOCK_MATERIAL_BYTES,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            // The table is only present to satisfy decodeSurface; materialCount = 0 means no shader ever reads it.
            MemoryUtil.memSet(materials.mapped, 0, BLOCK_MATERIAL_BYTES);
        } catch (RuntimeException e) {
            for (RtMemory.Buf constants : frameConstants) {
                if (constants != null) {
                    memory.destroy(constants);
                }
            }
            if (materials != null) {
                memory.destroy(materials);
            }
            throw e;
        }
        this.blockMaterials = materials;
    }

    long radianceView() {
        return radiance == null ? 0L : radiance.view;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /**
     * Uploads {@code frame} and records one dispatch. {@code depthView} is an R32F storage image in {@code GENERAL} of
     * the frame size. The caller must have transitioned the section table and the atlas views to the layouts the legacy
     * path uses and must keep its own barriers around the dispatch; this method adds the trailing full memory barrier
     * that makes both outputs visible to their consumers.
     */
    void record(VkCommandBuffer cmd, Scene scene, Atlases atlases, long depthView, Frame frame, Dispatch dispatch) {
        if (closed) {
            throw new IllegalStateException("path trace pass is closed");
        }
        if (frame.width <= 0 || frame.height <= 0) {
            throw new IllegalArgumentException("frame size must be positive, got " + frame.width + "x" + frame.height);
        }
        if (scene.tlas() == 0L) {
            throw new IllegalArgumentException("path trace needs a TLAS");
        }
        if (scene.sectionTable() == null || scene.sectionTable().handle == 0L) {
            throw new IllegalArgumentException("path trace needs the RtWorld section table");
        }
        if (atlases.sampler() == 0L || atlases.albedoView() == 0L || atlases.materialView() == 0L
            || atlases.normalView() == 0L) {
            throw new IllegalArgumentException("path trace needs the three block atlases and their sampler");
        }
        if (depthView == 0L) {
            throw new IllegalArgumentException("path trace needs a depth target");
        }
        ensure(cmd, frame.width, frame.height);
        // Rotate the frame-constant slot: the engine can have two frames in flight, and the GPU must not read this
        // frame's block after the next frame overwrote it.
        slot = (slot + 1) % SLOT_COUNT;
        frame.writeTo(MemoryUtil.memByteBuffer(frameConstants[slot].mapped, FRAME_CONSTANTS_BYTES));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            dispatch.writeTo(push);
            // Track before the dispatch: if recording fails midway, the command buffer still holds a native descriptor
            // pool that has to be released on the discard path.
            recordings.add(cmd.address());
            program.dispatch(cmd.address(), program.groups(width, 0), program.groups(height, 1), 1,
                resources(scene, atlases, depthView, slot), push);
        }
        VkBarriers.fullMemoryBarrier(cmd);
    }

    /**
     * The FrameConstants block the last {@link #record} uploaded, as a display-pass resource. A consumer that reads the
     * same camera, sun and previous-projection data binds this buffer instead of uploading a second copy that could
     * disagree with the traced frame.
     */
    SlangProgram.Resource frameResource() {
        if (slot < 0 || frameConstants[slot] == null) {
            throw new IllegalStateException("path trace has not uploaded frame constants yet");
        }
        return SlangProgram.Resource.uniformBuffer("frame", frameConstants[slot].handle, 0, FRAME_CONSTANTS_BYTES);
    }

    private List<SlangProgram.Resource> resources(Scene scene, Atlases atlases, long depthView, int frameSlot) {
        RtMemory.Buf table = scene.sectionTable();
        return List.of(
            SlangProgram.Resource.accelerationStructure("tlas", scene.tlas()),
            SlangProgram.Resource.storageImage("outImage", radiance.view),
            SlangProgram.Resource.storageBuffer("sections", table.handle, 0, table.size),
            SlangProgram.Resource.sampled("albedoAtlas", atlases.albedoView(), atlases.sampler()),
            SlangProgram.Resource.sampled("materialAtlas", atlases.materialView(), atlases.sampler()),
            SlangProgram.Resource.sampled("normalAtlas", atlases.normalView(), atlases.sampler()),
            SlangProgram.Resource.storageImage("outDepth", depthView),
            SlangProgram.Resource.uniformBuffer("frame", frameConstants[frameSlot].handle, 0, FRAME_CONSTANTS_BYTES),
            SlangProgram.Resource.storageBuffer("blockMaterials", blockMaterials.handle, 0, BLOCK_MATERIAL_BYTES));
    }

    /** (Re)creates the radiance output when the render size changed; the old image is destroyed only after the device is idle. */
    private void ensure(VkCommandBuffer cmd, int w, int h) {
        if (radiance != null && width == w && height == h) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        releaseRecordings();
        if (radiance != null) {
            memory.destroy(radiance);
            radiance = null;
        }
        radiance = memory.createImage("rt_path_radiance", w, h, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
            VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
        VkBarriers.transition(cmd, radiance.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
            VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        width = w;
        height = h;
        LOGGER.info("[VKB-RT] rt_path_trace {}x{} RGBA16F radiance, depth into rt_depth (Slang, {} binding(s))",
            w, h, program.manifest().bindings().size());
    }

    /**
     * Host calls this once the GPU finished (or the recording was discarded) for {@code commandBuffer}. The native core
     * keeps one descriptor pool per recording, so an unreleased recording pins it and refuses core shutdown.
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

    private void releaseRecordings() {
        for (Long commandBuffer : List.copyOf(recordings)) {
            releaseRecording(commandBuffer);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        VK10.vkDeviceWaitIdle(device);
        releaseRecordings();
        if (radiance != null) {
            memory.destroy(radiance);
            radiance = null;
        }
        for (RtMemory.Buf constants : frameConstants) {
            memory.destroy(constants);
        }
        memory.destroy(blockMaterials);
        width = 0;
        height = 0;
    }

    @Override
    public String toString() {
        return "RtPathTraceSlangPass[" + width + "x" + height + ", " + program + "]";
    }
}
