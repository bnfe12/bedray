package io.github.andrexarlay.vulkancompat.rt.entity;

/**
 * The one decision behind adding the local player's model to the RT scene: <b>first person only</b>. In third person the
 * game submits the player through {@code EntityRenderDispatcher} like any other entity, and a second submission would put a
 * duplicate body into the entity geometry, doubling its triangles and shadowing.
 */
public final class SelfAvatarGate {

    private SelfAvatarGate() {
    }

    public static boolean shouldCapture(boolean captureOpen, boolean enabled, boolean firstPerson, boolean avatarUsable) {
        return captureOpen && enabled && firstPerson && avatarUsable;
    }
}
