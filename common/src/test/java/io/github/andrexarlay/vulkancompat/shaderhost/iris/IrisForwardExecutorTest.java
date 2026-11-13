package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ProgramName;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2 tests: execution plan ordering, target/binding map, UBO range, and the no-device early return. */
class IrisForwardExecutorTest {

    @TempDir
    Path tmp;

    private Path zip(String name, String... entriesAndTexts) throws IOException {
        Path p = tmp.resolve(name);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (int i = 0; i < entriesAndTexts.length; i += 2) {
                z.putNextEntry(new ZipEntry(entriesAndTexts[i]));
                z.write(entriesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return p;
    }

    private IrisPassGraph.FullResult fullGraph() throws IOException {
        Path p = zip("Exec.zip",
            "shaders/shadow.vsh", """
                #version 120
                attribute vec3 vaPosition;
                uniform mat4 shadowModelView;
                uniform mat4 shadowProjection;
                void main() { gl_Position = shadowProjection * shadowModelView * vec4(vaPosition, 1.0); }
                """,
            "shaders/shadow.fsh", "#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n",
            "shaders/gbuffers_terrain.vsh", """
                #version 120
                attribute vec3 vaPosition;
                attribute vec4 vaColor;
                attribute vec2 vaUV0;
                uniform mat4 modelViewMatrix;
                uniform mat4 projectionMatrix;
                varying vec2 texcoord;
                varying vec4 color;
                void main() {
                    gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
                    texcoord = vaUV0; color = vaColor;
                }
                """,
            "shaders/gbuffers_terrain.fsh", """
                #version 120
                /* DRAWBUFFERS:01 */
                uniform sampler2D texture;
                varying vec2 texcoord;
                varying vec4 color;
                void main() {
                    gl_FragData[0] = texture2D(texture, texcoord) * color;
                    gl_FragData[1] = vec4(0.0);
                }
                """,
            "shaders/composite.fsh", """
                #version 120
                uniform sampler2D colortex0;
                uniform sampler2D colortex1;
                uniform vec3 cameraPosition;
                varying vec2 texcoord;
                void main() { gl_FragData[0] = texture2D(colortex0, texcoord) + texture2D(colortex1, texcoord) + vec4(cameraPosition, 0.0); }
                """,
            "shaders/final.fsh", """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() { gl_FragColor = texture2D(colortex0, texcoord); }
                """);
        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings settings = IrisPackSettings.parse(src, report);
            IrisPassGraph.FullResult res = IrisPassGraph.build(src, report, settings);
            assertTrue(res.success(), res.status().detail());
            return res;
        }
    }

    @Test
    void planOrdersPassesAndMapsTargets() throws IOException {
        IrisPassGraph.FullResult graph = fullGraph();
        try (IrisForwardExecutor ex = IrisForwardExecutor.createForTesting()) {
            ex.setGraph(graph);
            List<IrisForwardExecutor.Step> plan = ex.plan();
            assertEquals(List.of("shadow", "gbuffers_terrain", "composite", "final"),
                plan.stream().map(IrisForwardExecutor.Step::pass).toList());

            IrisForwardExecutor.Step shadow = plan.get(0);
            assertEquals(ProgramName.Family.SHADOW, shadow.family());
            assertFalse(shadow.fullscreen());
            assertTrue(shadow.writes().contains("shadowdepth0"));

            IrisForwardExecutor.Step terrain = plan.get(1);
            assertEquals(List.of("colortex0", "colortex1"), terrain.writes().stream().filter(w -> w.startsWith("colortex")).toList());
            assertTrue(terrain.writes().contains("depth0"));
            assertTrue(terrain.samplerBindings().contains(26)); // atlas
            assertEquals(graph.passes().get(1).uboBytes(), terrain.uboBytes());

            IrisForwardExecutor.Step composite = plan.get(2);
            assertTrue(composite.fullscreen());
            assertTrue(composite.reads().contains("colortex0"));
            assertTrue(composite.reads().contains("colortex1"));
            assertTrue(composite.samplerBindings().contains(2)); // colortex1
            assertEquals(graph.passes().get(2).uboBytes(), composite.uboBytes());

            IrisForwardExecutor.Step fin = plan.get(3);
            assertTrue(fin.writes().contains("engineColor"));
        }
    }

    @Test
    void recordWithoutDeviceReturnsFalse() throws IOException {
        IrisPassGraph.FullResult graph = fullGraph();
        try (IrisForwardExecutor ex = IrisForwardExecutor.createForTesting()) {
            ex.setGraph(graph);
            IrisForwardExecutor.FrameInputs in = new IrisForwardExecutor.FrameInputs(
                Map.of(), new float[16], new float[16], new float[16], new float[16], Map.of(),
                0L, 0L, 0L, 0L, 0L, 8, 8, 0f, 0, 0.05f, 128f, 0.016f);
            assertFalse(ex.record(null, in));
        }
    }

    /**
     * The Iris forward executor records the gbuffers geometry into its own colortex, then runs the fullscreen
     * deferred/composite/final chain and writes the engine colour image from a separate transient submission. If every
     * gbuffers pass skips because no geometry source arrived this frame, composite/final would still sample never-written
     * colortex and final would still overwrite the engine colour that vanilla rendered this same frame — an incomplete
     * frame submitted alongside vanilla's own draw of that image, which loses the Vulkan device (VK_ERROR_DEVICE_LOST,
     * observed as PerfMonitor "vkGetQueryPoolResults failed: -4"). A frame with gbuffers passes is therefore only
     * coherent to record when at least one of them actually has geometry to draw.
     */
    @Test
    void frameIsIncoherentWhenGbuffersHaveNoGeometry() throws IOException {
        IrisPassGraph.FullResult graph = fullGraph(); // shadow + gbuffers_terrain + composite + final
        assertTrue(IrisForwardExecutor.frameIsCoherent(graph.passes(), Set.of("terrain")),
            "with terrain geometry the gbuffers pass draws, so the post/final chain is coherent");
        assertFalse(IrisForwardExecutor.frameIsCoherent(graph.passes(), Set.of()),
            "with no gbuffers geometry the final must not composite over the engine target");
    }

    @Test
    void samplerBindingMapCoversRasterCore() {
        assertEquals(0, IrisForwardExecutor.bindingFor("colortex0"));
        assertEquals(2, IrisForwardExecutor.bindingFor("colortex1"));
        assertEquals(16, IrisForwardExecutor.bindingFor("colortex15"));
        assertEquals(19, IrisForwardExecutor.bindingFor("shadowtex0"));
        assertEquals(21, IrisForwardExecutor.bindingFor("shadowcolor0"));
        assertEquals(23, IrisForwardExecutor.bindingFor("lightmap"));
        assertEquals(26, IrisForwardExecutor.bindingFor("texture"));
        assertEquals(-1, IrisForwardExecutor.bindingFor("noSuchSampler"));
    }

    @Test
    void expressionTailEnlargesPerPassUbo() throws IOException {
        Path p = zip("Expr.zip",
            "shaders/shaders.properties", "uniform.float.testExpr = frameTimeCounter * 2.0\n",
            "shaders/composite.fsh", """
                #version 120
                uniform sampler2D colortex0;
                uniform float testExpr;
                varying vec2 texcoord;
                void main() { gl_FragData[0] = texture2D(colortex0, texcoord) * testExpr; }
                """,
            "shaders/final.fsh", """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() { gl_FragColor = texture2D(colortex0, texcoord); }
                """);
        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings settings = IrisPackSettings.parse(src, report);
            IrisPassGraph.FullResult res = IrisPassGraph.build(src, report, settings);
            assertTrue(res.success(), res.status().detail());
            try (IrisForwardExecutor ex = IrisForwardExecutor.createForTesting()) {
                ex.setGraph(res);
                IrisForwardExecutor.Step composite = ex.plan().stream()
                    .filter(s -> s.pass().equals("composite")).findFirst().orElseThrow();
                IrisPassGraph.FullPass compositePass = res.passes().stream()
                    .filter(fp -> fp.name().equals("composite")).findFirst().orElseThrow();
                assertEquals(compositePass.uboBytes(), composite.uboBytes());
                // testExpr is an expression uniform: it lives past the canonical region and enlarges the block.
                assertTrue(compositePass.uboOffsets().get("testExpr") >= IrisUniforms.UBO_LAYOUT.bytes());
                assertTrue(composite.uboBytes() > IrisUniforms.UBO_LAYOUT.bytes());
            }
        }
    }
}