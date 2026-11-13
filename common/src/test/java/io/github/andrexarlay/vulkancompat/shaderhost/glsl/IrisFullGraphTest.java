package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2 tests for the raster-core full graph: shadow -> gbuffers -> deferred -> composite -> final. */
class IrisFullGraphTest {

    @TempDir
    Path tmp;

    private static final String TERRAIN_VSH = """
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
            texcoord = vaUV0;
            color = vaColor;
        }
        """;

    private static final String TERRAIN_FSH = """
        #version 120
        /* DRAWBUFFERS:0 */
        uniform sampler2D texture;
        varying vec2 texcoord;
        varying vec4 color;
        void main() {
            gl_FragData[0] = texture2D(texture, texcoord) * color;
        }
        """;

    private static final String FULLSCREEN_FSH = """
        #version 120
        uniform sampler2D colortex0;
        varying vec2 texcoord;
        void main() {
            gl_FragData[0] = texture2D(colortex0, texcoord);
        }
        """;

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

    private IrisPassGraph.FullResult build(Path pack) throws IOException {
        try (PackSource src = PackSource.open(pack, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings settings = IrisPackSettings.parse(src, report);
            return IrisPassGraph.build(src, report, settings);
        }
    }

    @Test
    void bslLikePackOrdersShadowGbuffersDeferredCompositeFinal() throws IOException {
        Path p = zip("Full.zip",
            "shaders/shadow.vsh", """
                #version 120
                attribute vec3 vaPosition;
                uniform mat4 shadowModelView;
                uniform mat4 shadowProjection;
                void main() { gl_Position = shadowProjection * shadowModelView * vec4(vaPosition, 1.0); }
                """,
            "shaders/shadow.fsh", """
                #version 120
                void main() { gl_FragData[0] = vec4(1.0); }
                """,
            "shaders/gbuffers_terrain.vsh", TERRAIN_VSH,
            "shaders/gbuffers_terrain.fsh", TERRAIN_FSH,
            "shaders/gbuffers_water.vsh", TERRAIN_VSH,
            "shaders/gbuffers_water.fsh", TERRAIN_FSH,
            "shaders/gbuffers_weather.vsh", TERRAIN_VSH,
            "shaders/gbuffers_weather.fsh", TERRAIN_FSH,
            "shaders/shadowcomp.csh", "#version 450\nvoid main() {}\n",
            "shaders/deferred.fsh", FULLSCREEN_FSH,
            "shaders/composite.fsh", FULLSCREEN_FSH,
            "shaders/final.fsh", """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() { gl_FragColor = texture2D(colortex0, texcoord); }
                """,
            "shaders/shaders.properties", """
                program.gbuffers_weather.enabled=false
                program.shadowcomp.enabled=false
                """);

        IrisPassGraph.FullResult res = build(p);
        assertTrue(res.success(), res.status().detail());
        assertEquals(ShaderHostState.READY, res.status().state());
        List<String> names = res.passes().stream().map(IrisPassGraph.FullPass::name).toList();
        assertEquals(List.of("shadow", "gbuffers_terrain", "gbuffers_water", "deferred", "composite", "final"), names);
        assertEquals(ProgramName.Family.SHADOW, res.passes().get(0).family());
        assertEquals(ProgramName.Family.GBUFFERS, res.passes().get(1).family());
        assertEquals(ProgramName.Family.DEFERRED, res.passes().get(3).family());
        assertTrue(res.passes().get(3).fullscreen());
        assertFalse(res.passes().get(1).fullscreen());
        assertTrue(String.join(",", res.skipped()).contains("gbuffers_weather"));
        assertTrue(String.join(",", res.skipped()).contains("shadowcomp"));
        for (IrisPassGraph.FullPass pass : res.passes()) {
            assertNotNull(pass.vertexSpirv(), pass.name());
            assertNotNull(pass.fragmentSpirv(), pass.name());
            assertTrue(SpirvCompiler.isValidSpirv(pass.vertexSpirv()), pass.name() + " vertex SPIR-V");
            assertTrue(SpirvCompiler.isValidSpirv(pass.fragmentSpirv()), pass.name() + " fragment SPIR-V");
        }
        IrisPassGraph.FullPass terrain = res.passes().get(1);
        assertTrue(terrain.allUboUsed().containsKey("modelViewMatrix"));
        assertTrue(terrain.allSamplerBindings().contains(26)); // atlas "texture"
    }

    @Test
    void unsupportedRequiredAttributeNamed() throws IOException {
        Path p = zip("BadAttr.zip",
            "shaders/gbuffers_terrain.vsh", TERRAIN_VSH + "attribute vec4 vaTangent;\n",
            "shaders/gbuffers_terrain.fsh", TERRAIN_FSH,
            "shaders/final.fsh", FULLSCREEN_FSH);
        IrisPassGraph.FullResult res = build(p);
        assertFalse(res.success());
        assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, res.status().state());
        assertTrue(res.status().detail().contains("vaTangent"), res.status().detail());
    }

    @Test
    void enabledComputeProgramNamedUnsupported() throws IOException {
        Path p = zip("Compute.zip",
            "shaders/gbuffers_terrain.vsh", TERRAIN_VSH,
            "shaders/gbuffers_terrain.fsh", TERRAIN_FSH,
            "shaders/shadowcomp.csh", "#version 450\nvoid main() {}\n",
            "shaders/final.fsh", FULLSCREEN_FSH);
        IrisPassGraph.FullResult res = build(p);
        assertFalse(res.success());
        assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, res.status().state());
        assertTrue(res.status().detail().contains("shadowcomp"), res.status().detail());
    }

    @Test
    void dimensionFolderOverridesRootProgram() throws IOException {
        Path p = zip("Dim.zip",
            "shaders/gbuffers_terrain.vsh", TERRAIN_VSH,
            "shaders/gbuffers_terrain.fsh", TERRAIN_FSH,
            "shaders/world0/gbuffers_terrain.fsh", TERRAIN_FSH.replace("* color;", "* color * 0.5;"),
            "shaders/final.fsh", FULLSCREEN_FSH);
        IrisPassGraph.FullResult res = build(p);
        assertTrue(res.success(), res.status().detail());
        IrisPassGraph.FullPass terrain = res.passes().stream()
            .filter(x -> x.name().equals("gbuffers_terrain")).findFirst().orElseThrow();
        assertTrue(terrain.fragmentSource().contains("* color * 0.5"), terrain.fragmentSource());
    }
}