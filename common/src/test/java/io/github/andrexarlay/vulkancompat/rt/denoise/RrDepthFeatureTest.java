package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr.DlssRrParams;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RrDepthFeatureTest {
    @Test void nativeDlaaQualityFallbackAndScaledRrDeclareTheSameDepthContract() {
        var nativeRr = DlssRrDenoiser.hardwareFeatureInfo(1920,1080,1920,1080,DlssQuality.DLAA,false);
        var fallback = DlssRrDenoiser.hardwareFeatureInfo(1920,1080,1920,1080,DlssQuality.QUALITY,true);
        var scaled = DlssRrDenoiser.hardwareFeatureInfo(1280,720,1920,1080,DlssQuality.QUALITY,false);
        for(var info : java.util.List.of(nativeRr,fallback,scaled)) {
            assertEquals(DlssRrParams.DEPTH_HW,info.depthType());
            assertNotEquals(0,info.flags() & DlssRrParams.FLAG_DEPTH_INVERTED);
        }
        assertEquals(0,nativeRr.flags() & DlssRrParams.FLAG_MV_LOW_RES);
        assertNotEquals(0,fallback.flags() & DlssRrParams.FLAG_MV_LOW_RES);
        assertNotEquals(0,scaled.flags() & DlssRrParams.FLAG_MV_LOW_RES);
    }
}
