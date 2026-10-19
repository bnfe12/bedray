package io.github.andrexarlay.vulkancompat.rt.entity;

import org.joml.Matrix4f;

/**
 * Metadata record for a single captured entity instance in the current frame.
 */
public record EntitySceneRecord(
    int entityId,
    int geometryOffset,
    int geometryCount,
    int materialOffset,
    int materialCount,
    Matrix4f currentTransform,
    Matrix4f previousTransform,
    int flags,
    long geometryGeneration
) {
    public static final int FLAG_LIVING = 1 << 0;
    public static final int FLAG_ITEM = 1 << 1;
    public static final int FLAG_BLOCK_ENTITY = 1 << 2;
    public static final int FLAG_TRANSLUCENT = 1 << 3;
    public static final int FLAG_EMISSIVE = 1 << 4;
}
