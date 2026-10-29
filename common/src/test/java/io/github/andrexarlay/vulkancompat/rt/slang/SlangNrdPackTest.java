package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlangNrdPackTest {
    @Test void allFormatVariantsHaveExplicitSparseStorageAndTheSameLegacyPushOffsets() {
        Assumptions.assumeTrue(SlangPassLibrary.available("nrd_compose"),"NRD AOT entries not compiled");
        for(String name:List.of("nrd_pack_r10","nrd_pack_snorm","nrd_pack_rr","nrd_compose")) {
            var manifest=SlangPassLibrary.load(name).orElseThrow().manifest();
            assertEquals(56,manifest.pushConstantBytes(),name);
            assertArrayEquals(new int[]{8,8,1},manifest.threadGroupSize(),name);
            int[] expected=name.equals("nrd_compose") ? new int[]{0,3,4,7,8,11}
                : name.equals("nrd_pack_rr") ? new int[]{0,1,2,4,5,6,9,10,13} : new int[]{0,1,2,3,4,5,6,9,10,12,13};
            assertArrayEquals(expected,manifest.bindings().stream().mapToInt(SlangPassManifest.Binding::binding).toArray(),name);
            assertTrue(manifest.bindings().stream().allMatch(binding->binding.descriptorType()==BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE));
            assertFalse(manifest.spirvCapabilities().contains("StorageImageWriteWithoutFormat"));
            assertFalse(manifest.spirvCapabilities().contains("StorageImageReadWithoutFormat"));
        }
    }
}
