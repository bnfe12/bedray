package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Settings of the two ray tracing modes (all [EXPERIMENTAL]), persisted in {@code config/bedray.toml}
 * ({@code [ray_tracing]}, {@code [path_tracing]}). Ray Tracing (separate effects, each with its own switch, quality and
 * distance) and Path Tracing (full light transport) never share a value. A {@link Preset} sets the mode and every value at
 * once; changing a single value afterwards turns the preset into {@link Preset#CUSTOM}.
 * <p>
 * Session overrides for autotests (never saved): {@code -Dvkrtx.rt.<toml key>=<value>}, e.g. {@code -Dvkrtx.rt.rays_ao=true},
 * {@code -Dvkrtx.rt.path_bounces=4}, {@code -Dvkrtx.rt.path_denoiser=nrd}. Invalid values are logged and ignored.
 */
public final class RtSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/RtSettings");

    public enum PathDenoiser { OFF, NRD, DLSS_RR }

    /**
     * Frame-rate budget of the adaptive quality of the Path Tracing pass. The pass measures its own GPU time and lowers rays
     * per pixel first, then the path length, until it fits; the spp and bounces the player set are the ceiling, so it never
     * renders better than those values. The budget is a share ({@link #BUDGET_SHARE}) of one frame of that rate: the world
     * raster, the compositor and the present still cost their own time on top of the ray tracing pass.
     */
    public enum AdaptiveQuality {
        OFF(0), FPS30(30), FPS45(45), FPS60(60), FPS90(90), FPS120(120);

        public static final double BUDGET_SHARE = 0.7;
        public final int fps;

        AdaptiveQuality(int fps) {
            this.fps = fps;
        }

        /** GPU budget of the ray tracing pass in milliseconds; 0 when adaptive quality is off. */
        public double budgetMs() {
            return fps == 0 ? 0.0 : 1000.0 * BUDGET_SHARE / fps;
        }
    }

    /** Quality presets of the settings page; CUSTOM keeps the individual values. */
    public enum Preset {
        OFF(RtMode.OFF), LOW(RtMode.RAY_TRACED), MEDIUM(RtMode.RAY_TRACED), HIGH(RtMode.RAY_TRACED), ULTRA(RtMode.RAY_TRACED),
        PATH_TRACING(RtMode.PATH_TRACED), FAST(RtMode.PATH_TRACED), QUALITY(RtMode.PATH_TRACED), CUSTOM(null);

        private final RtMode mode;

        Preset(RtMode mode) {
            this.mode = mode;
        }

        /** Mode selected by the preset, null for CUSTOM (the RT Mode option decides). */
        public RtMode mode() {
            return mode;
        }
    }

    public static final int DISTANCE_MIN = 16;
    public static final int DISTANCE_MAX = 512;

    // ---- Ray Tracing mode: one switch + distance per effect (block light also has a sample count) ----
    public static boolean raysShadows = true;
    /** Shadow rays jittered inside a widened sun disk (soft penumbra); the accumulation averages them. */
    public static boolean raysSoftShadows = false;
    /** Stylised retro shadows: the sun visibility is evaluated once per world-space cell, so edges are blocky like a low-res shadow map. */
    public static boolean raysPixelShadows = false;
    /** Edge of a pixelated-shadow cell in texture pixels (1 = one pixel of a 16x16 block texture = 1/16 block), both modes. */
    public static int raysPixelShadowSize = 1;
    public static boolean raysReflections = false;
    public static int raysReflectionDistance = 128;
    public static boolean raysAo = false;
    /** AO radius in blocks. */
    public static int raysAoRadius = 3;
    public static boolean raysEmissive = true;
    public static int raysEmissiveQuality = 1;
    public static int raysEmissiveDistance = 32;
    /** One-bounce ray traced global illumination (replaces the vanilla sky-light ambient). */
    public static boolean raysGi = false;
    public static int raysGiDistance = 64;
    /** Temporal accumulation of the stochastic effects while the camera and the scene stand still. */
    public static boolean raysAccumulate = true;
    /**
     * ReSTIR temporal reuse of the ray pass: one reservoir per pixel keeps a torch sample across frames instead of
     * redrawn every frame. Off, the emissive term is sampled per frame again - noisier while moving, but free of any
     * chance that a stale record lights a surface it never belonged to.
     */
    public static boolean raysRestirTemporal = true;
    /** ReSTIR spatial reuse of the ray pass: the two checker phases pool the reservoirs of neighbouring pixels. */
    public static boolean raysRestirSpatial = true;

    // ---- Scene (both modes) ----
    /** RT distance = the game render distance (horizontal), 8 sections up and down. */
    public static boolean rtDistanceAuto = true;
    /** Manual RT distance in chunks (sections) when {@link #rtDistanceAuto} is off. */
    public static int rtDistanceChunks = 8;
    /** GPU memory for BLAS + geometry; above it the farthest sections are not built (nearest first). */
    public static int vramBudgetMb = 2048;
    /** Angular diameter of the sun/moon disk for soft shadows, tenths of a degree (5 = 0.5 deg = the real sun; 14 = a widened artistic disk, 1.4 deg: a visible but still crisp penumbra). */
    public static int sunSizeTenths = 5;
    /** Tilt of the sun/moon/star arc towards the south in degrees (0 = vanilla: sun through the zenith, no noon shadows). */
    public static int sunTiltDeg = 24;
    /**
     * "Baked vanilla light fill", percent 0..100, rays mode only (default 0 = off: light is fully traced, sky through rays and
     * block light through the emitters). Mixes back the vanilla vertex light: sky light multiplies the sky term and block light adds
     * a warm fill. Path mode never uses it. JVM override {@code -Dvkrtx.rt.bakedLightFill=<0..1>}.
     */
    public static int bakedLightFillPct = 0;
    /** Most important emissive quads (emission * area / distance^2 around the camera) kept in the light list. */
    public static int lightBudget = 512;
    /** Settings page layout: show the advanced / expert RT controls. */
    public static boolean showAdvanced = false;
    public static boolean showExpert = false;

    // ---- Materials (both modes) ----
    /** Normal maps generated from the albedo for textures without a LabPBR _n map. */
    public static boolean autoNormals = true;
    /** Strength of the generated normal maps in percent (100 = AutoNormals.BASE_STRENGTH). */
    public static int autoNormalStrength = 100;

    // ---- Water & Liquids ----
    public static final int WATER_STYLE_VANILLA = 0;
    public static final int WATER_STYLE_REALISTIC = 1;
    /** Water style: 0 = Vanilla (classic clear azure Minecraft look), 1 = Realistic (wave normal ripples, caustics, refraction). */
    public static int waterStyle = WATER_STYLE_REALISTIC;
    public static boolean directTranslucentShadows = true;
    public static boolean opticalMaterials = true;
    public static boolean indirectTranslucentShadows = true;
    public static boolean cullSharedGlass = true;
    public static boolean transparencyStats = false;

    // ---- Foliage & Wind ----
    public static boolean wavingFoliage = false;
    public static int wavingFoliageStrength = 100;

    // ---- Atmosphere & Fog ----
    public static boolean volumetricFogEnabled = true;
    public static float fogDensity = 1.0f;
    public static float fogInScattering = 1.0f;
    public static float fogDistanceScale = 1.0f;
    public static boolean rtFog = true;
    public static int rtFogDensity = 100;
    public static int rtFogInScattering = 100;

    // ---- Path Tracing mode ----
    public static int pathBounces = 3;
    public static int pathSpp = 1;
    public static boolean pathAccumulate = true;
    /**
     * ReSTIR direct-light reuse of the Slang path tracer: the block-emitter sample of a pixel lives in a per-pixel
     * reservoir and is reprojected into the next frame, so an interior converges while the camera moves. Off, every
     * frame redraws the emitter list of that pixel (the same list, the same RIS estimator, only no history).
     */
    public static boolean pathRestirDi = true;
    /**
     * ReSTIR spatial reuse of the Slang path tracer: the records the temporal stage wrote are pooled with the neighbours
     * on the same surface, and the next frame's temporal import reads the pooled half. Needs {@link #pathRestirDi}: with
     * direct-light reuse off there are no records to pool, so this switch alone does nothing.
     */
    public static boolean pathRestirSpatial = true;
    /**
     * ReSTIR PT of the Slang path tracer: the diffuse indirect light of a pixel's first bounce is kept in a per-pixel path
     * reservoir and reprojected into the next frame (restir/pt_temporal.slang). Off by default: the ring costs two
     * 144-byte records per pixel (597.2 MB at 1080p). Offered as a child of {@link #pathRestirDi}, and run only by the
     * reference backend, which owns the ring; the native core binds a stand-in.
     */
    public static boolean pathRestirPt = false;
    /**
     * The spatial stage of ReSTIR PT (restir/pt_spatial.slang): each pixel pools four neighbours' path records into the
     * half the next frame imports. On by default under its parent, which already carries the memory price; it costs up to
     * four first-edge visibility rays a pixel and no memory of its own.
     */
    public static boolean pathRestirPtSpatial = true;
    /** NRD by default: used when the NRD natives are installed, otherwise the path tracer falls back to accumulation (logged). */
    public static PathDenoiser pathDenoiser = PathDenoiser.NRD;
    public static int pathDistance = 256;
    /**
     * Adaptive quality of the path tracer: keeps the RT pass inside {@link AdaptiveQuality#budgetMs()} by lowering spp, then
     * bounces, below the values above (never above them). OFF by default: a budget is a target, and a pass that cannot reach
     * it (a 4K trace on a mid-range GPU costs far more than one frame of 60 FPS) would sit at one ray forever instead of
     * showing the scene the player asked for.
     */
    public static AdaptiveQuality adaptiveQuality = AdaptiveQuality.OFF;
    /** Tone mapping exposure adapts to the mean (log) luminance of the image (never darker than the ray mode). */
    public static boolean pathAutoExposure = true;
    /** Exposure compensation in tenths of EV (-40..+40, 0 = none). Auto exposure on: added to the adapted value; off: fixed exposure 1.1 * 2^EV. */
    public static int pathExposureEvTenths = 0;
    /**
     * DLSS Ray Reconstruction: feed flat voxel geometric normals (ng) to DLSS-RR
     * instead of perturbed texture shading normals (ns) to preserve sharp block edges and avoid neural blurring.
     * When off, perturbed shading normals (ns) are fed, giving a smooth/curved appearance to voxel corners.
     */
    public static boolean dlssRrGeometricNormals = true;
    /** Path tracing water caustics simulation focusing sunlight and light under water. */
    public static boolean caustics = false;
    /** Vibrant Visuals style: rich grass and water tones, high color contrast, soft bloom and cinematic ACES. */
    public static boolean vibrantVisuals = false;
    /** Particles (smoke, flames, sparks, bubbles, block breaking...) are drawn into the ray traced image (RtParticleLayer). */
    public static boolean rtParticles = true;
    /** Rain and snow columns are drawn into the ray traced image too (RtWeatherLayer, on the particle layer of RtParticleLayer). */
    public static boolean rtWeather = true;

    // ---- DLSS 5 Neural Rendering (DLSS-NR) [EXPERIMENTAL] ----
    public static boolean dlssNrEnabled = false;
    public static float dlssNrTransferStrength = 1.0f;
    public static float dlssNrColourStrength = 1.0f;
    public static float dlssNrMaxRatio = 2.0f;
    public static float dlssNrWorkingScale = 1.0f;
    public static int dlssNrDebugView = 0;

    /** Texture LOD bias for ray tracing material samplers. */
    public static float textureLodBias() {
        return TextureLodBias.getEffectiveLodBias();
    }

    public static float getEffectiveTextureLodBias() {
        return TextureLodBias.getEffectiveLodBias();
    }

    /** OFF for a new config; a config file without rt_preset (older version) loads as CUSTOM ({@link #presetSeen}). */
    public static Preset preset = Preset.MEDIUM;
    static boolean presetSeen;

    private record IntSetting(IntSupplier get, IntConsumer set, int min, int max) {
    }

    private record BoolSetting(BooleanSupplier get, Consumer<Boolean> set) {
    }

    private static final Map<String, IntSetting> INTS = new LinkedHashMap<>();
    private static final Map<String, BoolSetting> BOOLS = new LinkedHashMap<>();
    private static final Map<String, Integer> INT_OVR = new LinkedHashMap<>();
    private static final Map<String, Boolean> BOOL_OVR = new LinkedHashMap<>();
    private static final PathDenoiser DENOISER_OVR;
    /** {@code -Dvkrtx.rt.adaptive_quality=} value of this session; null when the config owns it (it is never written out). */
    private static final AdaptiveQuality ADAPTIVE_OVR;

    static {
        bool("rays_direct_translucent_shadows", () -> directTranslucentShadows, v -> directTranslucentShadows = v);
        bool("rays_optical_materials", () -> opticalMaterials, v -> opticalMaterials = v);
        bool("rays_indirect_translucent_shadows", () -> indirectTranslucentShadows, v -> indirectTranslucentShadows = v);
        bool("rays_cull_shared_glass", () -> cullSharedGlass, v -> cullSharedGlass = v);
        bool("rays_transparency_stats", () -> transparencyStats, v -> transparencyStats = v);
        bool("rays_shadows", () -> raysShadows, v -> raysShadows = v);
        bool("rays_soft_shadows", () -> raysSoftShadows, v -> raysSoftShadows = v);
        bool("rays_pixel_shadows", () -> raysPixelShadows, v -> raysPixelShadows = v);
        integer("rays_pixel_shadow_size", () -> raysPixelShadowSize, v -> raysPixelShadowSize = v, 1, 16);
        bool("rays_reflections", () -> raysReflections, v -> raysReflections = v);
        integer("rays_reflection_distance", () -> raysReflectionDistance, v -> raysReflectionDistance = v, DISTANCE_MIN, DISTANCE_MAX);
        bool("rays_ao", () -> raysAo, v -> raysAo = v);
        integer("rays_ao_radius", () -> raysAoRadius, v -> raysAoRadius = v, 1, 8);
        bool("rays_emissive", () -> raysEmissive, v -> raysEmissive = v);
        integer("rays_emissive_quality", () -> raysEmissiveQuality, v -> raysEmissiveQuality = v, 1, 8);
        integer("rays_emissive_distance", () -> raysEmissiveDistance, v -> raysEmissiveDistance = v, 8, 64);
        bool("rays_gi", () -> raysGi, v -> raysGi = v);
        integer("rays_gi_distance", () -> raysGiDistance, v -> raysGiDistance = v, DISTANCE_MIN, 256);
        bool("rays_accumulate", () -> raysAccumulate, v -> raysAccumulate = v);
        bool("rays_restir_temporal", () -> raysRestirTemporal, v -> raysRestirTemporal = v);
        bool("rays_restir_spatial", () -> raysRestirSpatial, v -> raysRestirSpatial = v);
        integer("path_bounces", () -> pathBounces, v -> pathBounces = v, 1, 8);
        integer("path_spp", () -> pathSpp, v -> pathSpp = v, 1, 8);
        bool("path_accumulate", () -> pathAccumulate, v -> pathAccumulate = v);
        bool("path_restir_di", () -> pathRestirDi, v -> pathRestirDi = v);
        bool("path_restir_spatial", () -> pathRestirSpatial, v -> pathRestirSpatial = v);
        bool("path_restir_pt", () -> pathRestirPt, v -> pathRestirPt = v);
        bool("path_restir_pt_spatial", () -> pathRestirPtSpatial, v -> pathRestirPtSpatial = v);
        integer("path_distance", () -> pathDistance, v -> pathDistance = v, DISTANCE_MIN, DISTANCE_MAX);
        bool("path_auto_exposure", () -> pathAutoExposure, v -> pathAutoExposure = v);
        integer("path_exposure_ev", () -> pathExposureEvTenths, v -> pathExposureEvTenths = v, -40, 40);
        bool("dlss_rr_geometric_normals", () -> dlssRrGeometricNormals, v -> dlssRrGeometricNormals = v);
        bool("path_caustics", () -> caustics, v -> caustics = v);
        bool("caustics", () -> caustics, v -> caustics = v);
        bool("vibrant_visuals", () -> vibrantVisuals, v -> vibrantVisuals = v);
        bool("rt_particles", () -> rtParticles, v -> rtParticles = v);
        bool("rt_weather", () -> rtWeather, v -> rtWeather = v);
        bool("auto_normals", () -> autoNormals, v -> autoNormals = v);
        bool("rt_distance_auto", () -> rtDistanceAuto, v -> rtDistanceAuto = v);
        integer("rt_distance_chunks", () -> rtDistanceChunks, v -> rtDistanceChunks = v, 2, 32);
        integer("rt_vram_budget_mb", () -> vramBudgetMb, v -> vramBudgetMb = v, 256, 16384);
        integer("sun_size_tenths", () -> sunSizeTenths, v -> sunSizeTenths = v, 1, 50);
        integer("sun_tilt_deg", () -> sunTiltDeg, v -> sunTiltDeg = v, 0, 45);
        integer("baked_light_fill_pct", () -> bakedLightFillPct, v -> bakedLightFillPct = v, 0, 100);
        String bakedProp = System.getProperty("vkrtx.rt.bakedLightFill");
        if (bakedProp != null) {
            try {
                bakedLightFillPct = Math.round(clampFloat(Float.parseFloat(bakedProp.trim()), 0.0f, 1.0f) * 100f);
            } catch (Exception ignored) {}
        }
        integer("rt_light_budget", () -> lightBudget, v -> lightBudget = v, 16, 8192);
        bool("rt_show_advanced", () -> showAdvanced, v -> showAdvanced = v);
        bool("rt_show_expert", () -> showExpert, v -> showExpert = v);
        integer("auto_normal_strength", () -> autoNormalStrength, v -> autoNormalStrength = v, 0, 400);
        integer("water_style", () -> waterStyle, v -> waterStyle = v, 0, 1);
        bool("waving_foliage", () -> wavingFoliage, v -> wavingFoliage = v);
        integer("waving_foliage_strength", () -> wavingFoliageStrength, v -> wavingFoliageStrength = v, 0, 200);
        bool("dlss_nr_enabled", () -> dlssNrEnabled, v -> dlssNrEnabled = v);
        integer("dlss_nr_debug_view", () -> dlssNrDebugView, v -> dlssNrDebugView = v, 0, 3);
        bool("rt_fog", () -> volumetricFogEnabled, v -> { volumetricFogEnabled = v; rtFog = v; });
        bool("volumetric_fog_enabled", () -> volumetricFogEnabled, v -> { volumetricFogEnabled = v; rtFog = v; });
        integer("rt_fog_density", () -> Math.round(fogDensity * 100f), v -> {
            rtFogDensity = v;
            fogDensity = clampFloat(v / 100f, 0.0f, 2.0f);
        }, 0, 200);
        integer("rt_fog_in_scattering", () -> Math.round(fogInScattering * 100f), v -> {
            rtFogInScattering = v;
            fogInScattering = clampFloat(v / 100f, 0.0f, 2.0f);
        }, 0, 200);
        integer("rt_fog_distance_scale", () -> Math.round(fogDistanceScale * 100f), v -> {
            fogDistanceScale = clampFloat(v / 100f, 0.2f, 2.0f);
        }, 20, 200);
        String fogEnProp = System.getProperty("vkrtx.rt.volumetric_fog_enabled");
        if (fogEnProp != null) {
            volumetricFogEnabled = Boolean.parseBoolean(fogEnProp.trim());
            rtFog = volumetricFogEnabled;
        }
        String fogDensProp = System.getProperty("vkrtx.rt.fog_density");
        if (fogDensProp != null) {
            try {
                fogDensity = clampFloat(Float.parseFloat(fogDensProp.trim()), 0.0f, 2.0f);
                rtFogDensity = Math.round(fogDensity * 100f);
            } catch (Exception ignored) {}
        }
        String fogInscProp = System.getProperty("vkrtx.rt.fog_in_scattering");
        if (fogInscProp != null) {
            try {
                fogInScattering = clampFloat(Float.parseFloat(fogInscProp.trim()), 0.0f, 2.0f);
                rtFogInScattering = Math.round(fogInScattering * 100f);
            } catch (Exception ignored) {}
        }
        String fogDistProp = System.getProperty("vkrtx.rt.fog_distance_scale");
        if (fogDistProp != null) {
            try {
                fogDistanceScale = clampFloat(Float.parseFloat(fogDistProp.trim()), 0.2f, 2.0f);
            } catch (Exception ignored) {}
        }
        String nrTStr = System.getProperty("vkrtx.rt.dlss_nr_transfer_strength");
        if (nrTStr != null) {
            try { dlssNrTransferStrength = clampFloat(Float.parseFloat(nrTStr.trim()), 0.0f, 2.0f); } catch (Exception ignored) {}
        }
        String nrCStr = System.getProperty("vkrtx.rt.dlss_nr_colour_strength");
        if (nrCStr != null) {
            try { dlssNrColourStrength = clampFloat(Float.parseFloat(nrCStr.trim()), 0.0f, 1.0f); } catch (Exception ignored) {}
        }
        String nrMStr = System.getProperty("vkrtx.rt.dlss_nr_max_ratio");
        if (nrMStr != null) {
            try { dlssNrMaxRatio = clampFloat(Float.parseFloat(nrMStr.trim()), 1.0f, 10.0f); } catch (Exception ignored) {}
        }
        String nrWStr = System.getProperty("vkrtx.rt.dlss_nr_working_scale");
        if (nrWStr != null) {
            try { dlssNrWorkingScale = clampFloat(Float.parseFloat(nrWStr.trim()), 0.5f, 1.0f); } catch (Exception ignored) {}
        }
        for (Map.Entry<String, IntSetting> e : INTS.entrySet()) {
            String v = System.getProperty("vkrtx.rt." + e.getKey());
            if (v != null) {
                try {
                    INT_OVR.put(e.getKey(), clamp(Integer.parseInt(v.trim()), e.getValue().min(), e.getValue().max()));
                } catch (NumberFormatException ex) {
                    LOGGER.error("[VKB-RT] invalid -Dvkrtx.rt.{}={} (integer expected), ignored", e.getKey(), v);
                }
            }
        }
        for (String key : BOOLS.keySet()) {
            String v = System.getProperty("vkrtx.rt." + key);
            if (v != null) {
                if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
                    BOOL_OVR.put(key, Boolean.parseBoolean(v));
                } else {
                    LOGGER.error("[VKB-RT] invalid -Dvkrtx.rt.{}={} (true|false expected), ignored", key, v);
                }
            }
        }
        String d = System.getProperty("vkrtx.rt.path_denoiser");
        PathDenoiser parsed = null;
        if (d != null) {
            String clean = d.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (clean.equals("RR") || clean.equals("DLSS_D") || clean.equals("DLSS-RR")) {
                clean = "DLSS_RR";
            }
            try {
                parsed = PathDenoiser.valueOf(clean);
            } catch (IllegalArgumentException e) {
                LOGGER.error("[VKB-RT] invalid -Dvkrtx.rt.path_denoiser={} (off|nrd|dlss_rr), ignored", d);
            }
        }
        DENOISER_OVR = parsed;
        String aq = System.getProperty("vkrtx.rt.adaptive_quality");
        AdaptiveQuality aqOvr = null;
        if (aq != null) {
            try {
                aqOvr = AdaptiveQuality.valueOf(aq.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LOGGER.error("[VKB-RT] invalid -Dvkrtx.rt.adaptive_quality={} (off|fps30|fps45|fps60|fps90|fps120), ignored", aq);
            }
        }
        ADAPTIVE_OVR = aqOvr;
        // a fresh config starts with the values of the default quality presets (the toml file overrides them on load)
        applyPreset(Preset.MEDIUM);
        writeAlphaPathDefaults();
        pathPreset = PathPreset.CUSTOM;
        if (!INT_OVR.isEmpty() || !BOOL_OVR.isEmpty() || DENOISER_OVR != null || ADAPTIVE_OVR != null) {
            LOGGER.info("[VKB-RT] settings overrides (session only): {} {} {} adaptive_quality={}", INT_OVR, BOOL_OVR,
                DENOISER_OVR == null ? "" : "path_denoiser=" + DENOISER_OVR, ADAPTIVE_OVR == null ? "from config" : ADAPTIVE_OVR);
        }
    }

    private RtSettings() {
    }

    private static void bool(String key, BooleanSupplier get, Consumer<Boolean> set) {
        BOOLS.put(key, new BoolSetting(get, set));
    }

    private static void integer(String key, IntSupplier get, IntConsumer set, int min, int max) {
        INTS.put(key, new IntSetting(get, set, min, max));
    }

    private static boolean b(String key) {
        Boolean o = BOOL_OVR.get(key);
        return o != null ? o : BOOLS.get(key).get().getAsBoolean();
    }

    private static int i(String key) {
        Integer o = INT_OVR.get(key);
        return o != null ? o : INTS.get(key).get().getAsInt();
    }

    // ---- Effective values used by the renderer (session override, else the configured value) ----
    public static boolean shadows() { return b("rays_shadows"); }
    public static boolean directTranslucentShadows() { return b("rays_direct_translucent_shadows"); }
    public static boolean opticalMaterials() { return b("rays_optical_materials"); }
    public static boolean indirectTranslucentShadows() { return b("rays_indirect_translucent_shadows"); }
    public static boolean cullSharedGlass() { return b("rays_cull_shared_glass"); }
    public static boolean transparencyStats() { return b("rays_transparency_stats"); }
    public static boolean softShadows() { return b("rays_soft_shadows"); }
    public static boolean pixelShadows() { return b("rays_pixel_shadows"); }
    public static int pixelShadowSize() { return i("rays_pixel_shadow_size"); }
    public static boolean reflections() { return b("rays_reflections"); }
    public static int reflectionDistance() { return i("rays_reflection_distance"); }
    public static boolean ao() { return b("rays_ao"); }
    public static int aoRadius() { return i("rays_ao_radius"); }
    public static boolean emissive() { return b("rays_emissive"); }
    public static int emissiveQuality() { return i("rays_emissive_quality"); }
    public static int emissiveDistance() { return i("rays_emissive_distance"); }
    public static boolean gi() { return b("rays_gi"); }
    public static int giDistance() { return i("rays_gi_distance"); }
    public static boolean raysAccumulation() { return b("rays_accumulate"); }
    /**
     * ReSTIR temporal reuse of the ray pass. A {@code -Dvkrtx.rt.rays_restir_temporal=false} session override wins, exactly
     * like the other keys, and the renderer turns the feature off by handing the shader a zero reservoir address.
     */
    public static boolean restirTemporal() { return b("rays_restir_temporal"); }
    /**
     * ReSTIR spatial reuse of the ray pass. The kernel itself is only built when the session kept
     * {@code -Dvkrtx.rt.restirSpatial} on, so this decides whether the two checker phases are dispatched.
     */
    public static boolean restirSpatial() { return b("rays_restir_spatial"); }
    /** ReSTIR direct-light reuse of the Slang path tracer (see {@code RtConfig.SLANG_RESTIR_DI} for the session switch). */
    public static boolean pathRestirDi() { return b("path_restir_di"); }
    /**
     * Spatial pooling of those same records, as a stage of its own because it costs two full-screen dispatches that trace
     * no rays. The Slang kernel is optional: without its AOT artifact this reads true and the pass simply never runs.
     */
    public static boolean pathRestirSpatial() { return b("path_restir_spatial"); }
    /** ReSTIR PT temporal reuse of the path tracer's diffuse indirect light; see {@link #pathRestirPt}. */
    public static boolean pathRestirPt() { return b("path_restir_pt"); }
    /** The PT spatial stage; see {@link #pathRestirPtSpatial}. */
    public static boolean pathRestirPtSpatial() { return b("path_restir_pt_spatial"); }
    public static int bounces() { return i("path_bounces"); }
    public static int spp() { return i("path_spp"); }
    public static boolean accumulate() { return b("path_accumulate"); }
    public static int pathMaxDistance() { return i("path_distance"); }
    public static boolean autoExposure() { return b("path_auto_exposure"); }
    public static int exposureEvTenths() { return i("path_exposure_ev"); }
    public static boolean dlssRrGeometricNormals() { return b("dlss_rr_geometric_normals"); }
    public static boolean caustics() { return b("path_caustics") || b("caustics"); }
    public static boolean vibrantVisuals() { return b("vibrant_visuals"); }
    public static boolean particles() { return b("rt_particles"); }
    /**
     * Weather (rain / snow columns) rides on the particle layer of {@code RtParticleLayer} and its composite, so it is only ever on
     * while particles are on: off with them, otherwise the own setting decides. The RT image itself is a further condition, checked
     * by {@code RtWeatherLayer.capturing()}.
     */
    public static boolean weather() { return b("rt_weather") && b("rt_particles"); }
    public static boolean autoNormalsOn() { return b("auto_normals"); }
    public static boolean rtDistanceAuto() { return b("rt_distance_auto"); }
    public static int rtDistanceChunks() { return i("rt_distance_chunks"); }
    public static int vramBudgetMb() { return i("rt_vram_budget_mb"); }
    public static int lightBudget() { return i("rt_light_budget"); }
    /** Angular radius of the sun/moon disk in radians. */
    public static float sunRadiusRad() { return (float) Math.toRadians(i("sun_size_tenths") / 20.0); }
    /** Tilt of the celestial arc in radians ({@link io.github.andrexarlay.vulkancompat.rt.world.RtSky#tiltRad}). */
    /** Strength 0..1 of the optional baked vanilla light fill (rays mode). */
    public static float bakedLightFill() { return i("baked_light_fill_pct") / 100f; }
    public static float sunTiltRad() { return (float) Math.toRadians(i("sun_tilt_deg")); }
    public static int autoNormalStrengthPercent() { return i("auto_normal_strength"); }
    public static PathDenoiser denoiser() { return DENOISER_OVR != null ? DENOISER_OVR : pathDenoiser; }
    /** GPU budget of the ray tracing pass in milliseconds, 0 when adaptive quality is off. */
    public static double adaptiveBudgetMs() {
        AdaptiveQuality q = ADAPTIVE_OVR != null ? ADAPTIVE_OVR : adaptiveQuality;
        return q == null ? 0.0 : q.budgetMs();
    }

    /**
     * Saves the value chosen on the settings page. A {@code -Dvkrtx.rt.adaptive_quality=} override still wins for this
     * session, and says so once here instead of leaving a page that appears to do nothing.
     */
    public static void setAdaptiveQuality(AdaptiveQuality v) {
        adaptiveQuality = v;
        if (ADAPTIVE_OVR != null && v != ADAPTIVE_OVR) {
            LOGGER.info("[VKB-RT] adaptive_quality saved as {} but this session runs {} (-Dvkrtx.rt.adaptive_quality)",
                v, ADAPTIVE_OVR);
        }
    }
    public static boolean volumetricFogEnabled() { return b("volumetric_fog_enabled"); }
    public static float fogDensity() { return fogDensity; }
    public static float fogInScattering() { return fogInScattering; }
    public static float fogDistanceScale() { return fogDistanceScale; }
    public static boolean fog() { return volumetricFogEnabled(); }
    public static int waterStyle() { return i("water_style"); }
    public static boolean wavingFoliage() { return b("waving_foliage"); }
    public static int wavingFoliageStrength() { return i("waving_foliage_strength"); }

    public static boolean dlssNr() { return b("dlss_nr_enabled"); }
    public static float dlssNrTransferStrength() { return dlssNrTransferStrength; }
    public static float dlssNrColourStrength() { return dlssNrColourStrength; }
    public static float dlssNrMaxRatio() { return dlssNrMaxRatio; }
    public static float dlssNrWorkingScale() { return dlssNrWorkingScale; }
    public static int dlssNrDebugView() { return i("dlss_nr_debug_view"); }

    public static io.github.andrexarlay.vulkancompat.rt.upscale.dlss.nr.DlssNrConfig getDlssNrConfig() {
        io.github.andrexarlay.vulkancompat.rt.upscale.dlss.nr.DlssNrConfig cfg = new io.github.andrexarlay.vulkancompat.rt.upscale.dlss.nr.DlssNrConfig();
        cfg.setEnabled(dlssNr());
        cfg.setTransferStrength(dlssNrTransferStrength);
        cfg.setColourStrength(dlssNrColourStrength);
        cfg.setMaxRatio(dlssNrMaxRatio);
        cfg.setWorkingScale(dlssNrWorkingScale);
        cfg.setDebugView(dlssNrDebugView());
        return cfg;
    }

    public static float clampFloat(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * The two Path Tracing modes of the settings page (backlog item 12: "Fast" and "Quality" only). A preset writes every
     * path value the path tracer reads each frame (RtPathTracerPass: spp, bounces, ray distance, accumulation, auto exposure;
     * RtFrameRenderer: denoiser, caustics flag). CUSTOM keeps the individual values (a changed expert value switches to it).
     * The tracing mode itself is not touched (the "Tracing" option decides).
     */
    public enum PathPreset {
        // spp, bounces, distance (blocks), caustics, accumulation, auto exposure, denoiser
        FAST(1, 2, 128, false, true, true, PathDenoiser.NRD),
        QUALITY(2, 4, 384, true, true, true, PathDenoiser.NRD),
        CUSTOM(0, 0, 0, false, false, true, null);

        public final int spp;
        public final int bounces;
        public final int distance;
        public final boolean caustics;
        public final boolean accumulate;
        public final boolean autoExposure;
        public final PathDenoiser denoiser;

        PathPreset(int spp, int bounces, int distance, boolean caustics, boolean accumulate, boolean autoExposure, PathDenoiser denoiser) {
            this.spp = spp;
            this.bounces = bounces;
            this.distance = distance;
            this.caustics = caustics;
            this.accumulate = accumulate;
            this.autoExposure = autoExposure;
            this.denoiser = denoiser;
        }

        /** "1 spp, 2 bounces, 128 blocks, NRD, caustics off" (tooltip and log). */
        public String describe() {
            if (this == CUSTOM) {
                return "custom values";
            }
            return String.format(Locale.ROOT, "%d spp, %d bounces, %d blocks, denoiser %s, caustics %s", spp, bounces, distance,
                denoiser, caustics ? "on" : "off");
        }
    }

    /** Path Tracing preset of the page; FAST for a new config, CUSTOM for an older file without pt_preset. */
    public static PathPreset pathPreset = PathPreset.FAST;
    static boolean pathPresetSeen;

    /** Writes the values of a path preset (not CUSTOM) without touching the mode or {@link #pathPreset}. */
    private static void writePathValues(PathPreset p) {
        if (p == PathPreset.CUSTOM) {
            return;
        }
        pathSpp = p.spp;
        pathBounces = p.bounces;
        pathDistance = p.distance;
        caustics = p.caustics;
        pathAccumulate = p.accumulate;
        pathAutoExposure = p.autoExposure;
        pathDenoiser = p.denoiser;
    }

    /**
     * Defaults of the alpha release (no preset is offered to the player): the values the owner checked as working ("custom"):
     * 2 spp, 4 bounces, 384 blocks, caustics on, accumulation on, auto exposure off, DLSS Ray Reconstruction (falls back to NRD or
     * accumulation when the library is missing, logged). The Fast / Quality presets stay in the code for autotests and later.
     */
    public static void writeAlphaPathDefaults() {
        pathSpp = 2;
        pathBounces = 4;
        pathDistance = 384;
        caustics = true;
        pathAccumulate = true;
        pathAutoExposure = false;
        pathDenoiser = PathDenoiser.DLSS_RR;
    }

    /** Path keys that a saved Fast / Quality preset produced: ignored when the file carries such a preset (see {@link #skipPresetValues}). */
    private static final java.util.Set<String> PRESET_PATH_KEYS = java.util.Set.of("path_spp", "path_bounces", "path_distance",
        "path_caustics", "caustics", "path_accumulate", "path_auto_exposure", "path_denoiser");
    /** Set while a config file with a saved PT preset (FAST / QUALITY) is read: its preset-made values are dropped (defaults apply). */
    static boolean skipPresetValues;

    /** Applies a Path Tracing preset (the settings page "PT Quality"). CUSTOM only stores the choice. The mode stays. */
    public static void applyPathPreset(PathPreset p) {
        pathPreset = p;
        writePathValues(p);
    }

    /** Effective path values as the renderer reads them (session overrides included), for the log. */
    public static String pathSummary() {
        return String.format(Locale.ROOT, "pt_preset=%s spp=%d bounces=%d distance=%d denoiser=%s caustics=%s accumulate=%s auto_exposure=%s",
            pathPreset, spp(), bounces(), pathMaxDistance(), denoiser(), caustics(), accumulate(), autoExposure());
    }

    /** Called by the page when the user changes one path value: the path preset becomes CUSTOM. */
    public static void markPathCustom() {
        pathPreset = PathPreset.CUSTOM;
    }

    /** Ray Tracing presets offered by the page (the path modes have their own {@link PathPreset}). */
    public static final java.util.Set<Preset> RAY_PRESETS = java.util.EnumSet.of(Preset.LOW, Preset.MEDIUM, Preset.HIGH, Preset.ULTRA,
        Preset.CUSTOM);

    /**
     * Applies a preset. LOW..ULTRA write the Ray Tracing values (the mode is not changed: the "Tracing" option decides); OFF
     * also switches tracing off (older config files, autotests). FAST / QUALITY / PATH_TRACING (older files and autotests)
     * switch to Path Tracing and apply the matching {@link PathPreset}; {@link #preset} stays as it was. CUSTOM only stores the
     * preset. The LOW..ULTRA values are written below.
     */
    public static void applyPreset(Preset p) {
        if (p == Preset.FAST || p == Preset.QUALITY || p == Preset.PATH_TRACING) {
            VulkanRtxConfig.setRtMode(RtMode.PATH_TRACED);
            applyPathPreset(p == Preset.FAST ? PathPreset.FAST : PathPreset.QUALITY);
            if (p == Preset.PATH_TRACING) {
                waterStyle = WATER_STYLE_REALISTIC;
            }
            return;
        }
        preset = p;
        if (p == Preset.CUSTOM) {
            return;
        }
        if (p == Preset.OFF) {
            VulkanRtxConfig.setRtMode(p.mode());
        }
        // common base (LOW): sun/moon as a disk (soft shadows), accumulation smooths the single shadow ray
        raysShadows = true;
        raysSoftShadows = true;
        raysPixelShadows = false;
        raysPixelShadowSize = 1;
        raysReflections = false;
        raysReflectionDistance = 64;
        // rays = fast RT: sun shadows, contact AO (one short cosine ray per pixel, accumulated) and local-light shadows
        raysAo = true;
        raysAoRadius = 3;
        raysEmissive = true;
        raysEmissiveQuality = 1;
        raysEmissiveDistance = 24;
        raysGi = false;
        raysGiDistance = 48;
        raysAccumulate = true;
        volumetricFogEnabled = true;
        fogDensity = 1.0f;
        fogInScattering = 1.0f;
        fogDistanceScale = 1.0f;
        rtFog = true;
        rtFogDensity = 100;
        rtFogInScattering = 100;
        waterStyle = WATER_STYLE_VANILLA;
        switch (p) {
            case MEDIUM -> {
                waterStyle = WATER_STYLE_REALISTIC;
                raysSoftShadows = true;
                raysReflections = true;
                raysAo = true;
                raysEmissiveDistance = 32;
                // one traced bounce: the cheapest light leak that removes the flat "ambient only" look indoors
                raysGi = true;
                raysGiDistance = 48;
            }
            case HIGH -> {
                waterStyle = WATER_STYLE_REALISTIC;
                raysSoftShadows = true;
                raysReflections = true;
                raysReflectionDistance = 128;
                raysAo = true;
                raysEmissiveQuality = 2;
                raysEmissiveDistance = 32;
                raysGi = true;
                raysGiDistance = 64;
            }
            case ULTRA -> {
                waterStyle = WATER_STYLE_REALISTIC;
                raysSoftShadows = true;
                raysReflections = true;
                raysReflectionDistance = 256;
                raysAo = true;
                raysEmissiveQuality = 4;
                raysEmissiveDistance = 48;
                raysGi = true;
                raysGiDistance = 128;
            }
            default -> {
                // OFF, LOW: the base values
            }
        }
    }

    /** Levels of the settings page for "Reset". */
    public enum Level { BASIC, ADVANCED, EXPERT, ALL }

    /** Boolean keys shown in the Advanced level (all integer keys belong to Expert). */
    public static final java.util.Set<String> ADVANCED_KEYS = java.util.Set.of("rays_shadows", "rays_soft_shadows", "rays_pixel_shadows", "rays_reflections",
        "rays_ao", "rays_gi", "rays_emissive", "rays_accumulate", "rt_distance_auto", "auto_normals", "path_accumulate",
        "path_auto_exposure", "dlss_rr_geometric_normals", "rt_fog", "volumetric_fog_enabled", "path_caustics", "caustics", "vibrant_visuals",
        "waving_foliage");

    /**
     * Resets one level of the settings page to the values of the current presets (Ray Tracing: MEDIUM when Custom; Path
     * Tracing: FAST when Custom): BASIC = tracing off, both presets back to their defaults, advanced/expert sections collapsed;
     * ADVANCED = the switches only (incl. the path denoiser); EXPERT = the numbers only; ALL = every value from the presets
     * (the mode is kept). Returns what was done, for the log.
     */
    public static String resetLevel(Level level) {
        Preset target = RAY_PRESETS.contains(preset) && preset != Preset.CUSTOM ? preset : Preset.MEDIUM;
        PathPreset pathTarget = pathPreset == PathPreset.FAST || pathPreset == PathPreset.QUALITY ? pathPreset : PathPreset.CUSTOM;
        if (level == Level.BASIC) {
            VulkanRtxConfig.setRtMode(RtMode.OFF);
            applyPreset(Preset.MEDIUM);
            writeAlphaPathDefaults();
            pathPreset = PathPreset.CUSTOM;
            showAdvanced = false;
            showExpert = false;
            return "mode=off alpha_defaults";
        }
        Map<String, Integer> ints = new LinkedHashMap<>();
        Map<String, Boolean> bools = new LinkedHashMap<>();
        INTS.forEach((k, v) -> ints.put(k, v.get().getAsInt()));
        BOOLS.forEach((k, v) -> bools.put(k, v.get().getAsBoolean()));
        PathDenoiser den = pathDenoiser;
        boolean adv = showAdvanced;
        boolean exp = showExpert;
        applyPreset(target);
        if (pathTarget == PathPreset.CUSTOM) {
            writeAlphaPathDefaults();
        } else {
            writePathValues(pathTarget);
        }
        if (level != Level.ALL) {
            // restore the keys of the other level
            ints.forEach((k, v) -> {
                if (level != Level.EXPERT) {
                    INTS.get(k).set().accept(v);
                }
            });
            bools.forEach((k, v) -> {
                if (level != Level.ADVANCED || !ADVANCED_KEYS.contains(k)) {
                    BOOLS.get(k).set().accept(v);
                }
            });
            if (level != Level.ADVANCED) {
                pathDenoiser = den;
            }
            preset = Preset.CUSTOM;
            pathPreset = PathPreset.CUSTOM;
        } else {
            pathPreset = pathTarget;
        }
        showAdvanced = adv;
        showExpert = exp;
        return "from_preset=" + target + " from_pt_preset=" + pathTarget + " preset_now=" + preset + " pt_preset_now=" + pathPreset;
    }

    /** Called by the settings page when the user changes one value: the preset becomes CUSTOM (the mode stays as it is). */
    public static void markCustom() {
        preset = Preset.CUSTOM;
    }

    /**
     * Applies one toml pair. Returns false for a key that is not an RT setting. Invalid or out-of-range values are logged and
     * the previous value is kept.
     */
    public static boolean applyKeyValue(String key, String val) {
        IntSetting is = INTS.get(key);
        if (is != null) {
            try {
                int v = Integer.parseInt(val.trim());
                if (v < is.min() || v > is.max()) {
                    LOGGER.warn("{} {} is outside {}..{}, keeping {}", key, v, is.min(), is.max(), is.get().getAsInt());
                } else {
                    is.set().accept(v);
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid {} '{}', keeping {}", key, val, is.get().getAsInt());
            }
            return true;
        }
        BoolSetting bs = BOOLS.get(key);
        if (bs != null) {
            if (val.equalsIgnoreCase("true") || val.equalsIgnoreCase("false")) {
                bs.set().accept(Boolean.parseBoolean(val));
            } else {
                LOGGER.warn("Invalid {} '{}' (true|false expected), keeping {}", key, val, bs.get().getAsBoolean());
            }
            return true;
        }
        switch (key) {
            case "path_denoiser" -> {
                String clean = val.trim().toUpperCase(Locale.ROOT).replace('-', '_');
                if (clean.equals("RR") || clean.equals("DLSS_D") || clean.equals("DLSS-RR")) {
                    clean = "DLSS_RR";
                }
                try {
                    pathDenoiser = PathDenoiser.valueOf(clean);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid path_denoiser '{}' (use off|nrd|dlss_rr), keeping {}", val, pathDenoiser);
                }
                return true;
            }
            case "adaptive_quality" -> {
                if (ADAPTIVE_OVR != null) {
                    LOGGER.info("[VKB-RT] adaptive_quality from the config ({}) ignored: -Dvkrtx.rt.adaptive_quality={} pins it",
                        val, ADAPTIVE_OVR);
                    return true;
                }
                try {
                    adaptiveQuality = AdaptiveQuality.valueOf(val.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid adaptive_quality '{}' (use OFF|FPS30|FPS45|FPS60|FPS90|FPS120), keeping {}", val, adaptiveQuality);
                }
                return true;
            }
            case "rt_preset" -> {
                presetSeen = true;
                try {
                    Preset p = Preset.valueOf(val.trim().toUpperCase(Locale.ROOT));
                    if (p == Preset.FAST || p == Preset.QUALITY || p == Preset.PATH_TRACING) {
                        preset = Preset.CUSTOM;
                        if (!pathPresetSeen) {
                            pathPreset = p == Preset.FAST ? PathPreset.FAST : PathPreset.QUALITY;
                            pathPresetSeen = true;
                        }
                    } else {
                        preset = p;
                    }
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid rt_preset '{}', keeping {}", val, preset);
                }
                return true;
            }
            case "pt_preset" -> {
                pathPresetSeen = true;
                try {
                    PathPreset saved = PathPreset.valueOf(val.trim().toUpperCase(Locale.ROOT));
                    pathPreset = saved;
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid pt_preset '{}' (use FAST|QUALITY|CUSTOM), keeping {}", val, pathPreset);
                }
                return true;
            }
            case "texture_lod_bias" -> {
                try {
                    TextureLodBias.manualTextureLodBias = Float.parseFloat(val);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid texture_lod_bias '{}', keeping {}", val, TextureLodBias.manualTextureLodBias);
                }
                return true;
            }
            case "dlss_nr_transfer_strength" -> {
                try {
                    dlssNrTransferStrength = clampFloat(Float.parseFloat(val.trim()), 0.0f, 2.0f);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid dlss_nr_transfer_strength '{}', keeping {}", val, dlssNrTransferStrength);
                }
                return true;
            }
            case "dlss_nr_colour_strength" -> {
                try {
                    dlssNrColourStrength = clampFloat(Float.parseFloat(val.trim()), 0.0f, 1.0f);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid dlss_nr_colour_strength '{}', keeping {}", val, dlssNrColourStrength);
                }
                return true;
            }
            case "dlss_nr_max_ratio" -> {
                try {
                    dlssNrMaxRatio = clampFloat(Float.parseFloat(val.trim()), 1.0f, 10.0f);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid dlss_nr_max_ratio '{}', keeping {}", val, dlssNrMaxRatio);
                }
                return true;
            }
            case "dlss_nr_working_scale" -> {
                try {
                    dlssNrWorkingScale = clampFloat(Float.parseFloat(val.trim()), 0.5f, 1.0f);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid dlss_nr_working_scale '{}', keeping {}", val, dlssNrWorkingScale);
                }
                return true;
            }
            case "volumetric_fog_enabled" -> {
                volumetricFogEnabled = Boolean.parseBoolean(val.trim());
                rtFog = volumetricFogEnabled;
                return true;
            }
            case "fog_density" -> {
                try {
                    fogDensity = clampFloat(Float.parseFloat(val.trim()), 0.0f, 2.0f);
                    rtFogDensity = Math.round(fogDensity * 100f);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid fog_density '{}', keeping {}", val, fogDensity);
                }
                return true;
            }
            case "fog_in_scattering" -> {
                try {
                    fogInScattering = clampFloat(Float.parseFloat(val.trim()), 0.0f, 2.0f);
                    rtFogInScattering = Math.round(fogInScattering * 100f);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid fog_in_scattering '{}', keeping {}", val, fogInScattering);
                }
                return true;
            }
            case "fog_distance_scale" -> {
                try {
                    fogDistanceScale = clampFloat(Float.parseFloat(val.trim()), 0.2f, 2.0f);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid fog_distance_scale '{}', keeping {}", val, fogDistanceScale);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Writes the preset and the {@code [ray_tracing]} values, then the {@code [path_tracing]} section (caller is inside [ray_tracing]). */
    public static void write(Writer w) throws IOException {
        w.write("# Ray Tracing quality preset (LOW, MEDIUM, HIGH, ULTRA or CUSTOM)\n");
        w.write("rt_preset = \"" + (preset != null ? preset.name() : "CUSTOM") + "\"\n");
        w.write("# --- Ray Tracing mode (rt_mode = \"rays\"): each effect has a switch, a quality (rays per pixel) and a distance (blocks)\n");
        w.write("# normal maps generated from the albedo for textures without LabPBR _n (strength in percent, 0..400)\n");
        w.write("auto_normals = " + autoNormals + "\n");
        w.write("# RT distance: as in game (render distance) or manual chunks; BLAS memory budget (MB); sun disk diameter (0.1 deg)\n");
        w.write("rt_distance_auto = " + rtDistanceAuto + "\n");
        w.write("rt_distance_chunks = " + rtDistanceChunks + "\n");
        w.write("rt_vram_budget_mb = " + vramBudgetMb + "\n");
        w.write("sun_size_tenths = " + sunSizeTenths + "\n");
        w.write("# tilt of the sun/moon arc towards the south in degrees (0 = vanilla: the sun passes the zenith, no noon shadows)\n");
        w.write("sun_tilt_deg = " + sunTiltDeg + "\n");
        w.write("# Baked vanilla light fill, 0..100 percent, rays mode only (0 = off: sky light through rays, block light through emitters)\n");
        w.write("baked_light_fill_pct = " + bakedLightFillPct + "\n");
        w.write("rt_light_budget = " + lightBudget + "\n");
        w.write("rt_show_advanced = " + showAdvanced + "\n");
        w.write("rt_show_expert = " + showExpert + "\n");
        w.write("auto_normal_strength = " + autoNormalStrength + "\n");
        w.write("# Volumetric / Distance Fog\n");
        w.write("volumetric_fog_enabled = " + volumetricFogEnabled + "\n");
        w.write("fog_density = " + fogDensity + "\n");
        w.write("fog_in_scattering = " + fogInScattering + "\n");
        w.write("fog_distance_scale = " + fogDistanceScale + "\n");
        w.write("rt_fog = " + rtFog + "\n");
        w.write("rt_fog_density = " + rtFogDensity + "\n");
        w.write("rt_fog_in_scattering = " + rtFogInScattering + "\n");
        w.write("# Water style: 0 = Vanilla, 1 = Realistic\n");
        w.write("water_style = " + waterStyle + "\n");
        w.write("waving_foliage = " + wavingFoliage + "\n");
        w.write("waving_foliage_strength = " + wavingFoliageStrength + "\n");
        w.write("# Vibrant Visuals (Mojang vibrant palette)\n");
        w.write("vibrant_visuals = " + vibrantVisuals + "\n");
        for (Map.Entry<String, BoolSetting> e : BOOLS.entrySet()) {
            if (e.getKey().startsWith("rays_")) {
                w.write(e.getKey() + " = " + e.getValue().get().getAsBoolean() + "\n");
            }
        }
        for (Map.Entry<String, IntSetting> e : INTS.entrySet()) {
            if (e.getKey().startsWith("rays_")) {
                w.write("# " + e.getValue().min() + ".." + e.getValue().max() + "\n");
                w.write(e.getKey() + " = " + e.getValue().get().getAsInt() + "\n");
            }
        }
        w.write("\n[path_tracing]\n");
        w.write("# Path Tracing mode (rt_mode = \"path\"): full light transport\n");
        w.write("# PT quality: FAST, QUALITY or CUSTOM\n");
        w.write("pt_preset = \"" + (pathPreset != null ? pathPreset.name() : "CUSTOM") + "\"\n");
        for (Map.Entry<String, IntSetting> e : INTS.entrySet()) {
            if (e.getKey().startsWith("path_")) {
                w.write("# " + e.getValue().min() + ".." + e.getValue().max() + "\n");
                w.write(e.getKey() + " = " + e.getValue().get().getAsInt() + "\n");
            }
        }
        for (Map.Entry<String, BoolSetting> e : BOOLS.entrySet()) {
            if (e.getKey().startsWith("path_")) {
                w.write(e.getKey() + " = " + e.getValue().get().getAsBoolean() + "\n");
            }
        }
        w.write("# OFF, NRD or DLSS_RR (DLSS_RR uses NVIDIA DLSS Ray Reconstruction)\n");
        w.write("path_denoiser = \"" + pathDenoiser.name() + "\"\n");
        w.write("# Adaptive quality: OFF, FPS30, FPS45, FPS60, FPS90 or FPS120 - the RT pass lowers rays per pixel, then bounces,\n");
        w.write("# below the values above to fit 70% of a frame of that rate (never above them)\n");
        w.write("adaptive_quality = \"" + (adaptiveQuality != null ? adaptiveQuality.name() : "OFF") + "\"\n");
        w.write("# DLSS Ray Reconstruction: flat voxel geometric normals (ng) for sharp block edges (true), or smooth/perturbed (false)\n");
        w.write("dlss_rr_geometric_normals = " + dlssRrGeometricNormals + "\n");
        w.write("\n[dlss_neural_rendering]\n");
        w.write("# DLSS 5 Neural Rendering (DLSS-NR) [EXPERIMENTAL]\n");
        w.write("dlss_nr_enabled = " + dlssNrEnabled + "\n");
        w.write("dlss_nr_transfer_strength = " + dlssNrTransferStrength + "\n");
        w.write("dlss_nr_colour_strength = " + dlssNrColourStrength + "\n");
        w.write("dlss_nr_max_ratio = " + dlssNrMaxRatio + "\n");
        w.write("dlss_nr_working_scale = " + dlssNrWorkingScale + "\n");
        w.write("dlss_nr_debug_view = " + dlssNrDebugView + "\n");
    }
}
