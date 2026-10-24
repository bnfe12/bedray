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
