package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Whole-body parity guards all live branches; only language/interface adaptations are normalized. */
class VelocityLiveShaderTest {
    private static String read(String path) throws IOException {
        return Files.readString(Path.of("src/main/resources/shaders/" + path));
    }
    private static String canonical(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "").replaceAll("\\s+", "");
    }
    @Test void preservesTheEntireLiveReprojectionBodyIncludingEntityAndDepthValidation() throws IOException {
        String glsl = read("glsl/velocity_reproject.comp"), slang = read("slang/history/velocity_live.slang");
        String expected = glsl.substring(glsl.indexOf("    // The projection was jittered"), glsl.indexOf("    imageStore(outMotionVectors"));
        String[][] types = {{"ivec2", "int2"}, {"uvec2", "uint2"}, {"vec2", "float2"}, {"vec3", "float3"}, {"vec4", "float4"}, {"mat4", "float4x4"}};
        for (var type : types) expected = expected.replace(type[0], type[1]);
        expected = expected.replace("texture(depthTexture, texelUv)", "depthTexture.SampleLevel(texelUv, 0.0)");
        String[][] products = {{"invViewProj", "float4(ndc, 0.5, 1.0)"}, {"invViewProj", "float4(0.0, 0.0, 0.5, 1.0)"},
            {"invViewProj", "float4(ndc.x, ndc.y, depth, 1.0)"}, {"prevViewProj", "float4(rayDir, 0.0)"},
            {"invCurr", "float4(worldPosRel, 1.0)"}, {"entityPrevTransform", "localPos"},
            {"prevViewProj", "float4(prevPosRel.xyz, 1.0)"}, {"prevViewProj", "prevPosRel"}};
        for (var product : products) expected = expected.replace(product[0] + " * " + product[1], "mul(" + product[0] + ", " + product[1] + ")");
        for (String matrix : new String[]{"entityCurrTransform", "entityPrevTransform"})
            expected = expected.replace(matrix + "[3].xyz", "float3(" + matrix + "[0][3], " + matrix + "[1][3], " + matrix + "[2][3])");
        expected = expected.replace("MotionBuf(entityMotion.xy).v[", "((float4*)velocityLiveAddress(entityMotion.xy))[")
            .replace("DepthBuf(prevDepth.xy).v[", "((float*)velocityLiveAddress(prevDepth.xy))[");
        String actual = slang.substring(slang.indexOf("    // The projection was jittered"), slang.lastIndexOf("    if (entityMotion.z"));
        assertEquals(canonical(expected), canonical(actual), "every live arithmetic operation/condition must survive the port");
        assertTrue(slang.contains("result: $$float4x4 = OpExtInst glsl450 MatrixInverse $matrix"));
        assertTrue(slang.contains("((float*)velocityLiveAddress(entityMotion.zw))[pixelCoord.y * size.x + pixelCoord.x] = disocclusionMask;"));
        assertTrue(slang.contains("return float4(motionVector, dynamicObjectMask, disocclusionMask);"));
    }
    @Test void liveEntryKeepsRg16fAndDoesNotSelectTheTypedVelocityContract() throws IOException {
        String source = read("slang/passes/velocity_reproject_live.slang");
        assertTrue(source.contains("vk::image_format(\"rg16f\")"));
        assertTrue(source.contains("RWTexture2D<float2> outMotionVectors"));
        assertTrue(source.contains("ConstantBuffer<VelocityLiveUniforms> frame"));
        assertFalse(source.contains("history/velocity.slang"));
        assertFalse(source.contains("FrameConstants"));
    }
    @Test void compiledManifestKeepsThe368ByteThreeBindingContract() {
        Assumptions.assumeTrue(SlangPassLibrary.available("velocity_reproject_live"), "live velocity AOT entry was not compiled");
        var manifest = SlangPassLibrary.load("velocity_reproject_live").orElseThrow().manifest();
        assertEquals(0, manifest.pushConstantBytes()); assertEquals(3, manifest.bindings().size());
        assertArrayEquals(new int[]{8, 8, 1}, manifest.threadGroupSize());
        assertEquals(0, manifest.binding("depthTexture").binding());
        assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, manifest.binding("depthTexture").descriptorType());
        assertEquals(1, manifest.binding("outMotionVectors").binding());
        assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE, manifest.binding("outMotionVectors").descriptorType());
        assertEquals(2, manifest.binding("frame").binding());
        assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER, manifest.binding("frame").descriptorType());
        assertEquals(368, manifest.binding("frame").elementBytes());
    }
}
