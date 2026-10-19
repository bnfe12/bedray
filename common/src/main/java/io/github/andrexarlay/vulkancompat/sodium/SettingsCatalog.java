package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Single description of what the Bedray settings screen tells the user about an option (docs/SETTINGS_AUDIT.md has the
 * decision table). Used by the Sodium page ({@link VulkanSodiumConfig}: tier, tooltip), by the right-hand panel
 * ({@link PreviewPanel}: description, cost, requirement, screenshots) and by the {@code settings_preview} autotest, which
 * takes one in-game screenshot per {@link Preview} (file {@code assets/bedray/textures/gui/preview/<previewBase>_<valueKey>.png}).
 * <p>
 * An option without {@link #previews()} shows an honest placeholder (the reason is {@link #noImage()}); nothing is drawn or
 * simulated. {@code previewBase} lets related options (strength sliders of a toggle) share the screenshots of the switch.
 */
public final class SettingsCatalog {
    private SettingsCatalog() {
    }

    /** ESSENTIAL: first page. ADVANCED: second page, collapsible groups. EXPERT: only with {@code -Dvkrtx.ui.expert=true} / toml {@code ui_expert}. */
    public enum Tier { ESSENTIAL, ADVANCED, EXPERT }

    /** Scene of the autotest world used for the screenshots of an option (see {@code SettingsPreviewScenario}). */
    public enum Scene { DAY, SUNSET, FOREST, WATER, INTERIOR, LAVA }

    /** One screenshot: file key, label shown on the picture and the action that puts the game into that state. */
    public record Preview(String valueKey, String label, Runnable apply) {
    }

    /**
     * @param path        option id path (namespace bedray)
     * @param tier        where the option lives
     * @param name        short English name (the lang files translate it)
     * @param desc        what changes, 1-2 sentences
     * @param cost        FPS / memory price in one line
     * @param requires    requirement or status, "" when none
     * @param scene       scene of the screenshots
     * @param cropU       centre of the zoomed crop (0..1)
     * @param cropV       centre of the zoomed crop (0..1)
     * @param previewBase prefix of the screenshot file names
     * @param previews    ordered values that have a screenshot (empty = none)
     * @param noImage     why there is no screenshot (shown instead of the picture)
     * @param keyOf       maps the live option value to a {@link Preview#valueKey()}; null = default mapping
     */
    public record Info(String path, Tier tier, String name, String desc, String cost, String requires, Scene scene,
                       float cropU, float cropV, String previewBase, List<Preview> previews, String noImage,
                       Function<Object, String> keyOf) {
        public boolean hasPreviews() {
            return !previews.isEmpty();
        }
    }

    private static final Map<String, Info> INFOS = new LinkedHashMap<>();

    public static Info get(String path) {
        return INFOS.get(path);
    }

    public static Map<String, Info> all() {
        return INFOS;
    }

    /** Distinct preview sets (one per previewBase): what the {@code settings_preview} scenario captures. */
    public static List<Info> captureList() {
        List<Info> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Info i : INFOS.values()) {
            if (i.hasPreviews() && seen.add(i.previewBase())) {
                out.add(i);
            }
        }
        return out;
    }

    /** True with {@code -Dvkrtx.ui.expert=true} or {@code ui_expert = true} in bedray.toml: shows the Expert and Debug groups. */
    public static boolean expertUi() {
        return Boolean.getBoolean("vkrtx.ui.expert") || VulkanRtxConfig.uiExpert;
    }

    // ---------------------------------------------------------------- helpers

    private static Preview p(String key, String label, Runnable apply) {
        return new Preview(key, label, apply);
    }

    private static Preview onOff(boolean on, Runnable apply) {
        return new Preview(on ? "on" : "off", on ? "On" : "Off", apply);
    }

    private static void put(String path, Tier tier, String name, String desc, String cost, String requires) {
        INFOS.put(path, new Info(path, tier, name, desc, cost, requires, Scene.DAY, 0.5f, 0.5f, path, List.of(),
            "No screenshot: this setting is shown by the numbers in its description.", null));
    }

    private static void put(String path, Tier tier, String name, String desc, String cost, String requires, Scene scene,
                            float u, float v, String base, List<Preview> previews, Function<Object, String> keyOf) {
        INFOS.put(path, new Info(path, tier, name, desc, cost, requires, scene, u, v, base, previews, "", keyOf));
    }

    private static void putNoImage(String path, Tier tier, String name, String desc, String cost, String requires, String why) {
        INFOS.put(path, new Info(path, tier, name, desc, cost, requires, Scene.DAY, 0.5f, 0.5f, path, List.of(), why, null));
    }

    private static void setPathMode() {
        VulkanRtxConfig.rtMode = RtMode.PATH_TRACED;
        VulkanRtxConfig.enableHardwareRayTracing = true;
    }

    private static void upscale(UpscalerBackend b, UpscalerMode m) {
        VulkanRtxConfig.applyUpscaler(b, b == UpscalerBackend.XESS ? m : null, b == UpscalerBackend.DLSS ? m : null,
            b == UpscalerBackend.FSR ? m : null, null, "settings_preview");
        VulkanRtxManager.applyResolutionScalingNow();
    }

    private static UpscalerMode nativeMode(UpscalerBackend b) {
        return switch (b) {
            case DLSS -> UpscalerMode.DLAA;
            case FSR -> UpscalerMode.NATIVE_AA;
            case XESS -> UpscalerMode.ULTRA_QUALITY_PLUS;
            default -> null;
        };
    }

    /** Backend used for the shared quality-mode screenshots (set by the scenario to the first available one). */
    public static volatile UpscalerBackend previewUpscaler = UpscalerBackend.DLSS;

    /** Deterministic state before each scene: Path Tracing, profile Fast, NRD, native bilinear, default look. Session only, nothing saved. */
    public static void previewBaseline() {
        setPathMode();
        RtSettings.writeAlphaPathDefaults();
        RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD;   // NRD for the captures: DLSS-RR needs files a reader may not have
        RtSettings.caustics = false;
        RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
        RtSettings.autoNormals = true;
        RtSettings.rtFog = true;
        RtSettings.volumetricFogEnabled = true;
        RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.GodRays.POST.ordinal());
        RtAtmosphereSettings.set("godrays_strength", 1.0);
        RtAtmosphereSettings.set("bloom", 1);
        RtAtmosphereSettings.set("bloom_strength", 0.25);
        RtAtmosphereSettings.set("sunglare", 1);
        RtAtmosphereSettings.set("fog_aerial", 1.0);
        upscale(UpscalerBackend.BILINEAR, null);
        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
        VulkanRtxConfig.syncUpscalerTypeWithPreset();
        VulkanRtxManager.applyResolutionScalingNow();
    }

    // ---------------------------------------------------------------- the table

    static {
        // ===================== ESSENTIAL (first page, 8 concepts) =====================
        put("rt_mode", Tier.ESSENTIAL, "Ray Tracing",
            "Off draws the classic game. Path Tracing replaces its lighting with traced light: real shadows, reflections, bounced light and glowing blocks.",
            "FPS: Path Tracing is the most expensive setting (use an upscaler). VRAM: +0.2-0.5 GB.",
            "Needs a GPU with VK_KHR_ray_query. Experimental.", Scene.DAY, 0.62f, 0.55f, "rt_mode", List.of(
                p("off", "Off", () -> {
                    VulkanRtxConfig.rtMode = RtMode.OFF;
                    VulkanRtxConfig.enableHardwareRayTracing = false;
                }),
                p("path", "Path Tracing", SettingsCatalog::setPathMode)),
            v -> v instanceof RtMode m ? (m == RtMode.OFF ? "off" : "path") : String.valueOf(v));
        put("path_bounces", Tier.ESSENTIAL, "Light Quality (bounces)",
            "How many times light may bounce. More bounces light dark rooms and tint walls with the colour of their neighbours.",
            "FPS: about -10% per extra bounce. VRAM: none.", "Path Tracing only.", Scene.INTERIOR, 0.5f, 0.55f, "path_bounces", List.of(
                p("1", "1 bounce", () -> RtSettings.pathBounces = 1),
                p("3", "3 bounces", () -> RtSettings.pathBounces = 3),
                p("6", "6 bounces", () -> RtSettings.pathBounces = 6)), null);
        put("path_auto_exposure", Tier.ESSENTIAL, "Auto Exposure",
            "The picture brightens in the dark and dims in bright light, like an eye. Off keeps one fixed brightness.",
            "FPS: none.", "Path Tracing only. Known issue: it can pulse or jump when the scene changes; turn it off if the brightness bothers you.",
            Scene.INTERIOR, 0.5f, 0.5f, "path_auto_exposure", List.of(
                onOff(false, () -> RtSettings.pathAutoExposure = false),
                onOff(true, () -> RtSettings.pathAutoExposure = true)), null);
        put("water_style", Tier.ESSENTIAL, "Water Style",
            "Vanilla keeps the classic clear animated water. Realistic adds waves, refraction and reflections.",
            "FPS: low. VRAM: none.", "", Scene.WATER, 0.5f, 0.5f, "water_style", List.of(
                p("vanilla", "Vanilla", () -> RtSettings.waterStyle = RtSettings.WATER_STYLE_VANILLA),
                p("realistic", "Realistic", () -> RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC)),
            v -> v instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : String.valueOf(v));
        put("atm_godrays", Tier.ESSENTIAL, "Light Shafts",
            "Visible beams of sun and moon light through leaves, windows and cave openings; strongest at sunrise and sunset.",
            "FPS: low-medium. VRAM: none.", "Needs ray tracing on.", Scene.SUNSET, 0.5f, 0.45f, "godrays", List.of(
                p("off", "Off", () -> RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.GodRays.OFF.ordinal())),
                p("on", "On", () -> RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.GodRays.POST.ordinal()))),
            v -> v instanceof RtAtmosphereSettings.GodRays g ? (g == RtAtmosphereSettings.GodRays.OFF ? "off" : "on") : String.valueOf(v));
        put("upscaler_backend", Tier.ESSENTIAL, "Upscaler",
            "Draws the 3D world smaller and rebuilds the full picture, so you get more FPS. DLSS, FSR and XeSS rebuild detail; Built-in only stretches.",
            "FPS: +25-60% at Quality. VRAM: +30-80 MB.", "DLSS needs an NVIDIA RTX GPU and its files; FSR and XeSS need their libraries (see docs/INSTALL.md).",
            Scene.DAY, 0.55f, 0.6f, "upscaler_backend", List.of(
                p("bilinear", "Built-in (native)", () -> upscale(UpscalerBackend.BILINEAR, null)),
                p("dlss", "DLSS Quality", () -> upscale(UpscalerBackend.DLSS, UpscalerMode.QUALITY)),
                p("fsr", "FSR Quality", () -> upscale(UpscalerBackend.FSR, UpscalerMode.QUALITY)),
                p("xess", "XeSS Quality", () -> upscale(UpscalerBackend.XESS, UpscalerMode.QUALITY))),
            v -> v instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : String.valueOf(v));
        List<Preview> modes = List.of(
            p("native", "Native (100%)", () -> upscale(previewUpscaler, nativeMode(previewUpscaler))),
            p("quality", "Quality", () -> upscale(previewUpscaler, UpscalerMode.QUALITY)),
            p("balanced", "Balanced", () -> upscale(previewUpscaler, UpscalerMode.BALANCED)),
            p("performance", "Performance", () -> upscale(previewUpscaler, UpscalerMode.PERFORMANCE)),
            p("ultra_performance", "Ultra Performance", () -> upscale(previewUpscaler, UpscalerMode.ULTRA_PERFORMANCE)));
        Function<Object, String> modeKey = v -> {
            if (v instanceof UpscalerMode m) {
                return switch (m) {
                    case QUALITY, ULTRA_QUALITY, ULTRA_QUALITY_PLUS -> "quality";
                    case BALANCED -> "balanced";
                    case PERFORMANCE -> "performance";
                    case ULTRA_PERFORMANCE -> "ultra_performance";
                    default -> "native";
                };
            }
            if (v instanceof VulkanRtxConfig.QualityPreset q) {
                return switch (q) {
                    case QUALITY -> "quality";
                    case BALANCED -> "balanced";
                    case PERFORMANCE -> "performance";
                    case ULTRA_PERFORMANCE -> "ultra_performance";
                    default -> "native";
                };
            }
            return "native";
        };
        for (String id : new String[] {"dlss_mode", "xess_mode", "fsr_mode", "quality_preset"}) {
            put(id, Tier.ESSENTIAL, id.equals("quality_preset") ? "3D Scale" : "Upscaler Quality",
                "How small the 3D world is drawn before it is rebuilt to full size. Quality is nearly as sharp as native; Ultra Performance is soft but fastest.",
                "FPS: Quality +30%, Balanced +45%, Performance +70-100%. VRAM: less at low scales.",
                "The screenshots were taken with the first available upscaler (see the caption).", Scene.DAY, 0.55f, 0.6f, "upscaler_mode", modes, modeKey);
        }
        INFOS.put("frame_generation_provider", new Info("frame_generation_provider", Tier.ESSENTIAL, "Frame Generation Method",
            "Auto uses NVIDIA DLSS Frame Generation (Streamline) when the NVIDIA driver and Streamline files are installed, otherwise AMD FSR 3 Frame Generation (works on any GPU with amd_fidelityfx_vk.dll, 2x mode).",
            "FPS: same as Frame Generation.",
            "Both methods hook the Vulkan swapchain at game startup, so changing the method requires a game restart.", Scene.DAY, 0.5f, 0.5f,
            "frame_generation_mode", List.of(), "A still image cannot show frame generation: it changes motion smoothness, not how a single frame looks.", null));
        INFOS.put("frame_generation_mode", new Info("frame_generation_mode", Tier.ESSENTIAL, "Frame Generation",
            "Inserts generated frames between rendered ones (2x with AMD FSR 3 or NVIDIA DLSS-G, 3x/4x Multi Frame Generation on RTX 50): smoother motion and higher displayed FPS.",
            "Displayed FPS x2-x4, rendered FPS lower. VRAM: +100-300 MB.",
            "Needs AMD FSR 3 (amd_fidelityfx_vk.dll, any GPU) or NVIDIA DLSS-G (RTX 40/50 + Streamline files) and a temporal upscaler (FSR, DLSS or XeSS).", Scene.DAY, 0.5f, 0.5f,
            "frame_generation_mode", List.of(), "A still image cannot show frame generation: it changes how smooth motion is, not how a frame looks. Watch the F3 screen for the FPS.", null));

        // ===================== ADVANCED =====================
        put("pt_preset", Tier.ADVANCED, "Path Tracing Profile",
            "Fast and Quality set rays per pixel, bounces and distance in one step. Custom appears when you change one of those values.",
            "FPS: Quality is about 2x the frame time of Fast.", "Path Tracing only.", Scene.INTERIOR, 0.5f, 0.55f, "pt_preset", List.of(
                p("fast", "Fast", () -> RtSettings.applyPathPreset(RtSettings.PathPreset.FAST)),
                p("quality", "Quality", () -> RtSettings.applyPathPreset(RtSettings.PathPreset.QUALITY))),
            v -> v instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : String.valueOf(v));
        put("path_spp", Tier.ADVANCED, "Rays per Pixel",
            "How many light paths are traced for each pixel every frame. More rays means less grain before the denoiser.",
            "FPS: linear - 2 rays cost about twice as much as 1.", "Path Tracing only.", Scene.INTERIOR, 0.5f, 0.55f, "path_spp", List.of(
                p("1", "1 ray", () -> RtSettings.pathSpp = 1),
                p("2", "2 rays", () -> RtSettings.pathSpp = 2),
                p("4", "4 rays", () -> RtSettings.pathSpp = 4)), null);
        putNoImage("path_distance", Tier.ADVANCED, "Ray Distance",
            "How far a light path may travel in blocks. Longer reaches distant sky and hills; shorter is faster.",
            "FPS: medium at 384+ blocks. VRAM: none.", "Path Tracing only.",
            "The difference shows only in large open scenes (distant terrain lighting); a still of the test scene would not show it.");
        put("path_denoiser", Tier.ADVANCED, "Denoiser",
            "Removes the grain of traced light. NRD is the default; DLSS Ray Reconstruction replaces denoiser and upscaler (RTX only). Off leaves raw grain.",
            "FPS: NRD 1-2 ms, Ray Reconstruction a little more. VRAM: +100-200 MB.",
            "NRD needs its library, Ray Reconstruction needs an NVIDIA RTX GPU and the DLSS files; a missing one falls back to accumulation.",
            Scene.INTERIOR, 0.5f, 0.55f, "path_denoiser", List.of(
                p("off", "Off", () -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF),
                p("nrd", "NRD", () -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD),
                p("dlss_rr", "DLSS Ray Reconstruction", () -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.DLSS_RR)),
            v -> v instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : String.valueOf(v));
        put("path_caustics", Tier.ADVANCED, "Water Caustics",
            "Sunlight focused by the water surface into moving bright patterns on the ground under water.",
            "FPS: medium. VRAM: none.", "Path Tracing only.", Scene.WATER, 0.5f, 0.75f, "path_caustics", List.of(
                onOff(false, () -> RtSettings.caustics = false), onOff(true, () -> RtSettings.caustics = true)), null);
        putNoImage("dlss_rr_geometric_normals", Tier.ADVANCED, "Sharp Block Edges (Ray Reconstruction)",
            "Gives DLSS Ray Reconstruction the flat block-face directions, which keeps block edges crisp. Off feeds smoothed directions.",
            "FPS: none.", "Only with Path Tracing and DLSS Ray Reconstruction.",
            "Needs the DLSS Ray Reconstruction library; it is not available on every machine, so no screenshot is shipped.");
        putNoImage("path_exposure_ev", Tier.ADVANCED, "Exposure Compensation",
            "Manual brightness offset in EV (-4.0 to +4.0). Shifts Auto Exposure when it is on, or sets the fixed brightness when Auto Exposure is off.",
            "FPS: none.", "Needs ray tracing on.",
            "Shares the Auto Exposure behaviour; adjust live to match your monitor brightness.");
        putNoImage("rt_particles", Tier.ADVANCED, "Particles in RT",
            "Smoke, flames, sparks, bubbles, block-break and weather splash particles in the ray traced image, occluded by terrain and lit by the scene.",
            "FPS: low.", "Needs ray tracing on.",
            "Particles are animated and short-lived; a still preview does not represent them.");
        putNoImage("waving_foliage", Tier.ADVANCED, "Waving Foliage",
            "Classic shaderpack wind waving on grass, leaves and cutout foliage.",
            "FPS: none.", "Needs ray tracing on.",
            "Dynamic wind animation on foliage quads.");
        putNoImage("waving_foliage_strength", Tier.ADVANCED, "Wind Strength",
            "Strength of the waving foliage wind motion (0 to 200 percent).",
            "FPS: none.", "Waving Foliage on.",
            "Slider controlling wind amplitude.");
        INFOS.put("atm_godrays_strength", shared("atm_godrays_strength", "atm_godrays", Tier.ADVANCED, "Light Shaft Strength",
            "Brightness of the light beams.", "FPS: none.", "Needs Light Shafts on.", v -> asNumber(v) > 0 ? "on" : "off"));
        INFOS.put("atm_godrays_quality", noImg("atm_godrays_quality", Tier.ADVANCED, "Light Shaft Quality",
            "Steps used to draw each beam: Low 6, Medium 8, High 12, Ultra 16. More steps give smoother beams.", "FPS: low to medium.", "Needs Light Shafts on.",
            "The difference between quality steps is subtle (smoother edges) and is not shown by a single still."));
        put("atm_bloom", Tier.ADVANCED, "Bloom",
            "A soft glow around very bright lights and glowing blocks, like a camera lens.", "FPS: low.", "Needs ray tracing on.",
            Scene.LAVA, 0.5f, 0.55f, "bloom", List.of(
                p("off", "Off", () -> RtAtmosphereSettings.set("bloom", 0)),
                p("on", "On", () -> RtAtmosphereSettings.set("bloom", 1))), null);
        INFOS.put("atm_bloom_strength", shared("atm_bloom_strength", "atm_bloom", Tier.ADVANCED, "Bloom Strength", "How strong the glow is.",
            "FPS: none.", "Needs Bloom on.", v -> asNumber(v) > 0 ? "on" : "off"));
        put("atm_sunglare", Tier.ADVANCED, "Sun Glare",
            "A bright halo around the sun when you look at it; hidden behind blocks and clouds.", "FPS: none.", "Needs ray tracing on.",
            Scene.SUNSET, 0.2f, 0.45f, "sunglare", List.of(
                p("off", "Off", () -> RtAtmosphereSettings.set("sunglare", 0)),
                p("on", "On", () -> RtAtmosphereSettings.set("sunglare", 1))), null);
        put("atm_fog_aerial", Tier.ADVANCED, "Distance Haze",
            "Far terrain fades into the colour of the sky, denser in rain and valleys. 0 turns it off.", "FPS: low.", "Needs Fog on.",
            Scene.DAY, 0.5f, 0.35f, "fog_aerial", List.of(
                p("0", "None", () -> RtAtmosphereSettings.set("fog_aerial", 0.0)),
                p("1", "Default", () -> RtAtmosphereSettings.set("fog_aerial", 1.0)),
                p("2", "Strong", () -> RtAtmosphereSettings.set("fog_aerial", 2.0))), null);
        put("rt_fog", Tier.ADVANCED, "Fog",
            "Atmospheric depth and fog in the traced picture, with the sun scattering through it.", "FPS: low.", "Needs ray tracing on.",
            Scene.SUNSET, 0.5f, 0.45f, "rt_fog", List.of(
                onOff(false, () -> RtSettings.volumetricFogEnabled = RtSettings.rtFog = false),
                onOff(true, () -> RtSettings.volumetricFogEnabled = RtSettings.rtFog = true)), null);
        INFOS.put("rt_fog_density", shared("rt_fog_density", "rt_fog", Tier.ADVANCED, "Fog Density", "How thick the fog is (100% = default).",
            "FPS: none.", "Needs Fog on.", v -> asNumber(v) > 0 ? "on" : "off"));
        put("auto_normals", Tier.ADVANCED, "Surface Relief",
            "Builds bumps from block textures so light and shadow catch the grain of stone and planks. Resource packs with their own normal maps keep them.",
            "FPS: low. VRAM: none.", "Needs ray tracing on.", Scene.DAY, 0.3f, 0.8f, "auto_normals", List.of(
                onOff(false, () -> RtSettings.autoNormals = false), onOff(true, () -> RtSettings.autoNormals = true)), null);
        putNoImage("point_sampling_sharpness", Tier.ADVANCED, "Pixel-Sharp Textures",
            "Keeps textures as hard square pixels when you look closely, instead of smoothing them.", "FPS: none.",
            "Takes effect after reloading resource packs (F3+T).",
            "The setting only applies after a resource reload, so a live before/after screenshot is not possible.");
        put("fsr_sharpness", Tier.ADVANCED, "FSR Sharpening",
            "Extra sharpening after FSR (RCAS). 0 is off.", "FPS: very low.", "FSR only.", Scene.DAY, 0.55f, 0.6f, "fsr_sharp", List.of(
                p("0", "Off", () -> {
                    VulkanRtxConfig.fsrSharpness = 0f;
                    upscale(UpscalerBackend.FSR, UpscalerMode.QUALITY);
                }),
                p("50", "50%", () -> VulkanRtxConfig.fsrSharpness = 0.5f),
                p("100", "100%", () -> VulkanRtxConfig.fsrSharpness = 1.0f)), v -> {
                double d = asNumber(v);
                return d < 25 ? "0" : d < 75 ? "50" : "100";
            });
        putNoImage("custom_scale", Tier.ADVANCED, "Custom 3D Scale",
            "Exact render size for the built-in upscaler, 25% to 100%.", "FPS: proportional to pixel count.", "Built-in upscaler with 3D Scale set to Custom.",
            "It is the same effect as the 3D Scale presets; see the 3D Scale screenshots.");
        putNoImage("frame_generation_reflex", Tier.ADVANCED, "NVIDIA Reflex",
            "Lowers input delay. Boost also raises GPU clocks.", "FPS: none.", "NVIDIA GPU with Streamline.",
            "Reflex changes latency, not the look of a frame.");
        putNoImage("frame_generation_limit", Tier.ADVANCED, "Presented FPS Cap",
            "Caps the displayed frame rate (0 = no cap). Keeps frame pacing steady near the monitor refresh rate.", "FPS: caps it.", "Frame Generation active.",
            "A frame cap changes timing, not the look of a frame.");
        putNoImage("frame_generation_status", Tier.ADVANCED, "Frame Generation Status",
            "Read-only: whether frame generation is active, and why not when it is not.", "", "", "Status line, nothing to preview.");
        putNoImage("debug_overlay", Tier.ADVANCED, "F3 Bedray Info",
            "Shows the Bedray block on the F3 screen: backend, FPS, ray tracing and upscaler state.", "FPS: none.", "",
            "It only adds text to the F3 screen.");

        // ===================== EXPERT (visible only with -Dvkrtx.ui.expert=true) =====================
        for (String[] e : new String[][] {
            {"rays_shadows", "RT Shadows", "Traced sun and moon shadows (Ray Tracing Fast mode)."},
            {"rays_soft_shadows", "RT Soft Shadows", "Shadows soften with distance from the caster (Ray Tracing Fast mode)."},
            {"rays_reflections", "RT Reflections", "Traced reflections on water, glass, metals (Ray Tracing Fast mode)."},
            {"rays_ao", "RT Ambient Occlusion", "Traced darkening in corners (Ray Tracing Fast mode)."},
            {"rays_gi", "RT Global Illumination", "One traced bounce of indirect light (Ray Tracing Fast mode)."},
            {"rays_emissive", "RT Block Light", "Light and shadows from glowing blocks (Ray Tracing Fast mode)."},
            {"rays_accumulate", "RT Temporal Accumulation", "Averages frames to remove noise (Ray Tracing Fast mode)."}}) {
            putNoImage(e[0], Tier.EXPERT, e[1], e[2], "FPS: medium-high.", "Hidden mode: Ray Tracing (Fast) is in development.",
                "The Ray Tracing (Fast) mode is not public yet, so it has no screenshots.");
        }
        for (String[] e : new String[][] {
            {"rays_shadow_quality", "RT Shadow Rays", "Shadow rays per pixel."},
            {"rays_reflection_quality", "RT Reflection Rays", "Reflection rays per pixel."},
            {"rays_reflection_distance", "RT Reflection Distance", "Longest reflection ray in blocks."},
            {"rays_ao_quality", "RT AO Rays", "Occlusion rays per pixel."},
            {"rays_ao_radius", "RT AO Radius", "Occluders farther than this do not darken."},
            {"rays_emissive_quality", "RT Block Light Samples", "Light samples per pixel."},
            {"rays_emissive_distance", "RT Block Light Distance", "Glowing blocks farther than this do not light a surface."},
            {"rays_gi_quality", "RT GI Rays", "Bounce rays per pixel."},
            {"rays_gi_distance", "RT GI Distance", "Longest bounce ray in blocks."}}) {
            putNoImage(e[0], Tier.EXPERT, e[1], e[2], "FPS: grows with the number.", "Hidden mode: Ray Tracing (Fast) is in development.",
                "The Ray Tracing (Fast) mode is not public yet, so it has no screenshots.");
        }
        putNoImage("rt_distance_auto", Tier.EXPERT, "RT Distance: As in Game", "On: ray tracing covers the game render distance. Off: the manual distance.",
            "FPS: more distance costs more.", "", "A distance setting; see its numbers.");
        putNoImage("rt_distance_chunks", Tier.EXPERT, "RT Distance (manual)", "Chunks covered by ray tracing when 'As in Game' is off.",
            "FPS and VRAM grow with the distance.", "", "A distance setting; see its numbers.");
        putNoImage("rt_vram_budget_mb", Tier.EXPERT, "RT Memory Budget", "GPU memory for chunk geometry; the farthest chunks are left out when it is full.",
            "FPS: none, but too large a value can exhaust VRAM.", "", "A memory limit has no look of its own.");
        putNoImage("rt_light_budget", Tier.EXPERT, "RT Light Budget", "Most important glowing block faces that cast traced light.",
            "FPS: grows with the number.", "", "A budget has no look of its own; too small drops far lamps.");
        putNoImage("sun_size_tenths", Tier.EXPERT, "Sun/Moon Size", "Disk diameter in tenths of a degree (real sun about 5). Bigger means softer shadows.",
            "FPS: none.", "", "Visible only as shadow softness at a moment of day; not captured.");
        putNoImage("auto_normal_strength", Tier.EXPERT, "Surface Relief Strength", "Depth of the generated relief.", "FPS: none.", "Surface Relief on.",
            "Shares the Surface Relief screenshots.");
        putNoImage("texture_lod_bias_offset", Tier.EXPERT, "Texture Sharpness Offset",
            "Extra mipmap bias; negative values make textures sharper (-0.2 to -0.5 suits pixel art). The automatic bias for upscalers is always on.",
            "FPS: none.", "", "A sub-pixel sharpness bias is not visible in a downscaled screenshot.");
        putNoImage("fsr_version", Tier.EXPERT, "FSR Version", "Which FSR inside the AMD library is used. Auto picks the newest (3.1).", "FPS: none.", "FSR only.",
            "Version choice, no visual preview.");
        putNoImage("atm_godrays_range", Tier.EXPERT, "Light Shaft Range", "Distance in blocks the beams are computed over.", "FPS: low.", "", "A range value.");
        putNoImage("atm_bloom_threshold", Tier.EXPERT, "Bloom Threshold", "Brightness from which a pixel starts to glow.", "FPS: none.", "", "Shares the Bloom screenshots.");
        putNoImage("atm_bloom_scatter", Tier.EXPERT, "Bloom Spread", "Balance between a tight and a wide glow.", "FPS: none.", "", "Shares the Bloom screenshots.");
        putNoImage("atm_sunglare_strength", Tier.EXPERT, "Sun Glare Strength", "Brightness of the sun halo.", "FPS: none.", "", "Shares the Sun Glare screenshots.");
        putNoImage("atm_sunglare_star", Tier.EXPERT, "Sun Glare Star", "Adds a six point star to the sun glare.", "FPS: none.", "", "Shares the Sun Glare screenshots.");
        putNoImage("atm_fog_vanilla", Tier.EXPERT, "Vanilla Fog Weight", "How much of the game's own fog is used.", "FPS: none.", "", "Shares the Fog screenshots.");
        putNoImage("rt_fog_in_scattering", Tier.EXPERT, "Fog In-scattering", "Sunlight and skylight scattering through the fog.", "FPS: low.", "", "Shares the Fog screenshots.");
        putNoImage("rt_debug_view", Tier.EXPERT, "RT Debug Buffer", "Replaces the world with an internal buffer (normals, depth, albedo...). For diagnosis only.",
            "FPS: low.", "", "A diagnostic view, not a look.");
    }

    private static Info shared(String path, String sharedWith, Tier tier, String name, String desc, String cost, String requires,
                               Function<Object, String> keyOf) {
        Info base = INFOS.get(sharedWith);
        return new Info(path, tier, name, desc, cost, requires, base.scene(), base.cropU(), base.cropV(), base.previewBase(), base.previews(), "", keyOf);
    }

    private static Info noImg(String path, Tier tier, String name, String desc, String cost, String requires, String why) {
        return new Info(path, tier, name, desc, cost, requires, Scene.DAY, 0.5f, 0.5f, path, List.of(), why, null);
    }

    private static double asNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return Double.NaN;
    }
}
