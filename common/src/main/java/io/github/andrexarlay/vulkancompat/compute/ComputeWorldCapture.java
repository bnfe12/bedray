package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshotStream;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;

/** Host-only game access; no dependency on the hardware RT gate or captured visible faces. */
final class ComputeWorldCapture {
    private final WorldSnapshotStream stream = new WorldSnapshotStream();
    void clear() { stream.clear(); }

    WorldSnapshot capture(CameraRenderState camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread())
            throw new IllegalStateException("World snapshot requires the client owning thread");
        var level = minecraft.level;
        if (level == null) throw new IllegalStateException("World is unavailable");
        var projection = WorldTargetManager.levelProjectionOr(camera.projectionMatrix);
        var inverseView = new Matrix4f(camera.viewRotationMatrix).invert();
        var bob = WorldTargetManager.levelCameraOriginOr(null);
        CameraSnapshot pose = new CameraSnapshot(camera.pos.x, camera.pos.y, camera.pos.z,
            new Matrix4f(projection).invert().get(new float[16]), inverseView.get(new float[16]),
            new float[]{bob.x,bob.y,bob.z});
        return stream.capture(level, pose, new WorldSnapshotStream.Source() {
            @Override public boolean loaded(int x, int y, int z) {
                return y >= level.getMinSectionY() && y <= level.getMaxSectionY()
                    && level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
            }
            @Override public byte[] readSection(int x, int y, int z) {
                if (!loaded(x,y,z)) return null;
                var chunk = level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
                if (chunk == null) return null;
                var section = chunk.getSection(level.getSectionIndexFromSectionY(y));
                byte[] cells = new byte[4096];
                if (section.hasOnlyAir()) return cells;
                for (int bz=0; bz<16; bz++) for (int by=0; by<16; by++) for (int bx=0; bx<16; bx++) {
                    var state = section.getBlockState(bx,by,bz);
                    if (state.isAir()) continue;
                    // Cached shape/state properties only: never call world-aware collision overrides here.
                    cells[bx+16*(by+16*bz)] = !state.getBlock().hasDynamicShape()
                        && !state.hasOffsetFunction() && !state.liquid()
                        && state.getFluidState().isEmpty() && state.getRenderShape() == RenderShape.MODEL
                        && state.canOcclude() && state.isSolidRender() && Block.isShapeFullBlock(state.getOcclusionShape())
                        ? io.github.andrexarlay.bedray.api.v1.SectionSnapshot.OPAQUE_CUBE
                        : io.github.andrexarlay.bedray.api.v1.SectionSnapshot.UNSUPPORTED;
                }
                return cells;
            }
        });
    }
}
