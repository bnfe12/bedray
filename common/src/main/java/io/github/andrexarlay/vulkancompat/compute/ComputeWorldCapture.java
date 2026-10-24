package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.AtlasFrame;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshotStream;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;

/** Host-only game access; no dependency on the hardware RT gate or captured visible faces. */
final class ComputeWorldCapture {
    private final WorldSnapshotStream stream = new WorldSnapshotStream();
    private final ComputeBlockMaterials materials = new ComputeBlockMaterials();
    private String pendingReason;
    private boolean extendedGeometry;
    void clear() { stream.clear(); materials.clear(); pendingReason = null; }
    String pendingReason() { return pendingReason; }

    /** Null means that this frame's camera is not usable yet; the next frame must retry. */
    WorldSnapshot capture(CameraRenderState camera, AtlasFrame atlas, boolean extended) {
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
        if (materials.consumeSessionChange()) stream.clear();
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

    private static Matrix4f inverseOrNull(Matrix4f matrix) {
        if (matrix == null || !matrix.isFinite()) return null;
        float determinant = matrix.determinant();
        if (!Float.isFinite(determinant) || determinant == 0f) return null;
        Matrix4f inverse = new Matrix4f(matrix).invert();
        return inverse.isFinite() ? inverse : null;
    }
}
