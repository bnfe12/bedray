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
 * Cap audit of the fg_prepare Slang port: SPIR-V 1.4 and NO capability outside the pinned Tier 1 ceiling
 * (Stage 2.0). fg_prepare uses no ray query / Int64 / device address / subgroup, so its caps are a strict
 * subset; this test fails if a port construct escalates beyond Tier 1.
 */
class FgPrepareSlangCapabilityTest {
    // Tier 1 base ceiling pinned from rt_tier1_probe (Stage 2.0): Shader=1, Int64=11,
    // StorageImageReadWithoutFormat=55, StorageImageWriteWithoutFormat=56, GroupNonUniform=61,
    // GroupNonUniformBallot=64, RayQueryKHR=4472, PhysicalStorageBufferAddresses=5347.
    private static final Set<Integer> TIER1_BASE = Set.of(1, 11, 55, 56, 61, 64, 4472, 5347);
    // fg_prepare stores motion vectors in rg16f images (bindings 1,3). R16G16_SFLOAT is not a core
    // mandatory storage format, so SPIR-V requires StorageImageExtendedFormats=49. The shaderc-compiled
    // GLSL production reference emits the IDENTICAL cap set [1,49] (verified offscreen via postProbe),
    // so 49 is inherent to the production ABI, not a Slang escalation. rt_tier1_probe used only rgba8
    // (a core format), which is why 49 was absent from its pinned ceiling.
    private static final int STORAGE_IMAGE_EXTENDED_FORMATS = 49;
    private static final Set<Integer> FG_PREPARE_GLSL_CAPS = Set.of(1, STORAGE_IMAGE_EXTENDED_FORMATS);

    @Test void fgPrepareIsSpirv14WithinTier1Caps() {
        ByteBuffer spirv = load("/assets/bedray_rt/shaders/fg_prepare_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");
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
        Set<Integer> allowed = new TreeSet<>(TIER1_BASE);
        allowed.add(STORAGE_IMAGE_EXTENDED_FORMATS);
        assertTrue(allowed.containsAll(caps),
            "fg_prepare pulled capabilities outside Tier 1 + extended storage formats: " + caps + " (allowed " + allowed + ")");
        // Exact parity with the GLSL production reference: the port adds or drops no capability.
        assertEquals(FG_PREPARE_GLSL_CAPS, caps,
            "fg_prepare caps must equal the shaderc GLSL reference [Shader=1, StorageImageExtendedFormats=49]");
        System.out.printf("[BEDRAY-POST-CAPS] fg_prepare caps=%s%n", caps);
    }

    private static ByteBuffer load(String path) {
        try (InputStream stream = FgPrepareSlangCapabilityTest.class.getResourceAsStream(path)) {
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