package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The game's multi-pass host: a Buffer graph compiles into a chain of contract-checked passes sharing one layout. */
class CustomRtChainTest {

    /** Bloom at 1/2 and 1/4, a temporal blend with the previous frame and a rain-dependent tint. */
    static final String BLOOM_RAIN = "{\"nodes\": ["
        + "{\"id\": \"c\", \"type\": \"mosaic.sceneColor\"},"
        + "{\"id\": \"bright\", \"type\": \"mosaic.math\", \"mask\": \"maximum\", \"inputs\": {\"a\": \"c\"}, \"defaults\": {\"b\": [0.7]}},"
        + "{\"id\": \"p1\", \"type\": \"mosaic.pass\", \"mask\": \"bright\", \"index\": 2, \"inputs\": {\"color\": \"bright\"}},"
        + "{\"id\": \"uv\", \"type\": \"mosaic.uv\"},"
        + "{\"id\": \"off\", \"type\": \"mosaic.const\", \"value\": [0.004, 0.0]},"
        + "{\"id\": \"uvA\", \"type\": \"mosaic.add\", \"inputs\": {\"a\": \"uv\", \"b\": \"off\"}},"
        + "{\"id\": \"uvB\", \"type\": \"mosaic.sub\", \"inputs\": {\"a\": \"uv\", \"b\": \"off\"}},"
        + "{\"id\": \"r1\", \"type\": \"mosaic.bufferIn\", \"mask\": \"bright\", \"inputs\": {\"uv\": \"uvA\"}},"
        + "{\"id\": \"r2\", \"type\": \"mosaic.bufferIn\", \"mask\": \"bright\", \"inputs\": {\"uv\": \"uvB\"}},"
        + "{\"id\": \"avg\", \"type\": \"mosaic.mix\", \"inputs\": {\"a\": \"r1\", \"b\": \"r2\"}},"
        + "{\"id\": \"p2\", \"type\": \"mosaic.pass\", \"mask\": \"blur\", \"index\": 4, \"inputs\": {\"color\": \"avg\"}},"
        + "{\"id\": \"glow\", \"type\": \"mosaic.bufferIn\", \"mask\": \"blur\"},"
        + "{\"id\": \"sum\", \"type\": \"mosaic.add\", \"inputs\": {\"a\": \"c\", \"b\": \"glow\"}},"
        + "{\"id\": \"last\", \"type\": \"mosaic.history\"},"
        + "{\"id\": \"taa\", \"type\": \"mosaic.mix\", \"inputs\": {\"a\": \"sum\", \"b\": \"last\"}},"
        + "{\"id\": \"rain\", \"type\": \"mosaic.rain\"},"
        + "{\"id\": \"wet\", \"type\": \"mosaic.mix\", \"inputs\": {\"a\": \"taa\", \"b\": \"c\", \"t\": \"rain\"}},"
        + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"wet\"}}]}";

    @Test
    void bufferGraphCompilesIntoAChainTheHostAccepts() throws Exception {
        Path pack = Files.createTempDirectory("mosaic-chain");
        Path bedray = Files.createDirectories(pack.resolve("shaders/bedray"));
        Files.writeString(bedray.resolve("mosaic.json"), BLOOM_RAIN, StandardCharsets.UTF_8);
        String previous = System.getProperty("bedray.mosaic.slang");
        System.setProperty("bedray.mosaic.slang", "off"); // the GLSL path is the one every machine has
        try (PackSource src = PackSource.open(pack, ScanLimits.defaults())) {
            VulkanGlslPackCompiler.Compiled last = CustomRtStage.compileStage(src, CustomRtStage.MOSAIC);
            List<CustomRtExecutor.ChainPass> chain = CustomRtStage.pendingChain;
            assertEquals(2, chain.size(), "bright and blur run before the final image");
            assertEquals(2, chain.get(0).scale());
            assertEquals(4, chain.get(1).scale());
            assertNotEquals(chain.get(0).binding(), chain.get(1).binding());
            assertNull(CustomRtStage.validate(last));
            for (CustomRtExecutor.ChainPass p : chain) assertNull(CustomRtStage.validate(p.compiled()));

            SpirvReflection.Module union = CustomRtStage.unionModule(last, chain);
            Set<Integer> bound = new java.util.TreeSet<>();
            for (SpirvReflection.Binding b : union.bindings()) bound.add(b.binding());
            assertTrue(bound.contains(CustomRtExecutor.HISTORY_BINDING), "previous frame: " + bound);
            assertTrue(bound.contains(CustomRtExecutor.WORLD_INFO_BINDING), "weather: " + bound);
            assertTrue(bound.contains(chain.get(0).binding()) && bound.contains(chain.get(1).binding()), "buffers: " + bound);

            // host-produced bindings never ask the pack for buffer16.bin / texture19.png / texture20.png
            CustomRtResources res = CustomRtResources.build(union, CustomRtStage.hostBindings(true, chain), "shaders/",
                (name, what) -> { throw new IllegalArgumentException("asked the pack for " + name); }, null);
            assertTrue(res.bufferSpecs().isEmpty() && res.textureSpecs().isEmpty());
        } finally {
            if (previous == null) System.clearProperty("bedray.mosaic.slang");
            else System.setProperty("bedray.mosaic.slang", previous);
        }
    }

    /** Temporal AA: the previous frame read where this pixel was last frame, blended 90 %. */
    static final String TAA = "{\"nodes\": ["
        + "{\"id\": \"c\", \"type\": \"mosaic.sceneColor\"},"
        + "{\"id\": \"r\", \"type\": \"mosaic.reproject\"},"
        + "{\"id\": \"h\", \"type\": \"mosaic.history\", \"inputs\": {\"uv\": \"r\"}},"
        + "{\"id\": \"m\", \"type\": \"mosaic.mix\", \"inputs\": {\"a\": \"c\", \"b\": \"h\"}, \"defaults\": {\"t\": [0.9]}},"
        + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"m\"}}]}";

    @Test
    void reprojectedHistoryIsHostProduced() throws Exception {
        Path pack = Files.createTempDirectory("mosaic-taa");
        Files.writeString(Files.createDirectories(pack.resolve("shaders/bedray")).resolve("mosaic.json"), TAA, StandardCharsets.UTF_8);
        String previous = System.getProperty("bedray.mosaic.slang");
        System.setProperty("bedray.mosaic.slang", "off");
        try (PackSource src = PackSource.open(pack, ScanLimits.defaults())) {
            VulkanGlslPackCompiler.Compiled c = CustomRtStage.compileStage(src, CustomRtStage.MOSAIC);
            assertNull(CustomRtStage.validate(c));
            Set<Integer> bound = new java.util.TreeSet<>();
            for (SpirvReflection.Binding b : c.module().bindings()) bound.add(b.binding());
            assertTrue(bound.containsAll(Set.of(CustomRtStage.B_DEPTH, CustomRtExecutor.PREV_FRAME_BINDING, CustomRtExecutor.HISTORY_BINDING)), "" + bound);
            CustomRtResources res = CustomRtResources.build(c.module(), CustomRtStage.hostBindings(true, List.of()), "shaders/",
                (name, what) -> { throw new IllegalArgumentException("asked the pack for " + name); }, null);
            assertTrue(res.bufferSpecs().isEmpty());
        } finally {
            if (previous == null) System.clearProperty("bedray.mosaic.slang");
            else System.setProperty("bedray.mosaic.slang", previous);
        }
    }

    @Test
    void studioSpirvOfEveryPassIsTakenByTheGame() throws Exception {
        java.nio.file.Path slangc = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        org.junit.jupiter.api.Assumptions.assumeTrue(slangc != null, "slangc not installed");
        Path pack = Files.createTempDirectory("mosaic-chain-spv");
        Path bedray = Files.createDirectories(pack.resolve("shaders/bedray"));
        Files.writeString(bedray.resolve("mosaic.json"), BLOOM_RAIN, StandardCharsets.UTF_8);
        var plan = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicPasses.plan(
            io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.parse(BLOOM_RAIN));
        for (var p : plan.passes()) { // what Mosaic Studio ships next to mosaic.json
            var gen = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicCodegen.generate(
                io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.parse(p.json()),
                io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.PREBUILT_OPTIONS, true);
            var r = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.compile(gen, slangc);
            assertTrue(r.ok(), p.name() + ": " + r.errors());
            String name = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.prebuiltName(p.name());
            Files.write(bedray.resolve(name), r.spirv());
            Files.writeString(bedray.resolve(name + ".json"), io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.sidecar(
                io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.sourceHash(gen.source()),
                new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSlang.Result("test", r.spirv().length)));
        }
        String previous = System.getProperty("bedray.mosaic.slang");
        System.clearProperty("bedray.mosaic.slang");
        String previousSlangc = System.getProperty("bedray.slangc");
        System.setProperty("bedray.slangc", pack.resolve("no-slangc-here.exe").toString()); // the game must not need slangc
        try (PackSource src = PackSource.open(pack, ScanLimits.defaults())) {
            VulkanGlslPackCompiler.Compiled last = CustomRtStage.compileStage(src, CustomRtStage.MOSAIC);
            assertEquals("studio SPIR-V", CustomRtStage.lastMosaicCompiler);
            assertNull(CustomRtStage.validate(last));
            assertEquals(2, CustomRtStage.pendingChain.size());
            for (CustomRtExecutor.ChainPass p : CustomRtStage.pendingChain) {
                assertTrue(p.compiled().entry().endsWith(".spv"), "pass " + p.binding() + " from " + p.compiled().entry());
                assertNull(CustomRtStage.validate(p.compiled()));
            }
        } finally {
            if (previous != null) System.setProperty("bedray.mosaic.slang", previous);
            if (previousSlangc == null) System.clearProperty("bedray.slangc");
            else System.setProperty("bedray.slangc", previousSlangc);
        }
    }

    @Test
    void rasterDepthPassCompilesWithItsThreeBindings() throws Exception {
        java.nio.ByteBuffer spv = io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler.compile(CustomRtExecutor.RASTER_DEPTH_GLSL,
            org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader, "raster-depth");
        SpirvReflection.Module m = SpirvReflection.reflect(spv.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN));
        assertEquals(List.of(SpirvReflection.DescriptorKind.COMBINED_IMAGE_SAMPLER, SpirvReflection.DescriptorKind.UNIFORM_BUFFER,
            SpirvReflection.DescriptorKind.STORAGE_IMAGE), m.bindings().stream().map(SpirvReflection.Binding::kind).toList());
        assertTrue(m.pushConstants());
        assertArrayEquals(new int[] {8, 8, 1}, m.localSize());
    }

    @Test
    void conflictingBindingKindsAcrossPassesAreRefused() {
        var a = new SpirvReflection.Module(SpirvReflection.Stage.COMPUTE, Set.of(),
            List.of(new SpirvReflection.Binding(0, 7, SpirvReflection.DescriptorKind.UNIFORM_BUFFER, 1, "a")), false, new int[] {8, 8, 1});
        var b = new SpirvReflection.Module(SpirvReflection.Stage.COMPUTE, Set.of(),
            List.of(new SpirvReflection.Binding(0, 7, SpirvReflection.DescriptorKind.STORAGE_BUFFER, 1, "b")), false, new int[] {8, 8, 1});
        var last = new VulkanGlslPackCompiler.Compiled("a", VulkanGlslPackCompiler.Kind.COMPUTE, null, a, false);
        var pass = new VulkanGlslPackCompiler.Compiled("b", VulkanGlslPackCompiler.Kind.COMPUTE, null, b, false);
        assertThrows(IllegalArgumentException.class,
            () -> CustomRtStage.unionModule(last, List.of(new CustomRtExecutor.ChainPass(pass, 1, 20))));
    }
}
