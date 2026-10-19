package io.github.andrexarlay.vulkancompat.rt.particles;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.Map;

/**
 * Access to the package-private record {@code QuadParticleFeatureRenderer$PreparedGroup} (javap -p, 26.3 client jar: fields
 * {@code layers}, {@code textures}, {@code translucent}). Implemented by mixin {@code MixinParticleGroupAccessor}.
 */
public interface ParticleGroupAccess {
    Map<SingleQuadParticle.Layer, StagedVertexBuffer.Draw> vkb$layers();

    Map<SingleQuadParticle.Layer, AbstractTexture> vkb$textures();

    boolean vkb$translucent();
}
