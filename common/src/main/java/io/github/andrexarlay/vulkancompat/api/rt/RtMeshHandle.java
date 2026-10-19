package io.github.andrexarlay.vulkancompat.api.rt;

/**
 * Opaque handle to a mesh registered in {@link RtSceneApi}.
 */
public record RtMeshHandle(long id, int vertexCount, int indexCount, RtLayer layer) {
    public static final RtMeshHandle INVALID = new RtMeshHandle(0L, 0, 0, RtLayer.SOLID);

    public boolean isValid() {
        return id > 0L && vertexCount > 0 && indexCount >= 3;
    }
}
