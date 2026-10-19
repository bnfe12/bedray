package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for {@link IrisShaderHost} lifecycle:
 * probing, transitioning to READY with {@link IrisPassGraph}, tracking active graph and passes,
 * handling unsupported pack features, compile errors, and teardown.
 */
class IrisShaderHostLifecycleTest {

    @TempDir
    Path gameDir;

    private IrisShaderHost host;

    @BeforeEach
    void setUp() {
        host = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(IrisPackChoice.PACK_PROPERTY);
    }

    private static Path findTinyTestpack() {
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

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void writeIrisProperties(String body) throws IOException {
        Files.createDirectories(gameDir.resolve("config"));
        Files.writeString(gameDir.resolve("config/iris.properties"), body, StandardCharsets.ISO_8859_1);
    }

    private Path writeZip(String filename, String... entriesAndContents) throws IOException {
        Path shaderpacks = gameDir.resolve("shaderpacks");
        Files.createDirectories(shaderpacks);
        Path zipFile = shaderpacks.resolve(filename);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (int i = 0; i < entriesAndContents.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entriesAndContents[i]));
                zos.write(entriesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    @Test
    void tinyTestpackProbesReadyAndPopulatesActiveGraphWithTwoPasses() throws IOException {
        Path tinySrc = findTinyTestpack();
        Path dest = gameDir.resolve("shaderpacks").resolve("tiny-testpack");
        copyDirectory(tinySrc, dest);

        writeIrisProperties("enableShaders=true\nshaderPack=tiny-testpack\n");

        ShaderHostStatus status = host.probe(ShaderHostContext.beforeDevice(gameDir));

        assertEquals(ShaderHostKind.IRIS, status.host());
        assertEquals(ShaderHostState.READY, status.state());
        assertTrue(status.detail().contains("tiny-testpack"), status.detail());
        assertTrue(status.detail().contains("2 passes"), status.detail());

        IrisPassGraph graph = host.activeGraph();
        assertNotNull(graph, "activeGraph must not be null after READY probe");
        assertEquals("tiny-testpack", graph.packName());
        assertEquals(2, graph.passCount());

        IrisPassGraph.Pass compositePass = graph.passes().get(0);
        assertEquals("composite", compositePass.name());
        assertFalse(compositePass.isFinal());
        assertNotNull(compositePass.vertexSpirv());
        assertNotNull(compositePass.fragmentSpirv());

        IrisPassGraph.Pass finalPass = graph.passes().get(1);
        assertEquals("final", finalPass.name());
        assertTrue(finalPass.isFinal());
        assertNotNull(finalPass.vertexSpirv());
        assertNotNull(finalPass.fragmentSpirv());

        assertNotNull(host.lastReport());
        assertEquals("tiny-testpack", host.lastReport().name());
    }

    @Test
    void teardownClearsActiveGraphAndLastReport() throws IOException {
        Path tinySrc = findTinyTestpack();
        Path dest = gameDir.resolve("shaderpacks").resolve("tiny-testpack");
        copyDirectory(tinySrc, dest);

        System.setProperty(IrisPackChoice.PACK_PROPERTY, "tiny-testpack");

        ShaderHostStatus status = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.READY, status.state());
        assertNotNull(host.activeGraph());
        assertNotNull(host.lastReport());

        host.teardown();
        assertNull(host.activeGraph(), "activeGraph must be null after teardown");
        assertNull(host.lastReport(), "lastReport must be null after teardown");
    }

    @Test
    void packWithShadowPassReturnsUnsupportedPackFeatures() throws IOException {
        writeZip("ShadowPack.zip",
            "shaders/composite.fsh", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n",
            "shaders/final.fsh", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n",
            "shaders/shadow.fsh", "#version 120\nvoid main() {}\n"
        );

        System.setProperty(IrisPackChoice.PACK_PROPERTY, "ShadowPack.zip");

        ShaderHostStatus status = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, status.state());
        assertTrue(status.detail().contains("unsupported"), status.detail());
        assertNull(host.activeGraph(), "activeGraph must be null when features are unsupported");
    }

    @Test
    void packWithGbuffersReturnsUnsupportedPackFeatures() throws IOException {
        writeZip("GbuffersPack.zip",
            "shaders/gbuffers_terrain.vsh", "#version 120\nvoid main() { gl_Position = ftransform(); }\n",
            "shaders/gbuffers_terrain.fsh", "#version 120\nvoid main() {}\n",
            "shaders/composite.fsh", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n",
            "shaders/final.fsh", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n"
        );

        System.setProperty(IrisPackChoice.PACK_PROPERTY, "GbuffersPack.zip");

        ShaderHostStatus status = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, status.state());
        assertTrue(status.detail().contains("unsupported programs"), status.detail());
        assertNull(host.activeGraph());
    }

    @Test
    void packWithCompileErrorReturnsCompileFailed() throws IOException {
        writeZip("CompileErrorPack.zip",
            "shaders/composite.fsh", "#version 120\nthis is invalid glsl syntax;\n",
            "shaders/final.fsh", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n"
        );

        System.setProperty(IrisPackChoice.PACK_PROPERTY, "CompileErrorPack.zip");

        ShaderHostStatus status = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.COMPILE_FAILED, status.state());
        assertTrue(status.detail().contains("compile error") || status.detail().contains("error:"), status.detail());
        assertNull(host.activeGraph(), "activeGraph must be null when compilation fails");
    }

    @Test
    void missingDeviceExtensionsReturnsDeviceUnsupported() throws IOException {
        writeZip("RtPack.zip",
            "shaders/ray0.rgen", "#version 460\n#extension GL_EXT_ray_tracing : require\nvoid main(){ traceRayEXT(acc,0,0xFF,0,0,0,vec3(0),0.0,vec3(1),1.0,0); }\n",
            "shaders/composite.fsh", "#version 120\nvoid main() {}\n",
            "shaders/final.fsh", "#version 120\nvoid main() {}\n"
        );

        System.setProperty(IrisPackChoice.PACK_PROPERTY, "RtPack.zip");

        // Device only has ray query, but pack needs ray tracing pipeline
        ShaderHostContext ctxWithDevice = new ShaderHostContext(gameDir,
            Optional.of(Set.of("VK_KHR_ray_query")));

        ShaderHostStatus status = host.probe(ctxWithDevice);
        assertEquals(ShaderHostState.DEVICE_UNSUPPORTED, status.state());
        assertTrue(status.detail().contains("VK_KHR_ray_tracing_pipeline"), status.detail());
        assertNull(host.activeGraph());
    }

    @Test
    void unselectedOrMissingPackReturnsNotInstalled() {
        ShaderHostStatus statusNoPack = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.NOT_INSTALLED, statusNoPack.state());
        assertNull(host.activeGraph());

        System.setProperty(IrisPackChoice.PACK_PROPERTY, "NonExistentPack.zip");
        ShaderHostStatus statusMissing = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.NOT_INSTALLED, statusMissing.state());
        assertNull(host.activeGraph());
    }

    @Test
    void subsequentFailedProbeClearsActiveGraph() throws IOException {
        Path tinySrc = findTinyTestpack();
        Path dest = gameDir.resolve("shaderpacks").resolve("tiny-testpack");
        copyDirectory(tinySrc, dest);

        System.setProperty(IrisPackChoice.PACK_PROPERTY, "tiny-testpack");
        ShaderHostStatus readyStatus = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.READY, readyStatus.state());
        assertNotNull(host.activeGraph());

        // Probe again with nonexistent pack
        System.setProperty(IrisPackChoice.PACK_PROPERTY, "DoesntExist.zip");
        ShaderHostStatus notInstalledStatus = host.probe(ShaderHostContext.beforeDevice(gameDir));
        assertEquals(ShaderHostState.NOT_INSTALLED, notInstalledStatus.state());
        assertNull(host.activeGraph(), "activeGraph must be reset on subsequent probe");
        assertNull(host.lastReport());
    }
}
