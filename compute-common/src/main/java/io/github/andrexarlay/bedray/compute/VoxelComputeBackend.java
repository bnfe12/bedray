package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.ComputeFrameContext;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackend;
import io.github.andrexarlay.bedray.api.v1.VoxelVolume;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDemoScene;

/** Opt-in software traversal for synthetic and host-supplied occupancy diagnostics. */
public final class VoxelComputeBackend implements GraphicsBackend {
    private VoxelComputePipeline pipeline;
    private final VoxelVolume demo = VoxelDemoScene.create();

    @Override public String id() { return "bedray_compute:voxels"; }

    @Override public boolean render(ComputeFrameContext frame) {
        if (frame.width() <= 0 || frame.height() <= 0 || frame.targetImage() == 0) return false;
        if (pipeline != null && !pipeline.matches(frame)) close();
        VoxelVolume volume = frame.scene() == null ? demo : frame.scene().snapshot();
        if (pipeline == null) {
            pipeline = new VoxelComputePipeline(frame.device(), frame.width(), frame.height(),
                    frame.targetFormat(), volume);
        }
        pipeline.record(frame.commandBuffer(), frame.targetImage(), frame.scene());
        return true;
    }

    @Override public void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
    }
}
