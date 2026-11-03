package io.github.andrexarlay.vulkancompat.rt.slang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the AOT Slang artifacts produced by {@code gradle/slang.gradle} from the mod resources:
 * {@code /shaders/slang/manifest/<name>.json} and {@code /shaders/slang/spv/<name>.spv}.
 *
 * <p>A missing index or an incomplete pass is a broken new-renderer build, not a reason to load legacy shaders.
 */
public final class SlangPassLibrary {
    public static final String MANIFEST_ROOT = "/shaders/slang/manifest/";
    public static final String SPIRV_ROOT = "/shaders/slang/spv/";

    private SlangPassLibrary() {
    }

    /** Names of every pass in the generated index, in build order. */
    public static List<String> names() {
        JsonObject index = readJson(MANIFEST_ROOT + "index.json");
        if (index == null) {
            throw new IllegalStateException("missing Slang pass index: " + MANIFEST_ROOT + "index.json");
        }
        List<String> names = new ArrayList<>();
        JsonElement passes = index.get("passes");
        if (passes == null || !passes.isJsonArray() || passes.getAsJsonArray().isEmpty()) {
            throw new IllegalStateException("Slang pass index has no entries");
        }
        for (JsonElement element : passes.getAsJsonArray()) {
            names.add(element.getAsString());
        }
        return List.copyOf(names);
    }

    public static boolean available(String name) {
        return SlangPassLibrary.class.getResource(MANIFEST_ROOT + name + ".json") != null
            && SlangPassLibrary.class.getResource(SPIRV_ROOT + name + ".spv") != null;
    }

    /** Loads one pass; empty when it was not compiled into this jar. */
    public static Optional<SlangPass> load(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pass name must not be blank");
        }
        if (!available(name)) {
            return Optional.empty();
        }
        JsonObject json = readJson(MANIFEST_ROOT + name + ".json");
        if (json == null) {
            throw new IllegalStateException("manifest for pass '" + name + "' disappeared while loading");
        }
        SlangPassManifest manifest = SlangPassManifest.parse(json, name);
        if (!manifest.name().equals(name)) {
            throw new IllegalStateException("manifest '" + name + "' declares pass '" + manifest.name() + "'");
        }
        return Optional.of(new SlangPass(manifest, readBytes(SPIRV_ROOT + name + ".spv")));
    }

    private static JsonObject readJson(String resource) {
        byte[] bytes = readBytesOrNull(resource);
        if (bytes == null) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException(resource + " is not a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static byte[] readBytes(String resource) {
        byte[] bytes = readBytesOrNull(resource);
        if (bytes == null) {
            throw new IllegalStateException("missing resource " + resource);
        }
        return bytes;
    }

    private static byte[] readBytesOrNull(String resource) {
        try (InputStream in = SlangPassLibrary.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
