package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** I1 metadata: programs per folder, properties, RT signals, content hash cache. Synthetic packs only. */
class ShaderpackScannerTest {
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

    @Test
    void programsFoldersAndProperties() throws IOException {
        Path p = zip("Pack.zip",
            "Pack/shaders/shaders.properties", """
                # comment
                iris.features.optional = CUSTOM_IMAGES SSBO
                #ifdef BIG
                bufferObject.0 = 1024
                #else
                bufferObject.0 = 64
                #endif
                image.voxel = voxel_sampler red_integer r16ui unsigned_int true false 128 64 128
                customTexture.noise = lib/noise.png
                program.world0/shadow.enabled = \\
                    SHADOWS
                """,
            "Pack/shaders/dimension.properties", "dimension.world0 = minecraft:overworld *\n",
            "Pack/shaders/world0/gbuffers_terrain.vsh", "#version 130\n",
            "Pack/shaders/world0/gbuffers_terrain.fsh", "#version 130\nuniform sampler2D normals;\n",
            "Pack/shaders/world0/shadow.vsh", "#version 130\n",
            "Pack/shaders/world0/composite1_a.csh", "#version 430\nlayout(local_size_x=8) in;\n",
            "Pack/shaders/world-1/final.fsh", "#version 130\n",
            "Pack/shaders/program/gbuffers_terrain.glsl", "// include, not a program\n",
            "Pack/shaders/final.fsh", "#version 150 core\n// #extension GL_EXT_ray_tracing : require\n/* traceRayEXT( */\n",
            "Pack/README.md", "x");
        PackReport r;
        try (PackSource s = PackSource.open(p, ScanLimits.DEFAULT)) {
            r = ShaderpackScanner.scan(s);
        }
        assertEquals("Pack/shaders/", r.shadersRoot());
        assertEquals(Set.of("", "world-1", "world0"), r.dimensions().keySet());
        assertTrue(r.dimensions().get("world0").containsKey("gbuffers_terrain"));
        assertFalse(r.dimensions().get("world0").containsKey("program"));
        assertEquals(Set.of(ProgramName.Stage.COMPUTE), r.dimensions().get("world0").get("composite1_a"));
        assertEquals(java.util.List.of("minecraft:overworld", "*"), r.dimensionIds().get("world0"));
        assertTrue(r.propertiesConditional());
        assertEquals(Set.of("CUSTOM_IMAGES", "SSBO"), r.optionalFeatures());
        assertEquals(Set.of(0), r.bufferObjects());
        assertEquals(Set.of("voxel"), r.customImages());
        assertEquals(Set.of("noise"), r.customTextures());
        assertEquals(PackReport.RtModel.NONE, r.rtModel(), "commented-out RT must not count");
        assertTrue(r.glslExtensions().isEmpty());
        assertEquals(4, r.glslVersions().get("130"));
        assertEquals(1, r.glslVersions().get("150 core"));
        assertTrue(r.capabilities().containsAll(Set.of(PackReport.Capability.GBUFFERS, PackReport.Capability.SHADOW_PASS,
            PackReport.Capability.COMPOSITE, PackReport.Capability.COMPUTE_PROGRAMS, PackReport.Capability.FINAL,
            PackReport.Capability.DIMENSION_FOLDERS, PackReport.Capability.CONDITIONAL_PROPERTIES, PackReport.Capability.SSBO,
            PackReport.Capability.CUSTOM_IMAGES, PackReport.Capability.CUSTOM_TEXTURES)));
        assertTrue(r.extraDeviceExtensions().isEmpty());
        assertTrue(r.plannedWork().get(3).startsWith("I5: pack does no ray tracing"));
        assertTrue(r.plannedWork().get(2).contains("LabPBR"));
    }

    @Test
    void vulkaniteStyleRtPack() throws IOException {
        Path p = zip("Rt.zip",
            "shaders/shaders.properties", "iris.features.required = SSBO CUSTOM_IMAGES\nrt.0.dispatch = 1.0 1.0 1\nrt.1.dispatch = 0.5 0.5 1\nrt.groups = 0 0\n",
            "shaders/ray0.rgen", "#version 460 core\n#include \"/lib/rt/gen.glsl\"\n",
            "shaders/ray0_0.rchit", "#version 460\n",
            "shaders/ray0_0.rmiss", "#version 460\n",
            "shaders/ray1.rgen", "#version 460 core\n#include \"/lib/rt/gen.glsl\"\n",
            "shaders/lib/rt/gen.glsl", "#extension GL_EXT_ray_tracing : enable\n#extension GL_EXT_ray_query : enable\nvoid f(){ traceRayEXT(acc, 0u, 0xFF, 0, 0, 0, ro, 0.0, rd, 1e9, 6); }\n",
            "shaders/composite1.csh", "#version 430 compatibility\n");
        PackReport r;
        try (PackSource s = PackSource.open(p, ScanLimits.DEFAULT)) {
            r = ShaderpackScanner.scan(s);
        }
        assertEquals(PackReport.RtModel.RAY_TRACING_PIPELINE, r.rtModel());
        assertEquals(Set.of(ShaderpackScanner.RT_PIPELINE_EXTENSION), r.extraDeviceExtensions());
        assertEquals(Set.of(0, 1), r.rtDispatch().keySet());
        assertEquals("0 0", r.rtGroups());
        assertEquals(Set.of("ray0", "ray0_0", "ray1"), r.programs(ProgramName.Family.RAY));
        assertTrue(r.capabilities().contains(PackReport.Capability.VULKANITE_RT_PASSES));
        assertFalse(r.capabilities().contains(PackReport.Capability.RAY_QUERY), "ray_query enabled but never called");
        assertTrue(r.glslExtensions().containsAll(Set.of("GL_EXT_ray_tracing", "GL_EXT_ray_query")));
        assertEquals(Set.of("CUSTOM_IMAGES", "SSBO"), r.requiredFeatures());
    }

    @Test
    void rayQueryOnlyPack() throws IOException {
        Path p = zip("Rq.zip", "shaders/composite.fsh",
            "#version 460\n#extension GL_EXT_ray_query : require\nvoid main(){ rayQueryEXT q; rayQueryInitializeEXT(q, acc, 0u, 0xFFu, o, 0.0, d, 1.0); }\n");
        try (PackSource s = PackSource.open(p, ScanLimits.DEFAULT)) {
            PackReport r = ShaderpackScanner.scan(s);
            assertEquals(PackReport.RtModel.RAY_QUERY, r.rtModel());
            assertTrue(r.extraDeviceExtensions().isEmpty(), "ray query is already registered by the core");
        }
    }

    @Test
    void notAShaderpack() throws IOException {
        Path p = zip("Res.zip", "assets/minecraft/x.png", "x");
        try (PackSource s = PackSource.open(p, ScanLimits.DEFAULT)) {
            assertThrows(PackScanException.class, () -> ShaderpackScanner.scan(s));
        }
    }

    @Test
    void cacheByContentHash() throws IOException {
        Path a = zip("A.zip", "shaders/final.fsh", "#version 150\n");
        Path b = tmp.resolve("B.zip");
        Files.copy(a, b);
        PackReportCache cache = new PackReportCache(ScanLimits.DEFAULT);
        PackReport ra = cache.get(a);
        PackReport ra2 = cache.get(a);
        assertEquals(1, cache.scans(), "unchanged fingerprint must not rescan");
        assertEquals(ra.sha256(), ra2.sha256());
        PackReport rb = cache.get(b);
        assertEquals(2, cache.scans(), "new path is hashed once");
        assertEquals(1, cache.distinctContents(), "identical content, one report");
        assertEquals(ra.sha256(), rb.sha256());
        assertEquals("B.zip", rb.name());

        Path a2 = zip("A.zip", "shaders/final.fsh", "#version 330\n");
        Files.setLastModifiedTime(a2, FileTime.fromMillis(Files.getLastModifiedTime(a2).toMillis() + 5000));
        PackReport changed = cache.get(a2);
        assertNotEquals(ra.sha256(), changed.sha256());
        assertEquals(3, cache.scans());
    }

    @Test
    void hashIsIndependentOfZipOrderAndContainer() throws IOException {
        Path a = zip("O1.zip", "shaders/a.fsh", "1", "shaders/b.fsh", "2");
        Path b = zip("O2.zip", "shaders/b.fsh", "2", "shaders/a.fsh", "1");
        Path dir = tmp.resolve("O3");
        Files.createDirectories(dir.resolve("shaders"));
        Files.writeString(dir.resolve("shaders/a.fsh"), "1");
        Files.writeString(dir.resolve("shaders/b.fsh"), "2");
        PackReportCache cache = new PackReportCache(ScanLimits.DEFAULT);
        assertEquals(cache.get(a).sha256(), cache.get(b).sha256());
        assertEquals(cache.get(a).sha256(), cache.get(dir).sha256());
    }
}
