package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssFeatureSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RrQualityPolicyTest {
    @Test void nativeResolutionRetainsDlaaWithoutDependingOnAnSrContext() {
        assertEquals(DlssQuality.DLAA, RrQualityPolicy.select(1920, 1080, 1920, 1080, false, null));
        assertEquals(DlssQuality.DLAA, RrQualityPolicy.select(1920, 1080, 1920, 1080, true, null));
    }

    @Test void liveQualityToPerformanceFollowsTheCurrentSdkDecision() {
        var quality = new DlssFeatureSettings(1280, 720, 1920, 1080, DlssQuality.QUALITY, 1);
        var performance = new DlssFeatureSettings(960, 540, 1920, 1080, DlssQuality.PERFORMANCE, 2);
        assertEquals(DlssQuality.QUALITY, RrQualityPolicy.select(1280, 720, 1920, 1080, true, quality));
        assertEquals(DlssQuality.PERFORMANCE, RrQualityPolicy.select(960, 540, 1920, 1080, true, performance));
    }

    @Test void customUsesSdkChosenModeInsteadOfTheUiQualityPlaceholder() {
        var custom = new DlssFeatureSettings(768, 432, 1920, 1080, DlssQuality.ULTRA_PERFORMANCE, 3);
        assertEquals(DlssQuality.ULTRA_PERFORMANCE, RrQualityPolicy.select(768, 432, 1920, 1080, true, custom));
    }

    @Test void identicalExtentsDoNotHideAChangedSdkMode() {
        for (var mode : new DlssQuality[]{DlssQuality.BALANCED, DlssQuality.PERFORMANCE}) {
            var settings = new DlssFeatureSettings(1000, 600, 1920, 1080, mode, 4);
            assertEquals(mode, RrQualityPolicy.select(1000, 600, 1920, 1080, true, settings));
        }
    }

    @Test void staleInputOrOutputSettingsAreRefusedBeforeFeatureCreation() {
        var settings = new DlssFeatureSettings(1280, 720, 1920, 1080, DlssQuality.QUALITY, 1);
        for (int[] dims : new int[][]{{960, 540, 1920, 1080}, {1280, 720, 2560, 1440}, {1280, 721, 1920, 1080}}) {
            var failure = assertThrows(IllegalStateException.class,
                () -> RrQualityPolicy.select(dims[0], dims[1], dims[2], dims[3], true, settings));
            assertTrue(failure.getMessage().contains("active_dlss="));
            assertTrue(failure.getMessage().contains("initEpoch=1"));
        }
    }

    @Test void activeDlssCannotSilentlyUseQualityWhenItsReadySnapshotIsMissing() {
        assertThrows(IllegalStateException.class, () -> RrQualityPolicy.select(960, 540, 1920, 1080, true, null));
    }

    @Test void anotherBackendRetainsItsFallbackWithoutBorrowingDlssMetadata() {
        var stale = new DlssFeatureSettings(960, 540, 1920, 1080, DlssQuality.PERFORMANCE, 1);
        assertEquals(DlssQuality.QUALITY, RrQualityPolicy.select(1280, 720, 1920, 1080, false, stale));
        assertEquals(DlssQuality.QUALITY, RrQualityPolicy.select(1280, 720, 1920, 1080, false, null));
    }

    @Test void invalidFrameSizesCannotProduceAnSdkMode() {
        assertThrows(IllegalArgumentException.class, () -> RrQualityPolicy.select(0, 720, 0, 720, false, null));
    }

    @Test void productionRrUsesTheReadyRuntimeSnapshotRatherThanStartupConfig() throws Exception {
        var root = Path.of("src/main/java/io/github/andrexarlay/vulkancompat");
        String rr = Files.readString(root.resolve("rt/denoise/DlssRrDenoiser.java"));
        assertFalse(rr.contains("DlssConfig.requestedQuality()"));
        assertTrue(rr.contains("DlssRuntime.activeFeatureSettings()"));
        assertTrue(rr.contains("RrQualityPolicy.select("));
        String runtime = Files.readString(root.resolve("rtx/upscaler/dlss/DlssRuntime.java"));
        int start = runtime.indexOf("DlssFeatureSettings activeFeatureSettings()");
        String body = runtime.substring(start, runtime.indexOf("\n    }", start));
        assertTrue(body.contains("featureMode != null ? featureMode : activeMode"));
        assertTrue(body.contains("state != State.READY"));
        assertFalse(body.contains("feature ==") || body.contains("feature !="), "resolution policy survives SR feature-only retirement");
    }
}
