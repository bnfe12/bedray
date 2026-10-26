package io.github.andrexarlay.bedray.compute;

import org.junit.jupiter.api.Test;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class ComputeShaderTest {
    @Test void compilesForVulkan10WithoutRayTracingCapabilities() {
        var spirv = ComputeShaderCompiler.compile(VoxelComputePipeline.shaderSource(), "voxel.comp").order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x07230203, spirv.getInt(0));
        assertEquals(0x00010000, spirv.getInt(4));
        for (int word = 5; word < spirv.remaining()/4;) {
            int instruction = spirv.getInt(word*4);
            int count = instruction >>> 16;
            assertTrue(count > 0);
            if ((instruction & 0xffff) == 17) {
                // Shader and ImageQuery are core. Exclude Int64, physical addresses and all RT capabilities.
                int capability = spirv.getInt((word+1)*4);
                assertTrue(capability == 1 || capability == 50, "Unexpected SPIR-V capability: " + capability);
            }
            word += count;
        }
    }
}
