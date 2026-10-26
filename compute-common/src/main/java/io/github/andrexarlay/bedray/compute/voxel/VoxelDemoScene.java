package io.github.andrexarlay.bedray.compute.voxel;

/** Synthetic geometry for the software traversal diagnostic; it is not a Minecraft world snapshot. */
public final class VoxelDemoScene {
    private VoxelDemoScene() {}

    public static VoxelGrid create() {
        VoxelGridBuilder grid = VoxelGrid.builder(32, 24, 32)
                .fillBox(0, 0, 0, 32, 1, 32, 1)
                .fillBox(8, 1, 8, 12, 10, 12, 2)
                .fillBox(20, 1, 17, 24, 15, 21, 3);
        for (int step = 0; step < 6; step++) {
            grid.fillBox(13 + step, 1, 4, 14 + step, 2 + step, 9, 4);
        }
        return grid.build();
    }
}
