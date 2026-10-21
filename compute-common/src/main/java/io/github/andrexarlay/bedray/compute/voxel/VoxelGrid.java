package io.github.andrexarlay.bedray.compute.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Immutable bounded grid. Material zero denotes an empty cell. */
public final class VoxelGrid {
    public static final int HEADER_BYTES = 16;
    public static final long DEFAULT_BUDGET_BYTES = 64L * 1024 * 1024;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] materials;

    VoxelGrid(int sizeX, int sizeY, int sizeZ, int[] materials) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.materials = materials.clone();
    }

    public static VoxelGridBuilder builder(int sizeX, int sizeY, int sizeZ) {
        return builder(sizeX, sizeY, sizeZ, DEFAULT_BUDGET_BYTES);
    }

    public static VoxelGridBuilder builder(int sizeX, int sizeY, int sizeZ, long budgetBytes) {
        return new VoxelGridBuilder(sizeX, sizeY, sizeZ, budgetBytes);
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int cellCount() { return materials.length; }
    public long byteSize() { return HEADER_BYTES + (long) Integer.BYTES * materials.length; }

    public int material(int x, int y, int z) {
        return materials[index(x, y, z)];
    }

    private int index(int x, int y, int z) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            throw new IndexOutOfBoundsException("Voxel coordinate outside grid: " + x + "," + y + "," + z);
        }
        return x + sizeX * (y + sizeY * z);
    }

    /** Writes the complete std430 buffer, starting at the current position, in little endian order. */
    public void writeTo(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        if (target.remaining() < byteSize()) {
            throw new IllegalArgumentException("Insufficient space for voxel buffer: " + byteSize());
        }
        ByteBuffer output = target.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(sizeX).putInt(sizeY).putInt(sizeZ).putInt(materials.length);
        for (int material : materials) {
            output.putInt(material);
        }
        target.position(output.position());
    }
}
