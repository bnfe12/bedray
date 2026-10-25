package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.config.structure.IntegerOption;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Private pending values backed by the same validators and bindings as the settings registration. */
final class SettingsDraft {
    enum Badge { AVAILABLE, EXPERIMENTAL, UNSUPPORTED_HARDWARE, MISSING_RUNTIME, REQUIRES_RESTART, SCAFFOLD_ONLY, FAILED_INITIALIZATION, INACTIVE }
    record RuntimeStatus(Badge badge, Component reason) {
        Component text() { return Component.translatable("bedray.settings.status." + badge.name().toLowerCase(Locale.ROOT)).copy().append(": ").append(reason); }
    }
    enum Category { OVERVIEW, LIGHTING, WORLD, UPSCALING, PRESETS, SYSTEM;
        Component title() { return Component.translatable("bedray.settings.category." + name().toLowerCase(Locale.ROOT)); }
    }
    record Entry(String path, SettingsCatalog.Info info, StatefulOption<?> option, Category category, String searchText) {}
    enum Experience { VANILLA, PATH, RAYS, VOXEL;
        Component title() { return Component.translatable("bedray.settings.experience." + name().toLowerCase(Locale.ROOT)); }
        Component description() { return Component.translatable("bedray.settings.experience." + name().toLowerCase(Locale.ROOT) + ".desc"); }
        boolean available() {
            return switch (this) {
                case VANILLA -> true;
                case PATH -> io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isHardwareRayTracingSupported();
                case RAYS -> VulkanRtxConfig.raysUnlocked() && io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker.isHardwareRayTracingSupported();
                case VOXEL -> io.github.andrexarlay.vulkanbridge.api.VulkanBridge.isVulkan()
                    && io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry.backend() != null;
            };
        }
    }
    private static final Set<String> SESSION = Set.of("renderer_status", "compute_scene", "compute_debug_view", "debug_overlay",
        "rt_debug_view", "frame_generation_status", "ui_expert");
    private static final Set<String> PRESET_SKIPPED = Set.of("ui_expert", "experimental_rays", "rt_show_advanced", "rt_show_expert",
        "enable_hardware_ray_tracing", "rt_preset", "pt_preset");
    final Config config = VulkanSodiumConfig.createScreenConfig();
    final List<Entry> entries = new ArrayList<>();
    private final Map<String, String> imported = new LinkedHashMap<>();

    SettingsDraft() {
        SettingsCatalog.all().forEach((path, info) -> {
            Option option = config.getOption(id(path));
            if (option instanceof StatefulOption<?> value) {
                String description = Component.translatableWithFallback("bedray.opt." + path + ".desc", info.desc()).getString();
                entries.add(new Entry(path, info, value, category(path),
                    (value.getName().getString() + " " + description + " " + info.name() + " " + path).toLowerCase(Locale.ROOT)));
            }
        });
    }

    static Identifier id(String path) { return Identifier.fromNamespaceAndPath("bedray", path); }
    boolean voxel() { return config.readEnumOption(id("renderer"), VulkanRtxConfig.RendererSelection.class) != VulkanRtxConfig.RendererSelection.HARDWARE_RT; }
    boolean hasQualityProfile() { return voxel() || config.readEnumOption(id("rt_mode"), RtMode.class) == RtMode.PATH_TRACED; }
    boolean changed() { return !imported.isEmpty() || config.anyOptionChanged(); }
    boolean editable(Entry e) {
        if (!hasQualityProfile()) return true;
        if (e.path().equals("renderer") || e.path().equals("rt_mode") || e.path().endsWith("quality_profile") || e.category() == Category.SYSTEM) return true;
        String profile = voxel() ? "voxel_quality_profile" : "path_quality_profile";
        return config.readEnumOption(id(profile), VulkanSodiumConfig.QualityProfile.class) == VulkanSodiumConfig.QualityProfile.CUSTOM;
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    void choose(Entry entry, Enum<?> value) {
        ((StatefulOption)entry.option()).modifyValue(value);
        if (entry.path().equals("renderer")) { applySelectedProfile(); return; }
        if (!entry.path().endsWith("quality_profile") || value == VulkanSodiumConfig.QualityProfile.CUSTOM) return;
        boolean fast = value == VulkanSodiumConfig.QualityProfile.FAST;
        if (entry.path().equals("voxel_quality_profile")) {
            stage("voxel_distance_auto", true);
            stage("voxel_memory_budget_mib", fast ? 128 : 256);
            stage("voxel_diffuse_bounce", !fast);
            stage("voxel_translucent_optics", !fast);
            stage("quality_preset", fast ? VulkanRtxConfig.QualityPreset.BALANCED : VulkanRtxConfig.QualityPreset.NATIVE);
        } else {
            stage("path_spp", fast ? 1 : 2);
            stage("path_bounces", fast ? 2 : 4);
            stage("path_distance", fast ? 256 : 512);
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stage(String path, Object value) {
        if (config.getOption(id(path)) instanceof StatefulOption option) option.modifyValue(value);
    }
    private void applySelectedProfile() {
        String path = voxel() ? "voxel_quality_profile" : "path_quality_profile";
        entries.stream().filter(e -> e.path().equals(path)).findFirst().ifPresent(e -> choose(e, (Enum<?>)e.option().getValidatedValue()));
    }
    Component experienceTitle() {
        if (voxel()) return Experience.VOXEL.title();
        RtMode mode = config.readEnumOption(id("rt_mode"), RtMode.class);
        return switch (mode) {
            case OFF -> Experience.VANILLA.title();
            case PATH_TRACED -> Experience.PATH.title();
            case RAY_TRACED -> Experience.RAYS.title();
            default -> Component.literal(mode.logName());
        };
    }
    void selectExperience(Experience experience) {
        if (!experience.available()) return;
        stage("renderer", experience == Experience.VOXEL ? VulkanRtxConfig.RendererSelection.VOXEL_LIVE_DEBUG : VulkanRtxConfig.RendererSelection.HARDWARE_RT);
        if (experience != Experience.VOXEL) stage("rt_mode", switch (experience) {
            case VANILLA -> RtMode.OFF;
            case PATH -> RtMode.PATH_TRACED;
            default -> RtMode.RAY_TRACED;
        });
        if (experience == Experience.PATH || experience == Experience.VOXEL) applySelectedProfile();
    }
    void customize() { stage(voxel() ? "voxel_quality_profile" : "path_quality_profile", VulkanSodiumConfig.QualityProfile.CUSTOM); }
    void revert() { imported.clear(); config.resetAllOptionsFromBindings(); }
    void reset() { imported.clear(); entries.stream().filter(e -> !SESSION.contains(e.path())).forEach(e -> e.option().resetToDefault()); }
    void apply() {
        Map<String, String> decoded = new LinkedHashMap<>();
        imported.forEach((key, value) -> decoded.put(key, unquote(value)));
        VulkanSodiumConfig.applyScreenConfig(config, decoded);
        imported.clear();
    }
    RuntimeStatus status(Entry entry) {
        if (entry.path().startsWith("frame_generation")) {
            var availability = io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.availability();
            Badge badge = switch (availability) {
                case READY -> Badge.AVAILABLE;
                case NEEDS_RESTART -> Badge.REQUIRES_RESTART;
                case NO_BINARIES -> Badge.MISSING_RUNTIME;
                case FAILED -> Badge.FAILED_INITIALIZATION;
                case UNSUPPORTED -> Badge.UNSUPPORTED_HARDWARE;
            };
            return new RuntimeStatus(badge, Component.literal(io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.statusLine()));
        }
        if (entry.path().equals("upscaler_backend") || entry.path().equals("dlss_mode") || entry.path().equals("xess_mode") || entry.path().startsWith("fsr_")) {
            var backend = config.readEnumOption(id("upscaler_backend"), io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.class);
            var availability = io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection.status(backend, VulkanRtxConfig.gameDir());
            Badge badge = switch (availability) {
                case AVAILABLE -> io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration.requiresRestart(backend) ? Badge.REQUIRES_RESTART : Badge.AVAILABLE;
                case NOT_IMPLEMENTED -> Badge.SCAFFOLD_ONLY;
                case LIBRARY_MISSING -> Badge.MISSING_RUNTIME;
                case UNSUPPORTED_GPU -> Badge.UNSUPPORTED_HARDWARE;
            };
            return new RuntimeStatus(badge, Component.translatable("bedray.settings.requested_runtime", backend.name(), VulkanRtxConfig.activeBackend.name()));
        }
        if (!entry.option().isEnabled()) return new RuntimeStatus(Badge.INACTIVE, Component.translatable("bedray.settings.inactive"));
        if (voxel()) {
            var backend = io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry.backend();
            if (backend == null) return new RuntimeStatus(Badge.MISSING_RUNTIME, Component.translatable("bedray.renderer.status.missing"));
            return new RuntimeStatus(Badge.EXPERIMENTAL, Component.literal(io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry.status()));
        }
        String failure = io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.failure();
        if (failure != null) return new RuntimeStatus(Badge.FAILED_INITIALIZATION, Component.literal(failure));
        if (entry.path().equals("path_denoiser")) return new RuntimeStatus(Badge.EXPERIMENTAL,
            Component.literal(io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.denoiserRuntimeState() + ": "
                + io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.denoiserResolutionReason()));
        return new RuntimeStatus(Badge.EXPERIMENTAL, Component.translatable("bedray.settings.runtime_separate"));
    }

    boolean visible(Entry e) {
        String p = e.path();
        if (p.equals("pt_preset") || p.equals("rt_mode")) return false;
        if (p.equals("path_quality_profile") && !hasQualityProfile()) return false;
        if (p.startsWith("voxel_") || p.startsWith("compute_")) return voxel() && !e.option().shouldHideControl();
        if (!voxel()) return !e.option().shouldHideControl();
        return Set.of("renderer", "renderer_status", "quality_preset", "custom_scale", "debug_overlay", "ui_expert").contains(p)
            && !e.option().shouldHideControl();
    }

    private static Category category(String p) {
        if (p.equals("renderer") || p.equals("renderer_status") || p.startsWith("voxel_") || p.equals("path_quality_profile")) return Category.OVERVIEW;
        if (p.startsWith("compute_") || p.startsWith("debug_") || p.equals("rt_debug_view") || p.equals("ui_expert")) return Category.SYSTEM;
        if (p.contains("mode") && (p.startsWith("dlss") || p.startsWith("fsr") || p.startsWith("xess"))
            || p.startsWith("upscaler") || p.startsWith("frame_generation") || p.startsWith("fsr_")
            || p.equals("quality_preset") || p.equals("custom_scale")) return Category.UPSCALING;
        if (p.startsWith("atm_") || p.startsWith("water_") || p.startsWith("waving_") || p.startsWith("auto_normal")
            || p.startsWith("texture_") || p.equals("point_sampling_sharpness") || p.startsWith("rt_fog")) return Category.WORLD;
        return Category.LIGHTING;
    }

    /** No runtime or file mutation: unsupported values remain unchanged and are counted separately. */
    int importText(String text) {
        Map<String, String> rawValues = parse(text);
        Map<String, String> values = new LinkedHashMap<>();
        // Dependent enum validation must see the imported renderer/backend before dependent modes.
        for (String key : List.of("renderer", "active_backend", "rt_mode")) if (rawValues.containsKey(key)) values.put(key, rawValues.get(key));
        values.putAll(rawValues);
        Map<String, String> known = parse(PresetStore.snapshot());
        int applied = 0;
        for (var item : values.entrySet()) {
            if (PRESET_SKIPPED.contains(item.getKey()) || !known.containsKey(item.getKey())) continue;
            Entry e = entries.stream().filter(row -> !SESSION.contains(row.path()) && key(row.path()).equals(item.getKey())).findFirst().orElse(null);
            if (e == null) {
                try {
                    String normalized = normalizeUnrepresented(item.getKey(), item.getValue(), known.get(item.getKey()));
                    if (normalized != null) { imported.put(item.getKey(), normalized); applied++; }
                } catch (IllegalArgumentException ignored) {
                    // Unknown enum values and malformed scalars do not count as staged settings.
                }
            } else {
                try {
                    setParsed(e, item.getValue());
                    applied++;
                } catch (IllegalArgumentException ignored) {
                    // The existing validator remains the authority; malformed values do not change the draft.
                }
            }
        }
        if (applied > 0) {
            if (!values.containsKey("voxel_quality_profile")) stage("voxel_quality_profile", VulkanSodiumConfig.QualityProfile.CUSTOM);
            if (!values.containsKey("path_quality_profile")) stage("path_quality_profile", VulkanSodiumConfig.QualityProfile.CUSTOM);
            if (values.containsKey(voxel() ? "voxel_quality_profile" : "path_quality_profile")) applySelectedProfile();
        }
        return applied;
    }

    String snapshot() {
        Map<String, String> values = parse(PresetStore.snapshot());
        values.putAll(imported);
        for (Entry e : entries) {
            if (SESSION.contains(e.path())) continue;
            String key = key(e.path());
            if (values.containsKey(key)) values.put(key, persistedValue(e));
        }
        StringBuilder text = new StringBuilder("# Bedray settings preset (values only)\n");
        values.forEach((key, value) -> text.append(key).append(" = ").append(value).append('\n'));
        return text.toString();
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null) return values;
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.startsWith("#") || s.startsWith("[")) continue;
            int eq = s.indexOf('=');
            if (eq > 0) values.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
        }
        return values;
    }

    private static String key(String path) {
        return switch (path) {
            case "upscaler_backend" -> "active_backend";
            case "frame_generation_mode" -> "framegen_mode";
            case "frame_generation_provider" -> "framegen_provider";
            case "frame_generation_reflex" -> "reflex_mode";
            case "frame_generation_limit" -> "framegen_frame_limit";
            default -> path;
        };
    }

    private static boolean fractional(String p) {
        return p.equals("fsr_sharpness") || p.equals("texture_lod_bias_offset") || p.startsWith("atm_") && !p.equals("atm_godrays");
    }

    private static String unquote(String raw) {
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1);
        if (value.contains("\"")) throw new IllegalArgumentException("Invalid preset quoting");
        return value;
    }

    private static String normalizeUnrepresented(String key, String raw, String existing) {
        String value = unquote(raw);
        if (key.equals("fsr1_mode")) {
            var mode = io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode.valueOf(value.toUpperCase(Locale.ROOT));
            if (!io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes.isOffered(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.FSR1, mode)) return null;
            return "\"" + mode.name() + "\"";
        }
        if (key.equals("fsr_version")) return "\"" + io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion.valueOf(value.toUpperCase(Locale.ROOT)).name() + "\"";
        if (existing.equalsIgnoreCase("true") || existing.equalsIgnoreCase("false")) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) return null;
            return value.toLowerCase(Locale.ROOT);
        }
        if (existing.startsWith("\"")) return null;
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) return null;
        if (existing.matches("-?\\d+")) {
            if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
            return Integer.toString((int)number);
        }
        return Double.toString(number);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setParsed(Entry e, String raw) {
        String value = unquote(raw);
        StatefulOption option = e.option();
        Object current = option.getValidatedValue();
        Object next;
        if (current instanceof Boolean) {
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) next = Boolean.parseBoolean(value);
            else if (e.path().startsWith("atm_")) {
                double numeric = Double.parseDouble(value);
                if (numeric != 0.0 && numeric != 1.0) throw new IllegalArgumentException();
                next = numeric == 1.0;
            } else throw new IllegalArgumentException();
        } else if (option instanceof IntegerOption number) {
            double parsed = Double.parseDouble(value);
            if (fractional(e.path())) parsed *= e.path().equals("texture_lod_bias_offset") ? 10 : 100;
            var validator = number.getSteppedValidator();
            if (!Double.isFinite(parsed) || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE
                || parsed < validator.min() || parsed > validator.max()) throw new IllegalArgumentException();
            int integer = (int)Math.round(parsed);
            if (!validator.isValueValid(integer)) throw new IllegalArgumentException();
            next = integer;
        } else if (current instanceof RtMode) {
            next = RtMode.parse(value);
            if (next == null) throw new IllegalArgumentException();
        } else if (current instanceof VulkanSodiumConfig.WaterStyle) {
            next = value.equals("0") ? VulkanSodiumConfig.WaterStyle.VANILLA : value.equals("1")
                ? VulkanSodiumConfig.WaterStyle.REALISTIC : Enum.valueOf(VulkanSodiumConfig.WaterStyle.class, value.toUpperCase(Locale.ROOT));
        } else if (current instanceof RtAtmosphereSettings.GodRays) {
            double parsed = Double.parseDouble(value);
            int ordinal = (int)parsed;
            if (!Double.isFinite(parsed) || parsed != ordinal || ordinal < 0 || ordinal >= RtAtmosphereSettings.GodRays.values().length) throw new IllegalArgumentException();
            next = RtAtmosphereSettings.GodRays.values()[ordinal];
        } else if (current instanceof Enum enumValue) {
            next = Enum.valueOf(enumValue.getDeclaringClass(), value.toUpperCase(Locale.ROOT));
        } else return;
        if (option instanceof EnumOption enumeration && !enumeration.isValueAllowed((Enum)next)) throw new IllegalArgumentException();
        option.modifyValue(next);
    }

    private static String persistedValue(Entry e) {
        Object value = e.option().getValidatedValue();
        if (value instanceof RtMode mode) return "\"" + mode.logName() + "\"";
        if (value instanceof VulkanSodiumConfig.WaterStyle style) return style == VulkanSodiumConfig.WaterStyle.VANILLA ? "0" : "1";
        if (value instanceof RtAtmosphereSettings.GodRays mode) return mode.ordinal() + ".0";
        if (value instanceof Boolean on && e.path().startsWith("atm_")) return on ? "1.0" : "0.0";
        if (value instanceof Number n && fractional(e.path())) return String.valueOf(n.doubleValue() / (e.path().equals("texture_lod_bias_offset") ? 10 : 100));
        if (value instanceof Enum<?> mode) return "\"" + mode.name() + "\"";
        return String.valueOf(value);
    }
}
