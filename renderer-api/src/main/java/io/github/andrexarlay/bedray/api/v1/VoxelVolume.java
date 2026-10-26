package io.github.andrexarlay.bedray.api.v1;

import java.nio.ByteBuffer;

/** Immutable unit-cell volume. GPU layout is a 16-byte LE dimension/count header, then uint cells. */
public interface VoxelVolume {
    int sizeX();
    int sizeY();
    int sizeZ();
    int cellCount();
    long byteSize();
    int material(int x, int y, int z);
    void writeTo(ByteBuffer target);
}
