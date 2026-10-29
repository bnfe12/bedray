package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-5: the std430 push block of {@code velocity_reproject} relied on unnamed padding. {@code float2
 * jitterPrevious} landed at offset 64 because four bytes after {@code entityTeleportThreshold} were implicitly
 * skipped, and the 16-byte struct alignment added eight unnamed tail bytes. A host writer that fills the block
 * field by field can silently put the jitter at 60 instead.
 * <p>
 * The block remains 96 bytes. Every gap is now a named field, and the compiled manifest is the second half of
 * the contract.
 */
class VelocityPushLayoutTest {
    private static final String SOURCE = "src/main/resources/shaders/slang/passes/velocity_reproject.slang";

    @Test
    void everyGapInThePushBlockIsNamed() throws IOException {
        String source = Files.readString(Path.of(SOURCE));
        int teleport = source.indexOf("float  entityTeleportThreshold;");
        int padJitter = source.indexOf("uint   padJitter;");
        int jitter = source.indexOf("float2 jitterPrevious;");
        assertTrue(teleport >= 0, "entityTeleportThreshold is the field before the implicit gap");
        assertTrue(padJitter > teleport && jitter > padJitter,
            "the four bytes before jitterPrevious must be an explicit padJitter field");

        int pad1 = source.indexOf("uint   pad1;");
        int pad2 = source.indexOf("uint2  pad2;");
        int close = source.indexOf("};", pad2);
        assertTrue(pad1 >= 0 && pad2 > pad1 && close > pad2,
            "the eight tail bytes must be the explicit pad2 field");
        assertEquals(1, count(source, "float2 jitterPrevious;"), "the jitter is declared exactly once");
        assertEquals(1, count(source, "uint2  pad2;"), "the tail is declared exactly once");
    }

    @Test
    void theCompiledBlockStays96Bytes() {
        Assumptions.assumeTrue(SlangPassLibrary.available("velocity_reproject"),
            "velocity_reproject.slang was not compiled");

        SlangPassManifest manifest = SlangPassLibrary.load("velocity_reproject").orElseThrow().manifest();
        assertEquals(96, manifest.pushConstantBytes(),
            "naming the gaps must not change the reflected block size");
        assertEquals(6, manifest.bindings().size(), "velocity_reproject: binding count");
        assertBinding(manifest, "depthTexture", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        assertBinding(manifest, "outMotion", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
        assertBinding(manifest, "prevDepth", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(4, manifest.binding("prevDepth").elementBytes(), "one linear view Z per pixel");
        assertBinding(manifest, "entityMotion", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertEquals(16, manifest.binding("entityMotion").elementBytes(), "an entity motion record is a float4");
        assertBinding(manifest, "disocclusionOut", 4, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER);
        assertBinding(manifest, "frame", 16, BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        assertEquals(608, manifest.binding("frame").elementBytes(), "FrameConstants stays 608 bytes");
        assertEquals(8, manifest.threadGroupSize()[0], "velocity_reproject: group x");
        assertEquals(8, manifest.threadGroupSize()[1], "velocity_reproject: group y");
        assertEquals(1, manifest.threadGroupSize()[2], "velocity_reproject: group z");
    }

    private static void assertBinding(SlangPassManifest manifest, String name, int binding, int type) {
        SlangPassManifest.Binding actual = manifest.binding(name);
        assertTrue(actual != null, () -> manifest.name() + " has no binding '" + name + "'");
        assertEquals(binding, actual.binding(), manifest.name() + ": binding of " + name);
        assertEquals(type, actual.descriptorType(), manifest.name() + ": type of " + name);
        assertEquals(1, actual.count(), manifest.name() + ": count of " + name);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
