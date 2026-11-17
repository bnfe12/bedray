package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.vulkancompat.shaderhost.IrisShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostContext;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Slang source for the post host, against SPIR-V that the Mosaic SDK really produced (slangc 2026.20, a node graph with
 * three parameters). No GPU: this proves the manifest contract, the pass the executor would get, and its validation.
 */
class MosaicPostSourceTest {
    @TempDir Path dir;

    @BeforeEach
    void setUp() throws Exception {
        for (String f : new String[] {"cinematic.post.vert.spv", "cinematic.post.frag.spv"}) {
            try (InputStream in = getClass().getResourceAsStream("/mosaic/" + f)) {
                assertNotNull(in, f);
                Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @AfterEach
    void tearDown() {
        MosaicPostSource.clear();
        System.clearProperty(MosaicPostSource.PROPERTY);
        System.clearProperty(MosaicPostSource.MANIFEST_PROPERTY);
    }

    /** A manifest in the format Mosaic writes. */
    private JsonObject manifest() {
        JsonObject root = new JsonObject();
        JsonObject active = new JsonObject();
        active.addProperty("POST_PROCESS", "cinematic");
        root.add("active", active);

        JsonObject asset = new JsonObject();
        asset.addProperty("name", "cinematic");
        asset.addProperty("domain", "POST_PROCESS");
        asset.addProperty("status", "COMPILED");
        JsonArray params = new JsonArray();
        String[] names = {"exposure", "vignette_strength", "film_grain"};
        double[] values = {0.4, 0.65, 1.0};
        for (int i = 0; i < 3; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("name", names[i]);
            p.addProperty("value", values[i]);
            params.add(p);
        }
        asset.add("parameters", params);

        JsonObject spirv = new JsonObject();
        spirv.addProperty("vertex", "cinematic.post.vert.spv");
        spirv.addProperty("fragment", "cinematic.post.frag.spv");
        spirv.addProperty("entry", "main");
        JsonObject images = new JsonObject();
        images.addProperty("colortex0", 0);
        spirv.add("sampledImages", images);
        spirv.addProperty("descriptorSet", 0);
        JsonObject buffer = new JsonObject();
        buffer.addProperty("descriptorSet", 0);
        buffer.addProperty("binding", 19);
        buffer.addProperty("layout", "std140");
        buffer.addProperty("sizeBytes", 16);
        JsonArray fields = new JsonArray();
        for (int i = 0; i < 3; i++) {
            JsonObject f = new JsonObject();
            f.addProperty("name", names[i]);
            f.addProperty("offset", i * 4);
            fields.add(f);
        }
        buffer.add("fields", fields);
        spirv.add("paramBuffer", buffer);
        asset.add("spirv", spirv);

        JsonArray assets = new JsonArray();
        assets.add(asset);
        root.add("assets", assets);
        return root;
    }

    private Path write(JsonObject manifest) throws Exception {
        Path f = dir.resolve("mosaic_manifest.json");
        Files.writeString(f, manifest.toString());
        return f;
    }

    private static JsonObject spirvOf(JsonObject manifest) {
        return manifest.getAsJsonArray("assets").get(0).getAsJsonObject().getAsJsonObject("spirv");
    }

    @Test
    void theActiveShaderBecomesOneFinalPassBuiltFromTheSpirvDirectly() throws Exception {
        var result = MosaicPostSource.load(write(manifest()));
        assertNull(result.error(), result.error());
        var loaded = result.loaded();

        IrisPassGraph graph = loaded.graph();
        assertEquals(1, graph.passCount());
        IrisPassGraph.Pass pass = graph.passes().get(0);
        assertTrue(pass.isFinal(), "the shader writes the presented image");
        assertEquals("mosaic:cinematic", pass.name());
        assertEquals(Set.of(0), pass.allSamplerBindings(), "colortex0 only: the graph does not read depth");
        assertEquals(java.util.List.of(0), pass.drawBuffers());
        assertTrue(pass.allPushUsed().isEmpty() && pass.allFrameUsed().isEmpty() && pass.allWorldUsed().isEmpty());
        for (ByteBuffer spv : new ByteBuffer[] {pass.vertexSpirv(), pass.fragmentSpirv()}) {
            assertTrue(spv.isDirect());
            assertEquals(0x07230203, spv.order(ByteOrder.LITTLE_ENDIAN).getInt(0), "real SPIR-V from slangc");
            assertEquals(0, spv.remaining() % 4);
        }
        assertTrue(pass.fragmentSpirv().remaining() > pass.vertexSpirv().remaining());
    }

    @Test
    void parametersLandInTheConstantBufferAtTheContractOffsets() throws Exception {
        var loaded = MosaicPostSource.load(write(manifest())).loaded();
        assertEquals(3, loaded.params().size());
        assertEquals(16, loaded.bufferBytes());
        assertTrue(loaded.bufferBytes() <= IrisUniforms.MOSAIC_BYTES);

        ByteBuffer buf = ByteBuffer.wrap(MosaicPostSource.parameterBytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.4f, buf.getFloat(0), 1e-6, "exposure at offset 0");
        assertEquals(0.65f, buf.getFloat(4), 1e-6, "vignette_strength at offset 4");
        assertEquals(1.0f, buf.getFloat(8), 1e-6, "film_grain at offset 8");
        assertEquals(19, IrisUniforms.BINDING_MOSAIC);
    }

    @Test
    void aPreferenceChangeReachesTheBufferWithoutReloadingTheShader() throws Exception {
        Path file = write(manifest());
        var loaded = MosaicPostSource.load(file).loaded();
        var graphBefore = loaded.graph();

        JsonObject changed = manifest();
        changed.getAsJsonArray("assets").get(0).getAsJsonObject().getAsJsonArray("parameters").get(0).getAsJsonObject().addProperty("value", 1.5);
        changed.getAsJsonArray("assets").get(0).getAsJsonObject().getAsJsonArray("parameters").get(2).getAsJsonObject().addProperty("value", 0.0);
        Files.writeString(file, changed.toString());
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        Thread.sleep(700); // the host looks at the manifest at most twice a second
        ByteBuffer buf = ByteBuffer.wrap(MosaicPostSource.parameterBytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.5f, buf.getFloat(0), 1e-6);
        assertEquals(0.65f, buf.getFloat(4), 1e-6, "untouched values stay");
        assertEquals(0.0f, buf.getFloat(8), 1e-6);
        assertSame(graphBefore, MosaicPostSource.loaded().graph(), "same pass graph: nothing was rebuilt");
    }

    @Test
    void aHalfWrittenManifestKeepsTheLastGoodValues() throws Exception {
        Path file = write(manifest());
        MosaicPostSource.load(file);
        Files.writeString(file, "{ \"assets\": [");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        Thread.sleep(700);
        ByteBuffer buf = ByteBuffer.wrap(MosaicPostSource.parameterBytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.4f, buf.getFloat(0), 1e-6);
    }

    @Test
    void aShaderThatReadsDepthGetsTheDepthSamplerAtBindingOne() throws Exception {
        JsonObject m = manifest();
        spirvOf(m).getAsJsonObject("sampledImages").addProperty("depthtex0", 1);
        var loaded = MosaicPostSource.load(write(m)).loaded();
        assertEquals(Set.of(0, 1), loaded.samplerBindings());
        assertEquals(Set.of(0, 1), loaded.graph().passes().get(0).allSamplerBindings());
    }

    @Test
    void nothingChosenYetIsRecoverableNotAnError() throws Exception {
        var none = MosaicPostSource.load(dir.resolve("missing.json"));
        assertNull(none.loaded());
        assertTrue(none.recoverable());

        JsonObject m = manifest();
        m.remove("active");
        var noActive = MosaicPostSource.load(write(m));
        assertTrue(noActive.recoverable());
        assertTrue(noActive.error().contains("no active post-process shader"));
    }

    @Test
    void brokenInputIsRefusedWithAReason() throws Exception {
        JsonObject failed = manifest();
        failed.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("status", "FAILED");
        assertTrue(MosaicPostSource.load(write(failed)).error().contains("has not compiled"));

        JsonObject traversal = manifest();
        spirvOf(traversal).addProperty("vertex", "../cinematic.post.vert.spv");
        assertTrue(MosaicPostSource.load(write(traversal)).error().contains("plain file names"));

        JsonObject entry = manifest();
        spirvOf(entry).addProperty("entry", "fsMain");
        assertTrue(MosaicPostSource.load(write(entry)).error().contains("entry point main"));

        JsonObject binding = manifest();
        spirvOf(binding).getAsJsonObject("paramBuffer").addProperty("binding", 18);
        assertTrue(MosaicPostSource.load(write(binding)).error().contains("binding 19"));

        JsonObject big = manifest();
        spirvOf(big).getAsJsonObject("paramBuffer").addProperty("sizeBytes", 512);
        assertTrue(MosaicPostSource.load(write(big)).error().contains("reserves 256"));

        JsonObject outside = manifest();
        spirvOf(outside).getAsJsonObject("paramBuffer").getAsJsonArray("fields").get(2).getAsJsonObject().addProperty("offset", 16);
        assertTrue(MosaicPostSource.load(write(outside)).error().contains("outside the buffer"));

        JsonObject image = manifest();
        spirvOf(image).getAsJsonObject("sampledImages").addProperty("colortex3", 5);
        assertTrue(MosaicPostSource.load(write(image)).error().contains("unsupported sampled image"));

        Files.write(dir.resolve("cinematic.post.frag.spv"), new byte[32]);
        assertTrue(MosaicPostSource.load(write(manifest())).error().contains("SPIR-V magic"));
    }

    @Test
    void theHostProbesTheMosaicSourceInsteadOfAShaderpack() throws Exception {
        write(manifest());
        System.setProperty(MosaicPostSource.PROPERTY, "true");
        System.setProperty(MosaicPostSource.MANIFEST_PROPERTY, dir.toString());
        assertTrue(IrisShaderHost.postHostEnabled(), "the render bridge's post gate opens for Mosaic too");

        IrisShaderHost host = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
        var status = host.probe(new ShaderHostContext(dir, Optional.empty()));
        assertEquals(ShaderHostState.READY, status.state(), status.detail());
        assertTrue(status.detail().contains("Mosaic Slang post shader cinematic"), status.detail());
        assertNotNull(host.activeGraph());
        assertEquals("mosaic:cinematic", host.activeGraph().packName());
        host.teardown();
        assertNull(MosaicPostSource.loaded(), "teardown drops the shader");

        // without an active shader the host reports not installed, as it does for a missing shaderpack
        JsonObject m = manifest();
        m.remove("active");
        write(m);
        var none = host.probe(new ShaderHostContext(dir, Optional.empty()));
        assertEquals(ShaderHostState.NOT_INSTALLED, none.state());
        assertNull(host.activeGraph());
    }

    @Test
    void theGateIsOffByDefault() {
        System.clearProperty(MosaicPostSource.PROPERTY);
        assertFalse(MosaicPostSource.enabled());
        assertNull(MosaicPostSource.parameterBytes(), "no Mosaic shader: the executor writes nothing");
    }
}
