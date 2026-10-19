package io.github.andrexarlay.vulkancompat.api.rt;

/**
 * Handle to a placed mesh instance in {@link RtSceneApi}.
 */
public record RtInstanceHandle(long id, RtMeshHandle mesh) {
    public static final RtInstanceHandle INVALID = new RtInstanceHandle(0L, RtMeshHandle.INVALID);

    public boolean isValid() {
        return id > 0L && mesh != null && mesh.isValid();
    }
}
