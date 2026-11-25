package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedray's own renderer as nodes. Every stage of the real pipeline (path tracer, ReSTIR DI, ReSTIR PT, Radiance
 * Cascades, the hybrid ray effects, fog, denoiser, exposure, post) is a node type {@code bedray.*} whose settings are the
 * renderer's real settings (keys, ranges, defaults and texts generated from RtSettings and the language file). A renderer
 * graph wires stages from Scene to Output: a stage that is wired in is on with its settings, a stage left out is turned
 * off. Compiling gives the key/value pairs the game applies live (RtSettings.applyKeyValue).
 */
public final class MosaicRenderer {

    private MosaicRenderer() {}

    public enum Type { BOOL, RANGE, CHOICE, PERCENT }

    /** One renderer setting: its bedray.toml key, the text players see, its range or choices and default. */
    public record Setting(String key, String label, String help, Type type, int min, int max, int def, List<String> options, String off) {
        public String format(double v) {
            return switch (type) {
                case BOOL -> v != 0 ? "true" : "false";
                case RANGE -> Integer.toString((int) Math.round(Math.max(min, Math.min(max, v))));
                case PERCENT -> String.format(java.util.Locale.ROOT, "%.2f", Math.max(min, Math.min(max, v)) / 100.0);
                case CHOICE -> options.get((int) Math.max(0, Math.min(options.size() - 1, Math.round(v)))).toLowerCase(java.util.Locale.ROOT);
            };
        }

        /** The value a setting's text in bedray.toml stands for, in node units (0/1, the number, the choice index). */
        public double parse(String s) {
            s = s.trim();
            return switch (type) {
                case BOOL -> Boolean.parseBoolean(s) ? 1 : 0;
                case RANGE -> {
                    try {
                        yield Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        yield def;
                    }
                }
                case CHOICE -> Math.max(0, options.indexOf(s.toUpperCase(java.util.Locale.ROOT).replace('-', '_')));
                case PERCENT -> {
                    try {
                        yield Math.round(Double.parseDouble(s) * 100);
                    } catch (NumberFormatException e) {
                        yield def;
                    }
                }
            };
        }
    }

    /**
     * A stage: node type, label, palette category, help, its place in the pipeline (wires must go from a lower to a higher
     * rank), the switches that turn it off when it is left out, the stages it needs upstream, and its settings.
     */
    public record Stage(String type, String label, String category, String help, int rank, List<String> enableKeys, List<String> needs,
                        String mode, List<Setting> settings) {
        /** A render-mode stage: wiring it in picks Bedray's mode (rt_mode). */
        public boolean isMode() { return mode != null; }

        public Setting setting(String key) {
            for (Setting s : settings) if (s.key().equals(key)) return s;
            return null;
        }
    }

    public static final String OUTPUT = "bedray.output";
    private static final Map<String, Stage> STAGES = new LinkedHashMap<>();

    private static Setting bool(String key, String label, String help, boolean def) {
        return new Setting(key, label, help, Type.BOOL, 0, 1, def ? 1 : 0, List.of(), null);
    }

    private static Setting range(String key, String label, String help, int min, int max, int def) {
        return new Setting(key, label, help, Type.RANGE, min, max, def, List.of(), null);
    }

    private static Setting choice(String key, String label, String help, List<String> options, int def, String off) {
        return new Setting(key, label, help, Type.CHOICE, 0, options.size() - 1, def, options, off);
    }

    private static Setting percent(String key, String label, String help, int def) {
        return new Setting(key, label, help, Type.PERCENT, 0, 100, def, List.of(), null);
    }

    private static void stage(String type, String label, String category, String help, int rank, List<String> enable, List<String> needs,
                              String mode, Setting... settings) {
        STAGES.put(type, new Stage(type, label, category, help, rank, enable, needs, mode, List.of(settings)));
    }

    static {
        stage("bedray.scene", "Scene", "Scene", "What the renderer sees: distance, lights, particles, weather, the hand.", 0, List.of(),
            List.of(), null,
            bool("rt_distance_auto", "RT Distance: As in Game", "On: ray tracing covers the game render distance. Off: the manual distance.", true),
            range("rt_distance_chunks", "RT Distance (manual)", "Chunks covered by ray tracing when 'As in Game' is off.", 2, 32, 8),
            range("rt_light_budget", "RT Light Budget", "Most important glowing block faces that cast traced light.", 16, 8192, 2048),
            bool("addon_lights", "Lights from other mods (experimental)", "Lets other mods that use the Bedray renderer API add point lights to the scene. The lights are checked and capped (64 per mod, 256 in total) and a mod that keeps failing is switched off for the session. Without such a mod this does nothing.", true),
            bool("hand_geometry", "Held item as ray-traced geometry (experimental)", "Traces the item in your hand as real geometry, so glass in the hand is lit and refracted like glass in the world. The raster item is not drawn. The arm and the off-hand stay raster and the item appears one frame late relative to its animation.", false),
            bool("rt_particles", "Particles in RT", "Smoke, flames, sparks, bubbles, block-break and weather splash particles in the ray traced image, occluded by terrain and lit by the scene.", true),
            bool("rt_weather", "Rain And Snow In RT", "Rain and snow columns in the ray traced image: occluded by terrain and fogged exactly as the game draws them, and never seen by the denoisers, so they do not smear or trail.", true),
            bool("icon_light_sky", "Sky mood on inventory icons (experimental)", "Tints the light on inventory item icons with the current sky colour instead of neutral white light, so icons match the time of day. Off keeps the neutral light.", false));
        stage("bedray.materials", "Materials", "Scene", "Surface options of every block: optical glass, automatic normals, self shadow, waving foliage, water.", 10, List.of(),
            List.of(), null,
            bool("rays_optical_materials", "Optical Materials", "Use authored IOR, thickness and absorption profiles, including dielectric clear-glass centres. Off restores legacy shader optics for comparison; clear interfaces can increase ray work.", true),
            bool("auto_normals", "RT Auto Normals", "Generates relief and normal maps from block textures for packs without LabPBR normal maps.", true),
            range("auto_normal_strength", "Auto Normal Strength", "Depth and strength of generated normal map relief.", 0, 400, 100),
            range("self_shadow", "Player Shadow (first person)", "Full keeps your body's entire shadow, including the ground under your feet. Off hides the shadow. Water and glass reflect your body in both modes.", 0, 2, 0),
            bool("waving_foliage", "Waving Foliage", "Classic shaderpack wind waving on grass, leaves and cutout foliage.", false),
            range("waving_foliage_strength", "Wind Strength", "Strength of the waving foliage wind motion (0 to 200 percent).", 0, 200, 100),
            range("water_style", "Water Style", "Vanilla keeps the classic clear animated water. Realistic adds waves, refraction and reflections.", 0, 1, 1),
            bool("rays_direct_translucent_shadows", "Direct Transparent Shadows", "Glass, ice and water tint direct-light shadows. Off passes their light without the color filter; opaque blockers and foliage still cast shadows.", true),
            bool("rays_indirect_translucent_shadows", "Secondary Transparent Shadows", "Filter shadows inside reflections and bounced lighting through transparent materials. Camera transparency stays enabled.", true),
            bool("rays_cull_shared_glass", "Cull Shared Glass Faces", "Conservatively remove matching internal full-block glass faces after Sodium capture. Reload the world to rebuild all sections after changing this option.", true));
        stage("bedray.sky", "Sun and sky", "Scene", "Size and tilt of the sun, how much baked light fills the shadows.", 20, List.of(),
            List.of(), null,
            range("sun_size_tenths", "Sun/Moon Size", "Disk diameter in tenths of a degree (real sun about 5). Bigger means softer shadows.", 1, 50, 5),
            range("sun_tilt_deg", "Sun and Moon Angle", "Tilts the path of the sun and moon to change the direction of shadows through the day.", 0, 45, 24),
            range("baked_light_fill_pct", "Baked light fill pct", null, 0, 100, 0));
        stage("bedray.voxels", "Voxels", "Scene", "The voxel world the tracers walk: distance, memory, quality profile, bounced light and glass, and which scene renderer runs.", 25, List.of(),
            List.of(), null,
            choice("voxel_quality_profile", "Voxel quality profile", "Preset of the voxel world: Fast, Beautiful or your own values.", List.of("FAST", "BEAUTIFUL", "CUSTOM"), 2, null),
            choice("renderer", "Scene renderer", "Hardware ray tracing of the real scene, or the live voxel renderer (debug).", List.of("HARDWARE_RT", "VOXEL_LIVE_DEBUG"), 0, "HARDWARE_RT"),
            bool("voxel_distance_auto", "Voxel Distance: Follow Minecraft", "Request the game render distance. Only loaded sections are captured; residency can be limited by memory and streaming budgets.", true),
            range("voxel_distance_chunks", "Voxel Distance (chunks)", "Requested horizontal radius when Follow Minecraft is off. Does not load or generate chunks.", 2, 32, 12),
            range("voxel_memory_budget_mib", "Voxel GPU Memory Budget", "Maximum scene storage in MiB. Nearby sections have priority; memory pressure can reduce coverage.", 64, 4096, 256),
            bool("voxel_diffuse_bounce", "Voxel Diffuse Bounce", "Enable the supported diffuse lighting bounce. Full physical light transport is still in development.", true),
            bool("voxel_translucent_optics", "Voxel Layered Transparency", "Enable the layered transparent optical path. Off skips this extra traversal and retains raster where replacement is unsupported.", true));
        stage("bedray.raster", "Raster (no ray tracing)", "Render mode", "Minecraft's own lighting with Bedray's post: the mode when nothing traces rays.", 30, List.of(),
            List.of(), "off");
        stage("bedray.pathTracer", "Path tracer", "Render mode", "The Slang path tracer: samples per pixel, bounces, distance, accumulation, caustics. Wired in, it is the render mode.", 30, List.of(),
            List.of(), "path",
            range("path_spp", "Samples per Pixel (SPP)", "Number of paths or rays traced per pixel and frame.", 1, 8, 1),
            range("path_bounces", "Max Bounces", "Maximum light bounces per path for Path Tracing.", 1, 8, 3),
            range("path_distance", "Max Ray Distance", "Maximum distance traversed by rays in blocks.", 16, 512, 256),
            bool("path_accumulate", "Path accumulate", null, true),
            bool("path_caustics", "Water Caustics", "Sunlight focused by the water surface into moving bright patterns on the ground under water.", false),
            bool("path_ser", "Shader Execution Reordering (SER)", "NVIDIA RTX 40/50: regroups path-tracing rays before shading. Same picture, only the scheduling changes. Measured 2.14x slower on the trace pass of an RTX 5060 Ti, so it is off by default; try it on your GPU. Applies after a game restart; ignored where the GPU lacks the feature.", false),
            bool("path_native_recording", "Native frame recording (experimental)", "Records the whole path-tracing frame inside bedray_core instead of the Java host. The picture is meant to be identical; it exists so the two paths can be compared on the same frame. A core without the exports keeps the Java path. Applies after a restart.", false));
        stage("bedray.rays", "Hybrid ray effects", "Render mode", "Ray traced shadows (soft, pixel), reflections, AO, block light with its own ReSTIR and one-bounce GI over raster. Wired in, it is the render mode.", 30, List.of(),
            List.of(), "choice",
            choice("rt_mode", "Ray effects", "Full ray effects, ambient occlusion only, or direct light only.", List.of("RAY_TRACED", "AO", "DIRECT"), 0, null),
            bool("rays_shadows", "Sun & Moon Light", "Traced direct lighting and shadows from the sun and moon.", true),
            bool("rays_soft_shadows", "RT Soft Shadows", "Shadows soften with distance from the caster (Ray Tracing Fast mode).", false),
            bool("rays_pixel_shadows", "RT Pixel Shadows", "Blocky, Minecraft-style sun and moon shadows: the sun and moon shadow is sampled once per world cell instead of once per pixel, so the edge becomes a staircase at block-texture resolution (both RT modes). Ambient occlusion, traced sky light and light from glowing blocks stay exact. There is one exception: cells that cross geometry keep exact shadows.", false),
            range("rays_pixel_shadow_size", "RT Pixel Shadow Size", "Size of a pixelated shadow in texture pixels (1 = one pixel of a 16x16 block texture); larger means chunkier edges. Only used when RT Pixel Shadows is on.", 1, 16, 1),
            bool("rays_reflections", "RT Reflections", "Traced reflections on water, glass, metals (Ray Tracing Fast mode).", false),
            range("rays_reflection_distance", "RT Reflection Distance", "Longest reflection ray in blocks.", 16, 512, 128),
            bool("rays_ao", "RT Ambient Occlusion", "Traced darkening in corners (Ray Tracing Fast mode).", false),
            range("rays_ao_radius", "RT AO Radius", "Occluders farther than this do not darken.", 1, 8, 3),
            bool("rays_emissive", "Emissive Lights", "Traced light and shadows from glowing blocks (torches, lanterns, glowstone, lava).", true),
            range("rays_emissive_quality", "RT Block Light Samples", "Light samples per pixel.", 1, 8, 1),
            range("rays_emissive_distance", "RT Block Light Distance", "Glowing blocks farther than this do not light a surface.", 8, 64, 32),
            bool("rays_restir_temporal", "ReSTIR Temporal Block Light", "A pixel keeps the block-light sample it chose last frame and refines it. Off re-samples block light every frame: noisier, and no old record is carried over.", true),
            bool("rays_restir_spatial", "ReSTIR Spatial Block Light", "A pixel also borrows samples from neighbours on the same surface (same normal, same plane), so one torch lights a wall from fewer, better-chosen samples. Needs ReSTIR Temporal Block Light on.", true),
            bool("rays_gi", "RT Global Illumination", "One traced bounce of indirect light (Ray Tracing Fast mode).", false),
            range("rays_gi_distance", "RT GI Distance", "Longest bounce ray in blocks.", 16, 256, 64),
            bool("rays_accumulate", "RT Temporal Accumulation", "Averages frames to remove noise (Ray Tracing Fast mode).", true),
            bool("rays_transparency_stats", "Transparency Counters", "Show delayed GPU query, candidate, layer and overflow counts in F3. Atomic counters add GPU overhead; off by default. Counts are not timings.", false));
        stage("bedray.restirDi", "ReSTIR DI", "Path tracing", "ReSTIR direct light: torch samples kept over frames (temporal) and shared with neighbours (spatial).", 40, List.of("path_restir_di"),
            List.of("bedray.pathTracer"), null,
            bool("path_restir_di", "ReSTIR Direct Light", "Reuses each pixel's glowing-block sample over frames instead of drawing a new one every frame, so torch light settles while the camera moves. Off re-samples every light per frame: noisier, and never carries an old record over.", true),
            bool("path_restir_spatial", "ReSTIR Spatial Direct Light", "A pixel also borrows light samples from neighbours on the same surface (same normal, same plane), so one torch lights a wall from fewer, better-chosen samples. Needs ReSTIR Direct Light on.", true));
        stage("bedray.restirPt", "ReSTIR PT", "Path tracing", "ReSTIR path reuse: bounced light of the first diffuse bounce kept over frames and pooled with neighbours. About 0.6 GB at 1080p.", 50, List.of("path_restir_pt"),
            List.of("bedray.restirDi"), null,
            bool("path_restir_pt", "ReSTIR Path Reuse (experimental)", "Reuses the light a pixel's first diffuse bounce brought back over the next frames, so bounced light settles while the camera moves. Costs about 0.6 GB of video memory at 1080p and one extra ray per reused pixel. Needs ReSTIR Direct Light on; the native backend ignores it.", false),
            bool("path_restir_pt_spatial", "ReSTIR Spatial Path Reuse", "Each pixel also borrows the bounced-light paths of four neighbouring pixels on the same surface, so bounced light settles from more paths. Up to four extra rays per pixel, no extra video memory. Needs ReSTIR Path Reuse on.", true));
        stage("bedray.radianceCascades", "Radiance Cascades", "Path tracing", "A radiance-cascade field as the diffuse continuation of the paths (experimental, applies after a restart).", 60, List.of("path_radiance_cascades"),
            List.of("bedray.pathTracer"), null,
            bool("path_radiance_cascades", "Radiance Cascades (experimental)", "Adds a finite-window radiance-cascade field as the diffuse continuation of path tracing. Needs both native cascade owners; if one is missing the path tracer keeps the normal bounce and the log says so. Applies after a restart.", false));
        stage("bedray.fog", "Volumetric fog", "Atmosphere", "Light shafts and fog: density, in-scattering, distance.", 70, List.of("volumetric_fog_enabled"),
            List.of(), null,
            bool("volumetric_fog_enabled", "Volumetric fog enabled", null, true),
            range("rt_fog_density", "Fog Density", "Atmospheric fog density (100% = default).", 0, 200, 100),
            range("rt_fog_in_scattering", "Fog In-scattering", "Sunlight and skylight in-scattering through the fog.", 0, 200, 100),
            range("rt_fog_distance_scale", "Fog Distance", "Changes the distance over which volumetric fog builds up.", 20, 200, 100));
        stage("bedray.denoiser", "Denoiser", "Image", "NRD or DLSS Ray Reconstruction turns the noisy path tracer into a clean image.", 80, List.of(),
            List.of(), null,
            choice("path_denoiser", "Denoiser", "Which denoiser cleans the image. Off shows the raw samples.", List.of("OFF", "NRD", "DLSS_RR"), 1, "OFF"),
            bool("dlss_neural_rendering", "DLSS Neural Rendering / DLSS 5 (experimental)", "Runs NVIDIA Neural Rendering on the raster world image after DLSS Super Resolution or DLAA. Needs the separately obtained NGX Neural Rendering runtime and an eligible RTX GPU, and frame generation and Ray Reconstruction must be off. Adds GPU work. Applies after a restart.", false));
        stage("bedray.upscaler", "Upscaler", "Image", "Render at a lower resolution and upscale: built-in Bilinear, FSR 1 and NIS on every GPU, FSR 2/3/4, XeSS and DLSS with their libraries.", 85, List.of(),
            List.of(), null,
            choice("quality_preset", "Quality preset", "Render resolution before upscaling: from a third of the screen to native.", List.of("ULTRA_PERFORMANCE", "PERFORMANCE", "BALANCED", "QUALITY", "NATIVE", "CUSTOM"), 4, "NATIVE"),
            choice("upscaler_backend", "Upscaler", "Built-in Bilinear, FSR 1 and NIS work on every GPU; FSR, XeSS and DLSS need their libraries.", List.of("BILINEAR", "FSR1", "NIS", "FSR", "XESS", "DLSS"), 0, "BILINEAR"),
            choice("fsr_version", "FSR version", "Which FSR the AMD library runs.", List.of("AUTO", "FSR_4", "FSR_3_1", "FSR_2"), 0, null),
            choice("fsr_mode", "FSR mode", "FSR resolution mode.", List.of("NATIVE_AA", "QUALITY", "BALANCED", "PERFORMANCE", "ULTRA_PERFORMANCE"), 1, null),
            choice("dlss_mode", "DLSS mode", "DLSS resolution mode (DLAA: native with DLSS anti-aliasing).", List.of("DLAA", "ULTRA_QUALITY", "QUALITY", "BALANCED", "PERFORMANCE", "ULTRA_PERFORMANCE"), 2, null),
            choice("dlss_sr_preset", "DLSS preset", "NVIDIA DLSS model preset.", List.of("AUTO", "J", "K", "L", "M"), 0, null),
            choice("xess_mode", "XeSS mode", "XeSS resolution mode.", List.of("NATIVE_AA", "ULTRA_QUALITY_PLUS", "ULTRA_QUALITY", "QUALITY", "BALANCED", "PERFORMANCE", "ULTRA_PERFORMANCE"), 3, null),
            choice("fsr1_mode", "FSR 1 mode", "FSR 1 resolution mode.", List.of("ULTRA_QUALITY", "QUALITY", "BALANCED", "PERFORMANCE"), 1, null),
            range("custom_scale", "Custom Scale", "3D render resolution from 25% to 100% with 5% step. Active only when Custom preset is selected.", 25, 100, 100),
            percent("fsr_sharpness", "FSR sharpness (%)", "Sharpening after FSR.", 0),
            percent("fsr1_sharpness", "FSR 1 sharpness (%)", "RCAS sharpening after FSR 1.", 30),
            percent("nis_sharpness", "NIS sharpness (%)", "Sharpening of NVIDIA Image Scaling.", 20));
        stage("bedray.exposure", "Exposure and tone", "Image", "Auto exposure, exposure in EV and the tone curve that maps light to the screen.", 90, List.of(),
            List.of(), null,
            choice("tonemapper", "Tonemapper", "The tone curve: Peak (Bedray), ACES, PBR Neutral, GT, AgX.", List.of("PEAK", "ACES", "PBR_NEUTRAL", "GT", "AGX"), 0, null),
            bool("path_auto_exposure", "Auto Exposure", "Dynamically adapts scene exposure based on average light levels.", true),
            range("path_exposure_ev", "Exposure Compensation", "Manual brightness offset in EV (-4.0 to +4.0). Shifts Auto Exposure when it is on, or sets the fixed brightness when Auto Exposure is off.", -40, 40, 0));
        stage("bedray.post", "Post effects", "Image", "Vibrant visuals, vignette and film grain of Bedray.", 100, List.of("vibrant_visuals", "vignette", "film_grain"),
            List.of(), null,
            bool("vibrant_visuals", "Vibrant Visuals", "Mojang-style vibrant palette: richer saturation and higher colour contrast on the tone-mapped picture. The built-in Vibrant preset pairs it with the ACES tone curve.", false),
            bool("vignette", "Vignette", "Darkens the corners of the picture for a cinematic, lens-like falloff. The centre is untouched; only the edges lose brightness.", false),
            bool("film_grain", "Film Grain", "Adds fine animated noise to the picture, strongest in the midtones like photographic film. It crawls every frame and never touches the converged history.", false));
    }

    public static List<Stage> stages() { return List.copyOf(STAGES.values()); }

    public static Stage stage(String type) { return STAGES.get(type); }

    public static boolean isStage(String type) { return STAGES.containsKey(type); }

    /** A setting by its bedray.toml key, or null. */
    public static Setting setting(String key) {
        if (key == null) return null;
        for (Stage s : STAGES.values()) for (Setting st : s.settings()) if (st.key().equals(key)) return st;
        return null;
    }

    /** The stage type that owns a setting, or null. */
    public static String stageOfSetting(String key) {
        for (Stage s : STAGES.values()) for (Setting st : s.settings()) if (st.key().equals(key)) return s.type();
        return null;
    }

    /**
     * The settings a renderer graph stands for: every stage wired into the output with its values (node inline values, else
     * the stage default), every stage left out switched off. Values of stages that are absent and have no switch are not sent.
     */
    public static Map<String, String> settings(MosaicGraph g) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Set<String> live = new java.util.HashSet<>();
        if (g.outputId() != null) collect(g, g.outputId(), live);
        java.util.Set<String> wiredTypes = new java.util.HashSet<>();
        for (String id : live) wiredTypes.add(g.node(id).type());
        for (Stage s : STAGES.values()) {
            if (wiredTypes.contains(s.type())) continue;
            for (String k : s.enableKeys()) {
                Setting st = s.setting(k);
                out.put(k, st != null && st.type() == Type.CHOICE && st.off() != null ? st.off().toLowerCase(java.util.Locale.ROOT) : "false");
            }
            for (Setting st : s.settings()) if (st.off() != null) out.put(st.key(), st.off().toLowerCase(java.util.Locale.ROOT));
        }
        String mode = "off";
        for (MosaicGraph.Node n : g.nodes()) {
            if (!live.contains(n.id())) continue;
            Stage s = STAGES.get(n.type());
            if (s == null) continue;
            if (s.isMode()) mode = s.mode();
            for (Setting st : s.settings()) {
                double[] v = n.defaults().get(st.key());
                double value = v != null && v.length > 0 ? v[0] : s.enableKeys().contains(st.key()) ? 1 : st.def();
                if (s.enableKeys().contains(st.key()) && v == null) value = 1; // wired in: on
                out.put(st.key(), st.format(value));
            }
        }
        // the wired render-mode stage decides rt_mode (Hybrid ray effects carries its flavour: rays, AO or direct)
        if (!"choice".equals(mode)) out.put("rt_mode", switch (mode) {
            case "path" -> "path";
            case "rays" -> "rays";
            default -> "off";
        });
        else {
            String flavour = out.getOrDefault("rt_mode", "ray_traced");
            out.put("rt_mode", switch (flavour) {
                case "ao" -> "ao";
                case "direct" -> "direct";
                default -> "rays";
            });
        }
        return out;
    }

    /** The render mode the wired stages pick: path, rays, ao, direct or off. */
    public static String mode(MosaicGraph g) { return settings(g).get("rt_mode"); }

    private static void collect(MosaicGraph g, String id, java.util.Set<String> into) {
        if (!into.add(id)) return;
        MosaicGraph.Node n = g.node(id);
        if (n == null) return;
        for (String r : n.inputs().values()) collect(g, MosaicGraph.split(r)[0], into);
    }

    /**
     * A starting graph: Scene -> Path tracer -> ReSTIR DI -> (ReSTIR PT when on) -> Fog -> Denoiser -> Exposure -> Post ->
     * Output, with the values of {@code state} (the game's current bedray.toml pairs) when given.
     */
    public static String defaultGraph(Map<String, String> state) {
        String rt = state == null ? "path" : state.getOrDefault("rt_mode", "path").toLowerCase(java.util.Locale.ROOT);
        List<String> chain = new ArrayList<>(List.of("bedray.scene", "bedray.materials", "bedray.sky", "bedray.voxels"));
        switch (rt) {
            case "off" -> chain.add("bedray.raster");
            case "rays", "ray_traced", "ao", "direct" -> chain.add("bedray.rays");
            default -> {
                chain.add("bedray.pathTracer");
                chain.add("bedray.restirDi");
                if (state != null && "true".equals(state.get("path_restir_pt"))) chain.add("bedray.restirPt");
                if (state != null && "true".equals(state.get("path_radiance_cascades"))) chain.add("bedray.radianceCascades");
            }
        }
        if (state == null || !"false".equals(state.get("volumetric_fog_enabled"))) chain.add("bedray.fog");
        if (rt.equals("path")) chain.add("bedray.denoiser");
        chain.addAll(List.of("bedray.upscaler", "bedray.exposure", "bedray.post"));
        StringBuilder sb = new StringBuilder("{\"kind\": \"renderer\", \"nodes\": [");
        String prev = null;
        int x = 40;
        for (String t : chain) {
            Stage s = STAGES.get(t);
            String id = t.substring("bedray.".length());
            sb.append("{\"id\": \"").append(id).append("\", \"type\": \"").append(t).append("\", \"x\": ").append(x).append(", \"y\": ")
                .append((x / 220) % 2 == 0 ? 80 : 180);
            if (prev != null) sb.append(", \"inputs\": {\"frame\": \"").append(prev).append("\"}");
            if (state != null) {
                StringBuilder d = new StringBuilder();
                for (Setting st : s.settings()) {
                    String v = state.get(st.key());
                    if (v == null) continue;
                    if (!d.isEmpty()) d.append(", ");
                    d.append("\"").append(st.key()).append("\": [").append(st.parse(v)).append("]");
                }
                if (!d.isEmpty()) sb.append(", \"defaults\": {").append(d).append("}");
            }
            sb.append("},");
            prev = id;
            x += 220;
        }
        sb.append("{\"id\": \"output\", \"type\": \"bedray.output\", \"x\": ").append(x).append(", \"y\": 80, \"inputs\": {\"frame\": \"")
            .append(prev).append("\"}}]}");
        return sb.toString();
    }
}
