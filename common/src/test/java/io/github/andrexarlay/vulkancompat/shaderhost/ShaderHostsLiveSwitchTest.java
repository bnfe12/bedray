package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #35 live shader_host switch (L2, no game/GPU): the UI records a pending request, the frame-head applier swaps the
 * session selection atomically (or defers to restart when the target needs device extensions the live device lacks).
 * The per-frame consumers already re-read {@link ShaderHosts#active()}, so the swap is the whole live mechanism.
 */
class ShaderHostsLiveSwitchTest {
    @TempDir
    Path game;

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

    @AfterEach
    void reset() {
        ShaderHosts.resetForTesting();
        ShaderHostConfig.setPersisted(ShaderHostKind.NATIVE);
        System.clearProperty(ShaderHostConfig.OVERRIDE_PROPERTY);
        System.clearProperty(IrisPackChoice.PACK_PROPERTY);
        VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(Set.of());
    }

    @Test
    void noopWhenTargetAlreadyActive() {
        ShaderHosts.bootstrap(game);
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active());
        ShaderHosts.requestSwitch(ShaderHostKind.NATIVE);
        ShaderHosts.SwitchResult r = ShaderHosts.applyPendingRequest();
        assertEquals(ShaderHosts.SwitchResult.Outcome.NOOP, r.outcome());
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active());
        assertNull(ShaderHosts.pendingRequest(), "the request is consumed at the frame boundary");
    }

    @Test
    void noPendingRequestIsCheapNoop() {
        ShaderHosts.bootstrap(game);
        assertNull(ShaderHosts.applyPendingRequest(), "nothing pending: the per-frame applier returns null");
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active());
    }

    @Test
    void unavailableIrisWithoutPackStaysNative() {
        ShaderHosts.bootstrap(game);
        ShaderHosts.requestSwitch(ShaderHostKind.IRIS);
        ShaderHosts.SwitchResult r = ShaderHosts.applyPendingRequest();
        assertEquals(ShaderHosts.SwitchResult.Outcome.UNAVAILABLE, r.outcome());
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active(), "an unready target must not become active");
    }

    @Test
    void liveNativeToIrisWithRasterPack() throws IOException {
        writeRasterPack("Mini.zip");
        writeIrisProperties("enableShaders=true\nshaderPack=Mini.zip\n");
        VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(Set.of());
        ShaderHosts.bootstrap(game);
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active(), "bootstrap honours the persisted native choice");

        ShaderHosts.requestSwitch(ShaderHostKind.IRIS);
        assertEquals(ShaderHostKind.IRIS, ShaderHostConfig.persisted(), "the choice is persisted for restart");
        ShaderHosts.SwitchResult r = ShaderHosts.applyPendingRequest();
        assertEquals(ShaderHosts.SwitchResult.Outcome.LIVE, r.outcome(), r.detail());
        assertEquals(ShaderHostKind.IRIS, ShaderHosts.active());
        assertNotNull(ShaderHosts.iris().activeFullGraph(), "the live switch compiled the raster core");
    }

    @Test
    void liveIrisToNative() throws IOException {
        writeRasterPack("Mini.zip");
        writeIrisProperties("enableShaders=true\nshaderPack=Mini.zip\n");
        VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(Set.of());
        ShaderHostConfig.setPersisted(ShaderHostKind.IRIS);
        ShaderHosts.bootstrap(game);
        assertEquals(ShaderHostKind.IRIS, ShaderHosts.active(), "precondition: iris bootstrapped active");

        ShaderHosts.requestSwitch(ShaderHostKind.NATIVE);
        ShaderHosts.SwitchResult r = ShaderHosts.applyPendingRequest();
        assertEquals(ShaderHosts.SwitchResult.Outcome.LIVE, r.outcome(), r.detail());
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active());
    }

    @Test
    void deferredWhenIrisNeedsMissingDeviceExtension() throws IOException {
        writeRtPack("Rt.zip");
        writeIrisProperties("enableShaders=true\nshaderPack=Rt.zip\n");
        ShaderHosts.bootstrap(game);
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active());
        // The live device has ray_query but not the ray_tracing_pipeline this pack needs: the switch must defer.
        VulkanCapabilityChecker.setCachedDeviceExtensionsForTesting(Set.of("VK_KHR_ray_query"));

        ShaderHosts.requestSwitch(ShaderHostKind.IRIS);
        ShaderHosts.SwitchResult r = ShaderHosts.applyPendingRequest();
        assertEquals(ShaderHosts.SwitchResult.Outcome.DEFERRED_RESTART, r.outcome(), r.detail());
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active(), "a deferred switch leaves the live host untouched");
        assertEquals(ShaderHostKind.IRIS, ShaderHostConfig.persisted(), "the choice is stored so it applies after restart");
        assertTrue(r.detail().contains("VK_KHR_ray_tracing_pipeline"), r.detail());
    }

    private void writeIrisProperties(String body) throws IOException {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/iris.properties"), body, StandardCharsets.ISO_8859_1);
    }

    private void writeRasterPack(String name) throws IOException {
        Files.createDirectories(game.resolve("shaderpacks"));
        zip(game.resolve("shaderpacks/" + name),
            "shaders/gbuffers_terrain.vsh", TERRAIN_VSH,
            "shaders/gbuffers_terrain.fsh", TERRAIN_FSH,
            "shaders/final.fsh", FULLSCREEN_FSH);
    }

    private void writeRtPack(String name) throws IOException {
        Files.createDirectories(game.resolve("shaderpacks"));
        zip(game.resolve("shaderpacks/" + name),
            "shaders/shaders.properties", "iris.features.required = SSBO\nbufferObject.0 = 64\nrt.0.dispatch = 1.0 1.0 1\n",
            "shaders/gbuffers_terrain.vsh", TERRAIN_VSH,
            "shaders/gbuffers_terrain.fsh", TERRAIN_FSH,
            "shaders/final.fsh", FULLSCREEN_FSH,
            "shaders/ray0.rgen", "#version 460\n#extension GL_EXT_ray_tracing : require\nvoid main(){ traceRayEXT(acc,0,0xFF,0,0,0,vec3(0),0.0,vec3(1),1.0,0); }\n",
            "shaders/ray0_0.rchit", "#version 460\n#extension GL_EXT_ray_tracing : require\nvoid main(){}\n");
    }

    private static void zip(Path zip, String... entriesAndTexts) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < entriesAndTexts.length; i += 2) {
                z.putNextEntry(new ZipEntry(entriesAndTexts[i]));
                OutputStream o = z;
                o.write(entriesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }
}