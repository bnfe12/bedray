package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cap audit of the iris_depth Slang port: SPIR-V 1.4 and NO capability outside the Tier 1 post-shader ceiling
 * (Stage 2.0 base + StorageImageExtendedFormats=49 from Stage 2.1a + ImageQuery=50 from Stage 2.1b). iris_depth
 * uses r32f storage (core mandatory) and GetDimensions/imageSize (OpImageQuerySize -> ImageQuery=50, a core Vulkan
 * 1.0 cap the GLSL reference also pulls; parity confirmed glsl=[1,50] slang=[1,50]). No ray query / Int64 / device
 * address / subgroup. Runs in plain :common:test (loads the .spv only, no shaderc).
 */
class IrisDepthSlangCapabilityTest {
    private static final Set<Integer> CEILING = Set.of(1, 11, 49, 50, 55, 56, 61, 64, 4472, 5347);

    @Test void irisDepthIsSpirv14WithinCeiling() {
        ByteBuffer spirv = load("/assets/bedray_rt/shaders/iris_depth_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");
        Set<Integer> caps = caps(spirv);
        assertTrue(CEILING.containsAll(caps), "iris_depth pulled capabilities outside the Tier 1 ceiling: " + caps);
        assertTrue(caps.contains(1), "Shader capability missing");
        System.out.printf("[BEDRAY-POST-CAPS] iris_depth caps=%s%n", caps);
    }

    static Set<Integer> caps(ByteBuffer spirv) {
        Set<Integer> caps = new TreeSet<>();
        int words = spirv.remaining() / 4;
        for (int word = 5; word < words;) {
            int instruction = spirv.getInt(word * 4);
            int count = instruction >>> 16;
            int opcode = instruction & 0xffff;
            assertTrue(count > 0, "Invalid SPIR-V instruction word count at word " + word);
            if (opcode == 17) caps.add(spirv.getInt((word + 1) * 4)); // OpCapability
            word += count;
        }
        return caps;
    }

    static ByteBuffer load(String path) {
        try (InputStream stream = IrisDepthSlangCapabilityTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = stream.readAllBytes();
            ByteBuffer copy = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            copy.put(bytes).flip();
            return copy.order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + path, failure);
        }
    }
}