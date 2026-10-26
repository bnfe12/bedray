package io.github.andrexarlay.vulkancompat.rt.slang;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Descriptor contract of one AOT-compiled Slang entry pass, as produced by the Gradle {@code compileSlang} task
 * ({@code gradle/slang.gradle}) from Slang's reflection JSON.
 *
 * <p>The shader is the source of truth: this record is parsed from the generated manifest, never hand-written, so a
 * shader edit cannot silently desynchronise the host from the pipeline layout. The Java side addresses bindings by
 * name ({@link Binding#name()}), so moving a binding number in the shader does not break the caller.
 *
 * <p>ABI v2 has one descriptor set, one descriptor per binding and compute-only programs; every rule below is
 * enforced here, at load time, before the native core sees the program.
 */
public record SlangPassManifest(int schema, String name, String source, String sourceSha256, String entryPoint,
                                String stage, String profile, int descriptorSet, int[] threadGroupSize,
                                int pushConstantBytes, int spirvWordCount, int requiredCapabilities,
                                List<String> spirvCapabilities, List<Binding> bindings) {
    /** Schema version this loader understands. */
    public static final int SCHEMA = 1;

    /** One descriptor binding: Slang parameter name, binding number, {@code VkDescriptorType}, descriptor count and
     * buffer element size (0 for images and acceleration structures). */
    public record Binding(String name, int binding, int descriptorType, int count, int elementBytes) {
    }

    public SlangPassManifest {
        threadGroupSize = threadGroupSize.clone();
        spirvCapabilities = List.copyOf(spirvCapabilities);
        bindings = List.copyOf(bindings);
        if (schema != SCHEMA) {
            throw new IllegalArgumentException(name + ": manifest schema " + schema + " != " + SCHEMA);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("manifest has no pass name");
        }
        if (entryPoint == null || entryPoint.isBlank()) {
            throw new IllegalArgumentException(name + ": manifest has no entry point");
        }
        if (!"compute".equals(stage)) {
            throw new IllegalArgumentException(name + ": ABI v2 registers compute programs, manifest says " + stage);
        }
        if (descriptorSet != 0) {
            throw new IllegalArgumentException(name + ": ABI v2 has one descriptor set, manifest says " + descriptorSet);
        }
        if (threadGroupSize.length != 3) {
            throw new IllegalArgumentException(name + ": threadGroupSize must have three components");
        }
        for (int size : threadGroupSize) {
            if (size <= 0) {
                throw new IllegalArgumentException(name + ": threadGroupSize components must be positive");
            }
        }
        if (pushConstantBytes < 0 || pushConstantBytes > 128) {
            // 128 is the Vulkan minimum guaranteed maxPushConstantsSize; the core rejects larger layouts anyway.
            throw new IllegalArgumentException(name + ": pushConstantBytes " + pushConstantBytes + " is outside 0..128");
        }
        if (spirvWordCount <= 0) {
            throw new IllegalArgumentException(name + ": spirvWordCount must be positive");
        }
        if ((requiredCapabilities & ~BedrayCoreLayouts.CAP_ALL) != 0) {
            throw new IllegalArgumentException(name + ": requiredCapabilities 0x"
                + Integer.toHexString(requiredCapabilities) + " contains bits this ABI does not define");
        }
        Set<Integer> seen = new HashSet<>();
        for (Binding binding : bindings) {
            if (!seen.add(binding.binding())) {
                throw new IllegalArgumentException(name + ": binding " + binding.binding() + " is declared twice");
            }
            if (binding.count() != 1) {
                throw new IllegalArgumentException(name + ": ABI v2 supports one descriptor per binding, " + binding.name()
                    + " wants " + binding.count());
            }
            if (binding.elementBytes() < 0) {
                throw new IllegalArgumentException(name + ": binding '" + binding.name() + "' has negative element size");
            }
            // Throws for a type ABI v2 cannot register.
            BedrayCoreLayouts.descriptorTypeName(binding.descriptorType());
        }
    }

    @Override
    public int[] threadGroupSize() {
        return threadGroupSize.clone();
    }

    /** Dispatch group count that covers {@code pixels} along {@code axis} (0 = x, 1 = y, 2 = z). */
    public int groups(int pixels, int axis) {
        if (pixels <= 0) {
            throw new IllegalArgumentException("dispatch extent must be positive");
        }
        int size = threadGroupSize[axis];
        return (int) (((long) pixels + size - 1L) / size);
    }

    /** Binding by Slang parameter name, or {@code null}. */
    public Binding binding(String parameter) {
        for (Binding binding : bindings) {
            if (binding.name().equals(parameter)) {
                return binding;
            }
        }
        return null;
    }

    static SlangPassManifest parse(JsonObject json, String fallbackName) {
        String name = string(json, "name", fallbackName);
        JsonArray group = array(json, "threadGroupSize");
        int[] threadGroupSize = {1, 1, 1};
        for (int i = 0; i < Math.min(3, group.size()); i++) {
            threadGroupSize[i] = group.get(i).getAsInt();
        }
        List<Binding> bindings = new ArrayList<>();
        for (JsonElement element : array(json, "bindings")) {
            JsonObject binding = element.getAsJsonObject();
            bindings.add(new Binding(
                string(binding, "name", ""),
                binding.get("binding").getAsInt(),
                BedrayCoreLayouts.descriptorTypeId(string(binding, "descriptorType", "")),
                binding.has("count") ? binding.get("count").getAsInt() : 1,
                binding.has("elementBytes") ? binding.get("elementBytes").getAsInt() : 0));
        }
        List<String> spirvCapabilities = new ArrayList<>();
        for (JsonElement element : array(json, "spirvCapabilities")) {
            spirvCapabilities.add(element.getAsString());
        }
        return new SlangPassManifest(
            json.has("schema") ? json.get("schema").getAsInt() : 0,
            name,
            string(json, "source", ""),
            string(json, "sourceSha256", ""),
            string(json, "entryPoint", "main"),
            string(json, "stage", "compute"),
            string(json, "profile", ""),
            json.has("descriptorSet") ? json.get("descriptorSet").getAsInt() : 0,
            threadGroupSize,
            json.has("pushConstantBytes") ? json.get("pushConstantBytes").getAsInt() : 0,
            json.has("spirvWordCount") ? json.get("spirvWordCount").getAsInt() : 0,
            json.has("requiredCapabilities") ? json.get("requiredCapabilities").getAsInt() : 0,
            spirvCapabilities,
            bindings);
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonArray() ? new JsonArray() : value.getAsJsonArray();
    }
}
