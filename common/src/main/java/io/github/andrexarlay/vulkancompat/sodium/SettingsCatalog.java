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
        // ===================== ESSENTIAL =====================
        putNoImage("renderer", Tier.ESSENTIAL, "World Renderer",
            "Hardware RT / Raster uses the saved hardware tracing mode. Voxel selects the included Bedray Compute provider: gray opaque cubes in a 48-block window with fixed diagnostic sunlight and traced shadows. Full textures, materials and light transport are pending. Frame generation is disabled and scaling becomes bilinear; the hardware RT mode stays saved.",
            "GPU cost is experimental and has not been measured in gameplay.",
            "Voxel requires the matching Bedray Compute add-on and the game's Vulkan backend; no RT extensions are required. Experimental.",
            "No gameplay comparison has been captured for the voxel renderer.");
        putNoImage("renderer_status", Tier.ESSENTIAL, "Renderer Status",
            "Reports the requested renderer and host command recording. A recorded frame does not establish gameplay compatibility.",
            "Read-only status.", "Missing add-ons and initialization failures are shown explicitly.",
            "Runtime status has no comparison image.");
        putNoImage("compute_scene", Tier.EXPERT, "Compute Diagnostic Scene",
            "Session diagnostic preference for the selected voxel renderer. Synthetic replaces the world with a fixed test scene; live mode draws opaque occupancy. World Renderer remains the main hardware/voxel switch. A saved voxel choice starts in live mode on the next launch.",
            "GPU cost is experimental and has not been measured in gameplay.", "Requires the included Bedray Compute provider.",
            "No live gameplay comparison has been captured.");
        putNoImage("compute_debug_view", Tier.EXPERT, "Compute Debug View",
            "Inspect opaque occupancy shading, face normals, ray distance or unsupported and missing cells. These are diagnostic views, without textures or full material lighting.",
            "Diagnostic shading only.", "Requires the included Bedray Compute provider with debug-view support.",
            "No live gameplay comparison has been captured.");
        put("rt_mode", Tier.ESSENTIAL, "Ray Tracing",
            "Off draws the classic game. Path Tracing replaces its lighting with traced light: real shadows, reflections, bounced light and glowing blocks.",
            "",
            "Needs a GPU with VK_KHR_ray_query. Experimental.", Scene.DAY, 0.62f, 0.55f, "rt_mode", List.of(
                p("off", "Off", () -> {
                    VulkanRtxConfig.rtMode = RtMode.OFF;
                    VulkanRtxConfig.enableHardwareRayTracing = false;
                }),
                p("path", "Path Tracing", SettingsCatalog::setPathMode)),
            v -> v instanceof RtMode m ? (m == RtMode.OFF ? "off" : "path") : String.valueOf(v));
        put("path_bounces", Tier.ESSENTIAL, "Light Quality (bounces)",
            "How many times light may bounce. More bounces light dark rooms and tint walls with the colour of their neighbours.",
            "", "Path Tracing only.", Scene.INTERIOR, 0.5f, 0.55f, "path_bounces", List.of(
                p("1", "1 bounce", () -> RtSettings.pathBounces = 1),
                p("3", "3 bounces", () -> RtSettings.pathBounces = 3),
                p("6", "6 bounces", () -> RtSettings.pathBounces = 6)), null);
        put("path_auto_exposure", Tier.ESSENTIAL, "Auto Exposure",
            "The picture brightens in the dark and dims in bright light, like an eye. Off keeps one fixed brightness.",
            "", "Path Tracing only. Known issue: it can pulse or jump when the scene changes; turn it off if the brightness bothers you.",
            Scene.INTERIOR, 0.5f, 0.5f, "path_auto_exposure", List.of(
                onOff(false, () -> RtSettings.pathAutoExposure = false),
                onOff(true, () -> RtSettings.pathAutoExposure = true)), null);
        put("water_style", Tier.ESSENTIAL, "Water Style",
            "Vanilla keeps the classic clear animated water. Realistic adds waves, refraction and reflections.",
            "", "", Scene.WATER, 0.5f, 0.5f, "water_style", List.of(
                p("vanilla", "Vanilla", () -> RtSettings.waterStyle = RtSettings.WATER_STYLE_VANILLA),
                p("realistic", "Realistic", () -> RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC)),
            v -> v instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : String.valueOf(v));
        put("atm_godrays", Tier.ESSENTIAL, "Light Shafts",
            "Visible beams of sun and moon light through leaves, windows and cave openings; strongest at sunrise and sunset.",
            "", "Needs ray tracing on.", Scene.SUNSET, 0.5f, 0.45f, "godrays", List.of(
                p("off", "Off", () -> RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.GodRays.OFF.ordinal())),
                p("on", "On", () -> RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.GodRays.POST.ordinal()))),
            v -> v instanceof RtAtmosphereSettings.GodRays g ? (g == RtAtmosphereSettings.GodRays.OFF ? "off" : "on") : String.valueOf(v));
        put("upscaler_backend", Tier.ESSENTIAL, "Upscaler",
            "Renders the 3D world at a smaller size and reconstructs the full image. DLSS, FSR and XeSS use temporal reconstruction; Built-in uses spatial scaling.",
            "", "DLSS needs an NVIDIA RTX GPU and its files; FSR and XeSS need their libraries (see docs/INSTALL.md).",
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
                "",
                "The screenshots were taken with the first available upscaler (see the caption).", Scene.DAY, 0.55f, 0.6f, "upscaler_mode", modes, modeKey);
        }
        INFOS.put("frame_generation_provider", new Info("frame_generation_provider", Tier.ESSENTIAL, "Frame Generation Method",
            "Auto uses NVIDIA DLSS Frame Generation (Streamline) when the NVIDIA driver and Streamline files are installed, otherwise AMD FSR 3 Frame Generation (works on any GPU with amd_fidelityfx_vk.dll, 2x mode).",
            "",
            "Both methods hook the Vulkan swapchain at game startup, so changing the method requires a game restart.", Scene.DAY, 0.5f, 0.5f,
            "frame_generation_mode", List.of(), "A still image cannot show frame generation: it changes motion smoothness, not how a single frame looks.", null));
        INFOS.put("frame_generation_mode", new Info("frame_generation_mode", Tier.ESSENTIAL, "Frame Generation",
            "Inserts frames the game did not draw: motion looks smoother and the reported FPS rises, while input latency grows a little."
                + " 2x adds one frame, 3x and 4x add more and need an RTX 40/50 card.",
            "",
            "Hardware renderer only. Needs a frame generation runtime (see Frame Generation Method) and a temporal upscaler;"
                + " the voxel renderer forces it off.", Scene.DAY, 0.5f, 0.5f,
            "frame_generation_mode", List.of(), "Generated frames need motion: a still image cannot show them.", null));

        // ===================== ADVANCED =====================
        put("pt_preset", Tier.ADVANCED, "Path Tracing Profile",
            "Fast and Quality set rays per pixel, bounces and distance in one step. Custom appears when you change one of those values.",
            "", "Path Tracing only.", Scene.INTERIOR, 0.5f, 0.55f, "pt_preset", List.of(
                p("fast", "Fast", () -> RtSettings.applyPathPreset(RtSettings.PathPreset.FAST)),
                p("quality", "Quality", () -> RtSettings.applyPathPreset(RtSettings.PathPreset.QUALITY))),
            v -> v instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : String.valueOf(v));
        put("path_spp", Tier.ADVANCED, "Rays per Pixel",
            "How many light paths are traced for each pixel every frame. More rays means less grain before the denoiser.",
            "", "Path Tracing only.", Scene.INTERIOR, 0.5f, 0.55f, "path_spp", List.of(
                p("1", "1 ray", () -> RtSettings.pathSpp = 1),
                p("2", "2 rays", () -> RtSettings.pathSpp = 2),
                p("4", "4 rays", () -> RtSettings.pathSpp = 4)), null);
        putNoImage("path_distance", Tier.ADVANCED, "Ray Distance",
            "How far a light path may travel in blocks. Longer reaches distant sky and hills; shorter is faster.",
            "", "Path Tracing only.",
            "The difference shows only in large open scenes (distant terrain lighting); a still of the test scene would not show it.");
        put("path_denoiser", Tier.ADVANCED, "Denoiser",
            "Removes the grain of traced light. NRD is the default; DLSS Ray Reconstruction replaces denoiser and upscaler (RTX only). Off leaves raw grain.",
            "",
            "NRD needs its library, Ray Reconstruction needs an NVIDIA RTX GPU and the DLSS files; a missing one falls back to accumulation.",
            Scene.INTERIOR, 0.5f, 0.55f, "path_denoiser", List.of(
                p("off", "Off", () -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF),
                p("nrd", "NRD", () -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD),
                p("dlss_rr", "DLSS Ray Reconstruction", () -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.DLSS_RR)),
            v -> v instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : String.valueOf(v));
        putNoImage("adaptive_quality", Tier.ADVANCED, "Adaptive Quality",
            "Keeps Path Tracing inside a GPU frame budget: when the measured time of the traced rays and the denoiser is over it, rays per pixel drop first and then bounces, "
                + "below the values you chose; when the frame has room again they come back. It never goes above your values. Off by default - turn it on if you want a steadier "
                + "frame rate in heavy scenes and accept less detail there.",
            "FPS: none on its own - it trades image detail for a steadier frame rate when a scene is too heavy for the budget.",
            "Path Tracing only. Uses the GPU timers, so it stays off when they were disabled at startup.",
            "A still cannot show a governor at work: the effect is in the frame time of a heavy scene, not in one image.");
        put("path_caustics", Tier.ADVANCED, "Water Caustics",
            "Sunlight focused by the water surface into moving bright patterns on the ground under water.",
            "", "Path Tracing only.", Scene.WATER, 0.5f, 0.75f, "path_caustics", List.of(
                onOff(false, () -> RtSettings.caustics = false), onOff(true, () -> RtSettings.caustics = true)), null);
        putNoImage("dlss_rr_geometric_normals", Tier.ADVANCED, "Sharp Block Edges (Ray Reconstruction)",
            "Gives DLSS Ray Reconstruction the flat block-face directions, which keeps block edges crisp. Off feeds smoothed directions.",
            "", "Only with Path Tracing and DLSS Ray Reconstruction.",
            "Needs the DLSS Ray Reconstruction library; it is not available on every machine, so no screenshot is shipped.");
        putNoImage("path_exposure_ev", Tier.ADVANCED, "Exposure Compensation",
            "Manual brightness offset in EV (-4.0 to +4.0). Shifts Auto Exposure when it is on, or sets the fixed brightness when Auto Exposure is off.",
            "", "Needs ray tracing on.",
            "Shares the Auto Exposure behaviour; adjust live to match your monitor brightness.");
        putNoImage("rt_particles", Tier.ADVANCED, "Particles in RT",
            "Smoke, flames, sparks, bubbles, block-break and weather splash particles in the ray traced image, occluded by terrain and lit by the scene.",
            "", "Needs ray tracing on.",
            "Particles are animated and short-lived; a still preview does not represent them.");
        putNoImage("rt_weather", Tier.ADVANCED, "Rain And Snow In RT",
            "Rain and snow columns in the ray traced image, drawn the way the game draws them: occluded by terrain and fogged, "
                + "but never seen by the denoisers, so they cannot smear or trail.",
            "", "Needs ray tracing and Particles in RT on.",
            "Columns only appear while it rains or snows; a still preview cannot show falling rain.");
        putNoImage("waving_foliage", Tier.ADVANCED, "Waving Foliage",
            "Classic shaderpack wind waving on grass, leaves and cutout foliage.",
            "", "Needs ray tracing on.",
            "Dynamic wind animation on foliage quads.");
        putNoImage("waving_foliage_strength", Tier.ADVANCED, "Wind Strength",
            "Strength of the waving foliage wind motion (0 to 200 percent).",
            "", "Waving Foliage on.",
            "Slider controlling wind amplitude.");
        INFOS.put("atm_godrays_strength", shared("atm_godrays_strength", "atm_godrays", Tier.ADVANCED, "Light Shaft Strength",
            "Brightness of the light beams.", "", "Needs Light Shafts on.", v -> asNumber(v) > 0 ? "on" : "off"));
        INFOS.put("atm_godrays_quality", noImg("atm_godrays_quality", Tier.ADVANCED, "Light Shaft Quality",
            "Steps used to draw each beam: Low 6, Medium 8, High 12, Ultra 16. More steps give smoother beams.", "", "Needs Light Shafts on.",
            "The difference between quality steps is subtle (smoother edges) and is not shown by a single still."));
        put("atm_bloom", Tier.ADVANCED, "Bloom",
            "A soft glow around very bright lights and glowing blocks, like a camera lens.", "", "Needs ray tracing on.",
            Scene.LAVA, 0.5f, 0.55f, "bloom", List.of(
                p("off", "Off", () -> RtAtmosphereSettings.set("bloom", 0)),
                p("on", "On", () -> RtAtmosphereSettings.set("bloom", 1))), null);
        INFOS.put("atm_bloom_strength", shared("atm_bloom_strength", "atm_bloom", Tier.ADVANCED, "Bloom Strength", "How strong the glow is.",
            "", "Needs Bloom on.", v -> asNumber(v) > 0 ? "on" : "off"));
        put("atm_sunglare", Tier.ADVANCED, "Sun Glare",
            "A bright halo around the sun when you look at it; hidden behind blocks and clouds.", "", "Needs ray tracing on.",
            Scene.SUNSET, 0.2f, 0.45f, "sunglare", List.of(
                p("off", "Off", () -> RtAtmosphereSettings.set("sunglare", 0)),
                p("on", "On", () -> RtAtmosphereSettings.set("sunglare", 1))), null);
        put("atm_fog_aerial", Tier.ADVANCED, "Distance Haze",
            "Far terrain fades into the colour of the sky, denser in rain and valleys. 0 turns it off.", "", "Needs Fog on.",
            Scene.DAY, 0.5f, 0.35f, "fog_aerial", List.of(
                p("0", "None", () -> RtAtmosphereSettings.set("fog_aerial", 0.0)),
                p("1", "Default", () -> RtAtmosphereSettings.set("fog_aerial", 1.0)),
                p("2", "Strong", () -> RtAtmosphereSettings.set("fog_aerial", 2.0))), null);
        put("rt_fog", Tier.ADVANCED, "Fog",
            "Atmospheric depth and fog in the traced picture, with the sun scattering through it.", "", "Needs ray tracing on.",
            Scene.SUNSET, 0.5f, 0.45f, "rt_fog", List.of(
                onOff(false, () -> RtSettings.volumetricFogEnabled = RtSettings.rtFog = false),
                onOff(true, () -> RtSettings.volumetricFogEnabled = RtSettings.rtFog = true)), null);
        INFOS.put("rt_fog_density", shared("rt_fog_density", "rt_fog", Tier.ADVANCED, "Fog Density", "How thick the fog is (100% = default).",
            "", "Needs Fog on.", v -> asNumber(v) > 0 ? "on" : "off"));
        put("auto_normals", Tier.ADVANCED, "Surface Relief",
            "Builds bumps from block textures so light and shadow catch the grain of stone and planks. Resource packs with their own normal maps keep them.",
            "", "Needs ray tracing on.", Scene.DAY, 0.3f, 0.8f, "auto_normals", List.of(
                onOff(false, () -> RtSettings.autoNormals = false), onOff(true, () -> RtSettings.autoNormals = true)), null);
        putNoImage("point_sampling_sharpness", Tier.ADVANCED, "Pixel-Sharp Textures",
            "Keeps textures as hard square pixels when you look closely, instead of smoothing them.", "",
            "Takes effect after reloading resource packs (F3+T).",
            "The setting only applies after a resource reload, so a live before/after screenshot is not possible.");
        put("fsr_sharpness", Tier.ADVANCED, "FSR Sharpening",
            "Extra sharpening after FSR (RCAS). 0 is off.", "", "FSR only.", Scene.DAY, 0.55f, 0.6f, "fsr_sharp", List.of(
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
            "Exact render size for the built-in upscaler, 25% to 100%.", "", "Built-in upscaler with 3D Scale set to Custom.",
            "It is the same effect as the 3D Scale presets; see the 3D Scale screenshots.");
        putNoImage("frame_generation_reflex", Tier.ADVANCED, "NVIDIA Reflex",
            "Lowers input delay. Boost also raises GPU clocks.", "", "NVIDIA GPU with Streamline.",
            "Reflex changes latency, not the look of a frame.");
        putNoImage("frame_generation_limit", Tier.ADVANCED, "Presented FPS Cap",
            "Caps the displayed frame rate (0 = no cap). Keeps frame pacing steady near the monitor refresh rate.", "", "Frame Generation active.",
            "A frame cap changes timing, not the look of a frame.");
        putNoImage("frame_generation_status", Tier.ADVANCED, "Frame Generation Status",
            "Read-only: whether frame generation is active, and why not when it is not.", "", "", "Status line, nothing to preview.");
        putNoImage("debug_overlay", Tier.ADVANCED, "F3 Bedray Info",
            "Shows the Bedray backend, rendering and upscaler diagnostics on the F3 screen.", "", "",
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
            putNoImage(e[0], Tier.EXPERT, e[1], e[2], "", "Hidden mode: Ray Tracing (Fast) is in development.",
                "The Ray Tracing (Fast) mode is not public yet, so it has no screenshots.");
        }
        // A look option, not a cost option: the only added work is one short ray (at most half a cell) that checks the
        // shared origin still sees the fragment it replaces.
        putNoImage("rays_pixel_shadows", Tier.EXPERT, "RT Pixel Shadows",
            "Blocky, Minecraft-style sun and moon shadows: the sun and moon shadow is sampled once per world cell instead of "
                + "once per pixel, so the edge becomes a staircase at block-texture resolution (both RT modes). Ambient "
                + "occlusion, traced sky light and light from glowing blocks stay exact. There is one exception: "
                + "cells that cross geometry keep exact shadows.",
            "FPS: none to low (one validation ray of at most half a cell per shaded hit).",
            "Needs ray tracing on; it applies to Path Tracing and to Ray Tracing (Fast).",
            "A look option with no preview screenshot yet.");
        for (String[] e : new String[][] {
            {"rays_shadow_quality", "RT Shadow Rays", "Shadow rays per pixel."},
            {"rays_pixel_shadow_size", "RT Pixel Shadow Size", "Size of a pixelated shadow in texture pixels (1 = one pixel of a 16x16 block texture); larger means chunkier edges. Only used when RT Pixel Shadows is on."},
            {"rays_reflection_quality", "RT Reflection Rays", "Reflection rays per pixel."},
            {"rays_reflection_distance", "RT Reflection Distance", "Longest reflection ray in blocks."},
            {"rays_ao_quality", "RT AO Rays", "Occlusion rays per pixel."},
            {"rays_ao_radius", "RT AO Radius", "Occluders farther than this do not darken."},
            {"rays_emissive_quality", "RT Block Light Samples", "Light samples per pixel."},
            {"rays_emissive_distance", "RT Block Light Distance", "Glowing blocks farther than this do not light a surface."},
            {"rays_gi_quality", "RT GI Rays", "Bounce rays per pixel."},
            {"rays_gi_distance", "RT GI Distance", "Longest bounce ray in blocks."}}) {
            putNoImage(e[0], Tier.EXPERT, e[1], e[2], "",
                e[0].equals("rays_pixel_shadow_size")
                    ? "Used when RT Pixel Shadows is on (both RT modes)."
                    : "Hidden mode: Ray Tracing (Fast) is in development.",
                "The Ray Tracing (Fast) mode is not public yet, so it has no screenshots.");
        }
        putNoImage("rt_distance_auto", Tier.EXPERT, "RT Distance: As in Game", "On: ray tracing covers the game render distance. Off: the manual distance.",
            "", "", "A distance setting; see its numbers.");
        putNoImage("rt_distance_chunks", Tier.EXPERT, "RT Distance (manual)", "Chunks covered by ray tracing when 'As in Game' is off.",
            "", "", "A distance setting; see its numbers.");
        putNoImage("rt_vram_budget_mb", Tier.EXPERT, "RT Memory Budget", "GPU memory for chunk geometry; the farthest chunks are left out when it is full.",
            "", "", "A memory limit has no look of its own.");
        putNoImage("rt_light_budget", Tier.EXPERT, "RT Light Budget", "Most important glowing block faces that cast traced light.",
            "", "", "A budget has no look of its own; too small drops far lamps.");
        putNoImage("sun_size_tenths", Tier.EXPERT, "Sun/Moon Size", "Disk diameter in tenths of a degree (real sun about 5). Bigger means softer shadows.",
            "", "", "Visible only as shadow softness at a moment of day; not captured.");
        putNoImage("auto_normal_strength", Tier.EXPERT, "Surface Relief Strength", "Depth of the generated relief.", "", "Surface Relief on.",
            "Shares the Surface Relief screenshots.");
        putNoImage("texture_lod_bias_offset", Tier.EXPERT, "Texture Sharpness Offset",
            "Extra mipmap bias; negative values make textures sharper (-0.2 to -0.5 suits pixel art). The automatic bias for upscalers is always on.",
            "", "", "A sub-pixel sharpness bias is not visible in a downscaled screenshot.");
        putNoImage("fsr_version", Tier.EXPERT, "FSR Version", "Which FSR inside the AMD library is used. Auto picks the newest (3.1).", "", "FSR only.",
            "Version choice, no visual preview.");
        putNoImage("atm_godrays_range", Tier.EXPERT, "Light Shaft Range", "Distance in blocks the beams are computed over.", "", "", "A range value.");
        putNoImage("atm_bloom_threshold", Tier.EXPERT, "Bloom Threshold", "Brightness from which a pixel starts to glow.", "", "", "Shares the Bloom screenshots.");
        putNoImage("atm_bloom_scatter", Tier.EXPERT, "Bloom Spread", "Balance between a tight and a wide glow.", "", "", "Shares the Bloom screenshots.");
        putNoImage("atm_sunglare_strength", Tier.EXPERT, "Sun Glare Strength", "Brightness of the sun halo.", "", "", "Shares the Sun Glare screenshots.");
        putNoImage("atm_fog_vanilla", Tier.EXPERT, "Vanilla Fog Weight", "How much of the game's own fog is used.", "", "", "Shares the Fog screenshots.");
        putNoImage("rt_fog_in_scattering", Tier.EXPERT, "Fog In-scattering", "Sunlight and skylight scattering through the fog.", "", "", "Shares the Fog screenshots.");
        putNoImage("rt_debug_view", Tier.EXPERT, "RT Debug Buffer", "Replaces the world with an internal buffer (normals, depth, albedo...). For diagnosis only.",
            "", "", "A diagnostic view, not a look.");
        putNoImage("voxel_quality_profile", Tier.ESSENTIAL, "Voxel Quality", "Fast lowers the memory budget and turns off the diffuse bounce and layered transparent optics. Beautiful enables them. Both follow Minecraft's distance; Custom unlocks those settings. The upscaler, frame generation and lighting stay editable in every profile.", "", "Experimental renderer.", "No voxel quality comparison has been captured.");
        putNoImage("path_quality_profile", Tier.ESSENTIAL, "Path Tracing Quality", "Fast traces one path per pixel with 2 bounces over 256 blocks; Beautiful traces two paths with 4 bounces over 512 blocks. Custom unlocks the three values. The upscaler, frame generation and lighting stay editable in every profile; the denoiser choice is never changed by a profile.", "", "Hardware Path Tracing only.", "Existing previews show individual hardware settings, not these profiles.");
        putNoImage("voxel_distance_auto", Tier.ADVANCED, "Voxel Distance: Follow Minecraft", "Request the game's render distance. Only loaded sections can become resident; actual coverage depends on the capture and memory budgets.", "", "Voxel renderer only.", "Coverage is reported by runtime statistics.");
        putNoImage("voxel_distance_chunks", Tier.ADVANCED, "Voxel Distance (chunks)", "Requested horizontal radius when Follow Minecraft is off. Increasing this value does not load or generate chunks.", "", "Voxel renderer only.", "Coverage is reported by runtime statistics.");
        putNoImage("voxel_memory_budget_mib", Tier.ADVANCED, "Voxel GPU Memory Budget", "Maximum GPU scene storage in MiB. The provider prioritizes nearby sections; memory pressure can reduce resident coverage below the requested distance.", "", "Voxel renderer only.", "Memory use is reported by runtime statistics.");
        putNoImage("voxel_diffuse_bounce", Tier.ADVANCED, "Voxel Diffuse Bounce", "Trace the supported diffuse bounce in the software renderer. Turning it off removes this lighting work; full physical light transport is still in development.", "", "Voxel renderer only.", "No voxel lighting comparison has been captured.");
        putNoImage("voxel_translucent_optics", Tier.ADVANCED, "Voxel Layered Transparency", "Enable the software renderer's layered transparent optical path. Turning it off skips this additional traversal and preserves the underlying raster where replacement is unsupported.", "", "Voxel renderer only.", "No voxel transparency comparison has been captured.");
        putNoImage("ui_expert", Tier.ADVANCED, "Expert Settings", "Show low-level rendering and diagnostic options. Apply to rebuild this screen with the new option set.", "", "", "Interface preference, no image comparison.");
        putNoImage("frame_generation_provider", Tier.ADVANCED, "Frame Generation Method", "Select the runtime provider. Changing it requires a restart; installed files do not establish that generation is active.", "", "Hardware renderer with a supported runtime.", "Generated frames require motion, so a still image cannot demonstrate them.");
        // Older descriptions carried generic FPS and VRAM estimates without linked measurements.
        INFOS.replaceAll((path, info) -> new Info(info.path(), info.tier(), info.name(), info.desc(), "", info.requires(),
            info.scene(), info.cropU(), info.cropV(), info.previewBase(), info.previews(), info.noImage(), info.keyOf()));
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
