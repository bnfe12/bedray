package io.github.andrexarlay.vulkancompat.rt.slang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the AOT Slang artifacts the {@code compileSlang} task produces. Skips when the build had no Slang SDK,
 * because then there is nothing to load and the runtime reports the missing AOT path.
 */
class SlangPassLibraryTest {
    @Test
    void heapAndDirectPushConstantsHaveNativeStorageAndKeepTheInputPosition() {
        try (Arena arena = Arena.ofConfined()) {
            for (ByteBuffer buffer : List.of(ByteBuffer.allocate(12), ByteBuffer.allocateDirect(12))) {
                buffer.position(2);
                buffer.put(2, (byte) 0x42).put(5, (byte) 0x7f);
                MemorySegment bytes = SlangProgram.nativePushConstants(arena, buffer, 4);
                assertTrue(bytes.isNative());
                assertEquals(4, bytes.byteSize());
                assertEquals((byte) 0x42, bytes.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
                assertEquals((byte) 0x7f, bytes.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 3));
                assertEquals(2, buffer.position());
            }
        }
    }

    @Test
    void everyIndexedPassLoadsWithAConsistentManifest() {
        List<String> names = SlangPassLibrary.names();
        Assumptions.assumeFalse(names.isEmpty(), "no Slang pass was compiled into this build");

        for (String name : names) {
            Optional<SlangPass> loaded = SlangPassLibrary.load(name);
            assertTrue(loaded.isPresent(), () -> "index lists " + name + " but it does not load");
            SlangPass pass = loaded.get();
            SlangPassManifest manifest = pass.manifest();
            assertEquals(name, manifest.name());
            assertEquals(0, manifest.descriptorSet(), "ABI v2 has one descriptor set");
            assertEquals("compute", manifest.stage());
            assertTrue(manifest.pushConstantBytes() <= 128, "push constants fit the Vulkan minimum");
            assertTrue(pass.wordCount() > 5, "SPIR-V carries at least the header");
            assertEquals(pass.wordCount(), manifest.spirvWordCount());
            for (SlangPassManifest.Binding binding : manifest.bindings()) {
                assertEquals(1, binding.count(), "ABI v2 registers one descriptor per binding");
                assertFalse(binding.name().isBlank());
                if (binding.descriptorType() == BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER
                    || binding.descriptorType() == BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
                    assertTrue(binding.elementBytes() > 0, () -> manifest.name() + ": buffer '" + binding.name()
                        + "' has no reflected element size");
                } else {
                    assertEquals(0, binding.elementBytes(), manifest.name() + ": image '" + binding.name()
                        + "' must not carry an element size");
                }
            }
        }
    }

    @Test
    void displayHistorySourcesHaveSeparateBindingsFromDestinations() {
        SlangPassManifest manifest = SlangPassLibrary.load("path_display").orElseThrow().manifest();
        assertEquals(9, manifest.bindings().size());
        assertBinding(manifest, "accum", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "historyDepth", 4, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "previousAccum", 7, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "previousDepth", 8, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
    }

    @Test
    void compositePassKeepsItsLegacyBindingContract() {
        Assumptions.assumeTrue(SlangPassLibrary.available("composite"), "composite.slang was not compiled");

        SlangPass pass = SlangPassLibrary.load("composite").orElseThrow();
        SlangPassManifest manifest = pass.manifest();
        assertEquals(5, manifest.bindings().size());
        assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            manifest.binding("rasterColor").descriptorType());
        assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE,
            manifest.binding("outComposite").descriptorType());
        assertEquals(0, manifest.binding("rasterColor").binding());
        assertEquals(4, manifest.binding("outComposite").binding());
        assertEquals(80, manifest.pushConstantBytes());
        assertEquals(8, manifest.threadGroupSize()[0]);
        assertEquals(8, manifest.threadGroupSize()[1]);
        assertEquals(1, manifest.threadGroupSize()[2]);
        assertEquals(240, manifest.groups(1920, 0));
        assertEquals(135, manifest.groups(1080, 1));
    }

    /**
     * The four bloom entry passes are dispatched by name from {@code RtBloomPass}; a shader edit that moves a
     * binding, changes the 128-byte block or the thread group must fail here instead of producing a wrong dispatch.
     */
    @Test
    void bloomPassesKeepTheHostContract() {
        Assumptions.assumeTrue(SlangPassLibrary.available("bloom_prefilter_down")
            && SlangPassLibrary.available("bloom_down")
            && SlangPassLibrary.available("bloom_up")
            && SlangPassLibrary.available("bloom_apply"), "bloom passes were not compiled");

        SlangPassManifest prefilter = SlangPassLibrary.load("bloom_prefilter_down").orElseThrow().manifest();
        assertBinding(prefilter, "srcTex", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBinding(prefilter, "dstImg", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(prefilter, "depthTex", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBloomShape(prefilter, 3);

        SlangPassManifest down = SlangPassLibrary.load("bloom_down").orElseThrow().manifest();
        assertBinding(down, "srcTex", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBinding(down, "dstImg", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBloomShape(down, 2);

        SlangPassManifest up = SlangPassLibrary.load("bloom_up").orElseThrow().manifest();
        assertBinding(up, "srcTex", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBinding(up, "dstImg", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBloomShape(up, 2);

        SlangPassManifest apply = SlangPassLibrary.load("bloom_apply").orElseThrow().manifest();
        assertBinding(apply, "srcTex", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBinding(apply, "finalImg", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(apply, "depthTex", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBloomShape(apply, 3);
    }

    /**
     * The path-trace entry is the first pass that reads the legacy section table. Its manifest is the host contract:
     * the device-address reads must keep requiring Int64 + buffer-device-address, and the geometry adapter must stay
     * bound to the table RtWorld writes.
     */
    @Test
    void pathTracePassKeepsTheLegacyGeometryContract() {
        Assumptions.assumeTrue(SlangPassLibrary.available("path_trace"), "path_trace.slang was not compiled");

        SlangPassManifest manifest = SlangPassLibrary.load("path_trace").orElseThrow().manifest();
        assertEquals(28, manifest.bindings().size(), "path_trace: binding count (26 + the ReSTIR PT ring at 36 + the stats slot at 37)");
        assertBinding(manifest, "tlas", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
        assertBinding(manifest, "outImage", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "sections", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(64, manifest.binding("sections").elementBytes(), "LegacySection is the 64-byte RtWorld table entry");
        assertBinding(manifest, "albedoAtlas", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        // The primary surface's own displacement, which is the only way a moved mob can be found again next frame.
        assertBinding(manifest, "outMotion", 5, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "materialAtlas", 9, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBinding(manifest, "normalAtlas", 10, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBinding(manifest, "outDepth", 12, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "frame", 16, BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        assertEquals(608, manifest.binding("frame").elementBytes(), "FrameConstants stays 608 bytes");
        assertBinding(manifest, "blockMaterials", 17, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(64, manifest.binding("blockMaterials").elementBytes(), "BlockMaterial stays 64 bytes");
        assertBinding(manifest, "emitters", 18, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(96, manifest.binding("emitters").elementBytes(),
            "BlockEmitterRecord is the 96-byte RtEmitters record, so one upload serves both paths");
        assertBinding(manifest, "reservoirs", 19, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(64, manifest.binding("reservoirs").elementBytes(),
            "DIReservoir is the 64-byte ReSTIR record: sample, stats, source, receiver");
        // The entity ring is the second geometry source of the scene: the path tracer reads the same
        // uploaded vertex/index/attr buffers the GLSL path bound, each at its own device offset.
        assertBinding(manifest, "entityPositions", 20, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(4, manifest.binding("entityPositions").elementBytes(), "one float bit-pattern per position");
        assertBinding(manifest, "entityIndices", 21, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(4, manifest.binding("entityIndices").elementBytes(), "one index per corner");
        assertBinding(manifest, "entityAttrs", 22, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(16, manifest.binding("entityAttrs").elementBytes(), "colour, light and uv as one uint4");
        assertBinding(manifest, "entityRects", 23, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(16, manifest.binding("entityRects").elementBytes(), "an atlas rect is a uint4");
        assertBinding(manifest, "entityTexels", 24, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(4, manifest.binding("entityTexels").elementBytes(), "ABGR8 texels");
        assertBinding(manifest, "entityPrevious", 25, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(4, manifest.binding("entityPrevious").elementBytes(),
            "the previous-frame position ring is the same float3 layout as the current one");
        // The seven DLSS-RR guide channels, in the order the denoiser exposes gbufferViews(): view depth, normal +
        // roughness, specular radiance, diffuse radiance + hit distance, albedo, direct light, specular albedo.
        assertBinding(manifest, "outViewZ", 26, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "outNormalRough", 27, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "outSpecular", 28, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "outDiffuse", 29, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "outAlbedo", 30, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "outDirect", 31, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "outSpecAlbedo", 32, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        // NRD's IN_MV channel: the per-pixel vec4 rt_pack_nrd.comp reads at StatsBuf.motionAddr, bound as a storage
        // buffer because an RGBA16F image's 8 bytes per pixel cannot hold the legacy 16-byte record the pack expects.
        assertBinding(manifest, "outMotionBuffer", 33, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(16, manifest.binding("outMotionBuffer").elementBytes(),
            "one vec4 (motion xyz + entity flag) per render pixel, as in rt_pack_nrd.comp's Vec4Buf");
        assertEquals(112, manifest.pushConstantBytes(),
            "path_trace: the 96-byte DI block plus the 16-byte entity state of this frame");
        assertEquals(BedrayCoreLayouts.CAP_RAY_QUERY | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS
            | BedrayCoreLayouts.CAP_SHADER_INT64, manifest.requiredCapabilities(),
            "path_trace: ray query + device addresses + 64-bit integers");
        assertEquals(8, manifest.threadGroupSize()[0], "path_trace: group x");
        assertEquals(8, manifest.threadGroupSize()[1], "path_trace: group y");
        assertEquals(1, manifest.threadGroupSize()[2], "path_trace: group z");
    }

    /**
     * The native-scene entry is the same program with a different binding 2 and traversal backend. Both manifests must
     * agree on every descriptor, the push block, the capability bits and the workgroup, otherwise the host would need a
     * second contract and the two entries could silently drift apart.
     */
    @Test
    void pathTraceScenePassKeepsTheSameHostContractAsTheLegacyEntry() {
        Assumptions.assumeTrue(SlangPassLibrary.available("path_trace"), "path_trace.slang was not compiled");
        Assumptions.assumeTrue(SlangPassLibrary.available("path_trace_scene"), "path_trace_scene.slang was not compiled");

        SlangPassManifest legacy = SlangPassLibrary.load("path_trace").orElseThrow().manifest();
        SlangPassManifest scene = SlangPassLibrary.load("path_trace_scene").orElseThrow().manifest();

        assertEquals(legacy.bindings().size(), scene.bindings().size(), "path_trace_scene: binding count");
        for (SlangPassManifest.Binding want : legacy.bindings()) {
            SlangPassManifest.Binding actual = scene.binding(want.name());
            assertTrue(actual != null, () -> "path_trace_scene has no binding '" + want.name() + "'");
            assertEquals(want.binding(), actual.binding(), "path_trace_scene: binding of " + want.name());
            assertEquals(want.descriptorType(), actual.descriptorType(), "path_trace_scene: type of " + want.name());
            assertEquals(want.count(), actual.count(), "path_trace_scene: count of " + want.name());
            assertEquals(want.elementBytes(), actual.elementBytes(), "path_trace_scene: element of " + want.name());
        }
        assertEquals(legacy.pushConstantBytes(), scene.pushConstantBytes(), "path_trace_scene: push block");
        assertEquals(legacy.requiredCapabilities(), scene.requiredCapabilities(),
            "path_trace_scene: capability bits must match the shared body");
        assertEquals(legacy.threadGroupSize()[0], scene.threadGroupSize()[0], "path_trace_scene: group x");
        assertEquals(legacy.threadGroupSize()[1], scene.threadGroupSize()[1], "path_trace_scene: group y");
        assertEquals(legacy.threadGroupSize()[2], scene.threadGroupSize()[2], "path_trace_scene: group z");
    }

    private static void assertBinding(SlangPassManifest manifest, String name, int binding, int type) {        SlangPassManifest.Binding actual = manifest.binding(name);
        assertTrue(actual != null, () -> manifest.name() + " has no binding '" + name + "'");
        assertEquals(binding, actual.binding(), manifest.name() + ": binding of " + name);
        assertEquals(type, actual.descriptorType(), manifest.name() + ": type of " + name);
        assertEquals(1, actual.count(), manifest.name() + ": count of " + name);
    }

    private static void assertBloomShape(SlangPassManifest manifest, int bindings) {
        assertEquals(0, manifest.descriptorSet(), manifest.name() + ": one descriptor set");
        assertEquals(bindings, manifest.bindings().size(), manifest.name() + ": binding count");
        assertEquals(128, manifest.pushConstantBytes(), manifest.name() + ": push constant block");
        assertEquals(0, manifest.requiredCapabilities(), manifest.name() + ": bloom needs no optional capability");
        assertEquals(8, manifest.threadGroupSize()[0], manifest.name() + ": group x");
        assertEquals(8, manifest.threadGroupSize()[1], manifest.name() + ": group y");
        assertEquals(1, manifest.threadGroupSize()[2], manifest.name() + ": group z");
    }

    /**
     * A pass that uses 64-bit integers or physical-storage-buffer addresses must declare the ABI bits the host has
     * to enable on the logical device; the build writes both the names and the mask, and the runtime checks them.
     */
    @Test
    void capabilityRequirementsAreParsedAndUnknownBitsAreRejected() {
        JsonObject json = JsonParser.parseString("""
            {"schema":1,"name":"x","entryPoint":"main","stage":"compute","descriptorSet":0,
             "threadGroupSize":[8,8,1],"pushConstantBytes":0,"spirvWordCount":5,
             "requiredCapabilities":517,"spirvCapabilities":["Int64","RayQueryKHR"],"bindings":[]}
            """).getAsJsonObject();
        SlangPassManifest manifest = SlangPassManifest.parse(json, "x");
        assertEquals(BedrayCoreLayouts.CAP_SHADER_INT64 | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS
            | BedrayCoreLayouts.CAP_RAY_QUERY, manifest.requiredCapabilities());
        assertEquals(List.of("Int64", "RayQueryKHR"), manifest.spirvCapabilities());

        JsonObject unknown = JsonParser.parseString("""
            {"schema":1,"name":"x","entryPoint":"main","stage":"compute","descriptorSet":0,
             "threadGroupSize":[8,8,1],"pushConstantBytes":0,"spirvWordCount":5,
             "requiredCapabilities":1048576,"bindings":[]}
            """).getAsJsonObject();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> SlangPassManifest.parse(unknown, "x"));
        assertTrue(error.getMessage().contains("does not define"), error.getMessage());
    }

    @Test
    void anUncompiledPassIsReportedAsAbsentNotAsAnError() {
        assertTrue(SlangPassLibrary.load("does.not.exist").isEmpty());
        assertFalse(SlangPassLibrary.available("does.not.exist"));
    }

    @Test
    void manifestsThatBreakAbiV2AreRejectedAtLoadTime() {
        JsonObject secondSet = JsonParser.parseString("""
            {"schema":1,"name":"x","entryPoint":"main","stage":"compute","descriptorSet":1,
             "threadGroupSize":[8,8,1],"pushConstantBytes":0,"spirvWordCount":5,"bindings":[]}
            """).getAsJsonObject();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> SlangPassManifest.parse(secondSet, "x"));
        assertTrue(error.getMessage().contains("one descriptor set"), error.getMessage());

        JsonObject arrayBinding = JsonParser.parseString("""
            {"schema":1,"name":"x","entryPoint":"main","stage":"compute","descriptorSet":0,
             "threadGroupSize":[8,8,1],"pushConstantBytes":0,"spirvWordCount":5,
             "bindings":[{"name":"t","binding":0,"descriptorType":"COMBINED_IMAGE_SAMPLER","count":4}]}
            """).getAsJsonObject();
        error = assertThrows(IllegalArgumentException.class, () -> SlangPassManifest.parse(arrayBinding, "x"));
        assertTrue(error.getMessage().contains("one descriptor per binding"), error.getMessage());
    }
}
