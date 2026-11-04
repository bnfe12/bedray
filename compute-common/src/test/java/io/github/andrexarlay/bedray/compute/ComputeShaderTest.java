package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import org.junit.jupiter.api.Test;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ComputeShaderTest {
    @Test void allAotVariantsRequireNoOptionalDeviceFeatures() {
        for (String variant : List.of("synthetic", "dense_textured", "dense_raster", "streamed_textured", "streamed_raster")) {
            var pass = SlangPassLibrary.load("nort_" + variant).orElseThrow();
            var manifest = pass.manifest();
            assertNull(io.github.andrexarlay.vulkancompat.rt.slang.SlangPassDriverGate.blocker(pass.name(), pass.spirv()));
            assertEquals("compute", manifest.stage());
            assertEquals(0, manifest.requiredCapabilities());
            assertEquals(112, manifest.pushConstantBytes());
            assertEquals(1, manifest.binding("gridData").elementBytes(), "raw buffer address unit is one byte");
            if (!variant.equals("synthetic")) {
                assertEquals(1, manifest.binding("modelData").elementBytes());
                assertEquals(32, manifest.binding("palette").elementBytes());
                assertEquals(VoxelComputePipeline.FRAME_BYTES, manifest.binding("environment").elementBytes());
            }
            assertArrayEquals(new int[]{8, 8, 1}, manifest.threadGroupSize());
            assertEquals(variant.equals("synthetic") ? 2 : variant.endsWith("raster") ? 10 : 6,
                manifest.bindings().size(), variant);
            {
                var spirv = java.nio.ByteBuffer.wrap(pass.spirv()).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(0x07230203, spirv.getInt(0));
                for (int word = 5; word < pass.wordCount();) {
                    int instruction = spirv.getInt(word * 4), count = instruction >>> 16;
                    assertTrue(count > 0);
                    if ((instruction & 0xffff) == 17) {
                        int capability = spirv.getInt((word + 1) * 4);
                        assertTrue(capability == 1 || capability == 50,
                            variant + ": unexpected optional SPIR-V capability " + capability);
                    }
                    word += count;
                }
            }
        }
    }
}
