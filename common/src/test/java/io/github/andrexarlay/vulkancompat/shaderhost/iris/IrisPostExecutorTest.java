package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for {@link IrisPostExecutor}: struct creation, binding layout, push constant packing,
 * execution plan sequencing, and graph validation.
 */
class IrisPostExecutorTest {

    private Path findTinyTestpack() {
        Path p = Path.of("src", "test", "resources", "testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        p = Path.of("..", "testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        p = Path.of("testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        throw new IllegalStateException("tiny-testpack not found at ../testpacks/tiny-testpack or testpacks/tiny-testpack");
    }

    @Test
    void pushConstantsPackingLayout() {
        int width = 1920;
        int height = 1080;
        float frameTimeCounter = 12.5f;
        int frameCounter = 42;
        float near = 0.05f;
        float far = 256.0f;
        float frameTime = 0.0166f;

        ByteBuffer buf = IrisPostExecutor.packPushConstants(width, height, frameTimeCounter, frameCounter, near, far, frameTime);
        assertNotNull(buf);
        assertEquals(IrisUniforms.PUSH_BYTES, buf.remaining());

        buf.order(ByteOrder.nativeOrder());
        assertEquals(1920.0f, buf.getFloat(0), 1e-6f, "viewWidth at offset 0");
        assertEquals(1080.0f, buf.getFloat(4), 1e-6f, "viewHeight at offset 4");
        assertEquals(1920.0f / 1080.0f, buf.getFloat(8), 1e-6f, "aspectRatio at offset 8");
        assertEquals(12.5f, buf.getFloat(12), 1e-6f, "frameTimeCounter at offset 12");
        assertEquals(42, buf.getInt(16), "frameCounter at offset 16");
        assertEquals(0.05f, buf.getFloat(20), 1e-6f, "near at offset 20");
        assertEquals(256.0f, buf.getFloat(24), 1e-6f, "far at offset 24");
        assertEquals(0.0166f, buf.getFloat(28), 1e-6f, "frameTime at offset 28");

        // Edge case: zero height defaults aspectRatio to 1.0
        ByteBuffer zeroH = IrisPostExecutor.packPushConstants(100, 0, 0.0f, 0, 0.05f, 100.0f, 0.0f);
        assertEquals(1.0f, zeroH.getFloat(8), 1e-6f);
    }

    @Test
    void descriptorSetLayoutBindings() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = IrisPostExecutor.createDescriptorBindings(stack);
            assertEquals(IrisUniforms.SAMPLER_BINDINGS + 1, bindings.remaining(), "colortex0..15 and depthtex0 samplers plus the frame uniform buffer");

            VkDescriptorSetLayoutBinding b0 = bindings.get(0);
            assertEquals(IrisUniforms.BINDING_COLORTEX0, b0.binding(), "Binding 0 index");
            assertEquals(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, b0.descriptorType(), "Binding 0 type");
            assertEquals(1, b0.descriptorCount(), "Binding 0 count");
            assertTrue((b0.stageFlags() & VK10.VK_SHADER_STAGE_VERTEX_BIT) != 0, "Binding 0 vertex stage");
            assertTrue((b0.stageFlags() & VK10.VK_SHADER_STAGE_FRAGMENT_BIT) != 0, "Binding 0 fragment stage");

            VkDescriptorSetLayoutBinding b1 = bindings.get(1);
            assertEquals(IrisUniforms.BINDING_DEPTHTEX0, b1.binding(), "Binding 1 index");
            assertEquals(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, b1.descriptorType(), "Binding 1 type");
            assertEquals(1, b1.descriptorCount(), "Binding 1 count");
            assertTrue((b1.stageFlags() & VK10.VK_SHADER_STAGE_VERTEX_BIT) != 0, "Binding 1 vertex stage");
            assertTrue((b1.stageFlags() & VK10.VK_SHADER_STAGE_FRAGMENT_BIT) != 0, "Binding 1 fragment stage");
        }
    }

    @Test
    void pushConstantRange() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer ranges = IrisPostExecutor.createPushConstantRange(stack);
            assertEquals(1, ranges.remaining());

            VkPushConstantRange r = ranges.get(0);
            assertEquals(0, r.offset(), "Push constant offset 0");
            assertEquals(IrisUniforms.PUSH_BYTES, r.size(), "Push constant size 32 bytes");
            assertTrue((r.stageFlags() & VK10.VK_SHADER_STAGE_VERTEX_BIT) != 0, "Push vertex stage");
            assertTrue((r.stageFlags() & VK10.VK_SHADER_STAGE_FRAGMENT_BIT) != 0, "Push fragment stage");
        }
    }

    @Test
    void colorBlendStateConfiguration() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineColorBlendStateCreateInfo blend = IrisPostExecutor.createColorBlendState(stack);
            assertNotNull(blend);
            assertFalse(blend.logicOpEnable(), "Logic op disabled");

            VkPipelineColorBlendAttachmentState.Buffer atts = blend.pAttachments();
            assertNotNull(atts);
            assertEquals(1, atts.remaining(), "One color attachment");

            VkPipelineColorBlendAttachmentState att = atts.get(0);
            assertFalse(att.blendEnable(), "Overwrite blend (blendEnable = false)");
            int expectedMask = VK10.VK_COLOR_COMPONENT_R_BIT
                | VK10.VK_COLOR_COMPONENT_G_BIT
                | VK10.VK_COLOR_COMPONENT_B_BIT
                | VK10.VK_COLOR_COMPONENT_A_BIT;
            assertEquals(expectedMask, att.colorWriteMask(), "RGBA write mask");
        }
    }

    @Test
    void rasterizationStateConfiguration() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineRasterizationStateCreateInfo raster = IrisPostExecutor.createRasterizationState(stack);
            assertNotNull(raster);
            assertEquals(VK10.VK_CULL_MODE_NONE, raster.cullMode(), "Cull mode none for fullscreen triangle");
            assertEquals(VK10.VK_POLYGON_MODE_FILL, raster.polygonMode(), "Polygon mode fill");
            assertFalse(raster.depthClampEnable(), "Depth clamp false");
            assertFalse(raster.rasterizerDiscardEnable(), "Rasterizer discard false");
            assertEquals(1.0f, raster.lineWidth(), 1e-6f);
        }
    }

    @Test
    void dynamicRenderingCreateInfo() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineRenderingCreateInfo info = IrisPostExecutor.createPipelineRenderingCreateInfo(stack, VK10.VK_FORMAT_R8G8B8A8_UNORM);
            assertNotNull(info);
            assertEquals(1, info.colorAttachmentCount(), "1 color attachment format");
            assertEquals(VK10.VK_FORMAT_R8G8B8A8_UNORM, info.pColorAttachmentFormats().get(0));
        }
    }

    @Test
    void planPassExecutionTinyTestpack() throws IOException {
        Path packDir = findTinyTestpack();
        IrisPassGraph.ParseResult res = IrisPassGraph.build(packDir, ScanLimits.DEFAULT);
        assertTrue(res.success());
        IrisPassGraph graph = res.graph();

        List<IrisPostExecutor.PassExecutionStep> plan = IrisPostExecutor.planPassExecution(graph, 0);
        assertEquals(2, plan.size(), "tiny-testpack has 2 passes");

        // Pass 0: composite toggles the physical bank of logical colortex0.
        IrisPostExecutor.PassExecutionStep step0 = plan.get(0);
        assertEquals(0, step0.passIndex());
        assertEquals("composite", step0.passName());
        assertFalse(step0.isFinal());
        assertEquals("colortex0[0]", step0.readTarget());
        assertEquals("colortex0[1]", step0.writeTarget());
        assertEquals(0, step0.slot());
        assertEquals(0, step0.setIndex());

        // Final reads the current logical color and writes separate presentation scratch.
        IrisPostExecutor.PassExecutionStep step1 = plan.get(1);
        assertEquals(1, step1.passIndex());
        assertEquals("final", step1.passName());
        assertTrue(step1.isFinal());
        assertEquals("colortex0[1]", step1.readTarget());
        assertEquals("presentation", step1.writeTarget());
        assertEquals(0, step1.slot());
        assertEquals(1, step1.setIndex());
    }

    @Test
    void planPassExecutionMultiCompositeFlipping() {
        // Construct a 4-pass mock graph: composite0, composite1, composite2, final
        IrisPassGraph.Pass p0 = mockPass("composite", ProgramName.Family.COMPOSITE, 0);
        IrisPassGraph.Pass p1 = mockPass("composite1", ProgramName.Family.COMPOSITE, 1);
        IrisPassGraph.Pass p2 = mockPass("composite2", ProgramName.Family.COMPOSITE, 2);
        IrisPassGraph.Pass p3 = mockPass("final", ProgramName.Family.FINAL, 3);

        IrisPassGraph graph = new IrisPassGraph("mock", "shaders/", List.of(p0, p1, p2, p3));
        List<IrisPostExecutor.PassExecutionStep> plan = IrisPostExecutor.planPassExecution(graph, 1);
        assertEquals(4, plan.size());

        // Pass 0: read colortex0, write colortex1
        assertEquals("colortex0[0]", plan.get(0).readTarget());
        assertEquals("colortex0[1]", plan.get(0).writeTarget());
        assertFalse(plan.get(0).isFinal());

        // Pass 1: read colortex1, write colortex0
        assertEquals("colortex0[1]", plan.get(1).readTarget());
        assertEquals("colortex0[0]", plan.get(1).writeTarget());
        assertFalse(plan.get(1).isFinal());

        // Pass 2: read colortex0, write colortex1
        assertEquals("colortex0[0]", plan.get(2).readTarget());
        assertEquals("colortex0[1]", plan.get(2).writeTarget());
        assertFalse(plan.get(2).isFinal());

        // Final reads bank1 of logical color0 and writes presentation scratch.
        assertEquals("colortex0[1]", plan.get(3).readTarget());
        assertEquals("presentation", plan.get(3).writeTarget());
        assertTrue(plan.get(3).isFinal());
    }

    @Test
    void validateGraphAcceptsTinyTestpack() throws IOException {
        Path packDir = findTinyTestpack();
        IrisPassGraph.ParseResult res = IrisPassGraph.build(packDir, ScanLimits.DEFAULT);
        assertTrue(res.success());

        assertDoesNotThrow(() -> IrisPostExecutor.validateGraph(res.graph()));
    }

    @Test
    void validateGraphRejectsEmptyOrNullBytecode() {
        assertThrows(NullPointerException.class, () -> IrisPostExecutor.validateGraph(null));

        IrisPassGraph emptyGraph = new IrisPassGraph("empty", "shaders/", List.of());
        assertThrows(IllegalArgumentException.class, () -> IrisPostExecutor.validateGraph(emptyGraph));

        // Pass with null SPIR-V
        IrisPassGraph.Pass badPass = new IrisPassGraph.Pass(
            "bad", ProgramName.Family.COMPOSITE, 0,
            "bad.vsh", "bad.fsh", "", "",
            null, null, null, null
        );
        IrisPassGraph badGraph = new IrisPassGraph("bad", "shaders/", List.of(badPass));
        assertThrows(IllegalArgumentException.class, () -> IrisPostExecutor.validateGraph(badGraph));
    }

    @Test
    void recordArgumentValidation() throws IOException {
        Path packDir = findTinyTestpack();
        IrisPassGraph.ParseResult res = IrisPassGraph.build(packDir, ScanLimits.DEFAULT);
        IrisPassGraph graph = res.graph();

        IrisPostExecutor exec = IrisPostExecutor.createForTesting();

        // null cmd
        assertThrows(IllegalArgumentException.class, () ->
            exec.record(null, graph, 1L, 2L, 3L, 1920, 1080, 0f, 0, 0.05f, 256f, 0f));

        // invalid dimensions on ensure
        assertThrows(IllegalArgumentException.class, () -> exec.ensure(0, 100));
        assertThrows(IllegalArgumentException.class, () -> exec.ensure(100, -5));
    }

    private static IrisPassGraph.Pass mockPass(String name, ProgramName.Family family, int order) {
        ByteBuffer dummySpv = ByteBuffer.allocate(16);
        return new IrisPassGraph.Pass(
            name, family, order,
            name + ".vsh", name + ".fsh", "", "",
            new io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisGlslTranslator.Result("", java.util.Set.of(),
                List.of(), java.util.Map.of(), java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), java.util.Set.of()),
            new io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisGlslTranslator.Result("", java.util.Set.of(),
                List.of(0), java.util.Map.of(), java.util.Set.of(), java.util.Set.of(0), java.util.Map.of(), java.util.Set.of()),
            dummySpv, dummySpv
        );
    }
}
