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

    /** Low 30 bits of {@code entityState.z}: the avatar's triangle count. */
    public static final int COUNT_MASK = 0x3FFFFFFF;

    /**
     * The word the shaders read as {@code entityState.z}: the avatar's triangle count in the low 30 bits and the
     * {@code self_shadow} mode ({@code RtSettings.SELF_SHADOW_*}) in the top two, unpacked by every pass that traces.
     */
    public static int packCountAndShadow(int triCount, int selfShadowMode) {
        if (triCount < 0 || triCount > COUNT_MASK) {
            throw new IllegalArgumentException("self-avatar triangle count out of range: " + triCount);
        }
        if (selfShadowMode < 0 || selfShadowMode > 2) {
            throw new IllegalArgumentException("self_shadow mode out of range: " + selfShadowMode);
        }
        return triCount | (selfShadowMode << 30);
    }
}
