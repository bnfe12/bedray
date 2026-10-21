package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.ComputeFrameContext;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackend;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDemoScene;

/** Opt-in synthetic voxel scene. Live Minecraft scene export is a separate milestone. */
public final class VoxelComputeBackend implements GraphicsBackend {
    private VoxelComputePipeline pipeline;

    @Override public String id() { return "bedray_compute:synthetic_voxels"; }

    @Override public boolean render(ComputeFrameContext frame) {
        if (frame.width() <= 0 || frame.height() <= 0 || frame.targetImage() == 0) return false;
        if (pipeline != null && !pipeline.matches(frame)) close();
        if (pipeline == null) {
            pipeline = new VoxelComputePipeline(frame.device(), frame.width(), frame.height(),
                    frame.targetFormat(), VoxelDemoScene.create());
        }
        pipeline.record(frame.commandBuffer(), frame.targetImage());
        return true;
    }

    @Override public void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
    }
}
