package io.github.andrexarlay.bedray.compute.voxel;

/** The ray parameter is measured against the original, potentially unnormalized, direction. */
public record VoxelHit(int x, int y, int z, int material, double t,
                       int normalX, int normalY, int normalZ, boolean startedInside) {}
