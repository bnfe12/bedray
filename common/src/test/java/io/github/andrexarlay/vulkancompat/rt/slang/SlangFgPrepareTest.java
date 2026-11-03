package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlangFgPrepareTest {
    @Test void producerUsesTypedDepthAndMotionWithAnEightByteResolutionPush() {
        Assumptions.assumeTrue(SlangPassLibrary.available("fg_prepare"),"FG AOT entry not compiled");
        var manifest=SlangPassLibrary.load("fg_prepare").orElseThrow().manifest();
        assertEquals(8,manifest.pushConstantBytes()); assertArrayEquals(new int[]{8,8,1},manifest.threadGroupSize());
        assertEquals(4,manifest.bindings().size());
        String[] names={"depthTexture","inMotionVectors","outDepth","outMotionVectors"};
        for(int i=0;i<4;++i) {
            var binding=manifest.binding(names[i]); assertNotNull(binding); assertEquals(i,binding.binding());
            assertEquals(i==0?BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE,binding.descriptorType());
        }
        assertFalse(manifest.spirvCapabilities().contains("StorageImageReadWithoutFormat"));
        assertFalse(manifest.spirvCapabilities().contains("StorageImageWriteWithoutFormat"));
    }
}
