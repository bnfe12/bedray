package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 1 cap audit: the Slang-compiled host-RT SPIR-V must be version 1.4 and stay within the
 * capability set pinned empirically from the compiled probe (Stage 2.0). A toolchain regression
 * that pulls an unexpected capability (e.g. an RT-pipeline or Int64-only construct) fails here.
 */
class RtSlangCapabilityTest {
    // Pinned from the raw OpCapability operands of rt_tier1_probe_v14.spv (slangc v2026.22,
    // -profile spirv_1_4 -emit-spirv-directly). Shader=1, Int64=11,
    // StorageImageReadWithoutFormat=55, StorageImageWriteWithoutFormat=56, GroupNonUniform=61,
    // GroupNonUniformBallot=64, RayQueryKHR=4472, PhysicalStorageBufferAddresses=5347.
    // ImageQuery (50) is absent: the probe issues no image-size queries.
    private static final Set<Integer> TIER1_CAPS = Set.of(1, 11, 55, 56, 61, 64, 4472, 5347);
    private static final int ADDRESSING_PHYSICAL_STORAGE_BUFFER64 = 5348;

    @Test void tier1ProbeIsSpirv14WithinPinnedCapabilities() {
        ByteBuffer spirv = load("/assets/bedray_rt/shaders/rt_tier1_probe_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");

        Set<Integer> caps = new TreeSet<>();
        Set<String> extensions = new HashSet<>();
        int addressingModel = -1;
        int words = spirv.remaining() / 4;
        for (int word = 5; word < words;) {
            int instruction = spirv.getInt(word * 4);
            int count = instruction >>> 16;
            int opcode = instruction & 0xffff;
            assertTrue(count > 0, "Invalid SPIR-V instruction word count at word " + word);
            if (opcode == 17) { // OpCapability
                caps.add(spirv.getInt((word + 1) * 4));
            } else if (opcode == 10) { // OpExtension
                extensions.add(readString(spirv, (word + 1) * 4));
            } else if (opcode == 14) { // OpMemoryModel
                addressingModel = spirv.getInt((word + 1) * 4);
            }
            word += count;
        }
        assertTrue(TIER1_CAPS.containsAll(caps),
            "Unexpected Tier 1 SPIR-V capabilities: " + caps + " (pinned " + TIER1_CAPS + ")");
        assertTrue(caps.contains(4472), "RayQueryKHR capability missing");
        assertTrue(caps.contains(5347), "PhysicalStorageBufferAddresses capability missing");
        assertTrue(extensions.contains("SPV_KHR_ray_query"), "SPV_KHR_ray_query extension missing");
        assertTrue(extensions.contains("SPV_KHR_physical_storage_buffer"),
            "SPV_KHR_physical_storage_buffer extension missing");
        assertEquals(ADDRESSING_PHYSICAL_STORAGE_BUFFER64, addressingModel,
            "OpMemoryModel addressing must be PhysicalStorageBuffer64");
    }

    private static String readString(ByteBuffer spirv, int byteOffset) {
        int end = byteOffset;
        while (spirv.get(end) != 0) end++;
        byte[] bytes = new byte[end - byteOffset];
        for (int i = 0; i < bytes.length; i++) bytes[i] = spirv.get(byteOffset + i);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuffer load(String path) {
        try (InputStream stream = RtSlangCapabilityTest.class.getResourceAsStream(path)) {
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