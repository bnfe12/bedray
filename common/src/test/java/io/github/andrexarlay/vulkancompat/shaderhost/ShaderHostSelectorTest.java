package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** I0: selector, states, fallback, invalidation, config key. Java-level only (L2), no game, no GPU. */
class ShaderHostSelectorTest {
    @TempDir
    Path game;

    @AfterEach
    void clearProps() {
        System.clearProperty(ShaderHostConfig.OVERRIDE_PROPERTY);
        System.clearProperty(IrisPackChoice.PACK_PROPERTY);
        ShaderHostConfig.setPersisted(ShaderHostKind.NATIVE);
    }

    private ShaderHostSelector selector() {
        return new ShaderHostSelector(new NativeShaderHost(), new IrisShaderHost(new PackReportCache(ScanLimits.DEFAULT)));
    }

    @Test
    void nativeIsReadyAndLogLineHasVerifierPrefix() {
        ShaderHostSelector.Selection s = selector().select(ShaderHostKind.NATIVE, ShaderHostContext.beforeDevice(game));
        assertEquals(ShaderHostKind.NATIVE, s.active());
        assertEquals(ShaderHostState.READY, s.activeStatus().state());
        assertFalse(s.fellBack());
        assertTrue(s.logLine().startsWith("[VKB-SHADERHOST] host=native state=READY"), s.logLine());
    }

    @Test
    void irisWithoutChosenPackFallsBackToNative() {
        ShaderHostSelector.Selection s = selector().select(ShaderHostKind.IRIS, ShaderHostContext.beforeDevice(game));
        assertEquals(ShaderHostState.NOT_INSTALLED, s.requestedStatus().state());
        assertEquals(ShaderHostKind.NATIVE, s.active());
        assertTrue(s.fellBack());
        assertTrue(s.logLine().startsWith("[VKB-SHADERHOST] host=native state=READY requested=iris requested_state=NOT_INSTALLED fallback=true"));
    }

    @Test
    void irisWithMissingPackIsNotInstalled() throws IOException {
        writeIrisProperties("enableShaders=true\nshaderPack=Nope.zip\n");
        ShaderHostStatus st = selector().probe(ShaderHostKind.IRIS, ShaderHostContext.beforeDevice(game));
        assertEquals(ShaderHostState.NOT_INSTALLED, st.state());
        assertTrue(st.detail().contains("Nope.zip"));
    }

    @Test
    void irisShadersDisabledIsNotInstalled() throws IOException {
        Files.createDirectories(game.resolve("shaderpacks"));
        writePack(game.resolve("shaderpacks/Mini.zip"), false);
        writeIrisProperties("enableShaders=false\nshaderPack=Mini.zip\n");
        assertEquals(ShaderHostState.NOT_INSTALLED, selector().probe(ShaderHostKind.IRIS, ShaderHostContext.beforeDevice(game)).state());
    }

    @Test
    void irisWithPackIsHonestlyUnsupportedUntilExecutorExists() throws IOException {
        Files.createDirectories(game.resolve("shaderpacks"));
        writePack(game.resolve("shaderpacks/Mini.zip"), false);
        writeIrisProperties("enableShaders=true\nshaderPack=Mini.zip\n");
        ShaderHostSelector sel = selector();
        ShaderHostSelector.Selection s = sel.select(ShaderHostKind.IRIS, ShaderHostContext.beforeDevice(game));
        assertEquals(ShaderHostState.UNSUPPORTED_PACK_FEATURES, s.requestedStatus().state());
        assertEquals(ShaderHostKind.NATIVE, s.active());
        assertNotNull(((IrisShaderHost) sel.host(ShaderHostKind.IRIS)).lastReport());
    }

    @Test
    void rtPipelinePackOnDeviceWithoutExtensionIsDeviceUnsupported() throws IOException {
        Files.createDirectories(game.resolve("shaderpacks"));
        writePack(game.resolve("shaderpacks/Rt.zip"), true);
        System.setProperty(IrisPackChoice.PACK_PROPERTY, "Rt.zip");
        ShaderHostContext withDevice = new ShaderHostContext(game, Optional.of(Set.of("VK_KHR_ray_query", "VK_KHR_acceleration_structure")));
        ShaderHostStatus st = selector().probe(ShaderHostKind.IRIS, withDevice);
        assertEquals(ShaderHostState.DEVICE_UNSUPPORTED, st.state(), st.detail());
        assertTrue(st.detail().contains("VK_KHR_ray_tracing_pipeline"));
    }

    @Test
    void packNameCannotLeaveShaderpacks() throws IOException {
        writeIrisProperties("enableShaders=true\nshaderPack=../config/iris.properties\n");
        ShaderHostStatus st = selector().probe(ShaderHostKind.IRIS, ShaderHostContext.beforeDevice(game));
        assertEquals(ShaderHostState.NOT_INSTALLED, st.state());
        assertTrue(st.detail().contains("invalid shaderpack name"), st.detail());
    }

    @Test
    void disabledForSessionIsStickyAndNativeCannotBeDisabled() {
        ShaderHostSelector sel = selector();
        sel.disableForSession(ShaderHostKind.IRIS, "compile failed in composite1");
        ShaderHostSelector.Selection s = sel.select(ShaderHostKind.IRIS, ShaderHostContext.beforeDevice(game));
        assertEquals(ShaderHostState.DISABLED_FOR_SESSION, s.requestedStatus().state());
        assertEquals("compile failed in composite1", s.requestedStatus().detail());
        assertEquals(ShaderHostKind.NATIVE, s.active());
        assertThrows(IllegalArgumentException.class, () -> sel.disableForSession(ShaderHostKind.NATIVE, "x"));
    }

    @Test
    void invalidationRules() {
        HostInvalidation.Plan same = HostInvalidation.plan(ShaderHostKind.NATIVE, ShaderHostKind.NATIVE, Set.of(), Optional.of(Set.of()));
        assertTrue(same.isNoop());
        HostInvalidation.Plan first = HostInvalidation.plan(null, ShaderHostKind.NATIVE, Set.of(), Optional.empty());
        assertTrue(first.invalidate().isEmpty());

        HostInvalidation.Plan sw = HostInvalidation.plan(ShaderHostKind.NATIVE, ShaderHostKind.IRIS, Set.of(), Optional.of(Set.of()));
        assertEquals(EnumSet.allOf(HostInvalidation.Resource.class), sw.invalidate());
        assertFalse(sw.restartRequired());

        HostInvalidation.Plan needsExt = HostInvalidation.plan(ShaderHostKind.NATIVE, ShaderHostKind.IRIS,
            Set.of("VK_KHR_ray_tracing_pipeline"), Optional.of(Set.of("VK_KHR_ray_query")));
        assertTrue(needsExt.restartRequired());
        assertEquals(Set.of("VK_KHR_ray_tracing_pipeline"), needsExt.missingExtensions());

        HostInvalidation.Plan beforeDevice = HostInvalidation.plan(ShaderHostKind.NATIVE, ShaderHostKind.IRIS,
            Set.of("VK_KHR_ray_tracing_pipeline"), Optional.empty());
        assertFalse(beforeDevice.restartRequired());
    }

    @Test
    void configKeyParseWriteAndOverride() throws IOException {
        assertEquals(ShaderHostKind.IRIS, ShaderHostKind.parse(" \"IRIS\" "));
        assertNull(ShaderHostKind.parse("vitrail"));
        ShaderHostConfig.applyValue("\"bogus\"");
        assertEquals(ShaderHostKind.NATIVE, ShaderHostConfig.persisted());

        Path toml = game.resolve("bedray.toml");
        Files.writeString(toml, "﻿[ray_tracing]\nrt_mode = \"off\"\n\n[shader_host]\nshader_host = \"iris\"\n", StandardCharsets.UTF_8);
        ShaderHostConfig.readFrom(toml);
        assertEquals(ShaderHostKind.IRIS, ShaderHostConfig.persisted());

        StringWriter w = new StringWriter();
        ShaderHostConfig.write(w);
        assertTrue(w.toString().contains("shader_host = \"iris\""));

        System.setProperty(ShaderHostConfig.OVERRIDE_PROPERTY, "native");
        assertEquals(ShaderHostKind.NATIVE, ShaderHostConfig.requested());
        assertEquals(ShaderHostKind.IRIS, ShaderHostConfig.persisted(), "a session override is never persisted");
    }

    @Test
    void bootstrapDefaultsToNativeReady() {
        ShaderHostSelector.Selection s = ShaderHosts.bootstrap(game);
        assertEquals(ShaderHostKind.NATIVE, ShaderHosts.active());
        assertEquals(ShaderHostState.READY, s.activeStatus().state());
    }

    private void writeIrisProperties(String body) throws IOException {
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/iris.properties"), body, StandardCharsets.ISO_8859_1);
    }

    static void writePack(Path zip, boolean rt) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(z, "shaders/shaders.properties", "iris.features.required = SSBO\nbufferObject.0 = 64\n" + (rt ? "rt.0.dispatch = 1.0 1.0 1\n" : ""));
            put(z, "shaders/gbuffers_terrain.vsh", "#version 330 compatibility\nvoid main(){gl_Position=ftransform();}\n");
            put(z, "shaders/gbuffers_terrain.fsh", "#version 330 compatibility\nvoid main(){}\n");
            put(z, "shaders/final.fsh", "#version 330\nvoid main(){}\n");
            if (rt) {
                put(z, "shaders/ray0.rgen", "#version 460\n#extension GL_EXT_ray_tracing : require\nvoid main(){ traceRayEXT(acc,0,0xFF,0,0,0,vec3(0),0.0,vec3(1),1.0,0); }\n");
                put(z, "shaders/ray0_0.rchit", "#version 460\n#extension GL_EXT_ray_tracing : require\nvoid main(){}\n");
            }
        }
    }

    static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        OutputStream o = z;
        o.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
