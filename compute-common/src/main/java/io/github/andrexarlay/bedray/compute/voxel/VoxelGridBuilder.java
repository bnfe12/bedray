package io.github.andrexarlay.bedray.compute.voxel;

/** Mutable construction state; each build owns an independent immutable copy. */
public final class VoxelGridBuilder {
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] materials;

    VoxelGridBuilder(int sizeX, int sizeY, int sizeZ, long budgetBytes) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("Voxel dimensions must be positive");
        }
        long count;
        long bytes;
        try {
            count = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
            bytes = Math.addExact(VoxelGrid.HEADER_BYTES, Math.multiplyExact(count, Integer.BYTES));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Voxel grid size overflow", overflow);
        }
        if (count > Integer.MAX_VALUE || bytes > Integer.MAX_VALUE || budgetBytes < bytes) {
            throw new IllegalArgumentException("Voxel grid exceeds buffer or memory budget: " + bytes);
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.materials = new int[(int) count];
    }

    public VoxelGridBuilder set(int x, int y, int z, int material) {
        checkMaterial(material);
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            throw new IndexOutOfBoundsException("Voxel coordinate outside grid: " + x + "," + y + "," + z);
        }
        materials[x + sizeX * (y + sizeY * z)] = material;
        return this;
    }

    public VoxelGridBuilder fillBox(int minX, int minY, int minZ,
                                    int maxXExclusive, int maxYExclusive, int maxZExclusive, int material) {
        checkMaterial(material);
        if (minX < 0 || minY < 0 || minZ < 0 || maxXExclusive > sizeX
                || maxYExclusive > sizeY || maxZExclusive > sizeZ
                || minX > maxXExclusive || minY > maxYExclusive || minZ > maxZExclusive) {
            throw new IndexOutOfBoundsException("Invalid voxel box");
        }
        for (int z = minZ; z < maxZExclusive; z++) {
            for (int y = minY; y < maxYExclusive; y++) {
                for (int x = minX; x < maxXExclusive; x++) {
                    materials[x + sizeX * (y + sizeY * z)] = material;
                }
            }
        }
        return this;
    }

    private static void checkMaterial(int material) {
        if (material < 0) {
            throw new IllegalArgumentException("Material identifier must be nonnegative");
        }
    }

    public VoxelGrid build() {
        return new VoxelGrid(sizeX, sizeY, sizeZ, materials);
    }
}
