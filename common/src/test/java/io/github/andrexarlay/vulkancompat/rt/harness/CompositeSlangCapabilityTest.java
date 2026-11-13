package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cap audit of the rt_composite Slang port: SPIR-V 1.4, caps within the Tier 1 post-shader ceiling
 * (Stage 2.0 base + StorageImageExtendedFormats=49 + ImageQuery=50). rgba8 storage write is core mandatory;
 * no GetDimensions (uses pc.size), so ImageQuery is not expected. Runs in plain :common:test (loads the .spv only).
 */
class CompositeSlangCapabilityTest {
    private static final Set<Integer> CEILING = Set.of(1, 11, 49, 50, 55, 56, 61, 64, 4472, 5347);

    @Test void compositeIsSpirv14WithinCeiling() {
        ByteBuffer spirv = IrisDepthSlangCapabilityTest.load("/assets/bedray_rt/shaders/rt_composite_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");
        Set<Integer> caps = IrisDepthSlangCapabilityTest.caps(spirv);
        assertTrue(CEILING.containsAll(caps), "rt_composite pulled capabilities outside the Tier 1 ceiling: " + caps);
        assertTrue(caps.contains(1), "Shader capability missing");
        System.out.printf("[BEDRAY-POST-CAPS] rt_composite caps=%s%n", caps);
    }
}