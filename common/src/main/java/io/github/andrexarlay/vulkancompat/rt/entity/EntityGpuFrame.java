package io.github.andrexarlay.vulkancompat.rt.entity;

/**
 * Encapsulates the GPU-uploaded vertex, index, material, and scene record buffers for the current frame.
 */
public record EntityGpuFrame(
    long frameId,
    long vertexBufferAddress,
    long indexBufferAddress,
    long materialBufferAddress,
    long sceneRecordBufferAddress,
    int vertexCount,
    int indexCount,
    int primitiveCount,
    int entityCount,
    int materialCount,
    long generation,
    boolean ready
) {
    public static final EntityGpuFrame EMPTY = new EntityGpuFrame(
        0L, 0L, 0L, 0L, 0L, 0, 0, 0, 0, 0, 0L, false
    );

    public boolean isValid() {
        return ready && vertexCount > 0 && primitiveCount > 0
            && vertexBufferAddress != 0L && indexBufferAddress != 0L;
    }
}
