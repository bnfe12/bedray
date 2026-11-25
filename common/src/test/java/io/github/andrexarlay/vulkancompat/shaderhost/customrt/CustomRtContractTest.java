package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IncludeExpander;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles shaders through the real include/compile/reflection path and checks the custom RT contract. */
class CustomRtContractTest {

    private static Path example() {
        for (Path p : List.of(Path.of("..", "examples", "custom-rt-sky-shadow"), Path.of("examples", "custom-rt-sky-shadow"))) {
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("examples/custom-rt-sky-shadow not found");
    }

    private static Path mosaicExample() {
        for (Path p : List.of(Path.of("..", "examples", "custom-rt-mosaic"), Path.of("examples", "custom-rt-mosaic"))) {
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("examples/custom-rt-mosaic not found");
    }

    private static VulkanGlslPackCompiler.Compiled compile(Map<String, String> files, String entry,
                                                           VulkanGlslPackCompiler.Kind kind) throws Exception {
        IncludeExpander.Reader reader = e -> {
            String text = files.get(e);
            if (text == null) throw new java.io.IOException("missing " + e);
            return text;
        };
        return VulkanGlslPackCompiler.compile("shaders/", entry, reader, kind, Map.of());
    }

    @Test
    void shippedExampleSatisfiesTheContract() throws Exception {
        String source = Files.readString(example().resolve("shaders/bedray/rt.comp"), StandardCharsets.UTF_8);
        var compiled = compile(Map.of(CustomRtStage.ENTRY, source), CustomRtStage.ENTRY, VulkanGlslPackCompiler.Kind.COMPUTE);
        assertNull(CustomRtStage.validate(compiled), "example must pass the contract check");
        SpirvReflection.Module m = compiled.module();
        assertEquals(SpirvReflection.Stage.COMPUTE, m.stage());
        assertArrayEquals(new int[] {8, 8, 1}, m.localSize());
        assertEquals(List.of(
                SpirvReflection.DescriptorKind.ACCELERATION_STRUCTURE, SpirvReflection.DescriptorKind.UNIFORM_BUFFER,
                SpirvReflection.DescriptorKind.COMBINED_IMAGE_SAMPLER, SpirvReflection.DescriptorKind.STORAGE_IMAGE,
                SpirvReflection.DescriptorKind.COMBINED_IMAGE_SAMPLER),
            m.bindings().stream().map(SpirvReflection.Binding::kind).toList());
        assertTrue(m.capabilities().contains(4472), "ray query capability expected");
    }

    @Test
    void bindingOutsideTheContractIsRefused() throws Exception {
        String source = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(set = 0, binding = 3, rgba16f) uniform writeonly image2D bedrayOutput;
            layout(std430, set = 1, binding = 0) buffer Extra { vec4 data[]; } extra;
            void main() { imageStore(bedrayOutput, ivec2(gl_GlobalInvocationID.xy), extra.data[0]); }
            """;
        var compiled = compile(Map.of(CustomRtStage.ENTRY, source), CustomRtStage.ENTRY, VulkanGlslPackCompiler.Kind.COMPUTE);
        String problem = CustomRtStage.validate(compiled);
        assertNotNull(problem);
        assertTrue(problem.contains("set 1 binding 0"), problem);
    }

    @Test
    void pushConstantsAndExtraBindingsSatisfyTheExtendedContract() throws Exception {
        // The extended contract: a push-constant block plus pack-file-backed bindings 5..31.
        String source = """
            #version 460
            #extension GL_EXT_ray_query : require
            #extension GL_EXT_samplerless_texture_functions : require
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(push_constant) uniform Push { vec4 strength; float bias; } push;
            layout(set = 0, binding = 0) uniform accelerationStructureEXT scene;
            layout(set = 0, binding = 3, rgba16f) uniform writeonly image2D bedrayOutput;
            layout(std430, set = 0, binding = 5) buffer Params { vec4 data[]; } params;
            layout(set = 0, binding = 6) uniform sampler2D lut;
            layout(set = 0, binding = 7) uniform texture2D gradient;
            void main() {
                // every binding must be used: shaderc drops unused ones from the module
                vec4 v = params.data[0] + push.strength + texelFetch(lut, ivec2(0), 0) + texelFetch(gradient, ivec2(0), 0);
                imageStore(bedrayOutput, ivec2(gl_GlobalInvocationID.xy), v);
            }
            """;
        var compiled = compile(Map.of(CustomRtStage.ENTRY, source), CustomRtStage.ENTRY, VulkanGlslPackCompiler.Kind.COMPUTE);
        assertNull(CustomRtStage.validate(compiled));
        assertTrue(compiled.module().pushConstants());
        SpirvReflection.Binding params = compiled.module().bindings().stream().filter(b -> b.binding() == 5).findFirst().orElseThrow();
        assertEquals(SpirvReflection.DescriptorKind.STORAGE_BUFFER, params.kind());
        assertEquals(SpirvReflection.DescriptorKind.SAMPLED_IMAGE,
            compiled.module().bindings().stream().filter(b -> b.binding() == 7).findFirst().orElseThrow().kind());
    }

    @Test
    void mosaicExampleSatisfiesTheExtendedContract() throws Exception {
        Path pack = mosaicExample();
        String source = Files.readString(pack.resolve("shaders/bedray/rt.comp"), StandardCharsets.UTF_8);
        var compiled = compile(Map.of(CustomRtStage.ENTRY, source), CustomRtStage.ENTRY, VulkanGlslPackCompiler.Kind.COMPUTE);
        assertNull(CustomRtStage.validate(compiled), "extended-contract example must pass the contract check");
        assertTrue(compiled.module().pushConstants(), "the demo shader declares a push-constant block");

        Map<String, byte[]> userFiles = new java.util.HashMap<>();
        userFiles.put("bedray/rt.push", Files.readAllBytes(pack.resolve("shaders/bedray/rt.push")));
        userFiles.put("bedray/buffer5.bin", Files.readAllBytes(pack.resolve("shaders/bedray/buffer5.bin")));
        userFiles.put("bedray/texture6.png", Files.readAllBytes(pack.resolve("shaders/bedray/texture6.png")));
        CustomRtResources resources = CustomRtResources.build(compiled.module(), "shaders/",
            (name, what) -> {
                byte[] data = userFiles.get(name);
                if (data == null) throw new IllegalArgumentException("missing " + name + " (" + what + ")");
                return data;
            },
            null);

        assertEquals(1, resources.bufferSpecs().size());
        assertEquals(1, resources.textureSpecs().size());
        assertEquals(5, resources.bufferSpecs().get(0).binding());
        assertEquals(6, resources.textureSpecs().get(0).binding());
        assertTrue(resources.pushBytes() > 0);
        byte[] push = resources.push(128);
        assertNotNull(push);
        assertEquals(16, push.length, "push stays at the file's bytes, zero-filled on the GPU side");
        assertFalse(resources.refresh(), "a zip pack (null live root) has nothing to live-update");

        byte[] grow = java.util.Arrays.copyOf(userFiles.get("bedray/buffer5.bin"), 80);
        userFiles.put("bedray/buffer5.bin", grow);
        CustomRtResources grown = CustomRtResources.build(compiled.module(), "shaders/",
            (name, what) -> userFiles.get(name), null);
        assertEquals(1, grown.bufferSpecs().size(), "the grown file still builds its spec at bootstrap; the live cap is in CustomRtResources");
    }

    @Test
    void aCombinedSamplerExtraBindingIsAccepted() throws Exception {
        String source = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(set = 0, binding = 5) uniform sampler2D tint;
            layout(set = 0, binding = 3, rgba16f) uniform writeonly image2D bedrayOutput;
            void main() { imageStore(bedrayOutput, ivec2(gl_GlobalInvocationID.xy), texture(tint, vec2(0.5))); }
            """;
        var compiled = compile(Map.of(CustomRtStage.ENTRY, source), CustomRtStage.ENTRY, VulkanGlslPackCompiler.Kind.COMPUTE);
        assertNull(CustomRtStage.validate(compiled));
    }

    @Test
    void reservedAndArrayExtrasAreRefused() throws Exception {
        String source = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(set = 0, binding = 3, rgba16f) uniform writeonly image2D bedrayOutput;
            layout(std430, set = 0, binding = 2) buffer Clash { vec4 v[]; } clash;
            void main() { imageStore(bedrayOutput, ivec2(gl_GlobalInvocationID.xy), clash.v[0]); }
            """;
        var compiled = compile(Map.of(CustomRtStage.ENTRY, source), CustomRtStage.ENTRY, VulkanGlslPackCompiler.Kind.COMPUTE);
        String problem = CustomRtStage.validate(compiled);
        assertNotNull(problem);
        assertTrue(problem.contains("binding 2 is STORAGE_BUFFER") && problem.contains("requires COMBINED_IMAGE_SAMPLER"), problem);
    }

    @Test
    void storageImageExtrasWithoutAProducerAreRefused() throws Exception {
        String source = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(set = 0, binding = 3, rgba16f) uniform writeonly image2D bedrayOutput;
            layout(set = 0, binding = 7, rgba16f) uniform writeonly image2D scratch;
            void main() {
                imageStore(scratch, ivec2(gl_GlobalInvocationID.xy), vec4(1)); // used, or shaderc drops the binding
                imageStore(bedrayOutput, ivec2(gl_GlobalInvocationID.xy), vec4(0));
            }
            """;
        var compiled = compile(Map.of(CustomRtStage.ENTRY, source), CustomRtStage.ENTRY, VulkanGlslPackCompiler.Kind.COMPUTE);
        String problem = CustomRtStage.validate(compiled);
        assertNotNull(problem);
        assertTrue(problem.contains("no pack file producer"), problem);
    }

    @Test
    void includeFirstEntryAndRuntimeArrayAreReflected() throws Exception {
        // Vulkanite hit-shader shape: the entry is only an #include whose target carries #version.
        String lib = """
            #version 460
            #extension GL_EXT_ray_tracing : require
            #extension GL_EXT_nonuniform_qualifier : require
            layout(location = 0) rayPayloadInEXT vec4 payload;
            layout(set = 1, binding = 0) buffer Quads { vec4 v[]; } geometryBuffers[];
            void main() { payload = geometryBuffers[nonuniformEXT(gl_InstanceCustomIndexEXT)].v[gl_PrimitiveID]; }
            """;
        var compiled = compile(Map.of("shaders/hit.rchit", "#include \"/lib/hit.glsl\"\n", "shaders/lib/hit.glsl", lib),
            "shaders/hit.rchit", VulkanGlslPackCompiler.Kind.CLOSEST_HIT);
        SpirvReflection.Module m = compiled.module();
        assertEquals(SpirvReflection.Stage.CLOSEST_HIT, m.stage());
        assertEquals(1, m.bindings().size());
        SpirvReflection.Binding b = m.bindings().get(0);
        assertEquals(1, b.set());
        assertEquals(SpirvReflection.DescriptorKind.STORAGE_BUFFER, b.kind());
        assertTrue(b.runtimeArray());
        assertTrue(m.capabilities().contains(5302), "RuntimeDescriptorArray expected");
    }
}
