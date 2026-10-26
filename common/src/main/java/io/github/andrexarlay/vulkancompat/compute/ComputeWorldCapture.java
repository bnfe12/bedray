package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.AtlasFrame;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshotStream;
import io.github.andrexarlay.bedray.api.v1.WorldUpdate;
import io.github.andrexarlay.bedray.api.v1.WorldCoverageBounds;
import io.github.andrexarlay.bedray.api.v1.SectionKey;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;

/** Host-only game access; no dependency on the hardware RT gate or captured visible faces. */
final class ComputeWorldCapture {
    private final WorldSnapshotStream stream = new WorldSnapshotStream();
    private final ComputeWorldStream streaming = new ComputeWorldStream();
    private final ComputeBlockMaterials materials = new ComputeBlockMaterials();
    private String pendingReason;
    private boolean extendedGeometry;
    void clear() { stream.clear(); streaming.clear(); materials.clear(); pendingReason = null; }
    void sectionChanged(int x, int y, int z) { streaming.changed(x, y, z); }
    void sectionRemoved(int x, int y, int z) { streaming.removed(x, y, z); }
    void invalidated(SectionKey key) { streaming.invalidated(key); }
    String pendingReason() { return pendingReason; }

    /** Null means that this frame's camera is not usable yet; the next frame must retry. */
    WorldSnapshot capture(CameraRenderState camera, AtlasFrame atlas, boolean extended) {
        return capture(camera, atlas, extended, false);
    }

    /** {@code cellTint}: the provider accepts API minor 5 per-cell tint (only meaningful with extended). */
    WorldSnapshot capture(CameraRenderState camera, AtlasFrame atlas, boolean extended, boolean cellTint) {
        CameraSnapshot pose = prepare(camera, atlas, extended, cellTint);
        if (pose == null) return null;
        var level = Minecraft.getInstance().level;
        materials.retainWindow((int)Math.floor(camera.pos.x / 16.0),
            (int)Math.floor(camera.pos.y / 16.0), (int)Math.floor(camera.pos.z / 16.0));
        return stream.capture(level, pose, new WorldSnapshotStream.Source() {
            @Override public boolean loaded(int x, int y, int z) {
                return y >= level.getMinSectionY() && y <= level.getMaxSectionY()
                    && level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
            }
            @Override public byte[] readSection(int x, int y, int z) {
                SectionSnapshot snapshot = readSectionSnapshot(x,y,z,0);
                if (snapshot == null) return null;
                byte[] cells = new byte[SectionSnapshot.CELL_COUNT];
                for (int bz=0; bz<16; bz++) for (int by=0; by<16; by++) for (int bx=0; bx<16; bx++)
                    cells[bx+16*(by+16*bz)] = snapshot.cell(bx,by,bz);
                return cells;
            }
            @Override public SectionSnapshot readSectionSnapshot(int x, int y, int z, long revision) {
                return materials.captureSection(x,y,z,revision,extended);
            }
        });
    }

    WorldUpdate captureStreaming(CameraRenderState camera, AtlasFrame atlas, boolean extended, boolean cellTint,
                                 int providerQueuedSections) {
        CameraSnapshot pose = prepare(camera, atlas, extended, cellTint);
        if (pose == null) return null;
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        int minecraftDistance = Math.clamp(minecraft.options.getEffectiveRenderDistance(), 1, 64);
        int requested = VulkanRtxConfig.voxelDistanceAuto() ? minecraftDistance : VulkanRtxConfig.voxelDistanceChunks();
        var bounds = new WorldCoverageBounds((int)Math.floor(pose.x() / 16), (int)Math.floor(pose.z() / 16),
            requested, Math.min(requested, minecraftDistance), level.getMinSectionY(), level.getMaxSectionY(),
            (long)VulkanRtxConfig.voxelMemoryBudgetMiB() * 1024 * 1024, ComputeWorldStream.UPLOAD_BYTES);
        return streaming.capture(level, atlas.generation(), pose, bounds, new ComputeWorldStream.Source() {
            @Override public boolean loaded(SectionKey key) {
                return key.y() >= level.getMinSectionY() && key.y() <= level.getMaxSectionY()
                    && level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false) != null;
            }
            @Override public boolean empty(SectionKey key) {
                var chunk = level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
                return chunk != null && chunk.getSection(level.getSectionIndexFromSectionY(key.y())).hasOnlyAir();
            }
            @Override public SectionSnapshot capture(SectionKey key, long revision) {
                return materials.captureSection(key.x(), key.y(), key.z(), revision, extended, true);
            }
        }, providerQueuedSections, new io.github.andrexarlay.bedray.api.v1.WorldRenderQuality(
            VulkanRtxConfig.voxelDiffuseBounce(), VulkanRtxConfig.voxelTranslucentOptics()));
    }

    private CameraSnapshot prepare(CameraRenderState camera, AtlasFrame atlas, boolean extended, boolean cellTint) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread())
            throw new IllegalStateException("World snapshot requires the client owning thread");
        var level = minecraft.level;
        if (extendedGeometry != extended) {
            clear();
            extendedGeometry = extended;
        }
        pendingReason = null;
        if (level == null || camera == null || !camera.initialized) {
            pendingReason = "Waiting for initialized world camera";
            return null;
        }
        if (atlas == null) {
            pendingReason = "Waiting for a live block atlas";
            return null;
        }
        if ((extended || WorldTargetManager.bobProjectionEnabled()) && !WorldTargetManager.hasCurrentLevelProjection()) {
            pendingReason = "Waiting for current-frame world projection";
            return null;
        }
        if (camera.pos == null || !Double.isFinite(camera.pos.x) || !Double.isFinite(camera.pos.y)
                || !Double.isFinite(camera.pos.z)) {
            pendingReason = "Waiting for finite camera position";
            return null;
        }
        var projection = extended ? WorldTargetManager.currentLevelProjection()
            : WorldTargetManager.levelProjectionOr(camera.projectionMatrix);
        var inverseProjection = inverseOrNull(projection);
        var inverseView = inverseOrNull(camera.viewRotationMatrix);
        if (inverseProjection == null || inverseView == null) {
            pendingReason = inverseProjection == null ? "Waiting for invertible world projection"
                : "Waiting for invertible camera view";
            return null;
        }
        var bob = extended ? WorldTargetManager.currentLevelCameraOrigin() : WorldTargetManager.levelCameraOriginOr(null);
        if (bob == null || !bob.isFinite()) {
            pendingReason = "Waiting for finite view-bob origin";
            return null;
        }
        CameraSnapshot pose = new CameraSnapshot(camera.pos.x, camera.pos.y, camera.pos.z,
            inverseProjection.get(new float[16]), inverseView.get(new float[16]),
            new float[]{bob.x,bob.y,bob.z});
        String materialPending = materials.prepare(minecraft, level, atlas);
        if (materialPending != null) {
            pendingReason = materialPending;
            return null;
        }
        materials.setCellTint(extended && cellTint);
        if (materials.consumeSessionChange()) { stream.clear(); streaming.clear(); }
        return pose;
    }

    private static Matrix4f inverseOrNull(Matrix4f matrix) {
        if (matrix == null || !matrix.isFinite()) return null;
        float determinant = matrix.determinant();
        if (!Float.isFinite(determinant) || determinant == 0f) return null;
        Matrix4f inverse = new Matrix4f(matrix).invert();
        return inverse.isFinite() ? inverse : null;
    }
}
