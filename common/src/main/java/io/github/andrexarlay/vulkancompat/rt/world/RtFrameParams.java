package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;

/** Host state packed by each render pass into its own Slang contract. */
public final class RtFrameParams {
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
    /** Path tracing: tone mapping adapts to the measured luminance ({@code path_auto_exposure}); the number it adapts to
     * is {@link #exposure}. */
    public static final int FLAG_AUTO_EXPOSURE = 1 << 8;
    /** Ray mode: one-bounce ray traced global illumination. */
    public static final int FLAG_GI = 1 << 9;
    /** Generated normal maps (sprites without LabPBR _n), strength in the frame slot. */
    public static final int FLAG_AUTO_NORMALS = 1 << 10;
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
    /** Section radius of the RT world this frame (16 blocks each): the ray profile traces no farther than this. */
    public int rtRadiusXz = 8;
    // ---- written to the per-frame slot (RtFrameRenderer), not pushed ----
    /**
     * The frame's tone-mapping exposure: {@link RtExposure#applied} of the auto state and the EV compensation. One number
     * for the whole frame - the display step, the volumetric composite, the denoiser packs and the StatsBuf slot all read
     * this field, so a slider cannot brighten one lane and leave another where it was.
     */
    public float exposure = RtExposure.MIN;
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
    /**
     * The upscaler's sub-pixel jitter of this frame in pixels, the value DLSS-RR and NRD are told; the primary rays use it
     * whenever it is active so the denoiser reprojects each sample to where it was traced.
     */
    public boolean upscalerJittered;
    /** RR owns coherent primary samples, positive linear depth and independent reflection geometry. CPU only. */
    public boolean rrGuides;
    public float upscalerJitterX;
    public float upscalerJitterY;
    public boolean sceneChanged;
    /** The camera stands in water ({@code FogType.WATER}), so a primary ray starts inside the medium. */
    public boolean cameraInWater;
    // ---- emitter list of this frame, for the Slang path tracer's block-emitter NEE (StatsBuf mirrors these) ----
    /** VkBuffer of the immutable BEM1 table published by {@link RtEmitters}. */
    public long emitterHandle;
    public long emitterOffset;
    public int emitterCount;
    /** Selected light-content revision ({@link RtEmitters#fingerprint()}); invalidates cached PT suffixes.
     * DI uses per-light stable IDs instead of clearing its whole ring on list changes. */
    public long emitterFingerprint;
    /** Camera position minus the block origin the list was rebuilt at: the shader subtracts it from every stored corner. */
    public float emitterCamRelX;
    public float emitterCamRelY;
    public float emitterCamRelZ;
    // ---- statistics slot of this frame, for the Slang counter pass (the same slot the host reads back) ----
    /** VkBuffer of the statistics ring and the byte offset of the slot this frame leased. The counter pass binds only
     * the sixteen-byte head of that slot, so it can reach the four counters and nothing else in the frame's record.
     * Borrowed from the renderer, never owned. */
    public long statsBuffer;
    public long statsOffset;
    public final RtSky.Key key = new RtSky.Key();
    public final float[] skyColor = {0.47f, 0.65f, 1.0f};
    /**
     * CPU only: the sky as a light source, zenith and horizon radiance of the dome a bounce or a sky sample receives
     * ({@link RtSky#skyLight}, the legacy hemisphere model). The visible sky ({@link #skyColor}) is far brighter and
     * bluer; lighting the scene with it washed every surface blue.
     */
    public final float[] skyLightZenith = new float[3];
    public final float[] skyLightHorizon = new float[3];
}
