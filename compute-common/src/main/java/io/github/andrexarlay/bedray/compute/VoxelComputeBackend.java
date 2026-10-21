package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.ComputeFrameContext;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackend;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDemoScene;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGrid;
import io.github.andrexarlay.bedray.compute.voxel.VoxelWorldUpload;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;

/** Opt-in synthetic or live opaque occupancy diagnostic on the host Vulkan device. */
public final class VoxelComputeBackend implements GraphicsBackend {
    private VoxelComputePipeline pipeline;
    private String detail = "Waiting for a world snapshot";
    @Override public String sceneDetail() { return detail; }
    @Override public boolean supportsSceneMode(GraphicsBackendRegistry.SceneMode mode) {
        return mode == GraphicsBackendRegistry.SceneMode.SYNTHETIC || mode == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
    }
    @Override public boolean supportsDebugViews() { return true; }
    private final io.github.andrexarlay.bedray.compute.voxel.VoxelWorldCache world =
        new io.github.andrexarlay.bedray.compute.voxel.VoxelWorldCache();

    private WorldSnapshot snapshot;
    private VoxelWorldUpload uploads = new VoxelWorldUpload();
    @Override public boolean wantsWorldSnapshots() {
        return GraphicsBackendRegistry.liveWorldEnabled() || Boolean.getBoolean("bedray.compute.captureWorld");
    }
    @Override public void acceptWorldSnapshot(io.github.andrexarlay.bedray.api.v1.WorldSnapshot snapshot) {
        world.accept(snapshot);
        this.snapshot = snapshot;
        int omitted = 0, opaque = 0;
        for (var section : snapshot.sections()) { omitted += section.unsupportedCount(); opaque += section.opaqueCount(); }
        detail = snapshot.sections().size()+"/27 sections; "+opaque+" opaque; "+omitted+" unsupported; missing sections unknown";
    }
    @Override public void clearWorldSnapshot() { world.clear(); snapshot = null; detail = "Waiting for a world snapshot"; uploads = new VoxelWorldUpload(); }

    @Override public String id() { return "bedray_compute:voxels"; }

    @Override public boolean render(ComputeFrameContext frame) {
        if (frame.sceneMode() == GraphicsBackendRegistry.SceneMode.OFF) return false;
        if (frame.width() <= 0 || frame.height() <= 0 || frame.targetImage() == 0) return false;
        boolean live = frame.sceneMode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
        if (live && snapshot == null) return false;
        if (pipeline != null && !pipeline.matches(frame))
            throw new IllegalStateException("Host must release compute resources before resize/device change");
        if (pipeline == null) {
            pipeline = new VoxelComputePipeline(frame.device(), frame.width(), frame.height(),
                    frame.targetFormat(), live ? VoxelGrid.builder(48,48,48).build() : VoxelDemoScene.create());
        }
        if (live) {
            var changed = uploads.update(snapshot);
            pipeline.recordWorld(frame.commandBuffer(), frame.targetImage(), snapshot.camera(), uploads, changed);
        } else pipeline.record(frame.commandBuffer(), frame.targetImage());
        return true;
    }

    @Override public void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
            uploads = new VoxelWorldUpload();
        }
    }
}
