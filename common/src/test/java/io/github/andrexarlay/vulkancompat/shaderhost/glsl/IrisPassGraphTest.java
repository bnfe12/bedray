package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostStatus;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPackOptions;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for {@link IrisPassGraph}: parsing, pass ordering, validation, and end-to-end SPIR-V compilation.
 */
class IrisPassGraphTest {

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
    void tinyTestpackBuildsAndCompilesFullPipeline() throws IOException {
        Path packDir = findTinyTestpack();
        IrisPassGraph.ParseResult res = IrisPassGraph.build(packDir, ScanLimits.DEFAULT);

        assertTrue(res.success(), "ParseResult should be success: " + res.status().detail());
        assertEquals(ShaderHostState.READY, res.status().state());

        IrisPassGraph graph = res.graph();
        assertNotNull(graph);
        assertEquals(2, graph.passCount());

        // Check composite pass
        IrisPassGraph.Pass composite = graph.passes().get(0);
        assertEquals("composite", composite.name());
        assertFalse(composite.isFinal());
        assertNotNull(composite.vertexSpirv());
        assertNotNull(composite.fragmentSpirv());
        assertTrue(SpirvCompiler.isValidSpirv(composite.vertexSpirv()));
        assertTrue(SpirvCompiler.isValidSpirv(composite.fragmentSpirv()));
        assertTrue(composite.allPushUsed().contains("viewWidth"));
        assertTrue(composite.allPushUsed().contains("frameTimeCounter"));
        assertTrue(composite.allSamplerBindings().contains(0)); // colortex0

        // Check final pass
        IrisPassGraph.Pass fin = graph.passes().get(1);
        assertEquals("final", fin.name());
        assertTrue(fin.isFinal());
        assertNotNull(fin.vertexSpirv());
        assertNotNull(fin.fragmentSpirv());
        assertTrue(SpirvCompiler.isValidSpirv(fin.vertexSpirv()));
        assertTrue(SpirvCompiler.isValidSpirv(fin.fragmentSpirv()));
        assertTrue(fin.allSamplerBindings().contains(0));
    }

    @Test
    void defaultVertexShaderSynthesizedWhenOmitted() throws IOException {
        Path p = zip("NoVsh.zip",
            "shaders/composite.fsh", """
                #version 120
                /* RENDERTARGETS: 0 */
                uniform sampler2D colortex0;
                varying vec4 texcoord;
                void main() {
                    gl_FragData[0] = texture2D(colortex0, texcoord.st);
                }
                """,
            "shaders/final.fsh", """
                #version 120
                uniform sampler2D colortex0;
                varying vec4 texcoord;
                void main() {
                    gl_FragColor = texture2D(colortex0, texcoord.st);
                }
                """
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertTrue(res.success(), res.status().detail());
            assertEquals(ShaderHostState.READY, res.status().state());
            IrisPassGraph graph = res.graph();
            assertEquals(2, graph.passCount());
            assertTrue(graph.passes().get(0).vertexPath().contains("[default]"));
            assertTrue(SpirvCompiler.isValidSpirv(graph.passes().get(0).vertexSpirv()));
        }
    }

    @Test
    void defaultVertexShaderMatchesVec2Texcoord() throws IOException {
        Path p = zip("Vec2Texcoord.zip",
            "shaders/final.fsh", """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() {
                    gl_FragColor = texture2D(colortex0, texcoord);
                }
                """
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertTrue(res.success(), res.status().detail());
            assertEquals(ShaderHostState.READY, res.status().state());
            IrisPassGraph.Pass fin = res.graph().finalPass();
            assertNotNull(fin);
            assertTrue(SpirvCompiler.isValidSpirv(fin.vertexSpirv()));
            assertTrue(SpirvCompiler.isValidSpirv(fin.fragmentSpirv()));
        }
    }

    @Test
    void compositePassOrdering() throws IOException {
        Path p = zip("Ordered.zip",
            "shaders/composite2.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/composite.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/composite1.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertTrue(res.success(), res.status().detail());
            IrisPassGraph graph = res.graph();
            assertEquals(4, graph.passCount());
            assertEquals("composite", graph.passes().get(0).name());
            assertEquals("composite1", graph.passes().get(1).name());
            assertEquals("composite2", graph.passes().get(2).name());
            assertEquals("final", graph.passes().get(3).name());
        }
    }

    @Test
    void compositePassOrderingWithSubpasses() throws IOException {
        Path p = zip("OrderedSub.zip",
            "shaders/composite2.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/composite1_b.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/composite_a.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/composite.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/composite1_a.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/composite1.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertTrue(res.success(), res.status().detail());
            IrisPassGraph graph = res.graph();
            assertEquals(7, graph.passCount());
            assertEquals("composite", graph.passes().get(0).name());
            assertEquals("composite_a", graph.passes().get(1).name());
            assertEquals("composite1", graph.passes().get(2).name());
            assertEquals("composite1_a", graph.passes().get(3).name());
            assertEquals("composite1_b", graph.passes().get(4).name());
            assertEquals("composite2", graph.passes().get(5).name());
            assertEquals("final", graph.passes().get(6).name());
        }
    }

    @Test
    void gbuffersIsUnsupportedPackFeatures() throws IOException {
        Path p = zip("WithGbuffers.zip",
            "shaders/gbuffers_terrain.vsh", "#version 120\nvoid main(){}\n",
            "shaders/gbuffers_terrain.fsh", "#version 120\nvoid main(){}\n",
            "shaders/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertFalse(res.success());
            assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, res.status().state());
            assertTrue(res.status().detail().contains("gbuffers_terrain"), res.status().detail());
        }
    }

    @Test
    void shadowPassIsUnsupportedPackFeatures() throws IOException {
        Path p = zip("WithShadow.zip",
            "shaders/shadow.vsh", "#version 120\nvoid main(){}\n",
            "shaders/shadow.fsh", "#version 120\nvoid main(){}\n",
            "shaders/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertFalse(res.success());
            assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, res.status().state());
            assertTrue(res.status().detail().contains("shadow"), res.status().detail());
        }
    }

    @Test
    void computePassIsUnsupportedPackFeatures() throws IOException {
        Path p = zip("WithCompute.zip",
            "shaders/composite.csh", "#version 430\nlayout(local_size_x=8) in;\nvoid main(){}\n",
            "shaders/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertFalse(res.success());
            assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, res.status().state());
            assertTrue(res.status().detail().contains("composite.csh") || res.status().detail().contains("COMPUTE"), res.status().detail());
        }
    }

    @Test
    void ssboIsUnsupportedPackFeatures() throws IOException {
        Path p = zip("WithSsbo.zip",
            "shaders/shaders.properties", "bufferObject.0 = 64\n",
            "shaders/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertFalse(res.success());
            assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, res.status().state());
            assertTrue(res.status().detail().contains("SSBO"), res.status().detail());
        }
    }

    @Test
    void worldFolderReplacesRootForItsDimension() throws IOException {
        Path p = zip("WithWorld0.zip",
            "shaders/world0/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(0.5);}\n",
            "shaders/final.fsh", "#version 120\nvarying vec4 texcoord;\nvoid main(){gl_FragColor=vec4(1);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            // Without dimension.properties Iris uses the classic world folders when they exist.
            assertEquals("world0", IrisPassGraph.folderFor(report, IrisPassGraph.OVERWORLD));
            assertEquals("", IrisPassGraph.folderFor(report, "minecraft:the_nether"));
            assertEquals(List.of("world0", ""), IrisPassGraph.usedFolders(report));

            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertTrue(res.success(), res.status().detail());
            assertEquals("world0", res.graph().folder());
            assertEquals("shaders/world0/final.fsh", res.graph().finalPass().fragmentPath());
        }
    }

    @Test
    void dimensionPropertiesMapFoldersWithWildcardFallback() throws IOException {
        Path p = zip("DimensionMap.zip",
            "shaders/dimension.properties", "dimension.world0 = minecraft:overworld *\n"
                + "dimension.world-1 = minecraft:the_nether\ndimension.world1 = minecraft:the_end\n",
            "shaders/world0/final.fsh", "#version 120\nvoid main(){gl_FragColor=vec4(1);}\n",
            "shaders/world-1/final.fsh", "#version 120\nvoid main(){gl_FragColor=vec4(0.2);}\n",
            "shaders/world1/final.fsh", "#version 120\nvoid main(){gl_FragColor=vec4(0.4);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            assertEquals("world0", IrisPassGraph.folderFor(report, IrisPassGraph.OVERWORLD));
            assertEquals("world-1", IrisPassGraph.folderFor(report, "minecraft:the_nether"));
            assertEquals("world1", IrisPassGraph.folderFor(report, "minecraft:the_end"));
            // A modded dimension falls back to the folder that lists "*".
            assertEquals("world0", IrisPassGraph.folderFor(report, "aether:the_aether"));
            // The root holds no programs and every dimension is covered by the wildcard, so it is not built.
            assertEquals(List.of("world0", "world-1", "world1"), IrisPassGraph.usedFolders(report));
            for (String folder : IrisPassGraph.usedFolders(report)) {
                IrisPassGraph.ParseResult res = IrisPassGraph.build(src, report, IrisPackOptions.Request.defaults(), folder);
                assertTrue(res.success(), folder + ": " + res.status().detail());
                assertEquals("shaders/" + folder + "/final.fsh", res.graph().finalPass().fragmentPath());
            }
        }
    }

    @Test
    void fullscreenPassesRunPrepareDeferredCompositeThenFinal() throws IOException {
        String pass = "#version 120\nuniform sampler2D colortex0;\nvarying vec2 texcoord;\n"
            + "void main(){gl_FragData[0]=texture2D(colortex0, texcoord);}\n";
        Path p = zip("PassOrder.zip",
            "shaders/composite2.fsh", pass,
            "shaders/deferred1.fsh", pass,
            "shaders/composite.fsh", pass,
            "shaders/prepare.fsh", pass,
            "shaders/deferred.fsh", pass,
            "shaders/final.fsh", "#version 120\nuniform sampler2D colortex0;\nvarying vec2 texcoord;\n"
                + "void main(){gl_FragColor=texture2D(colortex0, texcoord);}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertTrue(res.success(), res.status().detail());
            assertEquals(List.of("prepare", "deferred", "deferred1", "composite", "composite2", "final"),
                res.graph().passes().stream().map(IrisPassGraph.Pass::name).toList());
            assertEquals(ProgramName.Family.DEFERRED, res.graph().passes().get(1).family());
        }
    }

    @Test
    void distantHorizonsProgramsAreInactiveAndGeometryProgramsAreRefusedByName() throws IOException {
        String fin = "#version 120\nvoid main(){gl_FragColor=vec4(1);}\n";
        Path withDh = zip("WithDh.zip",
            "shaders/dh_terrain.vsh", "#version 120\nvoid main(){gl_Position=ftransform();}\n",
            "shaders/dh_terrain.fsh", fin,
            "shaders/clrwl_gbuffers.vsh", "#version 120\nvoid main(){gl_Position=ftransform();}\n",
            "shaders/clrwl_gbuffers.fsh", fin,
            "shaders/final.fsh", fin
        );
        try (PackSource src = PackSource.open(withDh, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertTrue(res.success(), res.status().detail());
            assertEquals(List.of("final"), res.graph().passes().stream().map(IrisPassGraph.Pass::name).toList());
        }

        Path withGbuffers = zip("WithGbuffers.zip",
            "shaders/gbuffers_terrain.vsh", "#version 120\nvoid main(){gl_Position=ftransform();}\n",
            "shaders/gbuffers_terrain.fsh", fin,
            "shaders/final.fsh", fin
        );
        try (PackSource src = PackSource.open(withGbuffers, ScanLimits.DEFAULT)) {
            ShaderHostStatus status = IrisPassGraph.validate(ShaderpackScanner.scan(src));
            assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, status.state());
            assertTrue(status.detail().contains("gbuffers_terrain (GBUFFERS)"), status.detail());
            assertTrue(status.detail().contains("geometry stage"), status.detail());
        }
    }

    @Test
    void unsupportedUniformInShaderYieldsUnsupportedFeatures() throws IOException {
        Path p = zip("UnsupportedUniform.zip",
            "shaders/final.fsh", """
                #version 120
                uniform float screenBrightness;
                void main() {
                    gl_FragColor = vec4(screenBrightness);
                }
                """
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertFalse(res.success());
            assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, res.status().state());
            assertTrue(res.status().detail().contains("screenBrightness"), res.status().detail());
        }
    }

    @Test
    void syntaxErrorInShaderYieldsCompileFailed() throws IOException {
        Path p = zip("SyntaxError.zip",
            // A valid output is required: without one the interface analysis refuses the pass before compilation
            // (UNSUPPORTED_PACK_FEATURES), and this test is about the compiler's own error path.
            "shaders/final.fsh", """
                #version 120
                void main() {
                    gl_FragColor = vec4(1.0);
                    this is invalid glsl code here;
                }
                """
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            IrisPassGraph.ParseResult res = IrisPassGraph.build(src);
            assertFalse(res.success());
            assertEquals(ShaderHostState.COMPILE_FAILED, res.status().state());
            assertTrue(res.status().detail().contains("compile error"), res.status().detail());
        }
    }

    @Test
    void missingFragmentShaderIsRejectedByValidate() throws IOException {
        Path p = zip("MissingFsh.zip",
            "shaders/composite.vsh", "#version 120\nvoid main(){gl_Position=ftransform();}\n"
        );

        try (PackSource src = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            ShaderHostStatus status = IrisPassGraph.validate(report);
            assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, status.state(), "validate should reject pass without fragment shader");
            assertTrue(status.detail().contains("fragment shader"), status.detail());
        }
    }
}
