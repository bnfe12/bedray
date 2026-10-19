package io.github.andrexarlay.vulkancompat.rt.entity;

import org.joml.Vector4f;

/**
 * Material parameters for an entity submesh.
 */
public record EntityMaterialRecord(
    int textureIndex,
    Vector4f baseColor,
    float alphaCutoff,
    int flags,
    float emissive,
    float roughness,
    float metallic,
    float transmission,
    float ior
) {
    public static final int FLAG_CUTOUT = 1 << 0;
    public static final int FLAG_TRANSLUCENT = 1 << 1;
    public static final int FLAG_EMISSIVE = 1 << 2;
    public static final int FLAG_SHADOW_CASTER = 1 << 3;

    public static final EntityMaterialRecord DEFAULT = new EntityMaterialRecord(
        0, new Vector4f(1f, 1f, 1f, 1f), 0.1f, FLAG_SHADOW_CASTER, 0f, 0.5f, 0f, 0f, 1.0f
    );
}
