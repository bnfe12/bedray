package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Native/legacy binding adapters share one unchanged march/store body; no GPU or host hookup. */
class RtSlangVolumetricSceneTest {
    private static final String ROOT = "src/main/resources/shaders/slang/";
    private static final String KERNEL = "light/volumetric_march_entry.slang";

    @Test void theExtractedKernelPreservesTheExistingMarchAndTemporalArithmetic() throws Exception {
        String body = block(read(KERNEL), "void volumetricMarchEntry<");
        // Fingerprint of the pre-extraction main body on 0898b004, with only its
        // four LegacySectionTraversal setup statements removed; whitespace ignored.
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(body.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8)));
        assertEquals("d0b6a58f09d5e34d59db20268af6e559de94e5be8959e88745fc642b2d379cf7", fingerprint,
            "an interface extraction must not silently change the ray, history or accumulation math");
        assertFalse(read(KERNEL).contains("[[vk::binding("), "the shared kernel has no descriptor ownership");
        assertFalse(read(KERNEL).contains("LegacySectionTraversal"));
        assertFalse(read(KERNEL).contains("HardwareTraversal"));
    }

    @Test void onlyTheSectionProviderChangesAtTheTwoBindingBoundaries() {
        String legacy = read("passes/volumetric_march.slang");
        String scene = read("passes/volumetric_march_scene.slang");
        for (String entry : new String[]{legacy, scene}) {
            assertEquals(5, entry.split("\\[\\[vk::binding\\(", -1).length - 1);
            assertTrue(entry.contains("[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;"));
            assertTrue(entry.contains("[[vk::image_format(\"r32f\")]] [[vk::binding(12, 0)]] RWTexture2D<float> outDepth;"));
            assertTrue(entry.contains("[[vk::image_format(\"rgba32f\")]] [[vk::binding(11, 0)]] RWTexture2D<float4> accumImage;"));
            assertTrue(entry.contains("[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;"));
            assertTrue(entry.contains("[[vk::push_constant]] VolumetricMarchPush pc;"));
            assertTrue(entry.contains("[numthreads(8, 8, 1)]"));
            assertTrue(entry.contains("volumetricMarchEntry(traversal, dispatchThreadID, outDepth, accumImage, frame, pc);"));
            assertFalse(entry.contains("marchLightShaft("), "march/store arithmetic must not be duplicated");
        }
        assertTrue(legacy.contains("[[vk::binding(2, 0)]] StructuredBuffer<LegacySection> sections;"));
        assertTrue(legacy.contains("traversal.sections = sections;"));
        assertTrue(legacy.contains("traversal.instanceMask = pc.instanceMask;"));
        assertTrue(scene.contains("[[vk::binding(2, 0)]] StructuredBuffer<GeometrySection> sections;"));
        assertTrue(scene.contains("GeometryProvider geometry;"));
        assertTrue(scene.contains("geometry.sections = sections;"));
        assertTrue(scene.contains("HardwareTraversal traversal;"));
        assertTrue(scene.contains("traversal.geometry = geometry;"));
        assertTrue(scene.contains("traversal.instanceMask = HARDWARE_INSTANCE_MASK_WORLD;"));
        assertFalse(scene.contains("SceneTraversal"), "entity decoding would require additional bound streams");
        assertFalse(scene.contains("entities ="));
    }

    @Test void nativeSectionIdentityComesFromItsFieldsRatherThanTheEqualByteSize() {
        String nativeSection = withoutComments(block(read("contracts/geometry.slang"), "struct GeometrySection {"))
            .replaceAll("\\s+", "");
        assertEquals("{int4origin;uint4meta;uint64_tvertexAddress;GeometryRangeranges[MAX_SECTION_GEOMETRIES];}", nativeSection);
        String legacySection = withoutComments(block(read("traversal/legacy_section_traversal.slang"), "struct LegacySection {"))
            .replaceAll("\\s+", "");
        assertEquals("{uint64_tposAddr;uint64_tuvAddr;uint64_tcolAddr;uint64_tlightAddr;uint4quadStart;int4origin;}", legacySection);
        assertNotEquals(nativeSection, legacySection, "both are 64 B but their addresses/origin/identity are incompatible");
        String hardware = read("traversal/hardware_traversal.slang");
        assertTrue(hardware.contains("static const uint HARDWARE_INSTANCE_MASK_WORLD    = 0x01u;"));
        assertTrue(hardware.contains("geometry.sections[instanceId]"));
        assertTrue(hardware.contains("result.sectionId = uint(section.origin.w);"));
    }

    @Test void theSharedPushKeepsEveryOriginalMemberTypeAndOrder() {
        String push = withoutComments(block(read(KERNEL), "struct VolumetricMarchPush {"))
            .replaceAll("\\s+", "");
        assertEquals("{float3keyDir;floatstrength;float3keyColor;floatrange;float3prevOriginDelta;floatrain;"
            + "floatexposure;floatinscatter;floatdensity;floatworldHeightOffset;floatrayBias;uintsteps;"
            + "uintframeIndex;uinthistoryValid;uintflipY;uintinstanceMask;uintpad0;}", push,
            "same 96-byte size alone would not detect a reordered scalar in the host push contract");
    }

    @Test void compiledMarchManifestsKeepTheSamePushResourceAndCapabilityContract() {
        Assumptions.assumeTrue(SlangPassLibrary.available("volumetric_march")
            && SlangPassLibrary.available("volumetric_march_scene"), "both volumetric entries were not selected for this build");
        for (String name : new String[]{"volumetric_march", "volumetric_march_scene"}) {
            var manifest = SlangPassLibrary.load(name).orElseThrow().manifest();
            assertEquals(96, manifest.pushConstantBytes());
            assertArrayEquals(new int[]{8, 8, 1}, manifest.threadGroupSize());
            assertEquals(5, manifest.bindings().size());
            requireBinding(manifest, "tlas", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0);
            requireBinding(manifest, "sections", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER, 64);
            requireBinding(manifest, "accumImage", 11, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE, 0);
            requireBinding(manifest, "outDepth", 12, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE, 0);
            requireBinding(manifest, "frame", 16, BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER, 608);
            assertEquals(BedrayCoreLayouts.CAP_RAY_QUERY | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS
                | BedrayCoreLayouts.CAP_SHADER_INT64, manifest.requiredCapabilities());
        }
    }

    private static void requireBinding(SlangPassManifest manifest, String name, int index, int type, int bytes) {
        var binding = manifest.binding(name);
        assertNotNull(binding, name);
        assertEquals(index, binding.binding(), name);
        assertEquals(type, binding.descriptorType(), name);
        assertEquals(bytes, binding.elementBytes(), name);
    }

    private static String read(String relative) {
        try { return Files.readString(Path.of(ROOT + relative)).replace("\r\n", "\n"); }
        catch (IOException failure) { throw new AssertionError("cannot read " + relative, failure); }
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String block(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(start >= 0, header);
        int begin = source.indexOf('{', start), depth = 0;
        for (int i = begin; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return source.substring(begin, i + 1);
        }
        throw new AssertionError("unclosed block " + header);
    }
}
