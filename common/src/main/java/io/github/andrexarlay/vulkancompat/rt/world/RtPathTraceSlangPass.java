package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/** Frame POD and reflected Slang binding contract for the C++ path recorder. */
final class RtPathTraceSlangPass {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Bedray/RT");

    private RtPathTraceSlangPass() {}

    static final String PASS_NAME = "path_trace_scene";
    /**
     * The ReSTIR PT spatial stage, one program per traversal backend like the trace itself: the same bindings by number
     * and the same push block, so it is recorded from this pass's own views and push bytes.
     */
    static final String PT_SPATIAL_SCENE_PASS_NAME = "restir_pt_spatial_scene";
    static final int FRAME_CONSTANTS_BYTES = 608;
    /** Mirrors {@code PATH_TRACE_MAX_SPP}: the ceiling of "Rays per Pixel", and the most rays one pixel may trace. */
    static final int MAX_SAMPLES_PER_PIXEL = 8;
    static final int PUSH_BYTES = 112;
    static final int BLOCK_MATERIAL_BYTES = 64;
    /** {@code light/block_emitters.slang}: the RtEmitters record, six vec4. */
    static final int EMITTER_BYTES = 96;
    static final int EMITTER_TABLE_ELEMENT_BYTES = 16;
    /** {@code restir/restir_di.slang}: one ReSTIR DI reservoir per pixel, pinned by {@code gradle/slang.gradle}. */
    static final int RESERVOIR_BYTES = 64;
    /**
     * {@code experimental/restir_pt.slang}: one {@code RestirPtReservoir} per pixel and parity half - the std430 layout
     * the reflection measures (112 B path, 16 B stats, 16 B source), pinned by {@code gradle/slang.gradle}. The module
     * claimed 112 B until the first host bound it; that was the packed payload, not the layout.
     */
    static final int PT_RESERVOIR_BYTES = 144;
    static final int SECTION_BYTES = 64;
    /** {@code traversal/entity_geometry.slang}: one float of a captured position or one texel of the entity atlas. */
    static final int ENTITY_SCALAR_BYTES = 4;
    /** {@code traversal/entity_geometry.slang}: colour, light, u, v of one captured vertex, and one atlas rect. */
    static final int ENTITY_VECTOR_BYTES = 16;
    /** {@code rt_pack_nrd.comp}: one Vec4Buf element of NRD's IN_MV channel, four floats per render pixel. */
    static final int MOTION_ELEMENT_BYTES = 16;
    /** One counter word of the stats slot the trace adds its F3 split into (binding 37, {@link RtStatsLayout#PATH_REJECTS}). */
    static final int PATH_STATS_WORD_BYTES = 4;
    static final int THREAD_GROUP = 8;

    /** Optional device capabilities the generated manifest requires; see {@code bedray_core.h}. */
    static final int REQUIRED_CAPABILITIES = BedrayCoreLayouts.CAP_RAY_QUERY
        | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS
        | BedrayCoreLayouts.CAP_SHADER_INT64;

    /** {@code PathTracePush.flags}: the pass fills the denoiser guides. Mirrors {@code PATH_TRACE_OPTION_GBUFFER}. */
    static final int OPTION_GBUFFER = 1;

    /** {@code PathTracePush.flags}: the pass also writes NRD's world-space motion channel. Mirrors the shader bit. */
    static final int OPTION_NRD_MOTION = 2;

    /**
     * The effect set of the ray profile, mirroring {@code PATH_TRACE_OPTION_*} in {@code path_tracer.slang}. Absent
     * bits are the full transport, so the path profile - which sets none of them - traces what it always traced.
     */
    static final int OPTION_NO_SPECULAR_BOUNCE = 4;
    static final int OPTION_NO_DIFFUSE_BOUNCE = 8;
    static final int OPTION_AMBIENT_ONLY_BOUNCE = 16;
    static final int OPTION_NO_KEY_SHADOWS = 32;
    static final int OPTION_HARD_KEY_SHADOWS = 64;

    /**
     * {@code PathTracePush.flags}: the primary receiver's shadow is evaluated once per world cell. Mirrors
     * {@code PATH_TRACE_OPTION_PIXEL_SHADOWS}; bits 8..15 of the same word carry the cell size in texture pixels
     * (1..16, one sixteenth of a block each), so the 112-byte push block did not have to grow a word.
     */
    static final int OPTION_PIXEL_SHADOWS = 128;

    static final int PIXEL_SHADOW_SIZE_SHIFT = 8;
    static final int PIXEL_SHADOW_MAX_SIZE = 16;

    /**
     * {@code PathTracePush.flags}: the host enabled block emitters for this frame. Mirrors
     * {@code PATH_TRACE_OPTION_BLOCK_EMITTERS}; without it a receiver past the emitter list's reach keeps tracing the
     * list instead of lighting from the vanilla block-light field, so the "RT Block Light" A/B stays exactly that.
     */
    static final int OPTION_BLOCK_EMITTERS = 1 << 16;

    /**
     * ReSTIR PT, mirroring {@code PATH_TRACE_OPTION_RESTIR_PT} and the {@code PATH_TRACE_PT_*} bits of
     * {@code restir/pt_temporal.slang}: the first ray of a pixel hands its diffuse continuation to the path reservoir.
     * The ring has its own parity and history bits because it does not need emitters - DI's copies are only written
     * while DI runs - and its own camera bits for the same reason. The flag requires {@link #ptReservoirBytes} of ring.
     */
    static final int OPTION_RESTIR_PT = 1 << 17;
    static final int PT_HISTORY = 1 << 18;
    static final int PT_PARITY = 1 << 19;
    static final int PT_STATIC = 1 << 20;
    static final int PT_FLIP_Y = 1 << 21;
    /** Explicit RR guide contract, independent of whether world-motion output is enabled. */
    static final int OPTION_RR_GUIDES = 1 << 22;
    static final int OPTION_RR_PT_DECORRELATE = 1 << 23;
    static final int PT_STATE_MASK = OPTION_RESTIR_PT | PT_HISTORY | PT_PARITY | PT_STATIC | PT_FLIP_Y;

    /** The seven denoiser guide images, in the order {@code gbufferViews()} hands them over. */
    record Guides(long viewZ, long normalRough, long diffuse, long albedo, long direct, long specular, long specAlbedo) {
        /**
         * The guides of a bound G-buffer, or null when this frame has none. A short array is a host bug: the shader
         * would write through a binding the host never filled, so the pass refuses it instead of guessing.
         */
        static Guides of(long[] views) {
            if (views == null) {
                return null;
            }
            if (views.length < 7) {
                throw new IllegalArgumentException("the denoiser G-buffer has " + views.length
                    + " view(s), the path trace writes 7 guide channels");
            }
            return new Guides(views[0], views[1], views[2], views[3], views[4], views[5], views[6]);
        }

        boolean complete() {
            return viewZ != 0L && normalRough != 0L && diffuse != 0L && albedo != 0L && direct != 0L
                && specular != 0L && specAlbedo != 0L;
        }
    }

    /** One binding the host promises to supply, checked against the generated manifest before the first dispatch. */
    record Expected(String name, int descriptorType) {
    }

    static final List<Expected> EXPECTED = List.of(
        new Expected("tlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR),
        new Expected("outImage", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("sections", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("albedoAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("outMotion", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("materialAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("normalAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("outDepth", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("frame", BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER),
        new Expected("blockMaterials", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("emitters", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("reservoirs", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityPositions", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityIndices", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityAttrs", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityRects", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityTexels", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityPrevious", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("outViewZ", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("outNormalRough", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("outSpecular", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("outDiffuse", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("outAlbedo", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("outDirect", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("outSpecAlbedo", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
        new Expected("outMotionBuffer", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("ptReservoirs", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("outPathStats", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER));

    /** The trace bindings the spatial stage reads: the first-edge visibility walk and the ring, nothing it would write. */
    static final List<Expected> PT_SPATIAL_EXPECTED = List.of(
        new Expected("tlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR),
        new Expected("sections", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("albedoAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("materialAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("normalAtlas", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        new Expected("frame", BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER),
        new Expected("blockMaterials", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityPositions", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityIndices", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityAttrs", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityRects", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityTexels", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("entityPrevious", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),
        new Expected("ptReservoirs", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER));

    /** Views borrowed from the current C++ scene generation. */
    record Scene(long tlas, RtMemory.Buf sectionTable) {}

    /**
     * Atlas inputs. One sampler serves all three views: the pipeline uses a nearest, clamp, level-0 sampler for
     * the block atlas, and the shader samples with an explicit level 0.
     */
    record Atlases(long albedoView, long materialView, long normalView, long sampler) {
    }

    /**
     * The emitter list of this frame: one segment of the {@link RtEmitters} ring, bound at its own offset. A count of 0
     * still needs a descriptor (the shader indexes it only through {@code pc.emitterCount}), so the pass substitutes the
     * dummy block-material table, which is allocated for exactly that reason.
     */
    record Emitters(long buffer, long offset, int count) {
        static final Emitters NONE = new Emitters(0L, 0L, 0);
    }

    /**
     * The ReSTIR DI ring of the path tracer: two parity halves of {@link #RESERVOIR_BYTES} per render pixel, owned by
     * {@link RtSlangPathPass}. Switching reuse off is a zero handle, and the pass then binds a one-record dummy: the
     * shader reads the bound length, so an inactive binding cannot be mistaken for a ring.
     */
    record Reservoirs(long buffer, long bytes) {
        static final Reservoirs NONE = new Reservoirs(0L, 0L);
    }

    /** One range of a device buffer to bind, or a zero handle for the pass's own one-element dummy. */
    record BufferView(long buffer, long offset, long bytes) {
        static final BufferView NONE = new BufferView(0L, 0L, 0L);
    }

    /**
     * The captured models of this frame, as {@link RtEntityGeometryManager} uploaded them: four slices of the vertex,
     * previous-vertex, index and attribute rings plus the RT entity atlas they texture. The bound length is the count
     * the shader reads, so a frame that captured nothing binds one-element dummies and decodes zero triangles rather
     * than a stale model.
     *
     * <p>{@code previous} is the same vertex list one frame earlier and the same length as {@code positions}: their
     * difference is the model's own motion, which is what lets a walking mob keep its history instead of noising.
     *
     * <p>{@code selfAvatarTriStart} and {@code selfAvatarTriCount} are the first-person body within those triangles: the
     * camera ray may not hit them, every other ray of the path may.
     */
    record Entities(BufferView positions, BufferView previous, BufferView indices, BufferView attrs,
                    BufferView rects, BufferView texels,
                    int atlasSize, int selfAvatarTriStart, int selfAvatarTriCount) {
        static final Entities NONE = new Entities(BufferView.NONE, BufferView.NONE, BufferView.NONE,
            BufferView.NONE, BufferView.NONE, BufferView.NONE, 0, 0, 0);
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
        /** The sky as a light (AtmosphereState.skyLightZenith/Horizon); the two vectors above are the visible sky. */
        final Vector4f skyLightZenith = new Vector4f();
        final Vector4f skyLightHorizon = new Vector4f();
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

        /** Mirrors {@code FRAME_FLAG_CAMERA_IN_WATER}: the eye's own block is water. */
        static final int FLAG_CAMERA_IN_WATER = 1 << 5;

        /**
         * Mirrors {@code FRAME_FLAG_WATER_REALISTIC} and {@code FRAME_FLAG_WATER_CAUSTICS}: the player's water style
         * and caustics switches, written from {@code RtFrameParams} so both path entries see one conversion.
         */
        static final int FLAG_WATER_REALISTIC = 1 << 6;
        static final int FLAG_WATER_CAUSTICS = 1 << 7;

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
            putVector(out, base + ATMOSPHERE + 32, skyLightZenith);
            putVector(out, base + ATMOSPHERE + 48, skyLightHorizon);
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
     * The 112-byte push block of {@code passes/path_trace_scene.slang}: path limits, sampling inputs, the emitter list of the
     * frame, the ReSTIR DI temporal state, the ray profile's bounce ranges and the entity ring of this frame. Exposure is not
     * here on purpose: the pass writes scene-referred radiance and the display step applies {@code FrameConstants.exposure}
     * exactly once.
     */
    record Dispatch(int maxDepth, int maxSurfaceSteps, int sampleIndex, int materialCount, int flags,
                    float rayBias, float alphaCutoff, float maxDistance, float maxRadiance, float sunProbability,
                    int emitterCount, float emitterProbability, float emitterCamRelX, float emitterCamRelY,
                    float emitterCamRelZ, int emitterCandidates,
                    float diCamDeltaX, float diCamDeltaY, float diCamDeltaZ, int diFlags, int diParity,
                    int entityAtlasSize, int selfAvatarTriStart, int selfAvatarTriCount, float autoNormalStrength,
                    float emitterDistance, float diffuseDistance, float specularDistance) {
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
            if (emitterCount < 0) {
                throw new IllegalArgumentException("emitterCount must be >= 0, got " + emitterCount);
            }
            if (!(emitterProbability >= 0f) || emitterProbability > 1f) {
                throw new IllegalArgumentException("emitterProbability must be in [0, 1], got " + emitterProbability);
            }
            if (emitterCandidates < 1 || emitterCandidates > 32) {
                throw new IllegalArgumentException("emitterCandidates must be in [1, 32], got " + emitterCandidates);
            }
            if (!Float.isFinite(emitterCamRelX) || !Float.isFinite(emitterCamRelY) || !Float.isFinite(emitterCamRelZ)) {
                throw new IllegalArgumentException("the emitter camera offset must be finite");
            }
            if (diParity < 0 || diParity > 1) {
                throw new IllegalArgumentException("the reservoir parity selects one of two halves, got " + diParity);
            }
            if (!Float.isFinite(diCamDeltaX) || !Float.isFinite(diCamDeltaY) || !Float.isFinite(diCamDeltaZ)) {
                throw new IllegalArgumentException("the camera delta of a reused reservoir must be finite");
            }
            if (entityAtlasSize < 0 || selfAvatarTriStart < 0 || selfAvatarTriCount < 0) {
                throw new IllegalArgumentException("the entity state of a frame cannot be negative, got "
                    + entityAtlasSize + "/" + selfAvatarTriStart + "/" + selfAvatarTriCount);
            }
            if (!(autoNormalStrength >= 0f) || !Float.isFinite(autoNormalStrength)) {
                throw new IllegalArgumentException("auto-normal strength must be finite and >= 0, got " + autoNormalStrength);
            }
            if (!Float.isFinite(emitterDistance) || !Float.isFinite(diffuseDistance)
                || !Float.isFinite(specularDistance)) {
                throw new IllegalArgumentException("the bounce distances must be finite, got "
                    + emitterDistance + "/" + diffuseDistance + "/" + specularDistance);
            }
        }

        /** The same dispatch with another option word: the backend fills the ReSTIR PT parity and history bits. */
        Dispatch withFlags(int optionFlags) {
            return new Dispatch(maxDepth, maxSurfaceSteps, sampleIndex, materialCount, optionFlags,
                rayBias, alphaCutoff, maxDistance, maxRadiance, sunProbability,
                emitterCount, emitterProbability, emitterCamRelX, emitterCamRelY, emitterCamRelZ, emitterCandidates,
                diCamDeltaX, diCamDeltaY, diCamDeltaZ, diFlags, diParity,
                entityAtlasSize, selfAvatarTriStart, selfAvatarTriCount, autoNormalStrength,
                emitterDistance, diffuseDistance, specularDistance);
        }

        Dispatch withDiState(int diFlags, int diParity) {
            return new Dispatch(maxDepth, maxSurfaceSteps, sampleIndex, materialCount, flags,
                rayBias, alphaCutoff, maxDistance, maxRadiance, sunProbability,
                emitterCount, emitterProbability, emitterCamRelX, emitterCamRelY, emitterCamRelZ, emitterCandidates,
                diCamDeltaX, diCamDeltaY, diCamDeltaZ, diFlags, diParity,
                entityAtlasSize, selfAvatarTriStart, selfAvatarTriCount, autoNormalStrength,
                emitterDistance, diffuseDistance, specularDistance);
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
            out.putInt(base + 40, emitterCount);
            out.putFloat(base + 44, emitterProbability);
            out.putFloat(base + 48, emitterCamRelX);
            out.putFloat(base + 52, emitterCamRelY);
            out.putFloat(base + 56, emitterCamRelZ);
            out.putFloat(base + 60, emitterCandidates);
            out.putFloat(base + 64, diCamDeltaX);
            out.putFloat(base + 68, diCamDeltaY);
            out.putFloat(base + 72, diCamDeltaZ);
            // The three words the reservoir and DI state left reserved now carry the ray profile's bounce ranges; a
            // dispatch that does not fill them (0) traces with the ray far plane exactly as before.
            out.putFloat(base + 76, emitterDistance);
            out.putInt(base + 80, diFlags);
            out.putInt(base + 84, diParity);
            out.putFloat(base + 88, diffuseDistance);
            out.putFloat(base + 92, specularDistance);
            out.putInt(base + 96, entityAtlasSize);
            out.putInt(base + 100, selfAvatarTriStart);
            out.putInt(base + 104, io.github.andrexarlay.vulkancompat.rt.entity.SelfAvatarGate.packCountAndShadow(
                selfAvatarTriCount, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.selfShadow));
            out.putFloat(base + 108, autoNormalStrength);
        }
    }

    /**
     * Bytes of the ReSTIR PT ring at a render extent: two parity halves of one {@link #PT_RESERVOIR_BYTES} record per
     * pixel - 597.2 MB at 1080p, which is why the switch is off by default.
     */
    static long ptReservoirBytes(int width, int height) {
        return 2L * width * height * PT_RESERVOIR_BYTES;
    }

    /** True when this device runtime can register the pass: the AOT artifact exists and every required bit is enabled. */
    static boolean isSupported(SlangRuntime runtime) {
        return runtime != null && runtime.supports(REQUIRED_CAPABILITIES) && SlangPassLibrary.available(PASS_NAME);
    }

    /**
     * Checks the generated manifest against the resources this class supplies. The shader is the source of truth, so a
     * mismatch prevents recording with an incompatible layout.
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
     * The spatial stage's manifest against what {@link #recordPtSpatial} binds: exactly the expected trace bindings, the
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
