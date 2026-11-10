package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlangRayGenerationTest {
    @Test void variantsKeepTheActualComputeResourceContractAndAddOnlyRequiredSchedulingFeatures() {
        for (String base : List.of("path_trace_scene", "path_trace_scene", "rc_path_trace_scene")) {
            SlangPass compute = SlangPassLibrary.load(base).orElseThrow();
            assertEquals(0, countOpcode(compute.spirv(), 5280)); // OpReorderThreadWithHintNV
            for (boolean ser : List.of(false, true)) {
                SlangPass variant = SlangPassLibrary.load(base + (ser ? ".ser" : ".raygen")).orElseThrow();
                var manifest = variant.manifest();
                assertEquals("raygeneration", manifest.stage());
                assertArrayEquals(new int[]{1,1,1}, manifest.threadGroupSize());
                assertEquals(compute.manifest().bindings(), manifest.bindings());
                assertEquals(compute.manifest().pushConstantBytes(), manifest.pushConstantBytes());
                assertEquals(compute.manifest().requiredCapabilities() | BedrayCoreLayouts.CAP_RAY_TRACING_PIPELINE
                    | (ser ? BedrayCoreLayouts.CAP_INVOCATION_REORDER_NV : 0), manifest.requiredCapabilities());
                assertEquals(BedrayCoreLayouts.VK_SHADER_STAGE_RAYGEN_BIT_KHR, manifest.shaderStage());
                assertEquals(BedrayCoreLayouts.PROGRAM_RAYGEN | (ser ? BedrayCoreLayouts.PROGRAM_SER_NV : 0), manifest.programFlags());
                assertEquals(1920, manifest.groups(1920, 0));
                assertEquals(1080, manifest.groups(1080, 1));
                assertEquals(5313, executionModel(variant.spirv())); // RayGenerationKHR
                assertEquals(ser, countOpcode(variant.spirv(), 5280) > 0,
                    "SER must emit real reorder instructions; ordinary raygen must remain independent of the extension");
            }
        }
    }

    private static int countOpcode(byte[] bytes, int wanted) {
        ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        for (int at = 20; at < bytes.length;) {
            int instruction = words.getInt(at), size = instruction >>> 16;
            assertTrue(size > 0 && size <= (bytes.length - at) / 4);
            if ((instruction & 0xffff) == wanted) count++;
            at += size * 4;
        }
        return count;
    }

    private static int executionModel(byte[] bytes) {
        ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 20; at < bytes.length;) {
            int instruction = words.getInt(at), size = instruction >>> 16;
            assertTrue(size > 0 && size <= (bytes.length - at) / 4);
            if ((instruction & 0xffff) == 15) return words.getInt(at + 4);
            at += size * 4;
        }
        throw new AssertionError("missing SPIR-V entry point");
    }
}
