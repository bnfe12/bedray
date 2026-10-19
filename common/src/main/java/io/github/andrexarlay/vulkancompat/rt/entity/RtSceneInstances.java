package io.github.andrexarlay.vulkancompat.rt.entity;

/**
 * Common contract describing all TLAS acceleration structure instances in the current frame,
 * as well as 24-bit instanceCustomIndex encoding for ray-tracing shaders.
 */
public record RtSceneInstances(
    long worldAsReference,
    long entityAsReference,
    long translucentAsReference,
    int worldInstanceCount,
    int entityInstanceCount,
    int translucentInstanceCount,
    long generation,
    boolean entityBlasReady
) {
    public static final int MASK_WORLD = 0x01;
    public static final int MASK_ENTITIES = 0x02;
    public static final int MASK_TRANSLUCENT = 0x04;
    public static final int MASK_PARTICLES = 0x08;
    public static final int MASK_FULL_SCENE = 0x0F;

    // 24-bit instanceCustomIndex encoding:
    // Bit 23: 0 = World section, 1 = Dynamic Entity
    // Bits 0..22: World section slot index OR Entity scene record index
    public static final int INSTANCE_TYPE_MASK = 0x800000;
    public static final int INSTANCE_INDEX_MASK = 0x7FFFFF;

    public static final RtSceneInstances EMPTY = new RtSceneInstances(0L, 0L, 0L, 0, 0, 0, 0L, false);

    public boolean hasEntities() {
        return entityBlasReady && entityAsReference != 0L && entityInstanceCount > 0;
    }

    public static int encodeWorldCustomIndex(int sectionSlot) {
        return sectionSlot & INSTANCE_INDEX_MASK;
    }

    public static int encodeEntityCustomIndex(int recordIndex) {
        return INSTANCE_TYPE_MASK | (recordIndex & INSTANCE_INDEX_MASK);
    }

    public static boolean isEntityInstance(int customIndex) {
        return (customIndex & INSTANCE_TYPE_MASK) != 0;
    }

    public static int getRecordIndex(int customIndex) {
        return customIndex & INSTANCE_INDEX_MASK;
    }
}
