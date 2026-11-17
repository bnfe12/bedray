package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisGlslTranslator;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPostExecutor;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A post-process shader written in Slang by the Mosaic SDK node editor, used instead of a shaderpack folder.
 *
 * <p>Mosaic compiles the shader with {@code slangc} and writes {@code <name>.post.vert.spv} / {@code .frag.spv} next to a
 * {@code mosaic_manifest.json}. This class reads that manifest, picks the asset marked active for POST_PROCESS, builds one
 * final {@link IrisPassGraph.Pass} straight from the SPIR-V (no GLSL, no pack scan) and hands it to the same
 * {@link IrisPostExecutor} that runs shaderpacks.
 *
 * <p>Contract with the shader (all in descriptor set 0, written by Mosaic into the manifest's {@code spirv} block):
 * entry point {@code main}; no vertex input, the vertex shader derives a fullscreen triangle from the vertex index;
 * {@code colortex0} at binding 0 and, only when the shader reads depth, {@code depthtex0} at binding 1 (combined image
 * samplers); one colour output at location 0. Parameters live in a std140 uniform buffer at binding
 * {@link IrisUniforms#BINDING_MOSAIC}: one float per parameter at byte offset {@code 4 * index}. {@link #parameterBytes()}
 * returns the current values every frame, so changing a preference never recompiles anything.
 *
 * <p>Opt-in: {@code -Dvkrtx.shader_host=iris -Dbedray.mosaic.post=true}; the manifest is
 * {@code <gameDir>/mosaic/shaders/mosaic_manifest.json} or {@code -Dbedray.mosaic.manifest=<file or folder>}. Replacing the
 * shader itself (another active asset, a recompiled SPIR-V) needs a restart in this milestone; parameter values do not.
 * Compile and unit-test evidence only; in-game execution is not verified.
 */
public final class MosaicPostSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-MOSAIC");

    /** System property that turns the source on. */
    public static final String PROPERTY = "bedray.mosaic.post";
    /** Optional manifest file or folder. */
    public static final String MANIFEST_PROPERTY = "bedray.mosaic.manifest";
    private static final long MAX_SPIRV_BYTES = 1L << 20;
    private static final long REFRESH_NANOS = 500_000_000L;

    /** One parameter in the constant buffer. */
    public record Param(String name, int offset, float value) {}

    /** What was loaded from the manifest. */
    public record Loaded(IrisPassGraph graph, String asset, Path manifest, List<Param> params, int bufferBytes,
                         String vertexFile, String fragmentFile, Set<Integer> samplerBindings) {}

    /** Exactly one of {@code loaded} and {@code error}; a recoverable error means nothing was chosen yet, not that something is broken. */
    public record Result(Loaded loaded, String error, boolean recoverable) {
        static Result ok(Loaded l) { return new Result(l, null, false); }
        static Result missing(String why) { return new Result(null, why, true); }
        static Result broken(String why) { return new Result(null, why, false); }
    }

    private static volatile Loaded current;
    private static volatile byte[] currentBytes;
    private static volatile long manifestStamp;
    private static volatile long lastCheck;

    private MosaicPostSource() {}

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** The manifest file: the property (a file or its folder), else {@code <gameDir>/mosaic/shaders/mosaic_manifest.json}. */
    public static Path manifestPath(Path gameDir) {
        String prop = System.getProperty(MANIFEST_PROPERTY);
        Path p = prop != null && !prop.isBlank() ? Path.of(prop) : gameDir.resolve("mosaic").resolve("shaders");
        return Files.isDirectory(p) ? p.resolve("mosaic_manifest.json") : p;
    }

    /** Reads the manifest and builds the pass graph. Never throws; the result says why when nothing could be built. */
    public static Result load(Path manifest) {
        try {
            if (!Files.isRegularFile(manifest)) return Result.missing("no Mosaic manifest at " + manifest);
            JsonObject root = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            JsonObject active = root.has("active") && root.get("active").isJsonObject() ? root.getAsJsonObject("active") : new JsonObject();
            if (!active.has("POST_PROCESS")) return Result.missing("no active post-process shader in the Mosaic manifest (pick one with Use in the library)");
            String slug = active.get("POST_PROCESS").getAsString();
            JsonObject asset = findAsset(root, slug);
            if (asset == null) return Result.broken("active shader " + slug + " is not listed in the manifest");
            String status = asset.has("status") ? asset.get("status").getAsString() : "";
            if (!status.equals("COMPILED")) return Result.broken("shader " + slug + " has not compiled (status " + (status.isEmpty() ? "unknown" : status) + ")");
            if (!asset.has("spirv") || !asset.get("spirv").isJsonObject()) return Result.broken("shader " + slug + " has no SPIR-V in the manifest");
            JsonObject spirv = asset.getAsJsonObject("spirv");
            if (!"main".equals(string(spirv, "entry", "main"))) return Result.broken("only entry point main is supported");

            String vertexFile = plainFileName(string(spirv, "vertex", null)), fragmentFile = plainFileName(string(spirv, "fragment", null));
            if (vertexFile == null || fragmentFile == null) return Result.broken("SPIR-V file names must be plain file names next to the manifest");
            Path dir = manifest.toAbsolutePath().getParent();
            ByteBuffer vertex = readSpirv(dir.resolve(vertexFile)), fragment = readSpirv(dir.resolve(fragmentFile));

            Set<Integer> samplers = new TreeSet<>();
            JsonObject images = spirv.has("sampledImages") && spirv.get("sampledImages").isJsonObject() ? spirv.getAsJsonObject("sampledImages") : new JsonObject();
            for (var e : images.entrySet()) {
                int binding = e.getValue().getAsInt();
                boolean color = e.getKey().equals("colortex0") && binding == IrisUniforms.BINDING_COLORTEX0;
                boolean depth = e.getKey().equals("depthtex0") && binding == IrisUniforms.BINDING_DEPTHTEX0;
                if (!color && !depth) return Result.broken("unsupported sampled image " + e.getKey() + " at binding " + binding);
                samplers.add(binding);
            }

            List<Param> params = new ArrayList<>();
            int bufferBytes = 0;
            if (spirv.has("paramBuffer") && spirv.get("paramBuffer").isJsonObject()) {
                JsonObject buffer = spirv.getAsJsonObject("paramBuffer");
                if (buffer.get("binding").getAsInt() != IrisUniforms.BINDING_MOSAIC || buffer.get("descriptorSet").getAsInt() != 0)
                    return Result.broken("the parameter buffer must be at set 0 binding " + IrisUniforms.BINDING_MOSAIC);
                bufferBytes = buffer.get("sizeBytes").getAsInt();
                if (bufferBytes < 0 || bufferBytes > IrisUniforms.MOSAIC_BYTES)
                    return Result.broken("parameter buffer is " + bufferBytes + " bytes, the host reserves " + IrisUniforms.MOSAIC_BYTES);
                Map<String, Float> values = currentValues(asset);
                for (JsonElement el : buffer.getAsJsonArray("fields")) {
                    JsonObject field = el.getAsJsonObject();
                    int offset = field.get("offset").getAsInt();
                    if (offset < 0 || offset % 4 != 0 || offset + 4 > bufferBytes) return Result.broken("parameter offset " + offset + " is outside the buffer");
                    String name = field.get("name").getAsString();
                    params.add(new Param(name, offset, values.getOrDefault(name, 0f)));
                }
            }

            IrisGlslTranslator.Result vertexMeta = meta(Set.of()), fragmentMeta = meta(samplers);
            IrisPassGraph.Pass pass = new IrisPassGraph.Pass("mosaic:" + slug, ProgramName.Family.FINAL, 0,
                vertexFile, fragmentFile, "", "", vertexMeta, fragmentMeta, vertex, fragment);
            IrisPassGraph graph = new IrisPassGraph("mosaic:" + slug, "shaders/", List.of(pass));
            IrisPostExecutor.validateGraph(graph);

            Loaded loaded = new Loaded(graph, slug, manifest, List.copyOf(params), bufferBytes, vertexFile, fragmentFile, Set.copyOf(samplers));
            current = loaded;
            currentBytes = bytes(loaded);
            manifestStamp = Files.getLastModifiedTime(manifest).toMillis();
            lastCheck = System.nanoTime();
            LOGGER.info("[VKB-MOSAIC] post shader {} loaded: {} parameter(s), {} sampled image(s)", slug, params.size(), samplers.size());
            return Result.ok(loaded);
        } catch (IOException | RuntimeException e) {
            return Result.broken("Mosaic manifest could not be used: " + e.getMessage());
        }
    }

    /**
     * The parameter buffer contents for this frame, or null when no Mosaic shader is loaded. Cheap: it re-reads the manifest
     * only when its modification time moved, at most twice a second, and only takes the parameter values from it.
     */
    public static byte[] parameterBytes() {
        Loaded l = current;
        if (l == null) return null;
        long now = System.nanoTime();
        if (now - lastCheck >= REFRESH_NANOS) {
            lastCheck = now;
            refreshValues(l);
        }
        return currentBytes;
    }

    private static void refreshValues(Loaded l) {
        try {
            long stamp = Files.getLastModifiedTime(l.manifest()).toMillis();
            if (stamp == manifestStamp) return;
            manifestStamp = stamp;
            JsonObject root = JsonParser.parseString(Files.readString(l.manifest())).getAsJsonObject();
            JsonObject asset = findAsset(root, l.asset());
            if (asset == null) return;
            Map<String, Float> values = currentValues(asset);
            List<Param> updated = new ArrayList<>();
            for (Param p : l.params()) updated.add(new Param(p.name(), p.offset(), values.getOrDefault(p.name(), p.value())));
            Loaded next = new Loaded(l.graph(), l.asset(), l.manifest(), List.copyOf(updated), l.bufferBytes(), l.vertexFile(), l.fragmentFile(), l.samplerBindings());
            currentBytes = bytes(next);
            current = next;
        } catch (IOException | RuntimeException e) {
            // a half-written manifest: keep the last good values and look again next time
        }
    }

    /** Drops the loaded shader (host teardown). */
    public static void clear() {
        current = null;
        currentBytes = null;
    }

    public static Loaded loaded() {
        return current;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static JsonObject findAsset(JsonObject root, String slug) {
        if (!root.has("assets")) return null;
        for (JsonElement e : root.getAsJsonArray("assets")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("name") && o.get("name").getAsString().equals(slug)) return o;
        }
        return null;
    }

    private static Map<String, Float> currentValues(JsonObject asset) {
        Map<String, Float> out = new java.util.HashMap<>();
        if (!asset.has("parameters")) return out;
        JsonArray list = asset.getAsJsonArray("parameters");
        for (JsonElement e : list) {
            JsonObject p = e.getAsJsonObject();
            if (p.has("name") && p.has("value")) out.put(p.get("name").getAsString(), p.get("value").getAsFloat());
        }
        return out;
    }

    static byte[] bytes(Loaded l) {
        ByteBuffer buf = ByteBuffer.allocate(Math.max(l.bufferBytes(), 16)).order(ByteOrder.LITTLE_ENDIAN);
        for (Param p : l.params()) buf.putFloat(p.offset(), p.value());
        return buf.array();
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    /** A bare file name: no separators, no parent references. */
    static String plainFileName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        return name;
    }

    private static ByteBuffer readSpirv(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("missing SPIR-V file " + file.getFileName());
        long size = Files.size(file);
        if (size < 20 || size > MAX_SPIRV_BYTES || size % 4 != 0) throw new IOException(file.getFileName() + " is not a SPIR-V module (" + size + " bytes)");
        byte[] raw = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(raw).flip();
        if (buf.getInt(0) != 0x07230203) throw new IOException(file.getFileName() + " does not start with the SPIR-V magic number");
        return buf;
    }

    /** Resource metadata for a synthetic pass: colour output 0, the given sampler bindings, no push, frame or world uniforms. */
    private static IrisGlslTranslator.Result meta(Set<Integer> samplerBindings) {
        return new IrisGlslTranslator.Result("", Set.of(), List.of(0), Map.of(), Set.of(), samplerBindings, Map.of(), Set.of());
    }
}
