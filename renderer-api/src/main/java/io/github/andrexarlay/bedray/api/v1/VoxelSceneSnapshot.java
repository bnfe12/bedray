package io.github.andrexarlay.bedray.api.v1;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Completed immutable world occupancy diagnostic. Cell values are classifications, not optical materials. */
public final class VoxelSceneSnapshot implements VoxelVolume {
    public static final int EMPTY = 0;
    public static final int FULL_CUBE = 1;
    public static final int EXCLUDED = 2;
    public static final int UNKNOWN = 3;
    public static final long DEFAULT_BUDGET_BYTES = 64L * 1024 * 1024;
    private final int originX, originY, originZ;
    private final int sizeX, sizeY, sizeZ;
    private final long worldEpoch, revision;
    private final int[] cells;
    private final int[] counts = new int[4];

    public VoxelSceneSnapshot(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
                              long worldEpoch, long revision, int[] cells) {
        this(originX, originY, originZ, sizeX, sizeY, sizeZ, worldEpoch, revision, cells, DEFAULT_BUDGET_BYTES);
    }

    public VoxelSceneSnapshot(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
                              long worldEpoch, long revision, int[] cells, long budgetBytes) {
        Objects.requireNonNull(cells, "cells");
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || worldEpoch < 0 || revision < 0) {
            throw new IllegalArgumentException("Positive volume dimensions and nonnegative revisions required");
        }
        long count;
        long bytes;
        try {
            count = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
            bytes = Math.addExact(16L, Math.multiplyExact(count, 4L));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Voxel dimensions overflow", overflow);
        }
        if (count > Integer.MAX_VALUE || bytes > Integer.MAX_VALUE || bytes > budgetBytes || count != cells.length) {
            throw new IllegalArgumentException("Voxel data length or memory budget mismatch");
        }
        this.cells = cells.clone();
        for (int cell : this.cells) {
            if (cell < EMPTY || cell > UNKNOWN) throw new IllegalArgumentException("Unknown occupancy classification " + cell);
            counts[cell]++;
        }
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.worldEpoch = worldEpoch;
        this.revision = revision;
    }

    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
    @Override public int sizeX() { return sizeX; }
    @Override public int sizeY() { return sizeY; }
    @Override public int sizeZ() { return sizeZ; }
    public long worldEpoch() { return worldEpoch; }
    public long revision() { return revision; }
    @Override public int cellCount() { return cells.length; }
    @Override public long byteSize() { return 16L + 4L * cells.length; }
    public int emptyCount() { return counts[EMPTY]; }
    public int fullCubeCount() { return counts[FULL_CUBE]; }
    public int excludedCount() { return counts[EXCLUDED]; }
    public int unknownCount() { return counts[UNKNOWN]; }

    public boolean contains(double worldX, double worldY, double worldZ) {
        return worldX >= originX && worldX < (long) originX + sizeX
                && worldY >= originY && worldY < (long) originY + sizeY
                && worldZ >= originZ && worldZ < (long) originZ + sizeZ;
    }

    @Override public int material(int x, int y, int z) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            throw new IndexOutOfBoundsException("Outside voxel snapshot");
        }
        return cells[x + sizeX * (y + sizeY * z)];
    }

    @Override public void writeTo(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        if (target.remaining() < byteSize()) throw new IllegalArgumentException("Snapshot buffer too small");
        ByteBuffer output = target.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(sizeX).putInt(sizeY).putInt(sizeZ).putInt(cells.length);
        for (int cell : cells) output.putInt(cell);
        target.position(output.position());
    }
}
