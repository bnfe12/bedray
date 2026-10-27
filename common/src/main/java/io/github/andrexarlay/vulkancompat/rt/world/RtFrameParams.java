package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * Push constants of rt_rays.comp / rt_path.comp (exactly 128 bytes, the guaranteed minimum of maxPushConstantsSize):
 * <pre>
 *   0  mat4  invViewProj
 *  64  uvec2 statsAddr
 *  72  uint  modeBits   view (bits 0-7) | aoSamples (8-15) | flipY (16) | tonemap (17) | accumCount (18-31)
 *  76  uint  frame
 *  80  vec4  keyDir (xyz towards the light, w = day factor)
 *  96  vec4  keyColor (rgb); w = uint flags (FLAG_*, read with floatBitsToUint)
 * 112  vec4  skyColor (rgb); w = uint params2: path: quality (0-7) | bounces (8-15) | spp (16-23) | rayDistance/4 (24-31);
 *                                             rays: shadow, emissive, GI, reflection rays (4 bits each, 0-15) | reflDistance/4 (24-31)
 * </pre>
 */
public final class RtFrameParams {
    public static final int BYTES = 128;

    /** PC member types in declaration order (layout(push_constant) uniform PC in rt_common.glsl; verified by RtStatsLayoutTest). */
    static final String[] PC_TYPES = {"mat4", "uvec2", "uint", "uint", "vec4", "vec4", "vec4"};
    /** std430 offsets of the PC members, computed once by the same rules the GLSL compiler applies. */
    static final int[] PC_OFFSETS = RtStatsLayout.std430Offsets(PC_TYPES);
    static final int OFF_INV_VIEW_PROJ = PC_OFFSETS[0];
    static final int OFF_STATS_ADDR = PC_OFFSETS[1];
    static final int OFF_MODE_BITS = PC_OFFSETS[2];
    static final int OFF_FRAME = PC_OFFSETS[3];
    static final int OFF_KEY_DIR = PC_OFFSETS[4];
    static final int OFF_KEY_COLOR = PC_OFFSETS[5];
    static final int OFF_SKY_COLOR = PC_OFFSETS[6];

    public static final int FLAG_SHADOWS = 1;
    public static final int FLAG_SOFT_SHADOWS = 1 << 1;
    public static final int FLAG_REFLECTIONS = 1 << 2;
    public static final int FLAG_AO = 1 << 3;
    public static final int FLAG_EMISSIVE = 1 << 4;
    /** The material/normal atlases hold baked data (else the shader uses defaults). */
    public static final int FLAG_MATERIALS = 1 << 5;
    /** Path tracing: accumulate into the accumulation image (else every frame is shown alone). */
    public static final int FLAG_ACCUMULATE = 1 << 6;
    /** Normal maps from the LabPBR _n atlas. */
    public static final int FLAG_NORMAL_MAPS = 1 << 7;
    /** Path tracing: tone mapping uses the auto exposure from the frame slot (see RtExposure). */
    public static final int FLAG_AUTO_EXPOSURE = 1 << 8;
    /** Ray mode: one-bounce ray traced global illumination. */
    public static final int FLAG_GI = 1 << 9;
    /** Generated normal maps (sprites without LabPBR _n), strength in the frame slot. */
    public static final int FLAG_AUTO_NORMALS = 1 << 10;
    /** DLSS-RR: feed flat voxel geometric normals (ng) instead of perturbed shading normals (ns). */
    public static final int FLAG_GEOMETRIC_NORMALS = 1 << 11;
    /** Volumetric fog and atmospheric haze. */
    public static final int FLAG_VOLUMETRIC_FOG = 1 << 12;
    /** Water style: 1 = Realistic (wave normals, caustics, refraction), 0 = Vanilla (classic clear). */
    public static final int FLAG_WATER_REALISTIC = 1 << 13;
    /** Vibrant Visuals tone mapping and color grade mode. */
    public static final int FLAG_VIBRANT_VISUALS = 1 << 14;
    /** Path tracing water caustics simulation. */
    public static final int FLAG_CAUSTICS = 1 << 15;
    /** First-hit sun NEE is included in the denoised radiance rather than passed through raw. */
    public static final int FLAG_SUN_DENOISED = 1 << 16;
    public static final int FLAG_SKIP_DIRECT_TRANSLUCENT = 1 << 17;
    public static final int FLAG_SKIP_INDIRECT_TRANSLUCENT = 1 << 18;
    public static final int FLAG_TRANSPARENCY_STATS = 1 << 19;
    public static final int FLAG_OPTICAL_MATERIALS = 1 << 20;
    /** Temporal accumulation stores irradiance (colour / primary albedo) and remodulates with the CURRENT albedo (sharp textures). */
    public static final int FLAG_DEMODULATE = 1 << 24;
    /** Low-discrepancy spatio-temporal sample sequence of the ray shaders (rt_noise.glsl); {@code -Dvkrtx.rt.lowDisc=off} for A/B runs. */
    public static final int FLAG_LOWDISC = 1 << 25;
    public static final int MAX_ACCUM = (1 << 14) - 1;

    public final Matrix4f invViewProj = new Matrix4f();
    /** View-projection of the previous frame, or {@code invViewProj}-consistent on the first frame after a reset. */
    public final Matrix4f prevViewProj = new Matrix4f();
    /** Camera delta between the previous and this frame's TLAS anchor; zero on the first frame. */
    public float cameraDeltaX;
    public float cameraDeltaY;
    public float cameraDeltaZ;
    /** True when neither the anchor nor the pose changed, so history can be read at the same pixel. */
    public boolean cameraStatic;
    /** CPU only: inverse view-projection without the upscaler jitter (accumulation reset decision). */
    public final Matrix4f invViewProjStable = new Matrix4f();
    public long statsAddress;
    public int mode;
    public int aoSamples;
    public boolean flipY;
    public boolean tonemap;
    /** Samples already in the accumulation image (0 = reset); path tracing only. */
    public int accumCount;
    public int frame;
    public int flags;
    public int quality = 1;
    public int bounces;
    public int spp = 1;
    public int rayDistance = 128;
    /** Ray mode: rays per pixel nibbles (RtRayEffectsPass.packQualities) replacing quality/bounces/spp in params2; -1 = path layout. */
    public int rayQualities = -1;
    /** Ray mode spatial filter (rt_atrous.comp): iteration index (params2 bits 16-19) and iteration count (20-23). */
    public int filterIter;
    public int filterIters;
    // ---- written to the per-frame slot (RtFrameRenderer), not pushed ----
    public float giDistance = 64f;
    public float emissiveDistance = 32f;
    public float aoRadius = 2.5f;
    /** Longest temporal history the shader may use this frame (RtAccumulation.cap). */
    public int historyCap = MAX_ACCUM;
    public boolean volumetricFogEnabled = true;
    public float fogDensity = 1.0f;
    public float fogInScattering = 1.0f;
    public float fogDistanceScale = 1.0f;
    // ---- CPU-only frame state (not pushed): camera position and geometry change, for the accumulation decision ----
    public double cameraX;
    public double cameraY;
    public double cameraZ;
    /** Effective camera origin in raw-camera-relative space (view bob); zero when bob projection is disabled. */
    public float bobX;
    public float bobY;
    public float bobZ;
    public boolean sceneChanged;
    // ---- emitter list of this frame, for the Slang path tracer's block-emitter NEE (StatsBuf mirrors these) ----
    /** VkBuffer of the {@link RtEmitters} ring; {@link #emitterOffset} selects the segment in use. */
    public long emitterHandle;
    public long emitterOffset;
    public int emitterCount;
    /** Camera position minus the block origin the list was rebuilt at: the shader subtracts it from every stored corner. */
    public float emitterCamRelX;
    public float emitterCamRelY;
    public float emitterCamRelZ;
    public final RtSky.Key key = new RtSky.Key();
    public final float[] skyColor = {0.47f, 0.65f, 1.0f};

    public int modeBits() {
        return (mode & 0xFF) | ((aoSamples & 0xFF) << 8) | (flipY ? 1 << 16 : 0) | (tonemap ? 1 << 17 : 0)
            | ((Math.min(accumCount, MAX_ACCUM) & 0x3FFF) << 18);
    }

    public int params2() {
        if (filterIters > 0) {
            int base = rayQualities >= 0 ? (rayQualities & 0xFFFF) : ((quality & 0xFF) | ((bounces & 0xFF) << 8));
            return (base & 0xFFFF) | ((filterIter & 15) << 16) | ((filterIters & 15) << 20)
                | ((Math.min(1020, rayDistance) / 4 & 0xFF) << 24);
        }
        if (rayQualities >= 0) {
            return (rayQualities & 0xFFFF) | ((filterIter & 15) << 16) | ((filterIters & 15) << 20) | ((Math.min(1020, rayDistance) / 4 & 0xFF) << 24);
        }
        return (quality & 0xFF) | ((bounces & 0xFF) << 8) | ((spp & 0xFF) << 16) | ((Math.min(1020, rayDistance) / 4 & 0xFF) << 24);
    }

    /** Writes the 128 bytes at absolute offset 0 (position and limit of {@code b} are not changed). */
    public void write(ByteBuffer b) {
        invViewProj.get(OFF_INV_VIEW_PROJ, b);
        b.putLong(OFF_STATS_ADDR, statsAddress);
        b.putInt(OFF_MODE_BITS, modeBits());
        b.putInt(OFF_FRAME, frame);
        b.putFloat(OFF_KEY_DIR, key.dir[0]).putFloat(OFF_KEY_DIR + 4, key.dir[1])
            .putFloat(OFF_KEY_DIR + 8, key.dir[2]).putFloat(OFF_KEY_DIR + 12, key.day);
        b.putFloat(OFF_KEY_COLOR, key.color[0]).putFloat(OFF_KEY_COLOR + 4, key.color[1])
            .putFloat(OFF_KEY_COLOR + 8, key.color[2]).putInt(OFF_KEY_COLOR + 12, flags);
        b.putFloat(OFF_SKY_COLOR, skyColor[0]).putFloat(OFF_SKY_COLOR + 4, skyColor[1])
            .putFloat(OFF_SKY_COLOR + 8, skyColor[2]).putInt(OFF_SKY_COLOR + 12, params2());
    }
}
