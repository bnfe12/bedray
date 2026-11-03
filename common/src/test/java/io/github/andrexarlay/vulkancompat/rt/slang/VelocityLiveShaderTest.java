package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Whole-body guards on the live branches; the GLSL original this was ported from is deleted. */
class VelocityLiveShaderTest {
    private static String read(String path) throws IOException {
        return Files.readString(Path.of("src/main/resources/shaders/" + path));
    }

    /**
     * The live reprojection is the one velocity shader the upscalers run, so its arithmetic must not be edited into a
     * different formula by accident: the anchors below are the branches that decide depth, entity motion and the
     * disocclusion mask, and the needles are the operations that have no equivalent spelling in the typed contract.
     */
    @Test void preservesTheEntireLiveReprojectionBodyIncludingEntityAndDepthValidation() throws IOException {
        String slang = read("slang/history/velocity_live.slang");
        int start = slang.indexOf("    // The projection was jittered");
        int end = slang.lastIndexOf("    if (entityMotion.z");
        assertTrue(start >= 0 && end > start, "the jittered-projection and entity-mask branches both still open the body");
        String body = slang.substring(start, end);
        for (String operation : new String[] {"depthTexture.SampleLevel(texelUv, 0.0)",
            "mul(invViewProj, float4(ndc, 0.5, 1.0))", "mul(prevViewProj, float4(rayDir, 0.0))",
            "inverse(entityCurrTransform)", "mul(entityPrevTransform, localPos)",
            "((float4*)velocityLiveAddress(entityMotion.xy))", "((float*)velocityLiveAddress(prevDepth.xy))"}) {
            assertTrue(body.contains(operation), "the live body lost " + operation);
        }
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
