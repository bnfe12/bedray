package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.ComputeSceneFrame;
import io.github.andrexarlay.bedray.api.v1.VoxelSceneSnapshot;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.IdentityHashMap;

/** Bounded client-thread sweep. Excluded/unloaded cells stay explicit diagnostic classifications. */
public final class LiveVoxelScene {
    private static final int SIDE_SECTIONS = 3;
    private static final int SIZE = SIDE_SECTIONS * 16;
    private static final int SECTION_COUNT = SIDE_SECTIONS * SIDE_SECTIONS * SIDE_SECTIONS;
    private static final int CELLS = SIZE * SIZE * SIZE;
    private final IdentityHashMap<BlockState, Integer> classifications = new IdentityHashMap<>();
    private ClientLevel world;
    private long worldEpoch, revision;
    private int anchorX, anchorY, anchorZ;
    private int progress;
    private boolean anchored;
    private int[] working, publishedCells;
    private VoxelSceneSnapshot completed;
    private String status = "World occupancy waiting for a world";

    public String status() { return status; }

    /** No mutable world data is read outside Minecraft's owning thread. */
    public ComputeSceneFrame capture(CameraRenderState camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) {
            status = "World occupancy requires the client owning thread";
            return null;
        }
        ClientLevel current = minecraft.level;
        if (current != world) {
            clear();
            world = current;
            worldEpoch++;
        }
        if (current == null || camera == null || camera.pos == null
                || camera.projectionMatrix == null || camera.viewRotationMatrix == null
                || !Double.isFinite(camera.pos.x) || !Double.isFinite(camera.pos.y) || !Double.isFinite(camera.pos.z)) {
            status = "World occupancy waiting for a valid camera";
            return null;
        }
        int sx = sectionCoordinate(camera.pos.x) - 1;
        int sy = sectionCoordinate(camera.pos.y) - 1;
        int sz = sectionCoordinate(camera.pos.z) - 1;
        if (!anchored || sx != anchorX || sy != anchorY || sz != anchorZ) {
            anchorX = sx; anchorY = sy; anchorZ = sz;
            anchored = true;
            progress = 0;
            if (working == null) working = new int[CELLS];
        }
        captureSection(current, progress++);
        if (progress == SECTION_COUNT) {
            int originX = anchorX * 16, originY = anchorY * 16, originZ = anchorZ * 16;
            boolean changed = completed == null || completed.originX() != originX || completed.originY() != originY
                    || completed.originZ() != originZ || !Arrays.equals(working, publishedCells);
            if (changed) {
                completed = new VoxelSceneSnapshot(originX, originY, originZ, SIZE, SIZE, SIZE,
                        worldEpoch, ++revision, working);
            }
            int[] previous = publishedCells;
            publishedCells = working;
            working = previous == null ? new int[CELLS] : previous;
            progress = 0;
        }
        // A previous complete sweep may remain useful during a reanchor, but never outside its world/bounds.
        if (completed == null || !completed.contains(camera.pos.x, camera.pos.y, camera.pos.z)) {
            status = "World occupancy scanning " + progress + "/" + SECTION_COUNT + " sections; normal renderer retained";
            return null;
        }
        status = "48 cubed; revision=" + completed.revision() + "; sweep=" + progress + "/" + SECTION_COUNT
                + "; full=" + completed.fullCubeCount() + "; excluded=" + completed.excludedCount()
                + "; unknown=" + completed.unknownCount();
        Matrix4f projection = WorldTargetManager.levelProjectionOr(camera.projectionMatrix);
        Vector3f bob = WorldTargetManager.levelCameraOriginOr(null);
        Matrix4f inverse = new Matrix4f(projection).mul(camera.viewRotationMatrix).invert();
        float localX = (float) ((camera.pos.x - completed.originX()) + bob.x);
        float localY = (float) ((camera.pos.y - completed.originY()) + bob.y);
        float localZ = (float) ((camera.pos.z - completed.originZ()) + bob.z);
        return new ComputeSceneFrame(completed, inverse.get(new float[16]), localX, localY, localZ,
                bob.x, bob.y, bob.z);
    }

    private static int sectionCoordinate(double coordinate) {
        double section = Math.floor(coordinate / 16.0);
        // Protect section-to-world integer conversion even for malformed external camera states.
        if (section < Integer.MIN_VALUE / 16L + 2L || section > Integer.MAX_VALUE / 16L - 2L) {
            throw new IllegalArgumentException("World camera coordinate exceeds integer block bounds");
        }
        return (int) section;
    }

    private void captureSection(ClientLevel current, int slot) {
        int dx = slot % SIDE_SECTIONS;
        int dy = (slot / SIDE_SECTIONS) % SIDE_SECTIONS;
        int dz = slot / (SIDE_SECTIONS * SIDE_SECTIONS);
        int sy = anchorY + dy;
        LevelChunkSection section = null;
        int absent = VoxelSceneSnapshot.UNKNOWN;
        if (sy < current.getMinSectionY() || sy > current.getMaxSectionY()) {
            absent = VoxelSceneSnapshot.EMPTY;
        } else {
            LevelChunk chunk = current.getChunkSource().getChunk(anchorX + dx, anchorZ + dz, ChunkStatus.FULL, false);
            if (chunk != null) {
                int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) {
                    section = chunk.getSection(sectionIndex);
                }
            }
        }
        boolean empty = section != null && section.hasOnlyAir();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                int at = dx * 16 + SIZE * (dy * 16 + y + SIZE * (dz * 16 + z));
                for (int x = 0; x < 16; x++) {
                    working[at + x] = section == null ? absent : empty ? VoxelSceneSnapshot.EMPTY
                            : classifications.computeIfAbsent(section.getBlockState(x, y, z), LiveVoxelScene::classify);
                }
            }
        }
    }

    private static int classify(BlockState state) {
        if (state.isAir()) return VoxelSceneSnapshot.EMPTY;
        // Cached state/shape properties only. No world callbacks, neighbour access or inferred PBR.
        return !state.liquid() && state.getFluidState().isEmpty() && state.getRenderShape() == RenderShape.MODEL
                && state.isSolidRender() && state.canOcclude() && !state.hasOffsetFunction()
                && !state.getBlock().hasDynamicShape() && Block.isShapeFullBlock(state.getOcclusionShape())
                ? VoxelSceneSnapshot.FULL_CUBE : VoxelSceneSnapshot.EXCLUDED;
    }

    public void clear() {
        world = null;
        anchored = false;
        progress = 0;
        working = publishedCells = null;
        completed = null;
        classifications.clear();
        status = "World occupancy waiting for a world";
    }
}
