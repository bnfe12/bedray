package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.ComputeFrameContext;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackend;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDemoScene;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGrid;
import io.github.andrexarlay.bedray.compute.voxel.VoxelWorldUpload;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldUpdate;
import io.github.andrexarlay.bedray.api.v1.WorldStreamingStatus;
import io.github.andrexarlay.bedray.compute.voxel.StreamedVoxelScene;

/** Opt-in synthetic or bounded live model traversal on the host Vulkan device. */
public final class VoxelComputeBackend implements GraphicsBackend {
    public VoxelComputeBackend() {
        if (GraphicsBackendRegistry.apiMinorVersion() < 6)
            throw new IllegalStateException("Bedray Compute requires renderer API 1.6 or newer");
    }
    private VoxelComputePipeline pipeline;
    private String detail = "Waiting for a world snapshot";
    @Override public String sceneDetail() {
        if(streamed.ready()) {
            var status=streamed.status();
            return status.residentSections()+" resident; "+status.queuedSections()+" queued; radius "
                +status.effectiveRenderDistanceChunks()+"/"+status.requestedRenderDistanceChunks()+" chunks; "
                +status.usedGpuBytes()+"/"+status.budgetGpuBytes()+" GPU scene bytes; "+status.reason()
                +(pipeline==null?"":"; accumulation "+pipeline.historyStatus());
        }
        if (pipeline == null || snapshot == null) return detail;
        var emitters = uploads.emitters();
        return detail + "; emitters " + emitters.count() + "/" + emitters.found()
            + (emitters.found() > emitters.count() ? " (nearest kept, rest via vanilla light)" : "")
            + "; accumulation " + pipeline.historyStatus();
    }
    @Override public boolean supportsSceneMode(GraphicsBackendRegistry.SceneMode mode) {
        return mode == GraphicsBackendRegistry.SceneMode.SYNTHETIC || mode == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
    }
    @Override public boolean supportsDebugViews() { return true; }
    @Override public boolean supportsModelGeometry() { return true; }
    @Override public boolean supportsCellTint() { return true; }
    @Override public boolean supportsWorldStreaming() { return true; }
    private final StreamedVoxelScene streamed=new StreamedVoxelScene();
    private io.github.andrexarlay.bedray.api.v1.WorldRenderQuality previousQuality;
    @Override public WorldStreamingStatus streamingStatus(){return streamed.status();}
    @Override public java.util.List<io.github.andrexarlay.bedray.api.v1.SectionKey> drainWorldInvalidations(){return streamed.drainInvalidations();}
    @Override public void acceptWorldUpdate(WorldUpdate update){
        if(pipeline!=null&&streamed.ready()&&streamed.requestedBudget()!=update.bounds().budgetGpuBytes())
            throw new IllegalStateException("Host must release compute resources before changing the scene budget");
        if(pipeline!=null&&(update.reset()||!update.renderQuality().equals(previousQuality)
                ||streamed.ready()&&streamed.atlasGeneration()!=update.atlasGeneration()))pipeline.invalidateHistory();
        previousQuality=update.renderQuality();
        streamed.accept(update);
    }
    private final io.github.andrexarlay.bedray.compute.voxel.VoxelWorldCache world =
        new io.github.andrexarlay.bedray.compute.voxel.VoxelWorldCache();

    private WorldSnapshot snapshot;
    private final VoxelWorldUpload uploads = new VoxelWorldUpload();
    private final java.util.IdentityHashMap<io.github.andrexarlay.bedray.api.v1.SectionSnapshot, Integer> texturedCounts =
        new java.util.IdentityHashMap<>();
    private long detailSession = -1, detailRevision = -1;
    @Override public boolean wantsWorldSnapshots() {
        return GraphicsBackendRegistry.liveWorldEnabled() || Boolean.getBoolean("bedray.compute.captureWorld");
    }
    @Override public void acceptWorldSnapshot(io.github.andrexarlay.bedray.api.v1.WorldSnapshot snapshot) {
        if (pipeline != null && (this.snapshot == null || this.snapshot.session() != snapshot.session()))
            pipeline.invalidateHistory();
        world.accept(snapshot);
        this.snapshot = snapshot;
        if (detailSession != snapshot.session() || detailRevision != snapshot.revision()) {
            texturedCounts.keySet().removeIf(section -> !snapshot.sections().contains(section));
            int omitted = 0, opaque = 0, textured = 0, models = 0, quads = 0, lightSections = 0, tinted = 0;
            for (var section : snapshot.sections()) {
                omitted += section.unsupportedCount(); opaque += section.opaqueCount();
                models += section.modelCount();
                if (section.models() != null) quads += section.models().quadCount();
                if (section.lighting() != null) lightSections++;
                if (section.tints() != null)
                    for (int index = 0; index < io.github.andrexarlay.bedray.api.v1.SectionSnapshot.CELL_COUNT; index++)
                        if (section.tints().argb(index) != 0) tinted++;
                Integer count = texturedCounts.get(section);
                if (count == null) {
                    int references = 0;
                    if (section.materials() != null) {
                        for (int index = 0; index < io.github.andrexarlay.bedray.api.v1.SectionSnapshot.CELL_COUNT; index++)
                            if (section.materials().reference(index) != 0) references++;
                    }
                    count = references;
                    texturedCounts.put(section, count);
                }
                textured += count;
            }
            // Cubes without a palette entry are never shaded gray: they keep the raster pixel ("raster cubes").
            detail = snapshot.sections().size()+"/27 sections; "+opaque+" cubes; "+textured+" textured; "
                +(opaque-textured)+" raster cubes; "+models+" model cells; "+quads+" palette quads; "
                +tinted+" cell-tinted; "+lightSections+" light sections; "+omitted+" unsupported; missing sections unknown";
            detailSession = snapshot.session(); detailRevision = snapshot.revision();
        }
    }
    @Override public void clearWorldSnapshot() {
        streamed.clear();
        if (snapshot == null) return;
        world.clear(); snapshot = null; detail = "Waiting for a world snapshot"; uploads.clear();
        texturedCounts.clear();
        detailSession = detailRevision = -1;
    }

    @Override public String id() { return "bedray_compute:voxels"; }

    @Override public boolean render(ComputeFrameContext frame) {
        if (frame.sceneMode() == GraphicsBackendRegistry.SceneMode.OFF) return false;
        if (frame.width() <= 0 || frame.height() <= 0 || frame.targetImage() == 0) return false;
        boolean live = frame.sceneMode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
        if(live&&streamed.ready()) {
            if(frame.atlas()==null||streamed.atlasGeneration()!=frame.atlas().generation())return false;
            if(pipeline!=null&&!pipeline.matches(frame))throw new IllegalStateException("Host must release compute resources before resize/device change");
            if(pipeline==null)pipeline=new VoxelComputePipeline(frame.device(),frame.width(),frame.height(),frame.targetFormat(),
                VoxelGrid.builder(1,1,1).build(),frame.atlas(),frame.raster(),streamed);
            pipeline.recordStreamedWorld(frame.commandBuffer(),frame.targetImage(),frame.lighting());
            return true;
        }
        if (live && snapshot == null) return false;
        if (live && snapshot.materialGeneration() != 0 && (frame.atlas() == null
                || snapshot.materialGeneration() != frame.atlas().generation())) return false;
        if (pipeline != null && !pipeline.matches(frame))
            throw new IllegalStateException("Host must release compute resources before resize/device change");
        if (pipeline == null) {
            pipeline = new VoxelComputePipeline(frame.device(), frame.width(), frame.height(),
                    frame.targetFormat(), live ? VoxelGrid.builder(48,48,48).build() : VoxelDemoScene.create(),
                    live ? frame.atlas() : null,live ? frame.raster() : null);
        }
        if (live) {
            var changed = uploads.update(snapshot);
            pipeline.recordWorld(frame.commandBuffer(), frame.targetImage(), snapshot.camera(), uploads, changed,frame.lighting());
        } else pipeline.record(frame.commandBuffer(), frame.targetImage());
        return true;
    }

    @Override public void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
            uploads.clear();
            streamed.clear();
        }
    }
}
