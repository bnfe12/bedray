package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPass;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Iris depth bridge was the last first-party runtime GLSL compile: it read {@code iris_depth.comp} and pushed it
 * through Shaderc while the bridge held a device. The pass now ships AOT like every renderer pass; these tests pin the
 * seam so a later edit cannot quietly bring the runtime compile back, and that the built artifact still matches the
 * push block and dispatch geometry the bridge hard-codes.
 */
class IrisDepthAotTest {
    private static final Path CONVERTER = Path.of("src", "main", "java",
        "io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisDepthConverter.java");
    private static final Path SHADER = Path.of("src", "main", "resources", "shaders", "slang", "passes",
        "iris_depth.slang");

    @Test
    void theBridgeLoadsTheAotPassInsteadOfCompilingGlsl() throws IOException {
        String source = Files.readString(CONVERTER);
        assertTrue(source.contains("SlangPass.load(\"iris_depth\")"),
            "the bridge must borrow the AOT artifact; a Shaderc compile here needs the compiler on every player box");
        assertTrue(source.contains("requireManifest(pass.manifest())"),
            "the bridge writes a fixed push block and dispatch grid, so the artifact is checked before first use");
        assertTrue(source.contains("pass.spirv()"), "the module is created from the AOT words");
        assertTrue(source.contains("SlangPassDriverGate.blocker(\"iris_depth\", pass.spirv())"),
            "the module meets the same driver gate as every pass the core registers");
        assertFalse(source.contains("SpirvCompiler"), "no runtime GLSL compile in the Iris depth bridge");
        assertFalse(source.contains("shaderc"), "no Shaderc reference in the Iris depth bridge");
        assertFalse(source.contains("iris_depth.comp"), "the GLSL source is gone, not merely unused");
    }

    @Test
    void theLegacyDepthShaderIsDeleted() {
        assertNull(IrisDepthConverter.class.getResource("/shaders/glsl/iris_depth.comp"),
            "iris_depth.comp must leave the jar; the artifact is /shaders/slang/spv/iris_depth.spv");
        assertFalse(Files.exists(Path.of("src", "main", "resources", "shaders", "glsl", "iris_depth.comp")),
            "the GLSL source leaves the tree with it");
    }

    @Test
    void theShaderPinsTheBridgeContract() throws IOException {
        String shader = Files.readString(SHADER);
        // Push block: float4x4 + four float4 = the 128 bytes the bridge writes with putFloat offsets.
        assertTrue(shader.contains("float4x4 inverseProjection;"), "inverse projection is the first 64 bytes");
        assertTrue(shader.contains("float4 inverseView0;"), "column 0 carries the depth encoding in .w");
        assertTrue(shader.contains("float4 inverseView1;"));
        assertTrue(shader.contains("float4 inverseView2;"));
        assertTrue(shader.contains("float4 parameters;"), "near/far/scale/bias end the 128-byte block");
        assertTrue(shader.contains("[[vk::push_constant]]"));
        assertTrue(shader.contains("[[vk::binding(0, 0)]] Sampler2D rasterDepth;"),
            "binding 0 is the combined raster depth sampler");
        assertTrue(shader.contains("[[vk::image_format(\"r32f\")]] [[vk::binding(1, 0)]] RWTexture2D<float> irisDepth;"),
            "binding 1 is the r32f storage image the bridge views as Iris depth");
        assertTrue(shader.contains("[numthreads(8, 8, 1)]"), "the bridge dispatches (w+7)/8 x (h+7)/8");
        // Every encoding branch of the GLSL conversion survives.
        assertTrue(shader.contains("encoding == 0"), "...");
        assertTrue(shader.contains("encoding == 1"));
        assertTrue(shader.contains("encoding == 3"));
        assertTrue(shader.contains("depth * pc.parameters.z + pc.parameters.w"), "hardware-to-GL scale and bias");
        assertTrue(shader.contains("farPlane / (farPlane - nearPlane)"), "the linear-to-window mapping");
        // GLSL mat3(a, b, c) builds columns; Slang's float3x3 would build rows, so the port spells the sum out.
        assertTrue(shader.contains("pc.inverseView0.xyz * ray.x + pc.inverseView1.xyz * ray.y + pc.inverseView2.xyz * ray.z"),
            "the inverse-view transform keeps the GLSL column convention");
    }

    @Test
    void theBuiltArtifactMatchesTheBridge() {
        Optional<SlangPass> loaded = SlangPass.load("iris_depth");
        Assumptions.assumeTrue(loaded.isPresent(), "no Slang SDK was available to the build");
        SlangPass pass = loaded.get();
        assertDoesNotThrow(() -> IrisDepthConverter.requireManifest(pass.manifest()));
        assertEquals(128, pass.manifest().pushConstantBytes());
        assertEquals(2, pass.manifest().bindings().size());
    }

    @Test
    void aManifestThatDisagreesWithTheBridgeIsRefused() {
        assertDoesNotThrow(() -> IrisDepthConverter.requireManifest(manifest(128, 8, 8,
            BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)));

        assertThrows(IllegalArgumentException.class,
            () -> IrisDepthConverter.requireManifest(manifest(120, 8, 8,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)),
            "the bridge always writes 128 bytes; a shorter block would read past the artifact's push");
        assertThrows(IllegalArgumentException.class,
            () -> IrisDepthConverter.requireManifest(manifest(128, 16, 16,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)),
            "the dispatch divides by eight");
        assertThrows(IllegalArgumentException.class,
            () -> IrisDepthConverter.requireManifest(manifest(128, 8, 8,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)),
            "slot 0 is bound as a combined image sampler");
        assertThrows(IllegalArgumentException.class,
            () -> IrisDepthConverter.requireManifest(manifest(128, 8, 8,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER)),
            "slot 1 is written as a storage image");
    }

    private static SlangPassManifest manifest(int pushBytes, int threadsX, int threadsY, int depthType,
                                              int outputType) {
        return new SlangPassManifest(1, "iris_depth", "passes/iris_depth.slang", "", "main", "compute",
            "cs_6_5", 0, new int[]{threadsX, threadsY, 1}, pushBytes, 64, 0, List.of(),
            List.of(new SlangPassManifest.Binding("rasterDepth", 0, depthType, 1, 0),
                new SlangPassManifest.Binding("irisDepth", 1, outputType, 1, 0)));
    }
}
