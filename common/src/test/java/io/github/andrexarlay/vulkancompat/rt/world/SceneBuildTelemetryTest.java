package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneBuildTelemetryTest {
    @Test void reportsWorkAcrossTheIntervalAndStartsTheNextWindowAtZero() {
        var telemetry = new SceneBuildTelemetry();
        telemetry.add(BedraySceneLayouts.FRAME_TLAS_REBUILT, 3, 0, 0);
        telemetry.add(BedraySceneLayouts.FRAME_TLAS_UPDATED | BedraySceneLayouts.FRAME_BLAS_COPY_ON_WRITE, 1, 0, 0);
        telemetry.add(BedraySceneLayouts.FRAME_TLAS_UPDATED, 0, 2, 0);
        telemetry.add(BedraySceneLayouts.FRAME_IDLE, 0, 0, 0);
        assertEquals(new SceneBuildTelemetry.Counts(4, 4, 2, 1, 2, 1, 0), telemetry.drain());
        assertEquals(new SceneBuildTelemetry.Counts(0, 0, 0, 0, 0, 0, 0), telemetry.drain());
    }

    @Test void deferredWorkIsVisibleInsteadOfBeingCountedAsAnUpdate() {
        var telemetry = new SceneBuildTelemetry(); telemetry.add(0, 0, 0, 1);
        assertEquals(new SceneBuildTelemetry.Counts(1, 0, 0, 0, 0, 0, 1), telemetry.drain());
    }

    @Test void updatesRequireTheNewCoreFeatureAndHonorTheDiagnosticOverride() {
        int required = BedraySceneLayouts.CAP_ACCELERATION_STRUCTURE | BedraySceneLayouts.CAP_DEVICE_ADDRESS;
        assertEquals(required, NativeSceneBridge.sceneCapabilities(0, false), "older ABI-v2 cores must keep rebuilding");
        assertEquals(required, NativeSceneBridge.sceneCapabilities(BedraySceneLayouts.FEATURE_SAFE_AS_UPDATE, true));
        assertEquals(required | BedraySceneLayouts.CAP_AS_UPDATE,
            NativeSceneBridge.sceneCapabilities(BedraySceneLayouts.FEATURE_SAFE_AS_UPDATE, false));
    }
}
