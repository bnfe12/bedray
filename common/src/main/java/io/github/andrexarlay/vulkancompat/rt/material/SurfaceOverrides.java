package io.github.andrexarlay.vulkancompat.rt.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Surface-PBR overrides from a resource pack sidecar, so an author can retune a material without LabPBR maps.
 * <p>
 * The sidecar is the file the optical profiles already use ({@code assets/<ns>/textures/<path>.bedray.json}); the surface
 * fields live in the same JSON next to the optical keys. Fields are individually optional and only apply to sprites that
 * have no LabPBR {@code _s} map &mdash; a LabPBR pack still wins, the override cannot fight the standard. These keys are
 * accepted by {@link OpticalMaterials} without interpretation, so one sidecar carries both.
 * <ul>
 *   <li>{@code roughness}: linear 0..1 (default auto-PBR value);</li>
 *   <li>{@code f0}: dielectric reflectance 0..1 before the metal flag;</li>
 *   <li>{@code metal}: boolean, metalness 1 (the albedo becomes the specular colour);</li>
 *   <li>{@code emission}: 0..1 whole-sprite emission strength;</li>
 *   <li>{@code emissionMode}: {@code none}, {@code whole} (uniform) or {@code bright} (per-pixel from the albedo).</li>
 * </ul>
 * Unknown fields, invalid types or out-of-range numbers log a rate-limited warning and keep the automatic values, the
 * same policy as the optical sidecar.
 */
public final class SurfaceOverrides {
    private static final Logger LOG = LoggerFactory.getLogger("VKB-RT");
    private static final java.util.concurrent.atomic.AtomicInteger WARNINGS = new java.util.concurrent.atomic.AtomicInteger();

    /** Fields this class owns; {@link OpticalMaterials#KEYS} must contain every name it uses for the same file. */
    static final java.util.Set<String> KEYS = java.util.Set
        .of("roughness", "f0", "metal", "emission", "emissionMode");

    private SurfaceOverrides() {}

    /**
     * Pure part: merge the surface fields of one sidecar into the auto-PBR material. Throws on invalid data; the caller
     * decides eligibility (no LabPBR {@code _s} map) and catches, mirroring {@link OpticalMaterials#read}.
     */
    public static AutoPbr.Material parse(JsonObject json, AutoPbr.Material base) {
        for (String key : json.keySet()) {
            if (OpticalMaterials.KEYS.contains(key) || KEYS.contains(key)) continue;
            throw new IllegalArgumentException("unknown field " + key);
        }
        if (json.has("metal") && (!json.get("metal").isJsonPrimitive() || !json.get("metal").getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("metal must be boolean");
        if (json.has("emissionMode")) {
            switch (json.get("emissionMode").getAsString()) {
                case "none" -> { if (json.has("emission")) throw new IllegalArgumentException("emissionMode none takes no emission"); }
                case "whole", "bright" -> { /* emission value applies */ }
                default -> throw new IllegalArgumentException("emissionMode must be none, whole or bright");
            }
        }
        return new AutoPbr.Material(
            number(json, "roughness", base.roughness(), 0f, 1f),
            number(json, "f0", base.f0(), 0f, 1f),
            json.has("metal") ? json.get("metal").getAsBoolean() : base.metal(),
            json.has("emission") ? scalar(json.get("emission"), "emission", 0f, 1f) : base.emission(),
            json.has("emissionMode")
                ? switch (json.get("emissionMode").getAsString()) {
                    case "none" -> AutoPbr.Emission.NONE;
                    case "whole" -> AutoPbr.Emission.WHOLE;
                    default -> AutoPbr.Emission.BRIGHT;
                }
                : base.emissionMode(),
            "sidecar");
    }

    /** Applies the override when the sprite has no LabPBR specular map; otherwise the base material passes unchanged. */
    public static AutoPbr.Material apply(AutoPbr.Material base, Identifier sprite, ResourceProvider resources,
                                         boolean labSpecular) {
        if (labSpecular) return base;
        Identifier sidecar = sprite.withPath("textures/" + sprite.getPath() + ".bedray.json");
        try {
            var resource = resources.getResource(sidecar);
            if (resource.isEmpty()) return base;
            try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonObject json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                return parse(json, base);
            }
        } catch (Exception e) {
            warn("Invalid surface override " + sidecar + ": " + e.getMessage() + "; using automatic values");
            return base;
        }
    }

    private static float number(JsonObject json, String key, float fallback, float min, float max) {
        return json.has(key) ? scalar(json.get(key), key, min, max) : fallback;
    }

    private static float scalar(com.google.gson.JsonElement value, String what, float min, float max) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(what + " must be a number");
        return bounded(value.getAsFloat(), min, max);
    }

    private static float bounded(float value, float min, float max) {
        if (!Float.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("value outside " + min + ".." + max);
        return value == 0f ? 0f : value;
    }

    private static void warn(String text) { if (WARNINGS.getAndIncrement() < 8) LOG.warn("[RT-SURFACE] {}", text); }
}
