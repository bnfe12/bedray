package io.github.andrexarlay.vulkancompat.rt.material;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable optical records selected by the five unused high source-metadata bits of the existing material atlas. */
public final class OpticalMaterials {
    public static final int CAPACITY = 32;
    public static final int FLOATS = 12;
    public static final int BYTES = CAPACITY * FLOATS * Float.BYTES;
    public static final int SHIFT = 3;
    private static final Logger LOG = LoggerFactory.getLogger("VKB-RT");
    private static final AtomicInteger WARNINGS = new AtomicInteger();
    private static final Set<String> KEYS = Set.of("ior", "thickness", "measurementDistance", "diffuseLayer", "transmittance", "kind", "cullSharedFaces");

    public record Profile(float ior, float thickness, float distance, float diffuse,
                          float red, float green, float blue, int kind, boolean cullShared) {
        public float[] data() {
            return new float[] {ior, thickness, distance, diffuse, red, green, blue, kind,
                cullShared ? 1f : 0f, 0f, 0f, 0f};
        }
    }

    private final List<Profile> profiles = new ArrayList<>();

    public OpticalMaterials() {
        profiles.add(null); // zero retains the legacy material model
        profiles.add(defaults(1));
        profiles.add(new Profile(1.52f, 1f, 1f, 1f, 0.97f, 0.97f, 0.97f, 1, true));
        profiles.add(defaults(2));
        profiles.add(defaults(3));
    }

    public synchronized int intern(Profile profile) {
        if (profile == null) return 0;
        int existing = profiles.indexOf(profile);
        if (existing >= 0) return existing;
        if (profiles.size() == CAPACITY) {
            warn("Optical palette is full (31 distinct profiles); sprite keeps legacy optics until the atlas is recreated");
            return 0;
        }
        profiles.add(profile);
        return profiles.size() - 1;
    }

    public synchronized List<Profile> snapshot() { return new ArrayList<>(profiles); }

    public synchronized boolean cullsGlass(int id) {
        return id > 0 && id < profiles.size() && profiles.get(id).kind() == 1 && profiles.get(id).cullShared();
    }

    public static int profileId(int material) { return (255 - (material >>> 24)) >>> SHIFT; }

    public void apply(int[] material, Identifier sprite, ResourceProvider resources) {
        int id = intern(read(sprite, resources));
        if (id == 0) return;
        for (int i = 0; i < material.length; i++) material[i] = LabPbr.withSourceFlag(material[i], id << SHIFT);
    }

    private static Profile read(Identifier sprite, ResourceProvider resources) {
        String path = sprite.getPath();
        String texture = path.substring(path.lastIndexOf('/') + 1);
        int kind = texture.contains("glass") ? 1
            : (texture.equals("water_still") || texture.equals("water_flow") || texture.equals("water_overlay")) ? 2
            : (texture.equals("ice") || texture.equals("packed_ice") || texture.equals("blue_ice") || texture.startsWith("frosted_ice_")) ? 3 : 0;
        Profile fallback = kind == 0 ? null : defaults(kind);
        if (fallback != null && kind == 1 && (path.equals("block/glass") || path.equals("glass")))
            fallback = new Profile(1.52f, 1f, 1f, 1f, 0.97f, 0.97f, 0.97f, 1, true);
        Identifier sidecar = sprite.withPath("textures/" + path + ".bedray.json");
        try {
            var resource = resources.getResource(sidecar);
            if (resource.isEmpty()) return fallback;
            try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (String key : json.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown field " + key);
                if (json.has("kind")) kind = switch (json.get("kind").getAsString()) {
                    case "glass" -> 1; case "water" -> 2; case "ice" -> 3; case "thin" -> 0;
                    default -> throw new IllegalArgumentException("kind must be glass, water, ice or thin");
                };
                Profile base = fallback != null && fallback.kind() == kind ? fallback : defaults(kind);
                float r = base.red(), g = base.green(), b = base.blue();
                if (json.has("transmittance")) {
                    var rgb = json.getAsJsonArray("transmittance");
                    if (rgb.size() != 3) throw new IllegalArgumentException("transmittance needs three channels");
                    r = scalar(rgb.get(0), 0f, 1f);
                    g = scalar(rgb.get(1), 0f, 1f);
                    b = scalar(rgb.get(2), 0f, 1f);
                }
                float diffuse = number(json, "diffuseLayer", base.diffuse(), 0f, 1f);
                if (kind == 2 && diffuse != 0f) throw new IllegalArgumentException("water diffuseLayer must be zero");
                if (json.has("cullSharedFaces") && (!json.get("cullSharedFaces").isJsonPrimitive()
                    || !json.get("cullSharedFaces").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException("cullSharedFaces must be boolean");
                return new Profile(number(json, "ior", base.ior(), 1f, 3f),
                    number(json, "thickness", base.thickness(), 0.0001f, 16f),
                    number(json, "measurementDistance", base.distance(), 0.0001f, 1024f),
                    diffuse, r, g, b, kind,
                    json.has("cullSharedFaces") ? json.get("cullSharedFaces").getAsBoolean() : base.cullShared());
            }
        } catch (Exception e) {
            warn("Invalid optical material " + sidecar + ": " + e.getMessage() + "; using automatic defaults");
            return fallback;
        }
    }

    private static Profile defaults(int kind) {
        return new Profile(kind == 2 ? 1.333f : kind == 3 ? 1.31f : 1.52f, 1f, 1f, 0f,
            kind == 2 ? 1f : 0.97f, kind == 2 ? 1f : 0.97f, kind == 2 ? 1f : 0.97f, kind, kind == 1);
    }

    private static float number(JsonObject json, String key, float fallback, float min, float max) {
        return json.has(key) ? scalar(json.get(key), min, max) : fallback;
    }

    private static float scalar(com.google.gson.JsonElement value, float min, float max) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("expected a number");
        return bounded(value.getAsFloat(), min, max);
    }

    private static float bounded(float value, float min, float max) {
        if (!Float.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("value outside " + min + ".." + max);
        return value == 0f ? 0f : value;
    }

    private static void warn(String text) { if (WARNINGS.getAndIncrement() < 8) LOG.warn("[RT-OPTICS] {}", text); }
}
